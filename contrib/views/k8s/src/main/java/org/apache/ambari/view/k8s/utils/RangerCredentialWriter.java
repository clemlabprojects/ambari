package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a JCEKS credential file (alias -> password) and publishes it as a k8s Secret
 * via your KubernetesService.
 *
 * No dependency on Ranger classes. Uses Hadoop credential provider only.
 */
public class RangerCredentialWriter {

    private static final Logger LOG = LoggerFactory.getLogger(RangerCredentialWriter.class);
    public static final String DEFAULT_ALIAS    = "sslTrustStore";
    public static final String DEFAULT_KEY_NAME = "cred.jceks";

    private final KubernetesService kube;
    private final String managedBy;

    public RangerCredentialWriter(KubernetesService kube) {
        this(kube, "ambari-k8s-view");
    }

    public RangerCredentialWriter(KubernetesService kube, String managedByLabel) {
        this.kube = Objects.requireNonNull(kube, "kube");
        this.managedBy = managedByLabel == null ? "ambari-k8s-view" : managedByLabel;
    }

    /**
     * Build a JCEKS store and upsert as an Opaque Secret using KubernetesService#createOrUpdateOpaqueSecret.
     *
     * @return map with the two properties you must inject in ranger-policymgr-ssl-trino.xml:
     *         xasecure.policymgr.clientssl.truststore.credential.file
     *         xasecure.policymgr.clientssl.truststore.credential.alias
     */
    public Map<String,String> ensureJceksSecretForTruststorePassword(
            String namespace,
            String secretName,
            String jceksMountPath,
            String alias,
            char[] password,
            Map<String,String> extraLabels,
            Map<String,String> extraAnn
    ) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(jceksMountPath, "jceksMountPath");
        Objects.requireNonNull(password, "password");

        final String useAlias = (alias == null || alias.isBlank()) ? DEFAULT_ALIAS : alias;

        LOG.info("Building jceks in-memory with jceksMountPath: {}, alias: {}", jceksMountPath, useAlias);

        // Delegate to multi-alias builder for a single alias
        Map<String,char[]> entries = new LinkedHashMap<>();
        entries.put(useAlias, password);
        byte[] jceksBytes = buildJceksBytes(entries);

        LOG.info("Built jceks in-memory with alias: {}", useAlias);

        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("managed-by", managedBy);
        labels.put("component", "ranger-credential");
        if (extraLabels != null) labels.putAll(extraLabels);

        Map<String,String> ann = new LinkedHashMap<>();
        ann.put("managed-by", managedBy);
        ann.put("clemlab.com/credential-kind", "jceks");
        ann.put("clemlab.com/updated-at", Instant.now().toString());
        if (extraAnn != null) ann.putAll(extraAnn);

        LOG.info("Storing JCEKS into secret '{}'", secretName);
        kube.createOrUpdateOpaqueSecret(
                namespace,
                secretName,
                DEFAULT_KEY_NAME,
                jceksBytes,
                labels,
                ann,
                Boolean.TRUE
        );

        Map<String,String> props = new LinkedHashMap<>();
        props.put("xasecure.policymgr.clientssl.truststore.credential.file", toProviderUri(jceksMountPath));
        props.put("xasecure.policymgr.clientssl.truststore.credential.alias", useAlias);
        return props;
    }

    /**
     * New variant: build a JCEKS store containing multiple aliases and upsert as k8s Secret.
     *
     * This overwrites the keystore content with exactly the aliases supplied.
     * If you want "incremental" semantics, call it with the full alias set each time.
     */
    public void ensureJceksSecretWithAliases(
            String namespace,
            String secretName,
            String jceksMountPath,
            Map<String,char[]> aliasesToPasswords,
            Map<String,String> extraLabels,
            Map<String,String> extraAnn
    ) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(jceksMountPath, "jceksMountPath");
        Objects.requireNonNull(aliasesToPasswords, "aliasesToPasswords");

        if (aliasesToPasswords.isEmpty()) {
            throw new IllegalArgumentException("aliasesToPasswords must not be empty");
        }

        LOG.info("Building multi-alias JCEKS for secret '{}': aliases={}", secretName, aliasesToPasswords.keySet());

        byte[] jceksBytes = buildJceksBytes(aliasesToPasswords);

        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("managed-by", managedBy);
        labels.put("component", "ranger-credential");
        if (extraLabels != null) labels.putAll(extraLabels);

        Map<String,String> ann = new LinkedHashMap<>();
        ann.put("managed-by", managedBy);
        ann.put("clemlab.com/credential-kind", "jceks");
        ann.put("clemlab.com/updated-at", Instant.now().toString());
        if (extraAnn != null) ann.putAll(extraAnn);

        kube.createOrUpdateOpaqueSecret(
                namespace,
                secretName,
                DEFAULT_KEY_NAME,
                jceksBytes,
                labels,
                ann,
                Boolean.TRUE
        );

        LOG.info("Stored multi-alias JCEKS in secret '{}' with aliases {}", secretName, aliasesToPasswords.keySet());
    }

    /**
     * Build a JCEKS keystore in a temp file with the given aliases and return its raw bytes.
     */
    private static byte[] buildJceksBytes(Map<String,char[]> aliasesToPasswords) {
        Path tmpDir = null;
        Path ksPath = null;
        try {
            tmpDir = Files.createTempDirectory("credks-");
            ksPath = tmpDir.resolve("cred.jceks");

            String uri = "jceks://file" + ksPath.toAbsolutePath();

            Configuration conf = new Configuration(false);
            conf.set("hadoop.security.credential.provider.path", uri);

            List<CredentialProvider> providers = CredentialProviderFactory.getProviders(conf);
            if (providers == null || providers.isEmpty()) {
                throw new IllegalStateException("No CredentialProvider found for uri: " + uri);
            }
            CredentialProvider provider = providers.get(0);

            for (Map.Entry<String,char[]> e : aliasesToPasswords.entrySet()) {
                String alias = e.getKey();
                char[] password = e.getValue();

                if (alias == null || alias.isBlank()) {
                    LOG.warn("Skipping blank alias in aliasesToPasswords");
                    continue;
                }
                if (password == null) {
                    LOG.warn("Skipping alias {} with null password", alias);
                    continue;
                }

                CredentialProvider.CredentialEntry existing = provider.getCredentialEntry(alias);
                if (existing != null) {
                    provider.deleteCredentialEntry(alias);
                }
                provider.createCredentialEntry(alias, password);
            }

            provider.flush();

            if (!Files.exists(ksPath)) {
                throw new IllegalStateException("Credential provider did not create keystore at: " + ksPath);
            }
            byte[] out = Files.readAllBytes(ksPath);
            if (out == null || out.length == 0) {
                throw new IllegalStateException("Generated JCEKS is empty at: " + ksPath);
            }
            return out;

        } catch (IOException ioe) {
            throw new RuntimeException("Failed to create JCEKS: " + ioe.getMessage(), ioe);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build JCEKS: " + e.getMessage(), e);
        } finally {
            if (ksPath != null) {
                try { Files.deleteIfExists(ksPath); } catch (Exception ignore) {}
            }
            if (tmpDir != null) {
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignore) {}
            }
        }
    }

    private static String toProviderUri(String absolutePath) {
        String p = absolutePath.replace("\\", "/");
        if (!p.startsWith("/")) p = "/" + p;
        return "jceks://file" + p;
    }
}