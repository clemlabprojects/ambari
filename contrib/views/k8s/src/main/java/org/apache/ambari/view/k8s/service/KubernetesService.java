package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.utils.Serialization;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;

import io.fabric8.openshift.client.OpenShiftClient;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.fabric8.kubernetes.client.utils.Serialization;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.*;

import org.apache.ambari.view.k8s.model.ComponentStatus;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.utils.CompositeTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import java.time.Instant;
import java.time.OffsetDateTime;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Service containing the real business logic to interact with the Kubernetes API.
 */
public class KubernetesService {
    
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesService.class);
    
    private final KubernetesClient client;
    private final boolean isConfigured;
    private ViewContext viewContext;
    private WebHookConfigurationService webHookConfigurationService;

    private final HelmRepositoryService repositoryService;
    private final HelmClient helmClient;
    private final HelmService helmService;

    private MountManager mountManager;

    private ViewConfigurationService configurationService;

    public static final String WEBHOOK_ENABLED_LABEL_KEY = "security.clemlab.com/webhooks-enabled";

    public KubernetesService(KubernetesClient client, boolean isConfigured) {
        this.viewContext = null; // Default constructor for testing
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.helmService = null; // testing
        this.repositoryService = null;
        this.configurationService = null;
    }

    public KubernetesService(ViewContext viewContext, KubernetesClient client, boolean isConfigured) {
        this.viewContext = viewContext;
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.helmService = null; // testing
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
        this.configurationService = new ViewConfigurationService(viewContext);
    }

    public KubernetesService(ViewContext ctx, KubernetesClient k8s, HelmClient helmClient, boolean isConfigured) {
        this.viewContext = ctx;
        this.client = k8s;
        this.isConfigured = isConfigured;
        // Internal services with the same mocked HelmClient
        this.helmClient = helmClient;
        this.helmService = new HelmService(ctx, helmClient);
        this.repositoryService = new HelmRepositoryService(ctx, helmClient);
    }

    public KubernetesService(ViewContext viewContext) {
        this.configurationService = new ViewConfigurationService(viewContext);
        String kubeconfigPath = configurationService.getKubeconfigPath();
        this.viewContext = viewContext;
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
        this.helmService = new HelmService(viewContext, helmClient);
        
        if (kubeconfigPath == null) {
            LOG.info("View is not configured. Kubernetes client will not be initialized.");
            this.isConfigured = false;
            this.client = null;
        } else {
            File configFile = new File(kubeconfigPath);
            if (!configFile.exists()) {
                LOG.warn("Stale configuration detected. Kubeconfig file not found at path: {}. Resetting configuration.", kubeconfigPath);
                configurationService.removeKubeconfigPath(); // Auto-healing: remove stale entry
                this.isConfigured = false;
                this.client = null;
            } else {
                // Initialize Kubernetes client with kubeconfig
                try {
                    EncryptionService encryptionService = new EncryptionService();
                    byte[] encryptedBytes = Files.readAllBytes(Paths.get(kubeconfigPath));
                    byte[] decryptedBytes = encryptionService.decrypt(encryptedBytes);
                    String kubeconfigContent = new String(decryptedBytes, StandardCharsets.UTF_8);
                    
                    LOG.info("Kubeconfig file loaded successfully from: {}", kubeconfigPath);
                    Config baseConfiguration = Config.fromKubeconfig(kubeconfigContent);
                    ConfigBuilder configurationBuilder = new ConfigBuilder(baseConfiguration);
                    LOG.info("Kubernetes client configuration loaded from kubeconfig file.\n");

                    LOG.info("Overriding view properties from ambari.properties into system properties for Kubernetes client.");
                    loadK8sPropsAsSystemProperties(viewContext);

                    // Parse kubeconfig to extract certificate
                    io.fabric8.kubernetes.api.model.Config kubeconfigModel = Serialization.unmarshal(kubeconfigContent, io.fabric8.kubernetes.api.model.Config.class);
                    String certificateAuthorityData = kubeconfigModel.getClusters().get(0).getCluster().getCertificateAuthorityData();
                    Config finalConfiguration = configurationBuilder.build();

                    if (certificateAuthorityData != null && !certificateAuthorityData.isEmpty()) {
                        LOG.info("Found 'certificate-authority-data' in kubeconfig. Adding it to the JVM's default SSL context.");

                        // Get the default JVM TrustManager
                        TrustManagerFactory defaultTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        defaultTrustManagerFactory.init((KeyStore) null); // Loads default cacerts
                        X509TrustManager defaultTrustManager = (X509TrustManager) defaultTrustManagerFactory.getTrustManagers()[0];

                        // Create a custom TrustManager for the K8s CA
                        byte[] decodedCertificateBytes = Base64.getDecoder().decode(certificateAuthorityData);
                        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                        X509Certificate caCertificate = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(decodedCertificateBytes));

                        KeyStore customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                        customTrustStore.load(null, null);
                        customTrustStore.setCertificateEntry("k8s-ca", caCertificate);

                        TrustManagerFactory customTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        customTrustManagerFactory.init(customTrustStore);
                        X509TrustManager customTrustManager = (X509TrustManager) customTrustManagerFactory.getTrustManagers()[0];

                        // Combine them and set the new default SSLContext
                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        // The CompositeTrustManager ensures we trust BOTH default CAs and our custom one
                        sslContext.init(null, new TrustManager[]{new CompositeTrustManager(defaultTrustManager, customTrustManager)}, null);
                        SSLContext.setDefault(sslContext);

                        LOG.info("JVM default SSL context has been updated to trust the Kubernetes CA.");

                    } else if (viewContext.getAmbariProperty("k8s.view.ssl.truststore.path") != null) {
                        String trustStorePath = viewContext.getAmbariProperty("k8s.view.ssl.truststore.path");
                        String trustStorePassword = viewContext.getAmbariProperty("k8s.view.ssl.truststore.password");
                        LOG.info("Using TrustStore defined in ambari.properties: {}", trustStorePath);
                        configurationBuilder.withTrustStoreFile(trustStorePath);
                        if (trustStorePassword != null) {
                            configurationBuilder.withTrustStorePassphrase(trustStorePassword);
                        }
                    } else {
                        LOG.info("No custom SSL configuration found. Using default JVM TrustStore.");
                    }

                    this.client = new DefaultKubernetesClient(finalConfiguration);
                    
                    if (false) { // Disabled OpenShift detection
                        LOG.info("OpenShift cluster detected. Adapting the client.");
                        this.client.adapt(OpenShiftClient.class);
                    } else {
                        LOG.info("Standard Kubernetes cluster detected.");
                    }
                    this.isConfigured = true;
                    LOG.info("Kubernetes client initialized successfully.");
                    
                } catch (IOException | java.security.NoSuchAlgorithmException | java.security.KeyStoreException | java.security.cert.CertificateException | java.security.KeyManagementException e) {
                    LOG.error("Failed to read the kubeconfig file at: {}", kubeconfigPath, e);
                    throw new IllegalStateException("Failed to read the Kubernetes configuration file.", e);
                }
            }
        }
    }

    private void checkConfiguration() {
        if (!isConfigured) {
            throw new IllegalStateException("The view is not configured with a kubeconfig.");
        }
    }

    public List<ClusterNode> getNodes() {
        checkConfiguration();
        LOG.info("Fetching node list from Kubernetes API.");
        return client.nodes().list().getItems().stream()
                .map(this::toClusterNode)
                .collect(Collectors.toList());
    }

    public List<ComponentStatus> getComponentStatuses() {
        checkConfiguration();
        LOG.info("Fetching component statuses from Kubernetes API.");
        return client.componentstatuses().list().getItems().stream()
                .map(this::toComponentStatus)
                .collect(Collectors.toList());
    }
    
    public List<ClusterEvent> getClusterEvents() {
        checkConfiguration();
        LOG.info("Fetching recent events from Kubernetes API.");

        List<Event> eventItems = client.v1().events().inAnyNamespace().list().getItems();

        Comparator<Event> byTimeDescending = Comparator.comparing(
            this::eventInstantSafely,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed();

        return eventItems.stream()
            .sorted(byTimeDescending)
            .limit(20)
            .map(this::toClusterEvent)
            .collect(Collectors.toList());
    }

    public ClusterStats getClusterStats() {
        checkConfiguration();
        LOG.info("Calculating cluster stats from Kubernetes API.");

        NodeList nodeList = client.nodes().list();
        PodList podList = client.pods().inAnyNamespace().list();
        List<String> runningPods = podList.getItems().stream()
            .filter(pod -> "Running".equalsIgnoreCase(pod.getStatus().getPhase()))
            .map(pod -> pod.getMetadata().getName())
            .collect(Collectors.toList());

        double totalCpuCapacity = 0;
        double totalMemoryCapacity = 0;
        long readyNodesCount = 0;

        for (Node node : nodeList.getItems()) {
            Map<String, Quantity> nodeCapacity = node.getStatus().getCapacity();
            if (nodeCapacity.containsKey("cpu")) {
                totalCpuCapacity += nodeCapacity.get("cpu").getNumericalAmount().doubleValue();
            }
            if (nodeCapacity.containsKey("memory")) {
                totalMemoryCapacity += nodeCapacity.get("memory").getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // To GiB
            }
            
            boolean isNodeReady = node.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
            if (isNodeReady) {
                readyNodesCount++;
            }
        }

        // NOTE: Real-time usage stats require the Kubernetes Metrics Server.
        // This implementation shows total capacity. A more advanced version would query the metrics API.
        double usedMemoryTotal = 0;
        for (Node node : nodeList.getItems()) {
            try {
                var nodeMetrics = client.top().nodes().metrics(node.getMetadata().getName());
                if (nodeMetrics != null && nodeMetrics.getUsage() != null && nodeMetrics.getUsage().containsKey("memory")) {
                    Quantity memoryQuantity = nodeMetrics.getUsage().get("memory");
                    usedMemoryTotal += memoryQuantity.getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // GiB
                }
            } catch (Exception ex) {
                LOG.warn("Could not fetch memory usage for node {}: {}", node.getMetadata().getName(), ex.getMessage());
            }
        }
        
        ClusterStats.ResourceStat cpuStatistics = new ClusterStats.ResourceStat(0, totalCpuCapacity); // Usage requires metrics
        ClusterStats.ResourceStat memoryStatistics = new ClusterStats.ResourceStat(usedMemoryTotal, totalMemoryCapacity);
        ClusterStats.ResourceStat podStatistics = new ClusterStats.ResourceStat(runningPods.size(), podList.getItems().size());
        ClusterStats.ResourceStat nodeStatistics = new ClusterStats.ResourceStat(readyNodesCount, nodeList.getItems().size());
        
        // Helm stats would require Helm client logic, which is complex. Returning placeholder.
        ClusterStats.HelmStat helmStatistics = new ClusterStats.HelmStat(0, 0, 0, 0);

        return new ClusterStats(cpuStatistics, memoryStatistics, podStatistics, nodeStatistics, helmStatistics);
    }

    // Helper methods to convert Fabric8 models to our DTOs

    private ClusterNode toClusterNode(Node node) {
        String nodeStatus = "NotReady";
        for (var condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                nodeStatus = "Ready";
                break;
            }
        }
        List<String> nodeRoles = node.getMetadata().getLabels().keySet().stream()
                .filter(labelKey -> labelKey.startsWith("node-role.kubernetes.io/"))
                .map(labelKey -> labelKey.substring(labelKey.indexOf("/") + 1))
                .collect(Collectors.toList());
        
        // Usage stats require metrics server, returning 0 for now.
        return new ClusterNode(node.getMetadata().getUid(), node.getMetadata().getName(), nodeStatus, nodeRoles, 0.0, 0.0);
    }
    
    private ComponentStatus toComponentStatus(io.fabric8.kubernetes.api.model.ComponentStatus componentStatus) {
        String healthStatus = "Unhealthy";
        ComponentCondition healthCondition = componentStatus.getConditions().stream()
                .filter(condition -> "Healthy".equals(condition.getType()))
                .findFirst().orElse(null);
        if (healthCondition != null && "True".equals(healthCondition.getStatus())) {
            healthStatus = "Healthy";
        }
        return new ComponentStatus(componentStatus.getMetadata().getName(), healthStatus);
    }
    
    private ClusterEvent toClusterEvent(Event event) {
        String eventType = (event.getType() != null && event.getType().equalsIgnoreCase("Warning"))
            ? "Warning" : "Info";

        String eventTimestamp = event.getLastTimestamp();
        if (eventTimestamp == null || eventTimestamp.isBlank()) {
            eventTimestamp = event.getMetadata() != null ? event.getMetadata().getCreationTimestamp() : null;
        }

        return new ClusterEvent(
            event.getMetadata() != null ? event.getMetadata().getUid() : null,
            eventType,
            event.getMessage(),
            eventTimestamp
        );
    }

    public static void loadK8sPropsAsSystemProperties(ViewContext context) {
        Map<String, String> contextProperties = context.getProperties();

        for (Map.Entry<String, String> propertyEntry : contextProperties.entrySet()) {
            String propertyKey = propertyEntry.getKey();
            if (!propertyKey.startsWith("k8sview.")) continue;

            // Remove the prefix, keep key as-is (dot-separated, lowercase)
            LOG.info("Found property: {} = {}", propertyKey, propertyEntry.getValue());
            LOG.info("Setting system property: {} = {}", propertyKey.substring("k8sview.".length()), propertyEntry.getValue());
            String systemPropertyKey = propertyKey.substring("k8sview.".length());
            String propertyValue = propertyEntry.getValue();

            if (propertyValue != null && !propertyValue.isBlank()) {
                System.setProperty(systemPropertyKey, propertyValue);
            }
        }
    }
    
    public String loadAvailableCharts() throws IOException {
        String chartsDirectoryPath = null;
        // Read property from ambari.properties via ViewContext
        String chartsProperty = null;
        if (client != null) {
            // Try to get from system properties first (if set)
            chartsProperty = System.getProperty("kubernetes.available.charts");
        }
        if (chartsProperty == null || chartsProperty.isBlank()) {
            // Fallback to view context property
            chartsProperty = this.viewContext.getAmbariProperty("k8sview.kubernetes.available.charts");
        }
        if (chartsProperty != null && !chartsProperty.isBlank()) {
            chartsDirectoryPath = chartsProperty;
        }
        if (chartsDirectoryPath != null && !chartsDirectoryPath.isBlank()) {
            File chartsConfigurationFile = new File(chartsDirectoryPath, "charts.json");
            if (chartsConfigurationFile.exists()) {
                return Files.readString(chartsConfigurationFile.toPath(), StandardCharsets.UTF_8);
            } else {
                throw new IOException("charts.json not found at: " + chartsConfigurationFile.getAbsolutePath());
            }
        } else {
            try (var inputStream = getClass().getClassLoader().getResourceAsStream("charts.json")) {
                if (inputStream == null) {
                    throw new IOException("charts.json not found in classpath.");
                }
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private boolean isOpenShift(KubernetesClient client) {
        // Attempts to list a resource that only exists on OpenShift.
        // If the request does not throw a 404 exception, it is an OpenShift cluster.
        return client.isAdaptable(OpenShiftClient.class);
    }

    /**
     * Ensure a namespace exists; creates it if missing.
     */
    public void createNamespace(String namespace) {

        Objects.requireNonNull(namespace, "namespace");
        try {
            client.namespaces()
                .createOrReplace(new NamespaceBuilder()
                    .withNewMetadata().withName(namespace).endMetadata()
                    .build());
        } catch (KubernetesClientException e) {
            throw new RuntimeException("Failed to create namespace: " + namespace, e);
        }
    }

    /**
     * Ensure a TARGET namespace exists and is labeled for webhook admission:
     *   security.clemlab.com/webhooks-enabled = "true"
     * Use this for namespaces where Trino/Spark/etc. pods will run.
     */
    public void ensureWebhookEnabledNamespace(String namespace) {
        ensureNamespaceLabel(namespace, WEBHOOK_ENABLED_LABEL_KEY, "true");
    }

    // ---------- internals ----------

    private void ensureNamespaceLabel(String namespace, String key, String value) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        try {
            Namespace targetNamespace = client.namespaces().withName(namespace).get();
            if (targetNamespace == null) {
                Namespace toCreate = new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(namespace)
                            .addToLabels(key, value)
                        .endMetadata()
                        .build();
                client.namespaces().resource(toCreate).create();
                LOG.info("Created namespace '{}' with label {}={}", namespace, key, value);
                return;
            }

            Map<String,String> merged = new LinkedHashMap<>();
            if (targetNamespace.getMetadata().getLabels() != null) merged.putAll(targetNamespace.getMetadata().getLabels());
            String cur = merged.get(key);
            if (Objects.equals(cur, value)) {
                LOG.info("Namespace '{}' already labeled {}={}", namespace, key, value);
                return;
            }

            merged.put(key, value);
            applyNamespaceLabelsWithRetry(namespace, merged, 1);
            LOG.info("Updated namespace '{}' label {}={} (was: {})", namespace, key, value, cur);

        } catch (KubernetesClientException e) {
            throw new RuntimeException("Failed to label namespace: " + namespace, e);
        }
    }


    /**
     * Applies the specified labels to a Kubernetes namespace, creating the namespace if it does not exist.
     * If a conflict occurs during label application (HTTP 409), the operation is retried up to {@code maxRetry} times.
     *
     * <p>
     * This method ensures that the given namespace is annotated with the provided labels.
     * If the namespace does not exist, it is created with the labels.
     * If the namespace exists, its labels are updated.
     * In case of concurrent modifications, the method retries the update to handle conflicts.
     * </p>
     *
     * @param namespace the name of the Kubernetes namespace to label
     * @param labels a map of label key-value pairs to apply to the namespace
     * @param maxRetry the maximum number of retry attempts in case of conflict errors
     * @throws KubernetesClientException if the operation fails and cannot be retried
     */
    private void applyNamespaceLabelsWithRetry(String namespace, Map<String,String> labels, int maxRetry) {
        int attempt = 0;
        while (true) {
            attempt++;
            Namespace cur = client.namespaces().withName(namespace).get();
            if (cur == null) {
                Namespace toCreate = new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(namespace)
                            .addToLabels(labels)
                        .endMetadata()
                        .build();
                client.namespaces().resource(toCreate).create();
                return;
            }
            Namespace patched = new NamespaceBuilder(cur)
                    .editOrNewMetadata()
                        .addToLabels(labels)
                    .endMetadata()
                    .build();
            try {
                client.namespaces().resource(patched).lockResourceVersion().replace();
                return;
            } catch (KubernetesClientException kce) {
                if (kce.getCode() == 409 && attempt <= maxRetry) {
                    LOG.warn("Conflict updating labels for namespace '{}', retrying ({}/{})",
                            namespace, attempt, maxRetry);
                    try { Thread.sleep(200L); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw kce;
            }
        }
    }
    /**
     * Delete a namespace (best effort). Returns true if the request was accepted.
     */
    public void deleteNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        client.namespaces().withName(namespace).delete();
    }

    private Instant eventInstantSafely(Event event) {
        if (event == null) return null;

        // 1) lastTimestamp (often empty on recent k8s)
        String eventTimestamp = event.getLastTimestamp();

        // 2) fallback: creationTimestamp (always present)
        if (eventTimestamp == null || eventTimestamp.isBlank()) {
            eventTimestamp = event.getMetadata() != null ? event.getMetadata().getCreationTimestamp() : null;
        }

        if (eventTimestamp == null || eventTimestamp.isBlank()) return null;

        // Tolerant parsers for ISO-8601/RFC3339 formats
        try {
            return OffsetDateTime.parse(eventTimestamp).toInstant();
        } catch (Exception ex) {
            try {
                return Instant.parse(eventTimestamp);
            } catch (Exception ex2) {
                LOG.debug("Unparseable event timestamp: {}", eventTimestamp, ex2);
                return null;
            }
        }
    }

    /**
     * Check if a CRD does exists in the kubernetes cluster
     */
    public boolean crdExists(String crdName) {
        Objects.requireNonNull(crdName, "crdName");
        checkConfiguration();
        try {
            return client.apiextensions()
                    .v1()
                    .customResourceDefinitions()
                    .withName(crdName)
                    .get() != null;
        } catch (KubernetesClientException e) {
            LOG.error("Erreur lors de la vérification du CRD {}: {}", crdName, e.getMessage());
            throw e;
        }
    }
    public void applyYaml(Path yamlPath, String releaseName, String releaseNamespace) {
        LOG.info("Applying YAML manifest from file: {}, for Helm release {}/{}", yamlPath, releaseNamespace, releaseName);
        Objects.requireNonNull(yamlPath, "yamlPath");
        Objects.requireNonNull(releaseName, "releaseName");
        Objects.requireNonNull(releaseNamespace, "releaseNamespace");
        checkConfiguration();

        try (InputStream is = Files.newInputStream(yamlPath)) {
            List<HasMetadata> items = client.load(is).items();

            for (HasMetadata item : items) {
                if ("CustomResourceDefinition".equals(item.getKind())) {
                    // Desired ownership for this release
                    final String desiredName = releaseName;
                    final String desiredNs   = releaseNamespace;

                    // Always put desired ownership into the in-memory object (for create path)
                    ensureHelmOwnership(item, desiredName, desiredNs);

                    final String crdName = item.getMetadata() != null ? item.getMetadata().getName() : null;
                    if (crdName == null || crdName.isBlank()) {
                        LOG.warn("CRD without metadata.name in {}, skipping.", yamlPath);
                        continue;
                    }

                    var crdClient = client.apiextensions().v1().customResourceDefinitions().withName(crdName);
                    var existing = crdClient.get();
                    if (existing != null) {
                        // Compare current ownership
                        var lm = existing.getMetadata();
                        String curManagedBy = lm != null && lm.getLabels() != null ? lm.getLabels().get("app.kubernetes.io/managed-by") : null;
                        String curRelName   = lm != null && lm.getAnnotations() != null ? lm.getAnnotations().get("meta.helm.sh/release-name") : null;
                        String curRelNs     = lm != null && lm.getAnnotations() != null ? lm.getAnnotations().get("meta.helm.sh/release-namespace") : null;

                        boolean needsPatch =
                            !"Helm".equals(curManagedBy) ||
                            !Objects.equals(desiredName, curRelName) ||
                            !Objects.equals(desiredNs, curRelNs);

                        if (needsPatch) {
                            LOG.info("Adopting CRD '{}' for Helm release {}/{} (was managed-by='{}', release-name='{}', release-namespace='{}')",
                                    crdName, desiredNs, desiredName, curManagedBy, curRelName, curRelNs);

                            crdClient.edit(c -> new CustomResourceDefinitionBuilder(c)
                                .editMetadata()
                                    .addToLabels("app.kubernetes.io/managed-by", "Helm")
                                    .addToAnnotations("meta.helm.sh/release-name", desiredName)
                                    .addToAnnotations("meta.helm.sh/release-namespace", desiredNs)
                                .endMetadata()
                                .build());

                            LOG.info("Adopted CRD '{}' → managed-by=Helm, release-name={}, release-namespace={}",
                                    crdName, desiredName, desiredNs);
                        } else {
                            LOG.debug("CRD '{}' already owned by {}/{}; no patch needed.", crdName, desiredNs, desiredName);
                        }

                        // IMPORTANT: don't createOrReplace() again for CRDs; we only adjusted metadata.
                        continue;
                    }

                    // Not present → create with ownership already set on the object
                    client.resource(item).create();
                    LOG.info("Created CRD '{}' with Helm ownership {}/{}", crdName, desiredNs, desiredName);
                    continue;
                }

                // Non-CRDs: apply normally
                client.resource(item).createOrReplace();
            }

            LOG.info("Manifest have been applied successfully from {}", yamlPath);

        } catch (KubernetesClientException e) {
            throw new RuntimeException("Echec d'application du YAML " + yamlPath + " : " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Lecture YAML impossible : " + yamlPath, e);
        }
    }

    public void applyClasspathYamlTemplate(String resourcePathOnClasspath,
                                           String releaseName,
                                           String targetNamespace,
                                           Map<String, String> variables) {
        Objects.requireNonNull(resourcePathOnClasspath, "resourcePathOnClasspath");
        Objects.requireNonNull(targetNamespace, "targetNamespace");
        checkConfiguration();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePathOnClasspath)) {
            if (is == null) {
                throw new IllegalArgumentException("Template not found on classpath: " + resourcePathOnClasspath);
            }
            String raw = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            // simple token replacement: {{KEY}}
            if (variables != null) {
                for (Map.Entry<String,String> e : variables.entrySet()) {
                    String token = "{{" + e.getKey() + "}}";
                    raw = raw.replace(token, e.getValue() == null ? "" : e.getValue());
                }
            }

            // load and apply
            try (InputStream rendered = new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))) {
                List<HasMetadata> items = client.load(rendered).items();
                for (HasMetadata item : items) {
                    // force target namespace for namespaced objects
                    if (item.getMetadata() != null) {
                        if (item.getMetadata().getNamespace() == null || item.getMetadata().getNamespace().isBlank()) {
                            item.getMetadata().setNamespace(targetNamespace);
                        }
                    }
                    client.resource(item).inNamespace(targetNamespace).createOrReplace();
                }
            }

            LOG.info("Applied template '{}' into namespace '{}'", resourcePathOnClasspath, targetNamespace);

        } catch (Exception ex) {
            throw new RuntimeException("Failed to apply template " + resourcePathOnClasspath + ": " + ex.getMessage(), ex);
        }
    }

    private static void ensureHelmOwnership(HasMetadata r, String releaseName, String releaseNamespace) {
        ObjectMeta md = r.getMetadata();
        if (md == null) {
            md = new ObjectMeta();
            r.setMetadata(md);
        }
        if (md.getLabels() == null) md.setLabels(new HashMap<>());
        if (md.getAnnotations() == null) md.setAnnotations(new HashMap<>());
        md.getLabels().put("app.kubernetes.io/managed-by", "Helm");
        md.getAnnotations().put("meta.helm.sh/release-name", releaseName);
        md.getAnnotations().put("meta.helm.sh/release-namespace", releaseNamespace);
    }

    /** Surcharge pratique si tu passes un String. */
    public void applyYaml(String yamlPath, String releaseName, String releaseNamespace) {
        applyYaml(java.nio.file.Paths.get(Objects.requireNonNull(yamlPath, "yamlPath")), releaseName, releaseNamespace);
    }

    public KubernetesClient getClient() { 
        return client; 
    }

    public ViewConfigurationService getConfigurationService(){
        return this.configurationService;
    }

    public void ensureImagePullSecretWithUsernameAndPassword(String repoId,
                                              String namespace,
                                              String secretName,
                                              String userName,
                                              String password,
                                              String registryServer,
                                              List<String> serviceAccountsToPatch) {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(userName, "userName");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(registryServer, "registryServer");
        if (serviceAccountsToPatch == null) serviceAccountsToPatch = List.of();


        final String dockerConfigJson = dockerConfigJson(registryServer, userName, password);

        // 1) Create or update the pull secret (type: kubernetes.io/dockerconfigjson)
        Secret desired = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(secretName)
                        .withNamespace(namespace)
                        .addToAnnotations("managed-by", "ambari-k8s-view")
                        .addToAnnotations("managed-at", Instant.now().toString())
                        .build())
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson",
                        Base64.getEncoder().encodeToString(dockerConfigJson.getBytes(StandardCharsets.UTF_8)))
                .build();

        Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null) {
            client.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created imagePullSecret '{}' in namespace '{}'", secretName, namespace);
        } else {
            // Replace to refresh creds atomically
            client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated imagePullSecret '{}' in namespace '{}'", secretName, namespace);
        }

        // 2) Patch ServiceAccounts to include the secret in imagePullSecrets
        try {
            var existingSas = client.serviceAccounts().inNamespace(namespace).list().getItems().stream()
                    .map(sa -> sa.getMetadata() != null ? sa.getMetadata().getName() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            serviceAccountsToPatch = serviceAccountsToPatch.stream()
                    .filter(existingSas::contains)
                    .collect(Collectors.toList());

            if (serviceAccountsToPatch.isEmpty()) {
                LOG.info("No matching ServiceAccounts found in namespace '{}'; skipping patch step.", namespace);
            }
        } catch (Exception e) {
            LOG.warn("Failed to list ServiceAccounts in namespace '{}': {}. Skipping patch step.", namespace, e.getMessage());
            serviceAccountsToPatch = List.of();
        }
        for (String saName : serviceAccountsToPatch) {
            if (saName == null || saName.isBlank()) continue;
            client.serviceAccounts().inNamespace(namespace).withName(saName).edit(sa -> {
                if (sa == null) return null;
                if (sa.getImagePullSecrets() == null) sa.setImagePullSecrets(new ArrayList<>());
                final String sn = secretName;
                boolean present = sa.getImagePullSecrets().stream()
                        .anyMatch(r -> r != null && sn.equals(r.getName()));
                if (!present) {
                    sa.getImagePullSecrets().add(new LocalObjectReferenceBuilder().withName(sn).build());
                    LOG.info("Patched ServiceAccount '{}/{}' to use imagePullSecret '{}'", namespace, saName, sn);
                } else {
                    LOG.debug("ServiceAccount '{}/{}' already references imagePullSecret '{}'", namespace, saName, sn);
                }
                return sa;
            });
        }
    }

    /** Build dockerconfigjson content for kubernetes.io/dockerconfigjson secrets. */
    private static String dockerConfigJson(String server, String username, String password) {
        String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        // Minimal format
        return "{ \"auths\": { \"" + server + "\": { " +
                "\"username\":\"" + esc(username) + "\"," +
                "\"password\":\"" + esc(password) + "\"," +
                "\"auth\":\"" + auth + "\"" +
                "} } }";
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void createMounts(String namespace, String releaseName, Map<String,Object> mounts){
        if(mountManager == null) {
            this.mountManager = new MountManager(this.client);
            mountManager.ensureMounts(namespace, releaseName, mounts);
        }

    }

    /**
     * Create or update an Opaque Secret with a single binary entry.
     * Data will be base64-encoded as required by Kubernetes.
     */
    public void createOrUpdateOpaqueSecret(String namespace,
                                           String secretName,
                                           String dataKey,
                                           byte[] data) {
        createOrUpdateOpaqueSecret(namespace, secretName, dataKey, data, null, null, Boolean.TRUE);
    }

    /**
     * Create or update an Opaque Secret (fully customizable).
     * If an existing Secret is immutable, we delete & recreate it atomically.
     */
    public void createOrUpdateOpaqueSecret(String namespace,
                                           String secretName,
                                           String dataKey,
                                           byte[] data,
                                           Map<String, String> labels,
                                           Map<String, String> annotations,
                                           Boolean immutable) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(dataKey, "dataKey");
        Objects.requireNonNull(data, "data");
        checkConfiguration();

        try {
            // Build desired secret
            Map<String, String> dataMap = new LinkedHashMap<>();
            dataMap.put(dataKey, Base64.getEncoder().encodeToString(data));

            ObjectMeta meta = new ObjectMetaBuilder()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToAnnotations("managed-by", "ambari-k8s-view")
                    .addToAnnotations("managed-at", java.time.Instant.now().toString())
                    .build();

            if (labels != null && !labels.isEmpty()) {
                if (meta.getLabels() == null) meta.setLabels(new HashMap<>());
                meta.getLabels().putAll(labels);
            }
            if (annotations != null && !annotations.isEmpty()) {
                if (meta.getAnnotations() == null) meta.setAnnotations(new HashMap<>());
                meta.getAnnotations().putAll(annotations);
            }

            Secret desired = new SecretBuilder()
                    .withMetadata(meta)
                    .withType("Opaque")
                    .withData(dataMap)
                    .withImmutable(immutable) // null = unset; TRUE = immutable; FALSE = mutable
                    .build();

            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();

            if (existing == null) {
                client.secrets().inNamespace(namespace).resource(desired).create();
                LOG.info("Created Opaque Secret '{}/{}'", namespace, secretName);
                return;
            }

            // If immutable and content changed → must delete & recreate
            boolean existingImmutable = Boolean.TRUE.equals(existing.getImmutable());
            boolean sameData = Objects.equals(
                    existing.getData() != null ? existing.getData().get(dataKey) : null,
                    desired.getData().get(dataKey)
            );

            if (existingImmutable && !sameData) {
                LOG.info("Secret '{}/{}' is immutable and data changed → delete & recreate", namespace, secretName);
                client.secrets().inNamespace(namespace).withName(secretName).delete();
                client.secrets().inNamespace(namespace).resource(desired).create();
                LOG.info("Recreated immutable Opaque Secret '{}/{}'", namespace, secretName);
                return;
            }

            // Otherwise: createOrReplace (handles metadata updates & mutable data changes)
            client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated Opaque Secret '{}/{}'", namespace, secretName);

        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            throw new RuntimeException("Failed to create/update Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }
}
