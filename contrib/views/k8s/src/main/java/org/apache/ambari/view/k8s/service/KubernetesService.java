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

    private final HelmRepositoryService repoSvc;      // for testing
    private final HelmClient helm;                    // for testing
    private final HelmService helmSvc;                    // for testing

    public KubernetesService(KubernetesClient client, boolean isConfigured) {
        this.viewContext = null; // Default constructor for testing
        this.client = client; // No client initialized
        this.isConfigured = isConfigured; // Not configured by default
        this.helm    = new HelmClientDefault();
        this.helmSvc = null; // testing
        this.repoSvc = null;
    }

    public KubernetesService(ViewContext viewContext, KubernetesClient client, boolean isConfigured) {
        this.viewContext = viewContext; // Default constructor for testing
        this.client = client; // No client initialized
        this.isConfigured = isConfigured; // Not configured by default
        this.helm    = new HelmClientDefault();
        this.helmSvc = null; // testing
        this.repoSvc = new HelmRepositoryService(viewContext, helm);
    }

    public KubernetesService(ViewContext ctx,  KubernetesClient k8s, HelmClient helmClient, boolean isConfigured) {
        this.viewContext = ctx;
        this.client      = k8s;
        this.isConfigured = isConfigured;
        // services internes avec le même HelmClient mocké
        this.helm = helmClient;
        this.helmSvc = new HelmService(ctx, helmClient);
        this.repoSvc = new HelmRepositoryService(ctx, helmClient);
    }

    public KubernetesService(ViewContext viewContext) {
        ViewConfigurationService configService = new ViewConfigurationService(viewContext);
        String kubeconfigPath = configService.getKubeconfigPath();
        this.viewContext = viewContext;
        this.helm    = new HelmClientDefault();
        this.repoSvc = new HelmRepositoryService(viewContext, helm);
        this.helmSvc = new HelmService(viewContext, helm);
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

                // the logic is the following:
                // 1. Read the kubeconfig file
                // 2. Decrypt it using the EncryptionService
                // 3. Parse the decrypted content to create a Kubernetes client
                
                try {
                    EncryptionService encryptionService = new EncryptionService();
                    byte[] encryptedBytes = Files.readAllBytes(Paths.get(kubeconfigPath));
                    byte[] decryptedBytes = encryptionService.decrypt(encryptedBytes);
                    String kubeconfigContent = new String(decryptedBytes, StandardCharsets.UTF_8);
                    
                    // On charge la configuration de base depuis le contenu du fichier
                    LOG.info("Kubeconfig file loaded successfully from: {}", kubeconfigPath);
                    Config baseConfig = Config.fromKubeconfig(kubeconfigContent);
                    ConfigBuilder configBuilder = new ConfigBuilder(baseConfig);
                    LOG.info("Kubernetes client configuration loaded from kubeconfig file.\n" + kubeconfigContent);

                    // On charge les propriétés de la vue dans le contexte
                    LOG.info("Overriding view properties from ambari.properties into system properties for Kubernetes client.");
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
                        // this.client = new DefaultKubernetesClient(finalConfig);
                    } else if (viewContext.getAmbariProperty("k8s.view.ssl.truststore.path") != null) {
                        String trustStorePath = viewContext.getAmbariProperty("k8s.view.ssl.truststore.path");
                        String trustStorePassword = viewContext.getAmbariProperty("k8s.view.ssl.truststore.password");
                        LOG.info("Using TrustStore defined in ambari.properties: {}", trustStorePath);
                        configBuilder.withTrustStoreFile(trustStorePath);
                        if (trustStorePassword != null) {
                            configBuilder.withTrustStorePassphrase(trustStorePassword);
                        }
                        // this.client = new DefaultKubernetesClient(finalConfig);
                    } else {
                        LOG.info("No custom SSL configuration found. Using default JVM TrustStore.");
                        // this.client = new DefaultKubernetesClient(finalConfig);
                    }

                    this.client = new DefaultKubernetesClient(finalConfig);
                    // if (isOpenShift(this.client)) {
                    if (false) {
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

        List<Event> items = client.v1().events().inAnyNamespace().list().getItems();

        Comparator<Event> byTimeDesc = Comparator.comparing(
            this::eventInstantSafely,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed();

        return items.stream()
            .sorted(byTimeDesc)
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
        double usedMemory = 0;
        for (Node node : nodeList.getItems()) {
            try {
                var metrics = client.top().nodes().metrics(node.getMetadata().getName());
                if (metrics != null && metrics.getUsage() != null && metrics.getUsage().containsKey("memory")) {
                    Quantity memQty = metrics.getUsage().get("memory");
                    usedMemory += memQty.getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // GiB
                }
            } catch (Exception ex) {
                LOG.warn("Could not fetch memory usage for node {}: {}", node.getMetadata().getName(), ex.getMessage());
            }
        }
        ClusterStats.ResourceStat cpuStat = new ClusterStats.ResourceStat(0, totalCpu); // Usage requires metrics
        ClusterStats.ResourceStat memStat = new ClusterStats.ResourceStat(usedMemory, totalMemory); // Usage requires metrics
        ClusterStats.ResourceStat podStat = new ClusterStats.ResourceStat(runningPods.size(), podList.getItems().size()); // Total pods is dynamic
        ClusterStats.ResourceStat nodeStat = new ClusterStats.ResourceStat(readyNodes, nodeList.getItems().size());
        
        // Helm stats would require Helm client logic, which is complex. Returning placeholder.
        ClusterStats.HelmStat helmStat = new ClusterStats.HelmStat(0, 0, 0, 0);

        return new ClusterStats(cpuStat, memStat, podStat, nodeStat, helmStat);
    }

    // public List<HelmRelease> getHelmReleases() {
    //     checkConfiguration();
    //     LOG.info("Fetching Helm releases (simulation).");
    //     // TODO: Implement real Helm client logic. This is a complex task
    //     // that might require adding a Helm client library dependency.
    //     return Collections.emptyList();
    // }

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
        String type = (event.getType() != null && event.getType().equalsIgnoreCase("Warning"))
            ? "Warning" : "Info";

        String ts = event.getLastTimestamp();
        if (ts == null || ts.isBlank()) {
            ts = event.getMetadata() != null ? event.getMetadata().getCreationTimestamp() : null;
        }

        return new ClusterEvent(
            event.getMetadata() != null ? event.getMetadata().getUid() : null,
            type,
            event.getMessage(),
            ts
        );
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
    public String loadAvailableCharts() throws IOException {
        String chartsPath = null;
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
            chartsPath = chartsProperty;
        }
        if (chartsPath != null && !chartsPath.isBlank()) {
            File chartsFile = new File(chartsPath, "charts.json");
            if (chartsFile.exists()) {
            return Files.readString(chartsFile.toPath(), StandardCharsets.UTF_8);
            } else {
            throw new IOException("charts.json not found at: " + chartsFile.getAbsolutePath());
            }
        } else {
            try (var is = getClass().getClassLoader().getResourceAsStream("charts.json")) {
            if (is == null) {
                throw new IOException("charts.json not found in classpath.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
    /**
    * Deploy a Helm chart (install or upgrade-install) using the injected HelmClient.
    * – Réécrit pour être testable : dépend UNIQUEMENT de helm & repoSvc, plus de CLI directe.
    */
    public void deployHelmChart(HelmDeployRequest request) {
      File tmpValues = null;
      File tmpChart  = null;
      try {
        // ---------- 1. values.yaml temporaire ----------
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        tmpValues = Files.createTempFile(request.getReleaseName() + "-", ".yaml").toFile();
        Map<String,Object> vals = request.getValues();
        if (vals != null && !vals.isEmpty()) {
          yaml.writeValue(tmpValues, vals);
        }

        // ---------- 2. Résolution du chart ----------
        String chartArg  = request.getChart();   // peut être repo/chart, URL .tgz, classpath:…
        Path   chartPath = null;

        if (chartArg.startsWith("classpath:")) {
          String res = chartArg.substring("classpath:".length());
          try (InputStream is = getClass().getClassLoader().getResourceAsStream(res)) {
            if (is == null) throw new IllegalArgumentException("Chart not found in classpath: " + res);
            tmpChart = Files.createTempFile("chart-", ".tgz").toFile();
            try (OutputStream os = new FileOutputStream(tmpChart)) { is.transferTo(os); }
          }
          chartPath = tmpChart.toPath();
          chartArg  = chartPath.toString();
        } else if (chartArg.endsWith(".tgz") || chartArg.endsWith(".tar.gz") ||
                  chartArg.startsWith("/")  || chartArg.startsWith("./")) {
          chartPath = Paths.get(chartArg);
        }

        // ---------- 3. Vérifier / enregistrer le repo HTTP ----------
        String repoPrefix = chartArg.contains("/") ? chartArg.substring(0, chartArg.indexOf('/')) : null;
        if (repoPrefix != null && repoSvc != null) {
          try {
            repoSvc.ensureHttpRepo(repoPrefix);   // sera un no-op si déjà present
          } catch (Exception ex) {
            LOG.warn("Repo {} introuvable ou inacessible : {}", repoPrefix, ex.getMessage());
          }
        }

        // ---------- 4. Appel Helm via HelmClient ----------
        PathConfig paths = new PathConfig(viewContext);
        Release rel = helm.install(
            chartArg,
            request.getReleaseName(),
            request.getNamespace(),
            paths.repositoriesConfig(),       // Path vers repositories.yaml
            null,                 // <-- fournis un vrai kubeconfig contents
            vals,
            900,
            true,   // createNamespace (souvent oui)
            true,   // wait
            false,  // atomic (mets true si tu veux rollback auto)
            false   // dryRun
        );

        LOG.info("Helm déployé : name={} ns={}", rel.getName(), rel.getNamespace());

      } catch (Exception e) {
        LOG.error("Échec du déploiement Helm pour release {}", request.getReleaseName(), e);
        throw new RuntimeException(e);
      } finally {
        try {
          if (tmpValues != null) Files.deleteIfExists(tmpValues.toPath());
          if (tmpChart  != null) Files.deleteIfExists(tmpChart.toPath());
        } catch (IOException ioe) {
          LOG.warn("Nettoyage valeurs/chart temporaires impossible", ioe);
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
    * Delete a namespace (best‑effort). Returns <code>true</code> if the request was accepted.
    */
    public void deleteNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        client.namespaces().withName(namespace).delete();
    }

    private Instant eventInstantSafely(Event e) {
        if (e == null) return null;

        // 1) lastTimestamp (souvent vide sur k8s récents)
        String ts = e.getLastTimestamp();

        // 2) fallback: creationTimestamp (toujours présent)
        if (ts == null || ts.isBlank()) {
            ts = e.getMetadata() != null ? e.getMetadata().getCreationTimestamp() : null;
        }

        if (ts == null || ts.isBlank()) return null;

        // Parsers tolérants aux formats ISO-8601/RFC3339
        try {
            return OffsetDateTime.parse(ts).toInstant();
        } catch (Exception ex) {
            try {
                return Instant.parse(ts);
            } catch (Exception ex2) {
                LOG.debug("Unparseable event timestamp: {}", ts, ex2);
                return null;
            }
        }
    }

    public KubernetesClient getClient() { return client; }
}
