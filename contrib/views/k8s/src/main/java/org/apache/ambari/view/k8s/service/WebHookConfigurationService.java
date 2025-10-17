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

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Base64;

/**
 * Manages Ambari's internal CA (PEM files on disk), issues/rotates client & server TLS certs,
 * and creates/updates Kubernetes Secrets.
 */
public class WebHookConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(WebHookConfigurationService.class);

    private static final String BOUNCY_CASTLE_PROVIDER = "BC";

    // Default file names for the internal CA
    private static final String DEFAULT_CA_CERT_FILENAME = "ambari-ca.crt";
    private static final String DEFAULT_CA_KEY_FILENAME  = "ambari-ca.key";

    // Default validity
    private static final int DEFAULT_CA_VALIDITY_DAYS      = 3650; // ~10 years
    private static final int DEFAULT_CLIENT_VALIDITY_DAYS  = 360;  // 6 months

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final KubernetesClient kubernetesClient;
    private final ViewConfigurationService configurationService;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public WebHookConfigurationService(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
        this.kubernetesClient = Objects.requireNonNull(kubernetesService.getClient(), "kubernetesClient");
        this.configurationService = new ViewConfigurationService(viewContext);
    }

    // -------------------- Public API (existing) --------------------

    /** Ensure Ambari CA exists on disk and return its material. */
    public CertificateAuthorityMaterial ensureAmbariCertificateAuthority() {
        Path caDirectory = resolveCaDirectory();
        Path caCertPath  = caDirectory.resolve(DEFAULT_CA_CERT_FILENAME);
        Path caKeyPath   = caDirectory.resolve(DEFAULT_CA_KEY_FILENAME);

        try {
            Files.createDirectories(caDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create CA directory: " + caDirectory, e);
        }

        if (Files.exists(caCertPath) && Files.exists(caKeyPath)) {
            LOG.info("Ambari CA already present at {}", caDirectory);
            try {
                String caCertPem = Files.readString(caCertPath, StandardCharsets.UTF_8);
                String caKeyPem  = Files.readString(caKeyPath, StandardCharsets.UTF_8);
                return new CertificateAuthorityMaterial(caCertPem, caKeyPem, caDirectory.toString());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read existing CA material from " + caDirectory, e);
            }
        }

        LOG.info("Generating a new Ambari internal CA under {}", caDirectory);
        KeyPair caKeyPair = generateKeyPair();
        X509Certificate caCertificate = selfSignCaCertificate(caKeyPair, "CN=Ambari Internal CA");

        String caCertPem = toPemCertificate(caCertificate);
        String caKeyPem  = toPemPrivateKey(caKeyPair.getPrivate());

        writeString(caCertPath, caCertPem);
        writeString(caKeyPath,  caKeyPem);

        LOG.info("Ambari CA generated: {}, {}", caCertPath, caKeyPath);
        return new CertificateAuthorityMaterial(caCertPem, caKeyPem, caDirectory.toString());
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

    // -------------------- New rotation-aware helpers --------------------

    /** Return base64(der) of current CA (no rotation). */
    public String currentCaBundleBase64() {
        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        try {
            X509Certificate c = parseCertificateFromPem(ca.caCertificatePem());
            return Base64.getEncoder().encodeToString(c.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode current CA", e);
        }
    }

    /**
     * If CA expires within minDaysLeft, rotate it (write new PEMs on disk) and return base64(der) of the NEW CA.
     * Otherwise, return base64(der) of the current CA without changes.
     */
    public String ensureCaWithOptionalRotation(int minDaysLeft) {
        CertificateAuthorityMaterial ca = ensureAmbariCertificateAuthority();
        X509Certificate caCert = parseCertificateFromPem(ca.caCertificatePem());
        if (!expiresBefore(caCert, minDaysLeft)) {
            try { return Base64.getEncoder().encodeToString(caCert.getEncoded()); }
            catch (Exception e) { throw new IllegalStateException("CA encode failed", e); }
        }

        LOG.warn("Ambari CA is nearing expiration (notAfter={}): rotating CA",
                caCert.getNotAfter().toInstant());

        // Generate NEW CA and replace files atomically
        Path dir = Paths.get(ca.storageDirectory());
        KeyPair kp = generateKeyPair();
        X509Certificate newCa = selfSignCaCertificate(kp, "CN=Ambari Internal CA");

        writeString(dir.resolve(DEFAULT_CA_CERT_FILENAME), toPemCertificate(newCa));
        writeString(dir.resolve(DEFAULT_CA_KEY_FILENAME),  toPemPrivateKey(kp.getPrivate()));

        try { return Base64.getEncoder().encodeToString(newCa.getEncoded()); }
        catch (Exception e) { throw new IllegalStateException("Failed to encode new CA", e); }
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
                String crtPem = new String(Base64.getDecoder().decode(existing.getData().get("client.crt")), StandardCharsets.UTF_8);
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
                String crtPem = new String(Base64.getDecoder().decode(existing.getData().get("tls.crt")), StandardCharsets.UTF_8);
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
                    .addToData("tls.crt", Base64.getEncoder().encodeToString(certPem.getBytes(StandardCharsets.UTF_8)))
                    .addToData("tls.key", Base64.getEncoder().encodeToString(keyPem.getBytes(StandardCharsets.UTF_8)))
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

    // -------------------- PEM helpers (simple, no password) --------------------

    private static String toPemCertificate(X509Certificate certificate) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Failed to encode certificate as PEM", e);
        }
    }

    private static String toPemPrivateKey(PrivateKey privateKey) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static X509Certificate parseCertificateFromPem(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
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
            byte[] der = Base64.getDecoder().decode(normalized);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid CA private key PEM", e);
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> List<T> safeList(List<T> maybeNull) {
        return maybeNull == null ? Collections.emptyList() : maybeNull;
    }

    private void writeString(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write file " + path, e);
        }
    }

    private Path resolveCaDirectory() {
        // Priority: ambari.property k8sview.webhook.ca.dir → else use view resource path + "/ca"
        String configured = viewContext.getAmbariProperty("k8s.view.webhook.ca.dir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        String base = configurationService.getViewResourcePath();
        return Paths.get(base, "ca");
    }

    // -------------------- Value objects --------------------

    public record CertificateAuthorityMaterial(String caCertificatePem,
                                               String caPrivateKeyPem,
                                               String storageDirectory) {}

    public record ClientCertificateMaterial(String clientCertificatePem,
                                            String clientPrivateKeyPem,
                                            String certificateAuthorityCertificatePem) {}
}
