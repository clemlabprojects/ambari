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

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final KubernetesClient kubernetesClient;
    private final ViewConfigurationService configurationService;

    // Per-directory in-JVM lock map
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> CA_LOCKS = new ConcurrentHashMap<>();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public WebHookConfigurationService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.kubernetesClient = Objects.requireNonNull(kubernetesService.getClient(), "kubernetesClient");
        this.configurationService = new ViewConfigurationService(viewContext);
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

        Secret existing = kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            kubernetesClient.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created webhook client mTLS Secret '{}/{}'", namespace, secretName);
        } else {
            kubernetesClient.secrets().inNamespace(namespace).resource(desired).createOrReplace();
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
     * ensure the ambari mutating webhook username/password are stored correctly as secret in k8s/openshift

     * @param namespace the target namespace
     * @param secretName the creds username/password
     */
    public void ensureWebhookSecret(
                                           String namespace,
                                           String secretName,
                                           String userName,
                                           String password) {

        KubernetesClient kubernetesClient = this.kubernetesService.getClient();
        Map<String,String> data = new LinkedHashMap<>();
        data.put("username", base64(userName));
        data.put("password", base64(password));

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName).withNamespace(namespace)
                .addToLabels("managed-by", "ambari-k8s-view")
                .addToLabels("component", "webhook-ambari-credentials")
                .endMetadata()
                .withType("Opaque")
                .addToData(data)
                .build();

        Secret existing = kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            kubernetesClient.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created webhook credentials Secret {}/{}", namespace, secretName);
        } else {
            kubernetesClient.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated webhook credentials Secret {}/{}", namespace, secretName);
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
     * Ensure/rotate client mTLS secret if absent/invalid/expiring within minDaysLeft.
     */
    public void ensureOrRotateClientMtlsSecret(String namespace,
                                               String secretName,
                                               String subjectCommonName,
                                               List<String> dnsSans,
                                               List<String> uriSans,
                                               int validityDays,
                                               int minDaysLeft,
                                               Map<String,String> extraLabels) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(secretName);

        Secret existing = kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
        boolean needIssue = true;

        if (existing != null && existing.getData() != null && existing.getData().get("client.crt") != null) {
            try {
                String crtPem = new String(getDecoder().decode(existing.getData().get("client.crt")), StandardCharsets.UTF_8);
                X509Certificate crt = parseCertificateFromPem(crtPem);
                if (!expiresBefore(crt, minDaysLeft)) {
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

        if (!needIssue) return;

        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        ClientCertificateMaterial client = issueClientCertificate(
                subjectCommonName, dnsSans, uriSans, validityDays);

        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "ambari-k8s-view");
        if (extraLabels != null) labels.putAll(extraLabels);

        X509Certificate c = parseCertificateFromPem(client.clientCertificatePem());
        Map<String,String> ann = rotationAnnotations(c, null);

        Secret desired = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName).withNamespace(namespace)
                .addToLabels(labels)
                .addToAnnotations(ann)
                .endMetadata()
                .withType("Opaque")
                .addToData("client.crt", base64(client.clientCertificatePem()))
                .addToData("client.key", base64(client.clientPrivateKeyPem()))
                .addToData("ca.crt",     base64(client.certificateAuthorityCertificatePem()))
                .build();

        kubernetesClient.secrets().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Issued/rotated client mTLS Secret {}/{} (notAfter={})",
                namespace, secretName, c.getNotAfter().toInstant());
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

        Secret existing = kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
        boolean needIssue = true;

        if (existing != null && existing.getData() != null && existing.getData().get("tls.crt") != null) {
            try {
                String crtPem = new String(getDecoder().decode(existing.getData().get("tls.crt")), StandardCharsets.UTF_8);
                X509Certificate crt = parseCertificateFromPem(crtPem);
                if (!expiresBefore(crt, minDaysLeft)) {
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
            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
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

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
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

            kubernetesClient.secrets().inNamespace(namespace).resource(desired).createOrReplace();
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

            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
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

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
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

            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
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

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
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
        // Priority: ambari.property k8s.view.webhook.ca.dir → else use view resource path + "/ca"
        String configured = viewContext.getAmbariProperty("k8s.view.webhook.ca.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        String base = configurationService.getViewResourcePath();
        return Paths.get(base, "ca");
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

    private static PrivateKey parsePrivateKeyFromPem(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = getDecoder().decode(normalized);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid CA private key PEM", e);
        }
    }

    private static String base64(String value) {
        return getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> List<T> safeList(List<T> maybeNull) {
        return maybeNull == null ? Collections.emptyList() : maybeNull;
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

        Secret existing = kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
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

        kubernetesClient.secrets().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Created/rotated Truststore CA Secret {}/{} (earliestNotAfter={})",
                namespace, secretName, earliest);
    }





}
