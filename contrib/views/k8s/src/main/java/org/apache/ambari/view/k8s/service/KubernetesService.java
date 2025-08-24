package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.marcnuri.helm.Helm;
import com.marcnuri.helm.InstallCommand;
import com.marcnuri.helm.Release;
import com.marcnuri.helm.UninstallCommand;
import com.marcnuri.helm.DryRun;

import io.fabric8.kubernetes.api.model.ComponentCondition;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import io.fabric8.openshift.client.OpenShiftClient;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.fabric8.kubernetes.client.utils.Serialization;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.*;

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
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;

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
import java.util.Base64;
import java.util.Collections;
import java.util.Objects;

/**
 * Service containing the real business logic to interact with the Kubernetes API.
 */
public class KubernetesService {
    
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesService.class);
    
    private final KubernetesClient client;
    private final boolean isConfigured;
    private ViewContext viewContext;

    private final HelmRepositoryService repositoryService;
    private final HelmClient helmClient;
    private final HelmService helmService;

    public KubernetesService(KubernetesClient client, boolean isConfigured) {
        this.viewContext = null; // Default constructor for testing
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.helmService = null; // testing
        this.repositoryService = null;
    }

    public KubernetesService(ViewContext viewContext, KubernetesClient client, boolean isConfigured) {
        this.viewContext = viewContext;
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.helmService = null; // testing
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
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
        ViewConfigurationService configurationService = new ViewConfigurationService(viewContext);
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
                    LOG.info("Kubernetes client configuration loaded from kubeconfig file.\n" + kubeconfigContent);

                    LOG.info("Overriding view properties from ambari.properties into system properties for Kubernetes client.");
                    loadK8sPropsAsSystemProperties(viewContext);

                    // Parse kubeconfig to extract certificate
                    io.fabric8.kubernetes.api.model.Config kubeconfigModel = Serialization.unmarshal(kubeconfigContent, io.fabric8.kubernetes.api.model.Config.class);
                    String certificateAuthorityData = kubeconfigModel.getClusters().get(0).getCluster().getCertificateAuthorityData();
                    Config finalConfiguration = configurationBuilder.build();

                    if (certificateAuthorityData != null && !certificateAuthorityData.isEmpty()) {
                        LOG.info("Found 'certificate-authority-data' in kubeconfig. Applying it to the client configuration.");
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

    /**
     * Deploys a Helm chart by generating a values.yaml file, running 'helm template',
     * and applying the resulting manifest with the Fabric8 client.
     *
     * @param request The deployment request containing all necessary information.
     * @throws IOException if there is an issue with file operations.
     * @throws InterruptedException if the 'helm' process is interrupted.
     */
    public void deployHelmChart(HelmDeployRequest request) {
        File temporaryValuesFile = null;
        File temporaryChartFile = null;
        try {
            // Create temporary values.yaml file
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            temporaryValuesFile = Files.createTempFile(request.getReleaseName() + "-", ".yaml").toFile();
            Map<String, Object> chartValues = request.getValues();
            if (chartValues != null && !chartValues.isEmpty()) {
                yamlMapper.writeValue(temporaryValuesFile, chartValues);
            }

            // Chart resolution
            String chartArgument = request.getChart();   // can be repo/chart, URL .tgz, classpath:...
            Path chartPath = null;

            if (chartArgument.startsWith("classpath:")) {
                String resourcePath = chartArgument.substring("classpath:".length());
                try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (inputStream == null) throw new IllegalArgumentException("Chart not found in classpath: " + resourcePath);
                    temporaryChartFile = Files.createTempFile("chart-", ".tgz").toFile();
                    try (OutputStream outputStream = new FileOutputStream(temporaryChartFile)) { 
                        inputStream.transferTo(outputStream); 
                    }
                }
                chartPath = temporaryChartFile.toPath();
                chartArgument = chartPath.toString();
            } else if (chartArgument.endsWith(".tgz") || chartArgument.endsWith(".tar.gz") ||
                      chartArgument.startsWith("/") || chartArgument.startsWith("./")) {
                chartPath = Paths.get(chartArgument);
            }

            // Check/register HTTP repository
            String repositoryPrefix = chartArgument.contains("/") ? chartArgument.substring(0, chartArgument.indexOf('/')) : null;
            if (repositoryPrefix != null && repositoryService != null) {
                try {
                    repositoryService.ensureHttpRepo(repositoryPrefix);   // will be a no-op if already present
                } catch (Exception ex) {
                    LOG.warn("Repository {} not found or inaccessible: {}", repositoryPrefix, ex.getMessage());
                }
            }

            // Helm call via HelmClient
            PathConfig pathConfiguration = new PathConfig(viewContext);
            Release helmRelease = helmClient.install(
                chartArgument,
                request.getReleaseName(),
                request.getNamespace(),
                pathConfiguration.repositoriesConfig(),       // Path to repositories.yaml
                null,                 // provide real kubeconfig contents
                chartValues,
                900,
                true,   // createNamespace (often yes)
                true,   // wait
                false,  // atomic (set true if you want auto rollback)
                false   // dryRun
            );

            LOG.info("Helm deployed: name={} ns={}", helmRelease.getName(), helmRelease.getNamespace());

        } catch (Exception e) {
            LOG.error("Helm deployment failed for release {}", request.getReleaseName(), e);
            throw new RuntimeException(e);
        } finally {
            try {
                if (temporaryValuesFile != null) Files.deleteIfExists(temporaryValuesFile.toPath());
                if (temporaryChartFile != null) Files.deleteIfExists(temporaryChartFile.toPath());
            } catch (IOException ioe) {
                LOG.warn("Unable to clean up temporary values/chart files", ioe);
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

    public KubernetesClient getClient() { 
        return client; 
    }
}
