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

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.crypto.SecretKey;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;

/**
 * Manages Ambari's internal CA (PEM files on disk), issues/rotates client & server TLS certs,
 * and creates/updates Kubernetes Secrets.
 *
 * Concurrency hardening:
 *  - Per-directory JVM-level ReadWriteLock.
 *  - Cross-process exclusive lock via .ca.lock file.
 *  - Atomic, fsync'ed file writes with ATOMIC_MOVE.
 */
public class WebHookConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(WebHookConfigurationService.class);

    private static final String BOUNCY_CASTLE_PROVIDER = "BC";

    // Default file names for the internal CA
    private static final String DEFAULT_CA_CERT_FILENAME = "ambari-ca.crt";
    private static final String DEFAULT_CA_KEY_FILENAME  = "ambari-ca.key";

    // Default validity
    private static final int DEFAULT_CA_VALIDITY_DAYS      = 3650; // ~10 years
    private static final int DEFAULT_CLIENT_VALIDITY_DAYS  = 360;  // ~12 months

    /** An ingress leaf with fewer days left than this is re-minted by a redeploy rather than kept. */
    static final int INGRESS_LEAF_MIN_DAYS_LEFT = 30;

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;

    // Per-directory in-JVM lock map
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> CA_LOCKS = new ConcurrentHashMap<>();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public WebHookConfigurationService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
    }

    /**
     * The view's current Kubernetes client. Read on every call: the connection is replaced when the operator
     * uploads a kubeconfig, selects another context or the token is renewed, and the previous one is closed.
     */
    private KubernetesClient kubernetesClient() {
        return Objects.requireNonNull(kubernetesService.getClient(), "The view is not configured with a kubeconfig.");
    }

    // -------------------- Public API --------------------

    /** Ensure Ambari CA exists on disk and return its material. */
    public CertificateAuthorityMaterial ensureAmbariCertificateAuthority() {
        Path caDirectory = resolveCaDirectory();
        Path caCertPath  = caDirectory.resolve(DEFAULT_CA_CERT_FILENAME);
        Path caKeyPath   = caDirectory.resolve(DEFAULT_CA_KEY_FILENAME);

        ReentrantReadWriteLock.WriteLock writeLock = caLock(caDirectory).writeLock();
        writeLock.lock();
        FileLock processLock = lockDirectoryExclusive(caDirectory);
        try {
            runIO(() -> { Files.createDirectories(caDirectory); return null; });

            if (Files.exists(caCertPath) && Files.exists(caKeyPath)) {
                LOG.info("Ambari CA already present at {}", caDirectory);
                String caCertPem = runIO(() -> Files.readString(caCertPath, StandardCharsets.UTF_8));
                String caKeyPem  = runIO(() -> Files.readString(caKeyPath,  StandardCharsets.UTF_8));
                return new CertificateAuthorityMaterial(caCertPem, caKeyPem, caDirectory.toString());
            }

            LOG.info("Generating a new Ambari internal CA under {}", caDirectory);
            KeyPair caKeyPair = generateKeyPair();
            X509Certificate caCertificate = selfSignCaCertificate(caKeyPair, "CN=Ambari Internal CA");

            String caCertPem = toPemCertificate(caCertificate);
            String caKeyPem  = toPemPrivateKey(caKeyPair.getPrivate());

            // atomic writes
            writeString(caCertPath, caCertPem);
            writeString(caKeyPath,  caKeyPem);

            LOG.info("Ambari CA generated: {}, {}", caCertPath, caKeyPath);
            return new CertificateAuthorityMaterial(caCertPem, caKeyPem, caDirectory.toString());
        } finally {
            try { if (processLock != null) processLock.close(); } catch (IOException ignore) {}
            writeLock.unlock();
        }
    }

    /** Issue a client certificate (mTLS) for webhook (or any client). */
    public ClientCertificateMaterial issueClientCertificate(String subjectCommonName,
                                                            List<String> dnsSubjectAlternativeNames,
                                                            List<String> uriSubjectAlternativeNames,
                                                            Integer validityDays) {
        Objects.requireNonNull(subjectCommonName, "subjectCommonName");
        LOG.info("Issuing client certificate for CN='{}', DNS SANs={}, URI SANs={}, validityDays={}",
                subjectCommonName, dnsSubjectAlternativeNames, uriSubjectAlternativeNames, validityDays);

        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        KeyPair clientKeyPair = generateKeyPair();

        int days = (validityDays == null || validityDays <= 0) ? DEFAULT_CLIENT_VALIDITY_DAYS : validityDays;

        X509Certificate clientCertificate = signClientCertificate(
                clientKeyPair.getPublic(),
                subjectCommonName,
                safeList(dnsSubjectAlternativeNames),
                safeList(uriSubjectAlternativeNames),
                days,
                ca
        );

        return new ClientCertificateMaterial(
                toPemCertificate(clientCertificate),
                toPemPrivateKey(clientKeyPair.getPrivate()),
                ca.caCertificatePem()
        );
    }

    /**
     * Issue a server certificate signed by the Ambari internal CA and package it into a keystore.
     *
     * @param dnsSans      DNS Subject Alternative Names to include
     * @param keystoreType keystore type (PKCS12 default, also supports JKS, PEM)
     * @param password     keystore password
     * @param validityDays validity in days (default 365)
     * @return keystore material plus PEMs for convenience
     */
    public ServerKeystoreMaterial issueServerKeystore(List<String> dnsSans,
                                                      String keystoreType,
                                                      char[] password,
                                                      Integer validityDays) {
        Objects.requireNonNull(password, "password");
        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        KeyPair serverKeyPair = generateKeyPair();
        int days = (validityDays == null || validityDays <= 0) ? 365 : validityDays;

        X509Certificate serverCertificate = signServerCertificate(
                serverKeyPair.getPublic(),
                safeList(dnsSans),
                days,
                ca
        );
        try {
            String type = (keystoreType == null || keystoreType.isBlank()) ? "PKCS12" : keystoreType.toUpperCase(Locale.ROOT);
            String serverCertPem = toPemCertificate(serverCertificate);
            String keyPem = toPemPrivateKey(serverKeyPair.getPrivate());

            if ("PEM".equals(type)) {
                // Return PEM bundle only; caller will create tls.crt/tls.key/ca.crt in Secret.
                return new ServerKeystoreMaterial(serverCertPem, keyPem, ca.caCertificatePem(), new byte[0]);
            }

            KeyStore ks = KeyStore.getInstance(type);
            ks.load(null, password);
            X509Certificate caCert = parseCertificateFromPem(ca.caCertificatePem());
            ks.setKeyEntry("server", serverKeyPair.getPrivate(), password, new Certificate[]{serverCertificate, caCert});
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ks.store(baos, password);
            return new ServerKeystoreMaterial(serverCertPem, keyPem, ca.caCertificatePem(), baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build server keystore", e);
        }
    }

    /** Create or replace the Opaque Secret with client.crt, client.key, ca.crt. */
    public void createOrUpdateWebhookClientSecret(String namespace,
                                                  String secretName,
                                                  ClientCertificateMaterial clientMaterial,
                                                  Map<String, String> additionalLabels) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(clientMaterial, "clientMaterial");

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        if (additionalLabels != null) labels.putAll(additionalLabels);

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .addToLabels(labels)
                .addToAnnotations("managed-at", Instant.now().toString())
                .endMetadata()
                .withType("Opaque")
                .addToData("client.crt", base64(clientMaterial.clientCertificatePem()))
                .addToData("client.key", base64(clientMaterial.clientPrivateKeyPem()))
                .addToData("ca.crt",     base64(clientMaterial.certificateAuthorityCertificatePem()))
                .build();

        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            kubernetesClient().secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created webhook client mTLS Secret '{}/{}'", namespace, secretName);
        } else {
            kubernetesClient().secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated webhook client mTLS Secret '{}/{}'", namespace, secretName);
        }
    }

    /** Convenience: ensure CA → issue client cert → create/update Secret. */
    public void ensureClientMtlsSecret(String namespace,
                                       String secretName,
                                       String subjectCommonName,
                                       List<String> dnsSubjectAlternativeNames,
                                       List<String> uriSubjectAlternativeNames,
                                       Integer clientValidityDays,
                                       Map<String, String> additionalLabels) {
        ClientCertificateMaterial client = issueClientCertificate(
                subjectCommonName,
                dnsSubjectAlternativeNames,
                uriSubjectAlternativeNames,
                clientValidityDays
        );
        createOrUpdateWebhookClientSecret(namespace, secretName, client, additionalLabels);
    }

    /**
     * The private key of the webhook's client certificate, as stored in its Secret.
     *
     * @param namespace  the webhook's namespace
     * @param secretName the client certificate's Secret ({@code client.key})
     * @return the key (PEM)
     * @throws IllegalStateException when the Secret or its key is missing
     */
    public String readClientPrivateKeyPem(String namespace, String secretName) {
        Secret secret = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        String key = secret == null || secret.getData() == null ? null : secret.getData().get("client.key");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Secret " + namespace + "/" + secretName + " holds no client.key");
        }
        return new String(getDecoder().decode(key), StandardCharsets.UTF_8);
    }

    /**
     * Deletes a Secret the view wrote, when it exists.
     *
     * @return true when a Secret was deleted
     */
    public boolean deleteSecretIfPresent(String namespace, String secretName) {
        if (kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get() == null) {
            return false;
        }
        kubernetesClient().secrets().inNamespace(namespace).withName(secretName).delete();
        LOG.info("Deleted Secret {}/{}", namespace, secretName);
        return true;
    }

    /**
     * The certificate of the view's internal CA (PEM), read without creating the CA.
     *
     * @param viewContext the view instance
     * @return the certificate; null when the view has not created its CA yet
     */
    public static String certificateAuthorityPem(ViewContext viewContext) {
        Path certificate = caDirectory(viewContext).resolve(DEFAULT_CA_CERT_FILENAME);
        try {
            return Files.exists(certificate) ? Files.readString(certificate, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            LOG.warn("Could not read the view's internal CA {}: {}", certificate, e.toString());
            return null;
        }
    }

    // -------------------- New rotation-aware helpers --------------------

    /** Return base64(der) of current CA (no rotation). */
    public String currentCaBundleBase64() {
        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        // ✅ Return base64(PEM BYTES), not DER
        return java.util.Base64.getEncoder()
            .encodeToString(ca.caCertificatePem().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * If CA expires within minDaysLeft, rotate it (write new PEMs on disk) and return base64(der) of the NEW CA.
     * Otherwise, return base64(der) of the current CA without changes.
     */
    public String ensureCaWithOptionalRotation(int minDaysLeft) {
        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        X509Certificate caCert = parseCertificateFromPem(ca.caCertificatePem());
        if (!expiresBefore(caCert, minDaysLeft)) {
            // ✅ base64(PEM bytes)
            return java.util.Base64.getEncoder()
                .encodeToString(ca.caCertificatePem().getBytes(StandardCharsets.UTF_8));
        }

        LOG.warn("Ambari CA is nearing expiration (notAfter={}): rotating CA",
                caCert.getNotAfter().toInstant());

        Path dir = Paths.get(ca.storageDirectory());
        ReentrantReadWriteLock.WriteLock writeLock = caLock(dir).writeLock();
        writeLock.lock();
        FileLock processLock = lockDirectoryExclusive(dir);
        try {
            // Re-check inside lock to avoid races if another thread rotated meanwhile
            CertificateAuthorityMaterial current = ensureAmbariCertificateAuthority();
            X509Certificate currentCert = parseCertificateFromPem(current.caCertificatePem());
            if (!expiresBefore(currentCert, minDaysLeft)) {
                return getEncoder().encodeToString(currentCert.getEncoded());
            }

            KeyPair kp = generateKeyPair();
            X509Certificate newCa = selfSignCaCertificate(kp, "CN=Ambari Internal CA");

            String newPem = toPemCertificate(newCa);
            writeString(dir.resolve(DEFAULT_CA_CERT_FILENAME), newPem);
            writeString(dir.resolve(DEFAULT_CA_KEY_FILENAME),  toPemPrivateKey(kp.getPrivate()));

            return getEncoder().encodeToString(newPem.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rotate CA", e);
        } finally {
            try { if (processLock != null) processLock.close(); } catch (IOException ignore) {}
            writeLock.unlock();
        }
    }

    /**
     * Ensure/rotate client mTLS secret if absent/invalid/expiring within minDaysLeft. A renewed certificate keeps the
     * key the Secret already holds, when that key is usable (see {@link #renewClientMtlsSecretIfPresent}).
     */
    public void ensureOrRotateClientMtlsSecret(String namespace,
                                               String secretName,
                                               String subjectCommonName,
                                               List<String> dnsSans,
                                               List<String> uriSans,
                                               int validityDays,
                                               int minDaysLeft,
                                               Map<String,String> extraLabels) {
        issueClientMtlsSecretIfDue(namespace, secretName, subjectCommonName, dnsSans, uriSans, validityDays, minDaysLeft,
                extraLabels, true);
    }

    /**
     * Renews the client mTLS Secret when it exists and its certificate is unreadable, was issued by an earlier CA, or
     * expires within {@code minDaysLeft}; a missing Secret is left missing. The new certificate is issued for the key
     * the Secret already holds, which is kept byte for byte: the keytab webhook's Ambari password is derived from that
     * key, so the renewal needs no Ambari administrator and does not change the password under the running webhook.
     * Only the view's Kubernetes access and its internal CA are used.
     *
     * @return true when a new certificate was written
     */
    public boolean renewClientMtlsSecretIfPresent(String namespace,
                                                  String secretName,
                                                  String subjectCommonName,
                                                  int validityDays,
                                                  int minDaysLeft,
                                                  Map<String,String> extraLabels) {
        return issueClientMtlsSecretIfDue(namespace, secretName, subjectCommonName, List.of(), List.of(), validityDays,
                minDaysLeft, extraLabels, false);
    }

    /**
     * The expiry of the certificate in a client mTLS Secret.
     *
     * @return when it expires; null when the Secret or its certificate is missing or unreadable
     */
    public Instant clientCertificateNotAfter(String namespace, String secretName) {
        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        String crt = existing == null || existing.getData() == null ? null : existing.getData().get("client.crt");
        if (crt == null) {
            return null;
        }
        try {
            return parseCertificateFromPem(new String(getDecoder().decode(crt), StandardCharsets.UTF_8))
                    .getNotAfter().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean issueClientMtlsSecretIfDue(String namespace,
                                               String secretName,
                                               String subjectCommonName,
                                               List<String> dnsSans,
                                               List<String> uriSans,
                                               int validityDays,
                                               int minDaysLeft,
                                               Map<String,String> extraLabels,
                                               boolean createIfAbsent) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(secretName);

        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null && !createIfAbsent) {
            return false;
        }
        boolean needIssue = true;
        X509Certificate current = null;

        if (existing != null && existing.getData() != null && existing.getData().get("client.crt") != null) {
            try {
                String crtPem = new String(getDecoder().decode(existing.getData().get("client.crt")), StandardCharsets.UTF_8);
                X509Certificate crt = parseCertificateFromPem(crtPem);
                current = crt;
                if (!issuedByCurrentAuthority(crt)) {
                    LOG.info("Client mTLS Secret {}/{} was issued by an earlier CA, will reissue", namespace, secretName);
                } else if (!expiresBefore(crt, minDaysLeft)) {
                    needIssue = false;
                    LOG.info("Client mTLS Secret up-to-date (notAfter={}) {}/{}",
                            crt.getNotAfter().toInstant(), namespace, secretName);
                } else {
                    LOG.info("Client mTLS Secret expiring soon (notAfter={}), will rotate {}/{}",
                            crt.getNotAfter().toInstant(), namespace, secretName);
                }
            } catch (Exception e) {
                LOG.warn("Client mTLS secret present but invalid; will reissue {}/{}: {}",
                        namespace, secretName, e.toString());
            }
        }

        if (!needIssue) return false;

        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        String keyData = existing == null || existing.getData() == null ? null : existing.getData().get("client.key");
        PublicKey kept = current == null ? null : publicKeyIfPaired(keyData, current.getPublicKey());
        String certificatePem;
        String keyDataOut;
        if (kept != null) {
            certificatePem = toPemCertificate(signClientCertificate(kept, subjectCommonName, safeList(dnsSans),
                    safeList(uriSans), validityDays, ca));
            keyDataOut = keyData;
        } else {
            ClientCertificateMaterial client = issueClientCertificate(subjectCommonName, dnsSans, uriSans, validityDays);
            certificatePem = client.clientCertificatePem();
            keyDataOut = base64(client.clientPrivateKeyPem());
        }

        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        if (extraLabels != null) labels.putAll(extraLabels);

        X509Certificate c = parseCertificateFromPem(certificatePem);
        Map<String,String> ann = rotationAnnotations(c, null);

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName).withNamespace(namespace)
                .addToLabels(labels)
                .addToAnnotations(ann)
                .endMetadata()
                .withType("Opaque")
                .addToData("client.crt", base64(certificatePem))
                .addToData("client.key", keyDataOut)
                .addToData("ca.crt",     base64(ca.caCertificatePem()))
                .build();

        kubernetesClient().secrets().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Issued/rotated client mTLS Secret {}/{} (notAfter={}, {})", namespace, secretName,
                c.getNotAfter().toInstant(), kept != null ? "same key" : "new key");
        return true;
    }

    /**
     * The certificate's public key when the stored private key is its pair, else null: a key that does not match, or
     * cannot be read, is replaced by a new one.
     */
    private static PublicKey publicKeyIfPaired(String keyData, PublicKey certificateKey) {
        if (keyData == null || keyData.isBlank()) {
            return null;
        }
        try {
            PrivateKey key = parsePrivateKeyFromPem(new String(getDecoder().decode(keyData), StandardCharsets.UTF_8));
            byte[] probe = "kdps-client-key-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(certificateKey);
            verifier.update(probe);
            return verifier.verify(signature) ? certificateKey : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ensure/rotate serving TLS Secret (kubernetes.io/tls) for the webhook Service.
     * SANs must include: service.ns.svc and service.ns.svc.cluster.local (or your DNS suffix).
     */
    public void ensureOrRotateServingTlsSecret(String namespace,
                                               String secretName,
                                               List<String> dnsSans,
                                               int validityDays,
                                               int minDaysLeft) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(secretName);

        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        boolean needIssue = true;

        if (existing != null && existing.getData() != null && existing.getData().get("tls.crt") != null) {
            try {
                String crtPem = new String(getDecoder().decode(existing.getData().get("tls.crt")), StandardCharsets.UTF_8);
                X509Certificate crt = parseCertificateFromPem(crtPem);
                if (!issuedByCurrentAuthority(crt)) {
                    LOG.info("Client mTLS Secret {}/{} was issued by an earlier CA, will reissue", namespace, secretName);
                } else if (!expiresBefore(crt, minDaysLeft)) {
                    needIssue = false;
                    LOG.info("Serving TLS Secret up-to-date (notAfter={}) {}/{}",
                            crt.getNotAfter().toInstant(), namespace, secretName);
                } else {
                    LOG.info("Serving TLS Secret expiring soon (notAfter={}), will rotate {}/{}",
                            crt.getNotAfter().toInstant(), namespace, secretName);
                }
            } catch (Exception e) {
                LOG.warn("Serving TLS secret present but invalid; will reissue {}/{}: {}",
                        namespace, secretName, e.toString());
            }
        }

        if (!needIssue) return;

        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        KeyPair kp = generateKeyPair();
        X509Certificate caCert = parseCertificateFromPem(ca.caCertificatePem());
        PrivateKey caKey = parsePrivateKeyFromPem(ca.caPrivateKeyPem());

        try {
            X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
            String cn = (dnsSans != null && !dnsSans.isEmpty()) ? dnsSans.get(0) : "webhook.svc";
            X500Name subject = new X500Name(new X500Principal("CN=" + cn).getName());
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(validityDays, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder b = new X509v3CertificateBuilder(
                    issuer, serial, Date.from(notBefore), Date.from(notAfter), subject,
                    SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded())
            );

            b.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            b.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            b.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtils.createSubjectKeyIdentifier(kp.getPublic()));

            if (dnsSans != null && !dnsSans.isEmpty()) {
                List<GeneralName> san = new ArrayList<>();
                for (String dns : dnsSans) {
                    if (dns != null && !dns.isBlank()) san.add(new GeneralName(GeneralName.dNSName, dns));
                }
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san.toArray(new GeneralName[0])));
            }

            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmFor(caKey))
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .build(caKey);

            X509CertificateHolder holder = b.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .getCertificate(holder);

            String certPem = toPemCertificate(cert);
            String keyPem  = toPemPrivateKey(kp.getPrivate());

            Map<String,String> ann = rotationAnnotations(cert, hexSerial(caCert));

            Secret desired = new SecretBuilder()
                    .withNewMetadata()
                    .withName(secretName).withNamespace(namespace)
                    .addToLabels("managed-by", "ambari-k8s-view")
                    .addToLabels("component", "webhook-serving-tls")
                    .addToAnnotations(ann)
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .addToData("tls.crt", getEncoder().encodeToString(certPem.getBytes(StandardCharsets.UTF_8)))
                    .addToData("tls.key", getEncoder().encodeToString(keyPem.getBytes(StandardCharsets.UTF_8)))
                    .build();

            kubernetesClient().secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Issued/rotated serving TLS Secret {}/{} (notAfter={})",
                    namespace, secretName, cert.getNotAfter().toInstant());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue serving TLS cert", e);
        }
    }

    // -------------------- Internal: CA + Cert generation --------------------

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate key pair", e);
        }
    }

    private X509Certificate selfSignCaCertificate(KeyPair caKeyPair, String distinguishedName) {
        try {
            LOG.info("Self-signing CA certificate with DN='{}'", distinguishedName);
            X500Name issuerAndSubject = new X500Name(distinguishedName);
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(DEFAULT_CA_VALIDITY_DAYS, ChronoUnit.DAYS);

            BigInteger serial = new BigInteger(64, new SecureRandom());
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuerAndSubject,
                    serial,
                    Date.from(notBefore),
                    Date.from(notAfter),
                    issuerAndSubject,
                    SubjectPublicKeyInfo.getInstance(caKeyPair.getPublic().getEncoded())
            );

            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .build(caKeyPair.getPrivate());

            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to self-sign CA certificate", e);
        }
    }

    /**
     *
     * @param clientPublicKey
     * @param subjectCommonName
     * @param dnsSubjectAlternativeNames
     * @param uriSubjectAlternativeNames
     * @param validityDays
     * @param certificateAuthorityMaterial
     * @return
     */
    private X509Certificate signClientCertificate(PublicKey clientPublicKey,
                                                  String subjectCommonName,
                                                  List<String> dnsSubjectAlternativeNames,
                                                  List<String> uriSubjectAlternativeNames,
                                                  int validityDays,
                                                  CertificateAuthorityMaterial certificateAuthorityMaterial) {
        try {
            X509Certificate caCert = parseCertificateFromPem(certificateAuthorityMaterial.caCertificatePem());
            PrivateKey caPrivateKey = parsePrivateKeyFromPem(certificateAuthorityMaterial.caPrivateKeyPem());

            X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
            X500Name subject = new X500Name(new X500Principal("CN=" + subjectCommonName).getName());

            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(validityDays, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuer,
                    serial,
                    Date.from(notBefore),
                    Date.from(notAfter),
                    subject,
                    SubjectPublicKeyInfo.getInstance(clientPublicKey.getEncoded())
            );

            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtils.createSubjectKeyIdentifier(clientPublicKey));

            List<GeneralName> sanEntries = new ArrayList<>();
            for (String dns : dnsSubjectAlternativeNames) {
                if (dns != null && !dns.isBlank()) sanEntries.add(new GeneralName(GeneralName.dNSName, dns));
            }
            for (String uri : uriSubjectAlternativeNames) {
                if (uri != null && !uri.isBlank()) sanEntries.add(new GeneralName(GeneralName.uniformResourceIdentifier, uri));
            }
            if (!sanEntries.isEmpty()) {
                GeneralNames gns = new GeneralNames(sanEntries.toArray(new GeneralName[0]));
                builder.addExtension(Extension.subjectAlternativeName, false, gns);
            }

            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmFor(caPrivateKey))
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .build(caPrivateKey);

            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .getCertificate(holder);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign client certificate", e);
        }
    }

    private X509Certificate signServerCertificate(PublicKey serverPublicKey,
                                                  List<String> dnsSubjectAlternativeNames,
                                                  int validityDays,
                                                  CertificateAuthorityMaterial certificateAuthorityMaterial) {
        try {
            X509Certificate caCert = parseCertificateFromPem(certificateAuthorityMaterial.caCertificatePem());
            PrivateKey caPrivateKey = parsePrivateKeyFromPem(certificateAuthorityMaterial.caPrivateKeyPem());

            X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
            String cn = (dnsSubjectAlternativeNames != null && !dnsSubjectAlternativeNames.isEmpty())
                    ? dnsSubjectAlternativeNames.get(0)
                    : "service.svc";
            X500Name subject = new X500Name(new X500Principal("CN=" + cn).getName());

            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(validityDays, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuer,
                    serial,
                    Date.from(notBefore),
                    Date.from(notAfter),
                    subject,
                    SubjectPublicKeyInfo.getInstance(serverPublicKey.getEncoded())
            );

            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtils.createSubjectKeyIdentifier(serverPublicKey));

            List<GeneralName> sanEntries = new ArrayList<>();
            for (String dns : dnsSubjectAlternativeNames) {
                if (dns != null && !dns.isBlank()) sanEntries.add(new GeneralName(GeneralName.dNSName, dns));
            }
            if (!sanEntries.isEmpty()) {
                GeneralNames gns = new GeneralNames(sanEntries.toArray(new GeneralName[0]));
                builder.addExtension(Extension.subjectAlternativeName, false, gns);
            }

            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmFor(caPrivateKey))
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .build(caPrivateKey);

            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter()
                    .setProvider(BOUNCY_CASTLE_PROVIDER)
                    .getCertificate(holder);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign server certificate", e);
        }
    }

    // -------------------- Helpers --------------------

    /** Whether the view's current internal CA signed the certificate: one signed by a rotated-out CA is not accepted. */
    private boolean issuedByCurrentAuthority(X509Certificate cert) {
        try {
            cert.verify(parseCertificateFromPem(ensureAmbariCertificateAuthority().caCertificatePem()).getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean expiresBefore(X509Certificate cert, int days) {
        Instant cutoff = Instant.now().plus(days, ChronoUnit.DAYS);
        return cert.getNotAfter().toInstant().isBefore(cutoff);
    }

    private static Map<String,String> rotationAnnotations(X509Certificate cert, String issuerSerialHex) {
        Map<String,String> a = new LinkedHashMap<>();
        a.put("clemlab.com/not-after", cert.getNotAfter().toInstant().toString());
        a.put("clemlab.com/issuer-serial", issuerSerialHex != null ? issuerSerialHex : "unknown");
        a.put("managed-by", "ambari-k8s-view");
        return a;
    }

    private static String hexSerial(X509Certificate cert) {
        return cert.getSerialNumber().toString(16);
    }

    // -------------------- Atomic FS + locking helpers --------------------

    /** Cross-process exclusive lock on a per-directory lock file. */
    private FileLock lockDirectoryExclusive(Path dir) {
        try {
            Files.createDirectories(dir);
            Path lockFile = dir.resolve(".ca.lock");
            FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.lock(); // blocks until exclusive
            return lock; // caller closes() to release (channel closes with it)
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to lock CA directory " + dir, e);
        }
    }

    /** Atomic write with fsync using temp file + ATOMIC_MOVE. */
    private void writeStringAtomic(Path path, String content) {
        runIO(() -> {
            Path dir = path.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true); // fsync data+metadata
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return null;
        });
    }

    private void writeString(Path path, String content) {
        writeStringAtomic(path, content);
    }

    private ReentrantReadWriteLock caLock(Path caDir) {
        String key = caDir.toAbsolutePath().normalize().toString();
        return CA_LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private Path resolveCaDirectory() {
        return caDirectory(viewContext);
    }

    private static Path caDirectory(ViewContext viewContext) {
        // Priority: ambari.property k8s.view.webhook.ca.dir → else use view resource path + "/ca"
        String configured = viewContext.getAmbariProperty("k8s.view.webhook.ca.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(ViewConfigurationService.workingDirectory(viewContext), "ca");
    }

    // Small utility to wrap checked IO without deprecated doPrivileged
    private <T> T runIO(CheckedIO<T> action) {
        try {
            return action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface CheckedIO<T> {
        T run() throws Exception;
    }

    // -------------------- PEM helpers (simple, no password) --------------------

    private static String toPemCertificate(X509Certificate certificate) {
        try {
            String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Failed to encode certificate as PEM", e);
        }
    }

    private static String toPemPrivateKey(PrivateKey privateKey) {
        String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static X509Certificate parseCertificateFromPem(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = getDecoder().decode(normalized);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid certificate PEM", e);
        }
    }

    /**
     * Reads a private key in PEM: PKCS#8 ({@code BEGIN PRIVATE KEY}), PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) or SEC1
     * ({@code BEGIN EC PRIVATE KEY}), RSA or EC. An encrypted key is refused: KDPS has no passphrase for it.
     */
    static PrivateKey parsePrivateKeyFromPem(String pem) {
        Object parsed = null;
        // OpenSSL writes an EC key's curve in an "EC PARAMETERS" block before the key: the blocks before it are passed.
        try (org.bouncycastle.openssl.PEMParser parser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(pem))) {
            for (Object block = parser.readObject(); block != null; block = parser.readObject()) {
                if (block instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo
                        || block instanceof org.bouncycastle.openssl.PEMKeyPair
                        || block instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair
                        || block instanceof org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo) {
                    parsed = block;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid CA private key PEM", e);
        }
        try {
            org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter converter =
                    new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE_PROVIDER);
            if (parsed instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            if (parsed instanceof org.bouncycastle.openssl.PEMKeyPair pair) {
                // Only the private half: a SEC1 key may leave its public key out.
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid CA private key PEM", e);
        }
        if (parsed instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair
                || parsed instanceof org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo) {
            throw new IllegalStateException("The CA private key is encrypted: upload it without a passphrase.");
        }
        throw new IllegalStateException("Invalid CA private key PEM: no private key found");
    }

    /** The signature algorithm of a key: SHA256withRSA for an RSA key, SHA256withECDSA for an EC one. */
    static String signatureAlgorithmFor(PrivateKey key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            default -> throw new IllegalStateException("The CA private key is a " + key.getAlgorithm()
                    + " key: KDPS signs with RSA and EC keys.");
        };
    }

    /**
     * Checks that a CA private key belongs to the CA certificate: a leaf signed with another key would name the CA as
     * its issuer, yet never verify against it.
     *
     * @throws IllegalArgumentException when the key cannot be read, is encrypted, or is not the certificate's
     */
    public static void checkCaKeyMatches(String caCertPem, String caKeyPem) {
        PrivateKey key;
        try {
            key = parsePrivateKeyFromPem(caKeyPem);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        try {
            X509Certificate caCert = parseCertificateFromPem(caCertPem);
            byte[] probe = "kdps-ca-key-check".getBytes(StandardCharsets.UTF_8);
            java.security.Signature signer = java.security.Signature.getInstance(signatureAlgorithmFor(key));
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            java.security.Signature verifier = java.security.Signature.getInstance(signatureAlgorithmFor(key));
            verifier.initVerify(caCert.getPublicKey());
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("The private key does not belong to the certificate: upload the key "
                        + "of this CA.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The private key does not belong to the certificate: " + e.getMessage(), e);
        }
    }

    private static String base64(String value) {
        return getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> List<T> safeList(List<T> maybeNull) {
        return maybeNull == null ? Collections.emptyList() : maybeNull;
    }

    // -------------------- Ingress TLS minting (cert + key + chain) --------------------

    /**
     * Mint a leaf TLS certificate for an ingress host and write it into a
     * {@code kubernetes.io/tls} Secret. Used by the {@code INGRESS_TLS_SELFSIGN}
     * deploy step when the operator picks {@code signedByAmbariCA} or
     * {@code signedByCompanyCA} mode.
     *
     * <p>The signing CA is provided by the caller — for Ambari Internal CA use
     * {@link #ensureAmbariCertificateAuthority()}, for an admin-uploaded company
     * CA use {@link CaRegistryService#material(String)}.
     *
     * <p>Only a Secret KDPS minted itself is written over (see {@link IngressTlsSecretGuard}), and a new leaf is
     * always minted, as "Renew TLS" wants.
     *
     * @param namespace      target namespace
     * @param secretName     Secret name to create / replace (typically {@code <release>-ingress-tls})
     * @param ingressHost    primary DNS name (becomes CN + first SAN entry)
     * @param extraSans      additional DNS SANs (cluster service name etc., may be empty)
     * @param validityDays   leaf validity (clamped to 1..825 to comply with the CA/Browser Forum cap)
     * @param ca             the signing CA material
     * @param additionalLabels extra labels applied to the Secret (audit / managed-by)
     * @throws IllegalStateException when the Secret exists and KDPS did not mint it
     */
    public ServerKeystoreMaterial issueIngressTlsSecret(String namespace,
                                                        String secretName,
                                                        String ingressHost,
                                                        List<String> extraSans,
                                                        Integer validityDays,
                                                        CertificateAuthorityMaterial ca,
                                                        Map<String, String> additionalLabels) {
        return issueIngressTlsSecret(namespace, secretName, ingressHost, extraSans, validityDays, ca,
                additionalLabels, false, false).material();
    }

    /**
     * Mint a leaf TLS certificate for an ingress host into a {@code kubernetes.io/tls} Secret, as
     * {@link #issueIngressTlsSecret(String, String, String, List, Integer, CertificateAuthorityMaterial, Map)}, with
     * two choices for a Secret that already exists.
     *
     * @param replaceExisting whether a Secret KDPS did not mint may be written over: the operator confirmed it. One
     *                        kept by cert-manager or the External Secrets Operator never is.
     * @param keepValidLeaf   whether a leaf KDPS minted with the same CA, for every requested name and with more than
     *                        {@link #INGRESS_LEAF_MIN_DAYS_LEFT} days left, is kept as it is instead of being re-minted:
     *                        a redeploy then leaves the Secret, and the certificate clients see, unchanged
     * @return the certificate the Secret holds, and whether it was kept
     * @throws IllegalStateException when the Secret must be left as it is (see {@link IngressTlsSecretGuard})
     */
    public IngressTlsOutcome issueIngressTlsSecret(String namespace,
                                                   String secretName,
                                                   String ingressHost,
                                                   List<String> extraSans,
                                                   Integer validityDays,
                                                   CertificateAuthorityMaterial ca,
                                                   Map<String, String> additionalLabels,
                                                   boolean replaceExisting,
                                                   boolean keepValidLeaf) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(ingressHost, "ingressHost");
        Objects.requireNonNull(ca, "ca");
        if (ingressHost.isBlank()) {
            throw new IllegalArgumentException("ingressHost must be non-empty");
        }

        // Build SAN list: ingress host first (also used as CN by signServerCertificate),
        // then any extras the caller wants (service.svc, service.svc.cluster.local, etc).
        List<String> sans = new ArrayList<>();
        sans.add(ingressHost);
        if (extraSans != null) {
            for (String s : extraSans) {
                if (s != null && !s.isBlank() && !sans.contains(s)) sans.add(s);
            }
        }

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        labels.put(IngressTlsSecretGuard.ISSUER_LABEL, "self-signed");
        if (additionalLabels != null) labels.putAll(additionalLabels);

        // Checked before anything is minted: a refused Secret must stay exactly as it is.
        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        IngressTlsSecretGuard.checkReplaceable(existing, namespace, secretName, replaceExisting);
        if (keepValidLeaf && existing != null) {
            X509Certificate current = currentIngressLeaf(existing, labels, sans, ca);
            if (current != null) {
                LOG.info("Kept ingress TLS Secret '{}/{}': its leaf is from the same CA, covers {} and is valid"
                        + " until {}", namespace, secretName, sans, current.getNotAfter().toInstant());
                Map<String, String> data = existing.getData();
                return new IngressTlsOutcome(new ServerKeystoreMaterial(
                        decodeUtf8(data.get("tls.crt")), decodeUtf8(data.get("tls.key")),
                        data.containsKey("ca.crt") ? decodeUtf8(data.get("ca.crt")) : ca.caCertificatePem(),
                        new byte[0]), true, current.getNotAfter().toInstant());
            }
        }

        // CA/Browser Forum baseline: leaf TLS certs cap at 825 days; clamp aggressively.
        int days = (validityDays == null || validityDays <= 0) ? 90
                : Math.min(validityDays, 825);

        // Ensure the CA itself hasn't expired (refuse to sign past it).
        try {
            X509Certificate caCert = parseCertificateFromPem(ca.caCertificatePem());
            Date now = Date.from(Instant.now().plus(days, ChronoUnit.DAYS));
            if (caCert.getNotAfter().before(now)) {
                throw new IllegalStateException("Signing CA expires before the requested leaf validity (" +
                        caCert.getNotAfter() + " < " + now + "); rotate the CA or shorten validityDays.");
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse signing CA certificate", ex);
        }

        KeyPair leafKeyPair = generateKeyPair();
        X509Certificate leafCert = signServerCertificate(leafKeyPair.getPublic(), sans, days, ca);

        String leafCertPem = toPemCertificate(leafCert);
        String leafKeyPem = toPemPrivateKey(leafKeyPair.getPrivate());

        // Build the cert chain: leaf + CA (PEM-concat) so clients can build a trust path
        // without needing the CA pre-installed.
        String chainPem = leafCertPem + ca.caCertificatePem();

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("ambari.clemlab.com/tls-issued-at", Instant.now().toString());
        annotations.put("ambari.clemlab.com/tls-not-after", leafCert.getNotAfter().toInstant().toString());
        annotations.put("ambari.clemlab.com/tls-serial",
                leafCert.getSerialNumber().toString(16));

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .addToLabels(labels)
                .addToAnnotations(annotations)
                .endMetadata()
                .withType("kubernetes.io/tls")
                // tls.crt is the chain (leaf + CA) so non-cluster clients without the CA trust
                // it via the bundle; tls.key is the leaf private key; ca.crt is the issuing CA
                // for callers that explicitly want only the CA (e.g. truststore building).
                .addToData("tls.crt", base64(chainPem))
                .addToData("tls.key", base64(leafKeyPem))
                .addToData("ca.crt",  base64(ca.caCertificatePem()))
                .build();

        if (existing == null) {
            kubernetesClient().secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created ingress TLS Secret '{}/{}' for host '{}', serial={}, notAfter={}",
                    namespace, secretName, ingressHost, leafCert.getSerialNumber().toString(16),
                    leafCert.getNotAfter().toInstant());
        } else {
            kubernetesClient().secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Replaced ingress TLS Secret '{}/{}' for host '{}', serial={}, notAfter={}",
                    namespace, secretName, ingressHost, leafCert.getSerialNumber().toString(16),
                    leafCert.getNotAfter().toInstant());
        }

        return new IngressTlsOutcome(
                new ServerKeystoreMaterial(leafCertPem, leafKeyPem, ca.caCertificatePem(), new byte[0]),
                false, leafCert.getNotAfter().toInstant());
    }

    /**
     * The leaf of {@code existing} when a redeploy can keep it: KDPS minted it with the CA the deploy asks for (same
     * {@code ca-mode} and {@code ca-name} labels, and signed by {@code ca}), it names every DNS name of {@code sans},
     * and it is valid for more than {@link #INGRESS_LEAF_MIN_DAYS_LEFT} days. Null when it must be re-minted.
     */
    private static X509Certificate currentIngressLeaf(Secret existing,
                                                      Map<String, String> requestedLabels,
                                                      List<String> sans,
                                                      CertificateAuthorityMaterial ca) {
        if (!IngressTlsSecretGuard.mintedByView(existing)) return null;
        Map<String, String> labels = existing.getMetadata().getLabels();
        for (String key : List.of("ambari.clemlab.com/ca-mode", "ambari.clemlab.com/ca-name")) {
            if (!Objects.equals(labels.get(key), requestedLabels.get(key))) return null;
        }
        Map<String, String> data = existing.getData();
        if (data == null || data.get("tls.crt") == null || data.get("tls.key") == null) return null;
        try {
            Collection<? extends Certificate> chain = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new java.io.ByteArrayInputStream(getDecoder().decode(data.get("tls.crt"))));
            if (chain.isEmpty()) return null;
            // The first certificate of the chain is the leaf (the CA follows it).
            X509Certificate leaf = (X509Certificate) chain.iterator().next();
            leaf.verify(parseCertificateFromPem(ca.caCertificatePem()).getPublicKey());
            leaf.checkValidity();
            if (expiresBefore(leaf, INGRESS_LEAF_MIN_DAYS_LEFT)) return null;
            Set<String> names = new HashSet<>();
            Collection<List<?>> altNames = leaf.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> altName : altNames) {
                    if (altName.size() > 1 && Integer.valueOf(2).equals(altName.get(0))) {
                        names.add(String.valueOf(altName.get(1)).toLowerCase(Locale.ROOT));
                    }
                }
            }
            for (String san : sans) {
                if (!names.contains(san.toLowerCase(Locale.ROOT))) return null;
            }
            return leaf;
        } catch (Exception e) {
            // Not signed by this CA, expired, or unreadable: mint a new one.
            LOG.info("Ingress TLS leaf in Secret '{}/{}' is re-minted: {}", existing.getMetadata().getNamespace(),
                    existing.getMetadata().getName(), e.toString());
            return null;
        }
    }

    private static String decodeUtf8(String base64Value) {
        return new String(getDecoder().decode(base64Value), StandardCharsets.UTF_8);
    }

    // -------------------- Value objects --------------------

    public record CertificateAuthorityMaterial(String caCertificatePem,
                                               String caPrivateKeyPem,
                                               String storageDirectory) {}

    public record ClientCertificateMaterial(String clientCertificatePem,
                                            String clientPrivateKeyPem,
                                            String certificateAuthorityCertificatePem) {}

    public record ServerKeystoreMaterial(String certificatePem,
                                         String privateKeyPem,
                                         String caCertificatePem,
                                         byte[] keystoreBytes) {}

    /**
     * What {@link #issueIngressTlsSecret} left in the ingress TLS Secret.
     *
     * @param material the certificate chain, key and CA the Secret holds
     * @param kept     true when the leaf already there was kept, false when a new one was minted
     * @param notAfter when the leaf expires
     */
    public record IngressTlsOutcome(ServerKeystoreMaterial material, boolean kept, Instant notAfter) {}


    // Load a KeyStore (JKS or PKCS12) from disk.
    public static KeyStore loadKeyStore(Path path, char[] password, String type) {
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore ks = KeyStore.getInstance(type != null && !type.isBlank() ? type : "JKS");
            ks.load(in, password);
            return ks;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load KeyStore '" + path + "' type=" + type, e);
        }
    }

    // Extract all X509 certificate entries from a KeyStore.
    public static List<X509Certificate> extractX509FromTrustStore(KeyStore ks) {
        try {
            List<X509Certificate> list = new ArrayList<>();
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                String alias = e.nextElement();
                java.security.cert.Certificate c = ks.getCertificate(alias);
                if (c instanceof X509Certificate x) {
                    list.add(x);
                } else if (c != null) {
                    // ignore non-x509 entries
                }
            }
            if (list.isEmpty()) {
                throw new IllegalStateException("No X509 certificates found in truststore");
            }
            return list;
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Failed to enumerate truststore entries", e);
        }
    }

    // Convert a list of X509 certs to a single PEM bundle string.
    public static String toPemBundle(List<X509Certificate> certs) {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : certs) {
            sb.append(toPemCertificate(c)); // you already have toPemCertificate(...)
        }
        return sb.toString();
    }

    /**
     * Parse every X.509 certificate found in a PEM string (supports a bundle carrying an
     * intermediate chain). Returns an empty list for null/blank input.
     */
    public static List<X509Certificate> parsePemCertificates(String pem) {
        if (pem == null || pem.isBlank()) {
            return new ArrayList<>();
        }
        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            List<X509Certificate> out = new ArrayList<>();
            for (java.security.cert.Certificate c : cf.generateCertificates(
                    new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))) {
                if (c instanceof X509Certificate x) {
                    out.add(x);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse PEM certificates: " + e.getMessage(), e);
        }
    }

    /**
     * Build a JKS truststore (trustedCertEntry per cert) from a cert list, protected by
     * {@code password}. Shared by the trust-bundle and truststore-registry paths so both emit an
     * identical JVM-consumable {@code truststore.jks}.
     */
    public static byte[] buildJksFromCerts(List<X509Certificate> certs, char[] password) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
            ks.load(null, null);
            int i = 0;
            for (X509Certificate c : certs) {
                ks.setCertificateEntry("ca-" + (i++), c);
            }
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                ks.store(baos, password);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build JKS truststore: " + e.getMessage(), e);
        }
    }

    // Return earliest NotAfter among certs (used for rotation decisions).
    public static Instant earliestExpiry(List<X509Certificate> certs) {
        return certs.stream()
                .map(c -> c.getNotAfter().toInstant())
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }

    // Compare two PEM bundles for exact equality (trimmed).
    public static boolean pemEqual(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }

    /**
    * Ensure/rotate a Secret containing Ambari's HTTPS truststore certificates (as PEM bundle).
    * Writes a Secret with key: ca.crt
    *
    * @param namespace        k8s namespace
    * @param secretName       Secret name to (create|replace)
    * @param truststorePath   absolute path to JKS (e.g. /etc/ambari-server/conf/truststore.jks)
    * @param truststoreType   "JKS" (default) or "PKCS12"
    * @param truststorePass   plain password chars (you can retrieve from Ambari configs/secure store)
    * @param minDaysLeft      rotate when earliest cert expires within this many days
    * @param extraLabels      optional labels
    */
    public void ensureOrRotateAmbariTruststoreCaSecret(String namespace,
                                                    String secretName,
                                                    String truststorePath,
                                                    String truststoreType,
                                                    char[] truststorePass,
                                                    int minDaysLeft,
                                                    Map<String,String> extraLabels) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(secretName);
        Objects.requireNonNull(truststorePath);

        Path jks = Paths.get(truststorePath);
        if (!Files.exists(jks)) {
            throw new IllegalStateException("Ambari truststore not found: " + jks);
        }

        KeyStore ks = loadKeyStore(jks, truststorePass, (truststoreType == null || truststoreType.isBlank()) ? "JKS" : truststoreType);
        List<X509Certificate> certs = extractX509FromTrustStore(ks);
        String pemBundle = toPemBundle(certs);
        Instant earliest = earliestExpiry(certs);

        Secret existing = kubernetesClient().secrets().inNamespace(namespace).withName(secretName).get();
        boolean needUpdate = (existing == null);

        if (!needUpdate) {
            String b64 = existing.getData() != null ? existing.getData().get("ca.crt") : null;
            if (b64 == null) {
                LOG.warn("Existing Secret missing ca.crt; will update {}/{}", namespace, secretName);
                needUpdate = true;
            } else {
                String currentPem = new String(getDecoder().decode(b64), StandardCharsets.UTF_8);
                boolean differs = !pemEqual(currentPem, pemBundle);
                boolean expiringSoon = earliest.isBefore(Instant.now().plus(minDaysLeft, ChronoUnit.DAYS));
                if (differs || expiringSoon) {
                    LOG.info("Truststore CA Secret needs update: differs={}, expiringSoon={} (earliestNotAfter={}) {}/{}",
                            differs, expiringSoon, earliest, namespace, secretName);
                    needUpdate = true;
                }
            }
        }

        if (!needUpdate) {
            LOG.info("Truststore CA Secret up-to-date (earliestNotAfter={}) {}/{}", earliest, namespace, secretName);
            return;
        }

        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        labels.put("component", "ambari-truststore-ca");
        if (extraLabels != null) labels.putAll(extraLabels);

        Map<String,String> ann = new LinkedHashMap<>();
        ann.put("managed-by", "ambari-k8s-view");
        ann.put("clemlab.com/not-after", earliest.toString());

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .addToAnnotations(ann)
                .endMetadata()
                .withType("Opaque")
                .addToData("ca.crt", getEncoder().encodeToString(pemBundle.getBytes(StandardCharsets.UTF_8)))
                .build();

        kubernetesClient().secrets().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Created/rotated Truststore CA Secret {}/{} (earliestNotAfter={})",
                namespace, secretName, earliest);
    }





}
