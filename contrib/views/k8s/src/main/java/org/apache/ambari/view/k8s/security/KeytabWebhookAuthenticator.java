/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.security;

import org.apache.ambari.view.k8s.requests.KeytabRequest;

import javax.security.auth.x500.X500Principal;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Authenticates the view's keytab webhook by the client certificate the view issued to it.
 *
 * <p>The webhook reaches the view through the Ambari REST API, whose HTTPS port does not ask for client certificates,
 * so the certificate cannot authenticate the TLS connection. The webhook instead proves on each keytab request that it
 * holds the certificate's private key: it sends the certificate, a timestamp, and a signature made with the key over
 * the timestamp and every field of the request. The view accepts the request only when the certificate was issued by
 * its own internal CA to the webhook, is valid, and the signature matches. A copied request cannot be replayed once
 * its timestamp is too old, nor changed to name another principal, namespace or Secret.
 *
 * <p>The webhook also has to get past Ambari's sign-in to reach the view. It signs in as a local Ambari account that
 * may only open this view instance; the password of that account is derived from the same private key (see
 * {@link #accountPassword}), so no password is kept in Kubernetes: the client certificate's Secret is the only
 * credential there.
 */
public final class KeytabWebhookAuthenticator {

    /** The webhook's client certificate, base64 of its DER encoding. */
    public static final String CERTIFICATE_HEADER = "X-KDPS-Webhook-Certificate";
    /** When the webhook signed the request, in seconds since the epoch. */
    public static final String TIMESTAMP_HEADER = "X-KDPS-Webhook-Timestamp";
    /** The signature, base64 of the DER-encoded signature made with the certificate's private key. */
    public static final String SIGNATURE_HEADER = "X-KDPS-Webhook-Signature";

    /** First line of what the webhook signs, so the signature cannot be reused for anything else. */
    static final String SIGNATURE_CONTEXT = "kdps-keytab-request";
    /** Mixed into the account password, so the password is not a hash an unrelated system could also compute. */
    static final String PASSWORD_CONTEXT = "kdps-keytab-webhook-ambari-login\n";

    /** How far the signing time may be from the view's clock: the webhook signs right before it sends. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /** Extended key usage of TLS client authentication, which the view puts in the webhook's certificate. */
    private static final String CLIENT_AUTH_USAGE = "1.3.6.1.5.5.7.3.2";

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN [A-Z0-9 ]+-----([A-Za-z0-9+/=\\s]*)-----END [A-Z0-9 ]+-----");

    private final String certificateAuthorityPem;
    private final String expectedCommonName;

    /**
     * @param certificateAuthorityPem the view's internal CA, which issues the webhook's client certificate; null when
     *                                the view has none yet, in which case no request is accepted
     * @param expectedCommonName      the common name the view gives the webhook's certificate
     */
    public KeytabWebhookAuthenticator(String certificateAuthorityPem, String expectedCommonName) {
        this.certificateAuthorityPem = certificateAuthorityPem;
        this.expectedCommonName = expectedCommonName;
    }

    /** Refusal of a webhook request, with the reason written to the view's log. */
    public static class RefusedException extends Exception {
        public RefusedException(String message) {
            super(message);
        }
    }

    /**
     * Whether the request claims to come from the keytab webhook, i.e. carries any of its proof headers. Such a
     * request is then either proven or refused: it never falls back to the caller's own KDPS role.
     *
     * @param headers the request headers
     * @return true when one of the proof headers is present
     */
    public static boolean claimsWebhook(MultivaluedMap<String, String> headers) {
        return headers != null && Stream.of(CERTIFICATE_HEADER, TIMESTAMP_HEADER, SIGNATURE_HEADER)
                .anyMatch(name -> first(headers, name) != null);
    }

    /**
     * Checks that the request was signed by the holder of the webhook's client certificate.
     *
     * @param headers the request headers
     * @param request the keytab request as received
     * @param now     the current time
     * @throws RefusedException when the certificate or the signature does not check out
     */
    public void authenticate(MultivaluedMap<String, String> headers, KeytabRequest request, Instant now)
            throws RefusedException {
        String certificateValue = first(headers, CERTIFICATE_HEADER);
        String timestampValue = first(headers, TIMESTAMP_HEADER);
        String signatureValue = first(headers, SIGNATURE_HEADER);
        if (certificateValue == null || timestampValue == null || signatureValue == null) {
            throw new RefusedException("the request lacks the webhook's certificate, timestamp or signature");
        }
        if (certificateAuthorityPem == null || certificateAuthorityPem.isBlank()) {
            throw new RefusedException("the view has no internal CA yet, so it issued no webhook certificate");
        }

        X509Certificate certificate;
        X509Certificate authority;
        try {
            certificate = parseCertificate(Base64.getDecoder().decode(certificateValue.trim()));
            authority = parseCertificate(pemBody(certificateAuthorityPem));
        } catch (Exception e) {
            throw new RefusedException("the certificate could not be read: " + e.getMessage());
        }
        try {
            certificate.verify(authority.getPublicKey());
        } catch (Exception e) {
            throw new RefusedException("the certificate was not issued by the view's internal CA");
        }
        if (!certificate.getIssuerX500Principal().equals(authority.getSubjectX500Principal())) {
            throw new RefusedException("the certificate was not issued by the view's internal CA");
        }
        try {
            certificate.checkValidity(Date.from(now));
        } catch (Exception e) {
            throw new RefusedException("the certificate is expired or not yet valid");
        }
        if (!expectedCommonName.equals(commonName(certificate.getSubjectX500Principal()))) {
            throw new RefusedException("the certificate was not issued to the keytab webhook");
        }
        if (!allowsClientAuthentication(certificate)) {
            throw new RefusedException("the certificate is not a client certificate");
        }

        long signedAt;
        try {
            signedAt = Long.parseLong(timestampValue.trim());
        } catch (NumberFormatException e) {
            throw new RefusedException("the timestamp is not a number of seconds");
        }
        if (Duration.between(Instant.ofEpochSecond(signedAt), now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new RefusedException("the request was signed at " + Instant.ofEpochSecond(signedAt)
                    + ", too far from the view's clock (" + now + ")");
        }

        byte[] signed = signingInput(timestampValue.trim(), request);
        if (signed == null) {
            throw new RefusedException("a field of the request holds a line break");
        }
        try {
            PublicKey key = certificate.getPublicKey();
            Signature verifier = Signature.getInstance(signatureAlgorithm(key));
            verifier.initVerify(key);
            verifier.update(signed);
            if (!verifier.verify(Base64.getDecoder().decode(signatureValue.trim()))) {
                throw new RefusedException("the signature does not match the request");
            }
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw new RefusedException("the signature could not be checked: " + e.getMessage());
        }
    }

    /**
     * What the webhook signs: the context, the timestamp and each field of the request, one per line, in a fixed order.
     * A field that is absent counts as empty.
     *
     * @param timestamp the signing time as sent
     * @param request   the keytab request
     * @return the bytes to sign; null when a field holds a line break, which would make two requests sign alike
     */
    public static byte[] signingInput(String timestamp, KeytabRequest request) {
        List<String> lines = List.of(SIGNATURE_CONTEXT, timestamp,
                orEmpty(request.getPrincipal()), orEmpty(request.getNamespace()), orEmpty(request.getPodName()),
                orEmpty(request.getSecretName()), orEmpty(request.getKeyNameInSecret()),
                orEmpty(request.getReleaseName()));
        for (String line : lines) {
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                return null;
            }
        }
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The password of the webhook's Ambari account, derived from the private key of its client certificate: whoever
     * holds the key can sign in, and nobody else learns the password from what Kubernetes stores. The webhook computes
     * the same value from the key it mounts.
     *
     * @param clientPrivateKeyPem the webhook's private key (PEM, PKCS#8)
     * @return the password: base64url, without padding, of SHA-256 over a fixed context and the key's DER bytes
     */
    public static String accountPassword(String clientPrivateKeyPem) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(PASSWORD_CONTEXT.getBytes(StandardCharsets.UTF_8));
            digest.update(pemBody(clientPrivateKeyPem));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static String first(MultivaluedMap<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        // Header names are case-insensitive; the map handed to the view may keep the case the client used.
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                List<String> values = headers.get(key);
                String value = values == null || values.isEmpty() ? null : values.get(0);
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** The DER bytes of the first PEM block of {@code pem}. */
    private static byte[] pemBody(String pem) {
        Matcher block = PEM_BLOCK.matcher(pem);
        String body = block.find() ? block.group(1) : pem;
        return Base64.getDecoder().decode(body.replaceAll("\\s", ""));
    }

    private static X509Certificate parseCertificate(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static String commonName(X500Principal principal) {
        for (String part : principal.getName(X500Principal.RFC2253).split(",")) {
            if (part.regionMatches(true, 0, "CN=", 0, 3)) {
                return part.substring(3);
            }
        }
        return null;
    }

    private static boolean allowsClientAuthentication(X509Certificate certificate) {
        try {
            List<String> usages = certificate.getExtendedKeyUsage();
            return usages != null && usages.contains(CLIENT_AUTH_USAGE);
        } catch (Exception e) {
            return false;
        }
    }

    private static String signatureAlgorithm(PublicKey key) {
        return "RSA".equalsIgnoreCase(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
    }
}
