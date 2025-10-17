package org.apache.ambari.view.k8s.utils;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Bootstrapper that prepares ONLY prerequisites for the mutating webhook:
 *  - ensures target namespace exists
 *  - ensures Ambari CA exists (or creates it)
 *  - issues client mTLS cert and stores it into a Secret (webhook -> Ambari)
 *  - issues serving TLS cert for the Kubernetes Service DNS and stores it into a Secret
 *  - computes caBundle (base64 DER of Ambari CA) and persists it to instance data
 *
 * It DOES NOT install any Kubernetes resources for the webhook (no Deployment, no WebhookConfiguration).
 * Run this once on View startup; it is safe to re-run (idempotent).
 */
public class WebHookBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(WebHookBootstrap.class);
    private static final String BC = "BC";

    static {
        // BouncyCastle once per JVM
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Prepare prerequisites for a given webhook.
     *
     * Reads the following optional keys from ambari.properties (passthrough convention):
     *   k8s.view.webhooks.<webhookName>.namespace              (default: ambari-managed-mutating-webhooks)
     *   k8s.view.webhooks.<webhookName>.appName               (default: <webhookName>)
     *   k8s.view.webhooks.<webhookName>.dnsSuffix             (default: .svc.cluster.local)
     *   k8s.view.webhooks.<webhookName>.clientMtls.secretName (default: <webhookName>-client-tls)
     *   k8s.view.webhooks.<webhookName>.serverTls.secretName  (default: <webhookName>-tls)
     *
     * Writes (into instance data) for Helm to consume later:
     *   k8s.view.webhooks.<webhookName>.webhook.caBundle = <base64 DER of Ambari CA>
     *
     * @param ctx          Ambari ViewContext
     * @param k8sSvc       KubernetesService adapter
     * @param webhookName  logical name (must match your Helm values prefix)
     */
    public static void prepareWebhookPrereqs(ViewContext ctx,
                                             KubernetesService k8sSvc,
                                             String webhookName) {
        Objects.requireNonNull(ctx,          "ViewContext must not be null");
        Objects.requireNonNull(k8sSvc,       "KubernetesService must not be null");
        Objects.requireNonNull(webhookName,  "webhookName must not be null");

        final long t0 = System.currentTimeMillis();
        LOG.info("[webhook={}]: starting prerequisite preparation", webhookName);

        // ---- 1) Read inputs from ambari.properties with sensible defaults ----
        final String ns        = prop(ctx, webhookName, "namespace",              "ambari-managed-mutating-webhooks");
        final String appName   = prop(ctx, webhookName, "appName",                webhookName);
        final String dnsSuffix = prop(ctx, webhookName, "dnsSuffix",              ".svc.cluster.local");
        final String clientSec = prop(ctx, webhookName, "clientMtls.secretName",  webhookName + "-client-tls");
        final String serveSec  = prop(ctx, webhookName, "serverTls.secretName",   webhookName + "-tls");

        LOG.info("[webhook={}] resolved inputs: namespace='{}', appName='{}', dnsSuffix='{}', clientSecret='{}', servingSecret='{}'",
                webhookName, ns, appName, dnsSuffix, clientSec, serveSec);

        // ---- 2) Ensure namespace exists ----
        try {
            k8sSvc.createNamespace(ns);
            LOG.info("[webhook={}] ensured namespace '{}'", webhookName, ns);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed ensuring namespace '{}'", webhookName, ns, e);
            throw e;
        }

        // ---- 3) Ensure Ambari CA exists (or create it) ----
        WebHookConfigurationService wh = new WebHookConfigurationService(ctx, k8sSvc);
        WebHookConfigurationService.CertificateAuthorityMaterial ca = wh.ensureAmbariCertificateAuthority();
        LOG.info("[webhook={}] Ambari CA ready (dir={})", webhookName, ca.storageDirectory());

        // ---- 4) Ensure client mTLS Secret used by the webhook to call Ambari ----
        try {
            wh.ensureClientMtlsSecret(
                    ns,
                    clientSec,
                    "ambari-webhook-client",           // CN label
                    List.of(),                         // DNS SANs (optional for client auth)
                    List.of(),                         // URI SANs (optional)
                    180,                               // validity days
                    Map.of("managed-by", "ambari-k8s-view", "component", "webhook-client-mtls")
            );
            LOG.info("[webhook={}] ensured client mTLS Secret {}/{}", webhookName, ns, clientSec);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed creating client mTLS Secret {}/{}", webhookName, ns, clientSec, e);
            throw e;
        }

        // ---- 5) Issue serving TLS cert for the Service DNS names and store as Secret ----
        //         Kubernetes API server connects over these names; SANs must match.
        final String svcShort = appName + "." + ns + ".svc";
        final String svcFqdn  = svcShort + (dnsSuffix.startsWith(".") ? dnsSuffix : "." + dnsSuffix);
        try {
            ServerCert sc = issueServerCert(ca, List.of(svcShort, svcFqdn), 365);
            createOrReplaceTlsSecret(k8sSvc.getClient(), ns, serveSec, sc.certPem(), sc.keyPem());
            LOG.info("[webhook={}] ensured serving TLS Secret {}/{} (SANs: {}, {})",
                    webhookName, ns, serveSec, svcShort, svcFqdn);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed creating serving TLS Secret {}/{}", webhookName, ns, serveSec, e);
            throw e;
        }

        // ---- 6) Compute caBundle (base64 DER of Ambari CA) and persist for Helm passthrough ----
        try {
            String caBundle = toBase64Der(ca.caCertificatePem());
            String key = "k8s.view.webhooks." + webhookName + ".webhook.caBundle";
            // Persist to instance-scoped store (safe to overwrite). Your Helm flow will read this back as an override.
            ctx.putInstanceData(key, caBundle);
            LOG.info("[webhook={}] stored caBundle into instance data key='{}' ({} bytes)", webhookName, key,
                    (caBundle != null ? caBundle.length() : 0));
        } catch (Exception e) {
            LOG.error("[webhook={}] failed computing/persisting caBundle", webhookName, e);
            throw e;
        }

        LOG.info("[webhook={}] prerequisite preparation complete in {} ms", webhookName, (System.currentTimeMillis() - t0));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Read a passthrough property with default: k8s.view.webhooks.<webhook>.<key> */
    private static String prop(ViewContext ctx, String webhookName, String key, String def) {
        String fullKey = "k8s.view.webhooks." + webhookName + "." + key;
        String val = null;
        try {
            val = ctx.getAmbariProperty(fullKey);
        } catch (Exception ignored) {
            // Some ViewContext impls may not throw; keep it defensive.
        }
        if (val != null && !val.isBlank()) return val.trim();
        return def;
    }

    /** Create or replace Kubernetes TLS secret with PEM data (keys: tls.crt, tls.key) */
    private static void createOrReplaceTlsSecret(KubernetesClient client, String ns, String name, String certPem, String keyPem) {
        Secret desired = new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(ns)
                .addToLabels("managed-by", "ambari-k8s-view")
                .addToLabels("component", "webhook-serving-tls")
                .endMetadata()
                .withType("kubernetes.io/tls")
                .addToData("tls.crt", Base64.getEncoder().encodeToString(certPem.getBytes(StandardCharsets.UTF_8)))
                .addToData("tls.key", Base64.getEncoder().encodeToString(keyPem.getBytes(StandardCharsets.UTF_8)))
                .build();

        Secret existing = client.secrets().inNamespace(ns).withName(name).get();
        if (existing == null) {
            client.secrets().inNamespace(ns).resource(desired).create();
            LOG.debug("Created serving TLS Secret {}/{}", ns, name);
        } else {
            client.secrets().inNamespace(ns).resource(desired).createOrReplace();
            LOG.debug("Updated serving TLS Secret {}/{}", ns, name);
        }
    }

    /** Simple holder for generated server certificate material. */
    private record ServerCert(String certPem, String keyPem) {}

    /** Issue a server certificate signed by the Ambari CA for the given DNS SANs. */
    private static ServerCert issueServerCert(WebHookConfigurationService.CertificateAuthorityMaterial ca,
                                              List<String> dnsSans,
                                              int validityDays) {
        try {
            KeyPair kp = generateEcKey();
            X509Certificate caCert = parseCert(ca.caCertificatePem());

            // Reuse your existing PEM parser if accessible; else local PKCS#8 EC parser
            PrivateKey caKey;
            try {
                var m = WebHookConfigurationService.class.getDeclaredMethod("parsePrivateKeyFromPem", String.class);
                m.setAccessible(true);
                caKey = (PrivateKey) m.invoke(null, ca.caPrivateKeyPem());
            } catch (Throwable t) {
                caKey = parsePrivateKeyPkcs8Ec(ca.caPrivateKeyPem());
            }

            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
            String cn = dnsSans.isEmpty() ? "webhook.svc" : dnsSans.get(0);
            X500Name subject = new X500Name(new X500Principal("CN=" + cn).getName());
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(validityDays, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());

            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder b = new X509v3CertificateBuilder(
                    issuer, serial, Date.from(notBefore), Date.from(notAfter), subject,
                    SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));

            // serverAuth extensions
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));

            if (!dnsSans.isEmpty()) {
                List<GeneralName> san = new ArrayList<>();
                for (String dns : dnsSans) if (dns != null && !dns.isBlank())
                    san.add(new GeneralName(GeneralName.dNSName, dns));
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san.toArray(new GeneralName[0])));
            }

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider(BC).build(caKey);
            X509CertificateHolder holder = b.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter().setProvider(BC).getCertificate(holder);

            return new ServerCert(toPemCert(cert), toPemKey(kp.getPrivate()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue server TLS cert", e);
        }
    }

    private static KeyPair generateEcKey() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }

    private static X509Certificate parseCert(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return (X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid certificate PEM", e);
        }
    }

    private static PrivateKey parsePrivateKeyPkcs8Ec(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("EC").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid EC private key PEM", e);
        }
    }

    private static String toPemCert(X509Certificate cert) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String toPemKey(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static String toBase64Der(String caCertPem) {
        try {
            X509Certificate ca = parseCert(caCertPem);
            return Base64.getEncoder().encodeToString(ca.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute caBundle", e);
        }
    }
}
