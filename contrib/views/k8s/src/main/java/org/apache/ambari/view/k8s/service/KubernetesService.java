package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.ComponentCondition;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.*;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.utils.CompositeTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;

/**
 * Service containing the real business logic to interact with the Kubernetes API.
 */
public class KubernetesService {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesService.class);
    private final KubernetesClient client;
    private final boolean isConfigured;

    public KubernetesService(ViewContext viewContext) {
        ViewConfigurationService configService = new ViewConfigurationService(viewContext);
        String kubeconfigPath = configService.getKubeconfigPath();
        
        if (kubeconfigPath == null) {
            LOG.info("View is not configured. Kubernetes client will not be initialized.");
            this.isConfigured = false;
            this.client = null;
        } else {
            File configFile = new File(kubeconfigPath);
            if (!configFile.exists()) {
                LOG.warn("Stale configuration detected. Kubeconfig file not found at path: {}. Resetting configuration.", kubeconfigPath);
                configService.removeKubeconfigPath(); // Auto-healing: remove stale entry
                this.isConfigured = false;
                this.client = null;
            } else {

                try {
                    EncryptionService encryptionService = new EncryptionService();
                    byte[] encryptedBytes = Files.readAllBytes(Paths.get(kubeconfigPath));
                    byte[] decryptedBytes = encryptionService.decrypt(encryptedBytes);
                    String kubeconfigContent = new String(decryptedBytes, StandardCharsets.UTF_8);
                    
                    // On charge la configuration de base depuis le contenu du fichier
                    LOG.info("Kubeconfig file loaded successfully from: {}", kubeconfigPath);
                    Config baseConfig = Config.fromKubeconfig(kubeconfigContent);
                    ConfigBuilder configBuilder = new ConfigBuilder(baseConfig);

                    // On charge les propriétés de la vue dans le contexte
                    LOG.info("Loading view properties from ambari.properties into system properties for Kubernetes client.");
                    loadK8sPropsAsSystemProperties(viewContext);
                    // On parse le kubeconfig pour extraire le certificat
                    io.fabric8.kubernetes.api.model.Config kubeCfgModel = Serialization.unmarshal(kubeconfigContent, io.fabric8.kubernetes.api.model.Config.class);
                    String caData = kubeCfgModel.getClusters().get(0).getCluster().getCertificateAuthorityData();
                    Config finalConfig = configBuilder.build();

                    if (caData != null && !caData.isEmpty()) {
                        LOG.info("Found 'certificate-authority-data' in kubeconfig. Applying it to the client configuration.");
 
                        LOG.info("Found 'certificate-authority-data' in kubeconfig. Adding it to the JVM's default SSL context.");

                        // === 1. Get the default JVM TrustManager ===
                        TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        defaultTmf.init((KeyStore) null); // Loads default cacerts
                        X509TrustManager defaultTrustManager = (X509TrustManager) defaultTmf.getTrustManagers()[0];

                        // === 2. Create a custom TrustManager for the K8s CA ===
                        byte[] decodedCaBytes = Base64.getDecoder().decode(caData);
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        X509Certificate caCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decodedCaBytes));

                        KeyStore customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                        customTrustStore.load(null, null);
                        customTrustStore.setCertificateEntry("k8s-ca", caCert);

                        TrustManagerFactory customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        customTmf.init(customTrustStore);
                        X509TrustManager customTrustManager = (X509TrustManager) customTmf.getTrustManagers()[0];

                        // === 3. Combine them and set the new default SSLContext ===
                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        // The CompositeTrustManager ensures we trust BOTH default CAs and our custom one
                        sslContext.init(null, new TrustManager[]{new CompositeTrustManager(defaultTrustManager, customTrustManager)}, null);
                        SSLContext.setDefault(sslContext);

                        LOG.info("JVM default SSL context has been updated to trust the Kubernetes CA.");

                        // Now, the Fabric8 client (and any other client) will use the new default context.
                        // You no longer need to configure the client's SSL context individually.
                        this.client = new DefaultKubernetesClient(finalConfig);
                    } else if (viewContext.getAmbariProperty("k8s.view.ssl.truststore.path") != null) {
                        String trustStorePath = viewContext.getAmbariProperty("k8s.view.ssl.truststore.path");
                        String trustStorePassword = viewContext.getAmbariProperty("k8s.view.ssl.truststore.password");
                        LOG.info("Using TrustStore defined in ambari.properties: {}", trustStorePath);
                        configBuilder.withTrustStoreFile(trustStorePath);
                        if (trustStorePassword != null) {
                            configBuilder.withTrustStorePassphrase(trustStorePassword);
                        }
                        this.client = new DefaultKubernetesClient(finalConfig);
                    } else {
                        LOG.info("No custom SSL configuration found. Using default JVM TrustStore.");
                        this.client = new DefaultKubernetesClient(finalConfig);
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
        // Fetch last 20 events, sorted by timestamp
        return client.v1().events().inAnyNamespace().list().getItems().stream()
                .sorted((e1, e2) -> e2.getLastTimestamp().compareTo(e1.getLastTimestamp()))
                .limit(20)
                .map(this::toClusterEvent)
                .collect(Collectors.toList());
    }

    public ClusterStats getClusterStats() {
        checkConfiguration();
        LOG.info("Calculating cluster stats from Kubernetes API.");

        NodeList nodeList = client.nodes().list();
        PodList podList = client.pods().inAnyNamespace().list();

        double totalCpu = 0;
        double totalMemory = 0;
        long readyNodes = 0;

        for (Node node : nodeList.getItems()) {
            Map<String, Quantity> capacity = node.getStatus().getCapacity();
            if (capacity.containsKey("cpu")) {
                totalCpu += capacity.get("cpu").getNumericalAmount().doubleValue();
            }
            if (capacity.containsKey("memory")) {
                totalMemory += capacity.get("memory").getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // To GiB
            }
            
            boolean isReady = node.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
            if (isReady) {
                readyNodes++;
            }
        }

        // NOTE: Real-time usage stats require the Kubernetes Metrics Server.
        // This implementation shows total capacity. A more advanced version would query the metrics API.
        
        ClusterStats.ResourceStat cpuStat = new ClusterStats.ResourceStat(0, totalCpu); // Usage requires metrics
        ClusterStats.ResourceStat memStat = new ClusterStats.ResourceStat(0, totalMemory); // Usage requires metrics
        ClusterStats.ResourceStat podStat = new ClusterStats.ResourceStat(podList.getItems().size(), 0); // Total pods is dynamic
        ClusterStats.ResourceStat nodeStat = new ClusterStats.ResourceStat(readyNodes, nodeList.getItems().size());
        
        // Helm stats would require Helm client logic, which is complex. Returning placeholder.
        ClusterStats.HelmStat helmStat = new ClusterStats.HelmStat(0, 0, 0, 0);

        return new ClusterStats(cpuStat, memStat, podStat, nodeStat, helmStat);
    }

    public List<HelmRelease> getHelmReleases() {
        checkConfiguration();
        LOG.info("Fetching Helm releases (simulation).");
        // TODO: Implement real Helm client logic. This is a complex task
        // that might require adding a Helm client library dependency.
        return Collections.emptyList();
    }

    // --- Helper methods to convert Fabric8 models to our DTOs ---

    private ClusterNode toClusterNode(Node node) {
        String status = "NotReady";
        for (var condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                status = "Ready";
                break;
            }
        }
        List<String> roles = node.getMetadata().getLabels().keySet().stream()
                .filter(k -> k.startsWith("node-role.kubernetes.io/"))
                .map(k -> k.substring(k.indexOf("/") + 1))
                .collect(Collectors.toList());
        
        // Usage stats require metrics server, returning 0 for now.
        return new ClusterNode(node.getMetadata().getUid(), node.getMetadata().getName(), status, roles, 0.0, 0.0);
    }
    
    private ComponentStatus toComponentStatus(io.fabric8.kubernetes.api.model.ComponentStatus cs) {
        String status = "Unhealthy";
        ComponentCondition condition = cs.getConditions().stream()
                .filter(c -> "Healthy".equals(c.getType()))
                .findFirst().orElse(null);
        if (condition != null && "True".equals(condition.getStatus())) {
            status = "Healthy";
        }
        return new ComponentStatus(cs.getMetadata().getName(), status);
    }
    
    private ClusterEvent toClusterEvent(Event event) {
        String type = "Info";
        if (event.getType() != null && event.getType().equalsIgnoreCase("Warning")) {
            type = "Warning";
        }
        // More sophisticated logic could map different reasons to 'Alert'
        
        return new ClusterEvent(event.getMetadata().getUid(), type, event.getMessage(), event.getLastTimestamp());
    }

    public static void loadK8sPropsAsSystemProperties(ViewContext context) {
        Map<String, String> properties = context.getProperties();

        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("k8sview.")) continue;

            // Remove the prefix, keep key as-is (dot-separated, lowercase)
            LOG.info("Found property: {} = {}", key, entry.getValue());
            LOG.info("Setting system property: {} = {}", key.substring("k8sview.".length()), entry.getValue());
            String systemKey = key.substring("k8sview.".length());
            String value = entry.getValue();

            if (value != null && !value.isBlank()) {
                System.setProperty(systemKey, value);
            }
        }
    }
}
