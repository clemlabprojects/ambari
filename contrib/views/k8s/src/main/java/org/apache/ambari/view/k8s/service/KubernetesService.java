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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.WatchEvent;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.ComponentCondition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import io.fabric8.openshift.client.OpenShiftClient;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.fabric8.kubernetes.client.internal.SSLUtils;
import io.fabric8.kubernetes.client.utils.Serialization;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.RenderConfigSpec;
import org.apache.ambari.view.k8s.model.*;
import org.apache.ambari.view.k8s.model.kube.KubeNamespace;
import org.apache.ambari.view.k8s.model.kube.KubePod;
import org.apache.ambari.view.k8s.model.kube.KubePodContainerStatus;
import org.apache.ambari.view.k8s.model.kube.KubeServiceDTO;
import org.apache.ambari.view.k8s.model.kube.KubeEventDTO;
import org.apache.ambari.view.k8s.dto.RenderConfigType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmClientDefault;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.utils.CompositeTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import org.springframework.util.FileCopyUtils;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import java.time.Instant;
import java.time.OffsetDateTime;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;

import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;

/**
 * Service containing the real business logic to interact with the Kubernetes API.
 * Thread-safe for concurrent read operations; client is immutable after construction.
 */
public class KubernetesService {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesService.class);
    // Singleton cache per view instance to avoid reinitializing K8s client repeatedly
    private static final java.util.concurrent.ConcurrentMap<String, KubernetesService> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();
    
    private KubernetesClient client;
    private boolean isConfigured;
    private ViewContext viewContext;
    private WebHookConfigurationService webHookConfigurationService;

    private final HelmRepositoryService repositoryService;
    private final HelmService helmService;
    private final HelmClient helmClient;
    private final AtomicBoolean monitoringBootstrapScheduled = new AtomicBoolean(false);
    private final AtomicReference<String> metricsSource = new AtomicReference<>("unknown");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Boolean> runningInsideKubernetesCache = new AtomicReference<>(null);
    private final AtomicReference<String> lastPrometheusProxyUrlLogged = new AtomicReference<>(null);

    private MountManager mountManager;

    private ViewConfigurationService configurationService;

    public static final String WEBHOOK_ENABLED_LABEL_KEY = "security.clemlab.com/webhooks-enabled";

    // caching implementation
    private final Cache<String, List<Map<String, String>>> serviceCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();
    // Short-lived cache for expensive stats/metrics calls
    private final Cache<String, ClusterStats> statsCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(4)
            .build();
    // Monitoring bootstrap defaults
    private static final String DEFAULT_MONITORING_RELEASE = "kube-prometheus-stack";
    private static final String DEFAULT_MONITORING_NAMESPACE = "monitoring";
    private static final String DEFAULT_MONITORING_CHART = "kube-prometheus-stack";
    private static final String MONITORING_DEFAULT_REPO_FALLBACK_ID = "monitoring-default";
    private static final String MONITORING_DEFAULT_REPO_FALLBACK_NAME = "Monitoring repository";
    private static final String MONITORING_DEFAULT_REPO_AUTO_CREATE_PROP = "monitoring.defaultRepo.autoCreate";
    private static final String SETTINGS_KEY = "view.settings.json";
    private static final String OPENSHIFT_THANOS_URL_DEFAULT = "https://thanos-querier.openshift-monitoring.svc:9091";
    private static final String KUBERNETES_SERVICE_HOST_ENV = "KUBERNETES_SERVICE_HOST";
    private static final String KUBERNETES_SERVICE_PORT_ENV = "KUBERNETES_SERVICE_PORT";
    private static final Path KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH = Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/token");


    private static final int MAX_METRICS_NODES = Integer.getInteger("k8s.view.metrics.sample.nodes", 200);
    // Shared pool for short metrics calls to avoid blocking the main thread
    private static final ExecutorService METRICS_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "k8s-metrics-pool");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService BOOTSTRAP_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "k8s-monitoring-bootstrap");
        t.setDaemon(true);
        return t;
    });

    public static KubernetesService get(ViewContext ctx) {
        KubernetesService svc = INSTANCES.computeIfAbsent(ctx.getInstanceName(), k -> new KubernetesService(ctx));
        svc.scheduleMonitoringBootstrapAsync();
        return svc;
    }

    public KubernetesService(KubernetesClient client, boolean isConfigured) {
        this.viewContext = null; // Default constructor for testing
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(null, helmClient);
        this.helmService = new HelmService(null, helmClient);
        this.configurationService = null;
    }

    public KubernetesService(ViewContext viewContext, KubernetesClient client, boolean isConfigured) {
        this.viewContext = viewContext;
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
        this.helmService = new HelmService(viewContext, helmClient);
        this.configurationService = new ViewConfigurationService(viewContext);
    }

    public KubernetesService(ViewContext ctx, KubernetesClient k8s, HelmClient helmClient, boolean isConfigured) {
        this.viewContext = ctx;
        this.client = k8s;
        this.isConfigured = isConfigured;
        // Internal services with the same mocked HelmClient
        this.helmClient = helmClient;
        this.repositoryService = new HelmRepositoryService(ctx, helmClient);
        this.helmService = new HelmService(ctx, helmClient);
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

    public List<ClusterNode> getNodes(int limit, int offset) {
        checkConfiguration();
        LOG.info("Fetching node list from Kubernetes API.");
        var items = client.nodes().list().getItems();
        int from = Math.min(Math.max(offset, 0), items.size());
        int to = Math.min(from + Math.max(limit, 1), items.size());
        List<Node> pageNodes = items.subList(from, to);
        Map<String, NodeCapacity> capacities = new LinkedHashMap<>();
        List<ClusterNode> nodes = pageNodes.stream()
                .map(node -> {
                    ClusterNode cn = toClusterNode(node);
                    capacities.put(node.getMetadata().getName(), new NodeCapacity(
                            capacityQuantity(node, "cpu"),
                            capacityQuantity(node, "memory") / (1024 * 1024 * 1024)
                    ));
                    return cn;
                })
                .collect(Collectors.toList());
        Map<String, ClusterMetrics> metrics = fetchNodeMetrics(pageNodes, capacities);
        nodes.forEach(node -> {
            ClusterMetrics m = metrics.get(node.getNodeName());
            if (m != null) {
                node.setCpuUsagePercent(m.cpuUsagePercent);
                node.setMemoryUsagePercent(m.memoryUsagePercent);
            }
        });
        return nodes;
    }

    public int countNodes() {
        checkConfiguration();
        return client.nodes().list().getItems().size();
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

    public ClusterStats getClusterStats(boolean forceRefresh) {
        ClusterStats cachedStats = statsCache.getIfPresent("clusterStats");
        if (cachedStats != null && !forceRefresh) {
            return cachedStats;
        }
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
        // Prefer Prometheus/Thanos when available, fallback to metrics-server.
        double usedMemoryTotal = 0;
        double usedCpuTotal = 0;
        AtomicBoolean metricsFound = new AtomicBoolean(false);
        metricsSource.set("unknown");

        // Attempt Prometheus first
        try {
            MonitoringSettings settings = loadMonitoringSettings();
            if (settings.preferPrometheus) {
                MonitoringInfo prom = discoverMonitoringPrometheus();
                String promUrl = null;
                if (isOpenShift(client)) {
                    promUrl = viewContext.getAmbariProperty("openshift.thanos.url");
                    if (promUrl == null || promUrl.isBlank()) {
                        promUrl = "https://thanos-querier.openshift-monitoring.svc:9091";
                    }
                    // Use API proxy when Ambari is outside the cluster.
                    MonitoringInfo openShiftMonitoringInfo = new MonitoringInfo("openshift-monitoring", "openshift-monitoring", promUrl);
                    promUrl = resolvePrometheusReachableUrl(openShiftMonitoringInfo);
                } else if (prom != null) {
                    // Prefer a reachable URL; out-of-cluster Ambari uses the API proxy.
                    promUrl = resolvePrometheusReachableUrl(prom);
                }
                if (promUrl != null && !promUrl.isBlank()) {
                    double[] promMetrics = queryPrometheusClusterUsage(promUrl);
                    if (promMetrics != null) {
                        usedCpuTotal = promMetrics[0];
                        usedMemoryTotal = promMetrics[1];
                        metricsFound.set(true);
                        metricsSource.set(isOpenShift(client) ? "openshift-thanos" : "prometheus");
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Prometheus metrics fetch failed: {}", e.getMessage());
        }

        // Fallback to metrics-server if Prometheus was not available
        List<CompletableFuture<double[]>> usageFutures = new ArrayList<>();
        List<Node> sampledNodes = nodeList.getItems();
        if (sampledNodes.size() > MAX_METRICS_NODES) {
            sampledNodes = sampledNodes.subList(0, MAX_METRICS_NODES);
            LOG.info("Sampling first {} nodes for metrics ({} total)", MAX_METRICS_NODES, nodeList.getItems().size());
        }
        if (!metricsFound.get()) {
            for (Node node : sampledNodes) {
                String nodeName = node.getMetadata().getName();
                usageFutures.add(CompletableFuture.supplyAsync(() -> {
                    double[] usage = new double[]{0.0, 0.0}; // [cpu cores, mem GiB]
                    try {
                        var nodeMetrics = client.top().nodes().metrics(nodeName);
                        if (nodeMetrics != null && nodeMetrics.getUsage() != null) {
                            metricsFound.set(true);
                            if (nodeMetrics.getUsage().containsKey("cpu")) {
                                Quantity cpuQty = nodeMetrics.getUsage().get("cpu");
                                usage[0] = cpuQty.getNumericalAmount().doubleValue();
                            }
                            if (nodeMetrics.getUsage().containsKey("memory")) {
                                Quantity memoryQuantity = nodeMetrics.getUsage().get("memory");
                                usage[1] = memoryQuantity.getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // GiB
                            }
                        }
                    } catch (Exception ex) {
                        LOG.warn("Could not fetch usage for node {}: {}", nodeName, ex.getMessage());
                    }
                    return usage;
                }, METRICS_POOL));
            }
            for (CompletableFuture<double[]> f : usageFutures) {
                try {
                    double[] u = f.get(2, TimeUnit.SECONDS);
                    usedCpuTotal += u[0];
                    usedMemoryTotal += u[1];
                } catch (TimeoutException te) {
                    LOG.warn("Timeout fetching node metrics");
                } catch (Exception ex) {
                    LOG.warn("Could not fetch node usage: {}", ex.getMessage());
                }
            }
            if (metricsFound.get()) {
                metricsSource.set("metrics-server");
            }
        }
        
        if (!metricsFound.get()) {
            usedCpuTotal = -1;
            usedMemoryTotal = -1;
        }
        
        ClusterStats.ResourceStat cpuStatistics = new ClusterStats.ResourceStat(usedCpuTotal, totalCpuCapacity); // Usage requires metrics
        ClusterStats.ResourceStat memoryStatistics = new ClusterStats.ResourceStat(usedMemoryTotal, totalMemoryCapacity);
        ClusterStats.ResourceStat podStatistics = new ClusterStats.ResourceStat(runningPods.size(), podList.getItems().size());
        ClusterStats.ResourceStat nodeStatistics = new ClusterStats.ResourceStat(readyNodesCount, nodeList.getItems().size());
        
        // Helm stats (best-effort): list releases using the helm client. If it fails, keep zeros.
        int deployed = 0, pending = 0, failed = 0, total = 0;
        try {
            String kubeconfigContent = getConfigurationService().getKubeconfigContents();
            List<com.marcnuri.helm.Release> releases = new HelmService(this.viewContext).list(null, kubeconfigContent);
            total = releases.size();
            for (com.marcnuri.helm.Release r : releases) {
                String st = "";
                try {
                    st = String.valueOf(r.getStatus()).toLowerCase(Locale.ROOT);
                } catch (Exception e) {
                    // Expected: Release status may be null or unreadable in some edge cases
                    // Default to empty string and continue processing other releases
                    LOG.debug("Could not read status for release {}: {}", r.getName(), e.getMessage());
                }
                if (st.contains("fail")) {
                    failed++;
                } else if (st.contains("pending")) {
                    pending++;
                } else {
                    deployed++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to compute Helm stats: {}", e.toString());
        }
        ClusterStats.HelmStat helmStatistics = new ClusterStats.HelmStat(deployed, pending, failed, total);

        ClusterStats result = new ClusterStats(cpuStatistics, memoryStatistics, podStatistics, nodeStatistics, helmStatistics);
        result.setSource(metricsSource.get());
        statsCache.put("clusterStats", result);
        return result;
    }

    /**
     * List namespaces with lightweight metadata.
     */
    public List<KubeNamespace> listNamespaces() {
        checkConfiguration();
        NamespaceList list = client.namespaces().list();
        return list.getItems().stream().map(ns -> {
            KubeNamespace dto = new KubeNamespace();
            dto.name = ns.getMetadata() != null ? ns.getMetadata().getName() : null;
            dto.labels = ns.getMetadata() != null && ns.getMetadata().getLabels() != null ? ns.getMetadata().getLabels() : Collections.emptyMap();
            dto.createdAt = ns.getMetadata() != null ? ns.getMetadata().getCreationTimestamp() : null;
            dto.status = ns.getStatus() != null ? ns.getStatus().getPhase() : null;
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * List pods in a namespace with optional label selector.
     */
    public List<KubePod> listPods(String namespace, String labelSelector) {
        checkConfiguration();
        PodList list = (labelSelector == null || labelSelector.isBlank())
                ? client.pods().inNamespace(namespace).list()
                : client.pods().inNamespace(namespace).withLabelSelector(labelSelector).list();
        return list.getItems().stream().map(this::toKubePod).collect(Collectors.toList());
    }

    /**
     * List services in a namespace with optional label selector.
     */
    public List<KubeServiceDTO> listServices(String namespace, String labelSelector) {
        checkConfiguration();
        ServiceList list = (labelSelector == null || labelSelector.isBlank())
                ? client.services().inNamespace(namespace).list()
                : client.services().inNamespace(namespace).withLabelSelector(labelSelector).list();
        return list.getItems().stream().map(svc -> {
            KubeServiceDTO dto = new KubeServiceDTO();
            dto.name = svc.getMetadata() != null ? svc.getMetadata().getName() : null;
            dto.namespace = namespace;
            dto.type = svc.getSpec() != null ? svc.getSpec().getType() : null;
            dto.clusterIP = svc.getSpec() != null ? svc.getSpec().getClusterIP() : null;
            dto.labels = svc.getMetadata() != null && svc.getMetadata().getLabels() != null ? svc.getMetadata().getLabels() : Collections.emptyMap();
            Map<String, Integer> ports = new LinkedHashMap<>();
            if (svc.getSpec() != null && svc.getSpec().getPorts() != null) {
                svc.getSpec().getPorts().forEach(p -> ports.put(p.getName() != null ? p.getName() : p.getPort().toString(), p.getPort()));
            }
            dto.ports = ports;
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * Tail logs from a pod (optionally container-specific).
     */
    public String tailPodLog(String namespace, String podName, String container, int tailLines) {
        checkConfiguration();
        try {
            var podResource = client.pods().inNamespace(namespace).withName(podName);
            if (container != null && !container.isBlank()) {
                return podResource.inContainer(container).tailingLines(tailLines).getLog(true);
            }
            return podResource.tailingLines(tailLines).getLog(true);
        } catch (Exception ex) {
            LOG.warn("Failed to fetch logs for pod {} in ns {}: {}", podName, namespace, ex.toString());
            throw ex;
        }
    }

    /**
     * Delete a pod (used for restart/cleanup).
     */
    public void deletePod(String namespace, String podName, Integer graceSeconds) {
        checkConfiguration();
        client.pods()
            .inNamespace(namespace)
            .withName(podName)
            .withGracePeriod(graceSeconds != null ? graceSeconds : 0)
            .delete();
    }

    /**
     * Restart pod by deleting it and letting the controller recreate.
     */
    public void restartPod(String namespace, String podName) {
        deletePod(namespace, podName, 0);
    }

    /**
     * Describe pod (yaml string for UI).
     */
    public String describePod(String namespace, String podName) {
        checkConfiguration();
        Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return "";
        return Serialization.asYaml(pod);
    }

    /**
     * Describe service (yaml string for UI).
     */
    public String describeService(String namespace, String name) {
        checkConfiguration();
        var svc = client.services().inNamespace(namespace).withName(name).get();
        if (svc == null) return "";
        return Serialization.asYaml(svc);
    }

    /**
     * Events in a namespace (optionally filtered).
     */
    public List<KubeEventDTO> listEvents(String namespace, String labelSelector) {
        checkConfiguration();
        EventList evs = (labelSelector == null || labelSelector.isBlank())
                ? client.v1().events().inNamespace(namespace).list()
                : client.v1().events().inNamespace(namespace).withLabelSelector(labelSelector).list();
        return evs.getItems().stream().map(this::toEventDTO).collect(Collectors.toList());
    }

    /**
     * Events for a specific pod.
     */
    public List<KubeEventDTO> listPodEvents(String namespace, String podName) {
        checkConfiguration();
        EventList evs = client.v1().events()
            .inNamespace(namespace)
            .withField("involvedObject.name", podName)
            .list();
        return evs.getItems().stream().map(this::toEventDTO).collect(Collectors.toList());
    }

    private KubeEventDTO toEventDTO(Event ev) {
        KubeEventDTO dto = new KubeEventDTO();
        dto.reason = ev.getReason();
        dto.message = ev.getMessage();
        dto.type = ev.getType();
        dto.lastTimestamp = ev.getLastTimestamp();
        if (ev.getInvolvedObject() != null) {
            dto.involvedKind = ev.getInvolvedObject().getKind();
            dto.involvedName = ev.getInvolvedObject().getName();
        }
        return dto;
    }

    private KubePod toKubePod(Pod pod) {
        KubePod dto = new KubePod();
        dto.name = pod.getMetadata() != null ? pod.getMetadata().getName() : null;
        dto.namespace = pod.getMetadata() != null ? pod.getMetadata().getNamespace() : null;
        dto.podIP = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
        dto.phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
        dto.nodeName = pod.getSpec() != null ? pod.getSpec().getNodeName() : null;
        dto.labels = pod.getMetadata() != null && pod.getMetadata().getLabels() != null ? pod.getMetadata().getLabels() : Collections.emptyMap();
        dto.startTime = pod.getStatus() != null ? pod.getStatus().getStartTime() : null;
        List<KubePodContainerStatus> containers = new ArrayList<>();
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            pod.getStatus().getContainerStatuses().forEach(cs -> {
                KubePodContainerStatus st = new KubePodContainerStatus();
                st.name = cs.getName();
                st.ready = Boolean.TRUE.equals(cs.getReady());
                st.restartCount = cs.getRestartCount() != null ? cs.getRestartCount() : 0;
                if (cs.getState() != null) {
                    if (cs.getState().getRunning() != null) st.state = "Running";
                    else if (cs.getState().getWaiting() != null) st.state = "Waiting";
                    else if (cs.getState().getTerminated() != null) st.state = "Terminated";
                }
                containers.add(st);
            });
        }
        dto.containers = containers;
        return dto;
    }

    /**
     * Value class for discovered monitoring stack info.
     */
    public record MonitoringInfo(String namespace, String release, String url) {}
    public static class MonitoringSettings {
        public boolean autoBootstrap = true;
        public boolean preferPrometheus = true;
        public boolean allowMetricsServerFallback = false;
        public boolean skipOnOpenShift = false;
        public String repoId;
        public ThanosSettings thanos = new ThanosSettings();
        public boolean preferOpenShiftMonitoring = true;
        // Ingress/NodePort exposure for Prometheus/Thanos
        public String prometheusHost;
        public String prometheusIngressClass;
        public Integer prometheusNodePort;
        public String thanosHost;
        public String thanosIngressClass;
        public Integer thanosNodePort;
    }

    public static class ThanosSettings {
        public boolean enabled = false;
        public String bucket;
        public String endpoint;
        public String region;
        public String accessKey;
        public String secretKey;
        public boolean insecure = false;
    }

    private MonitoringSettings loadMonitoringSettings() {
        try {
            String json = viewContext.getInstanceData(SETTINGS_KEY);
            if (json != null && !json.isBlank()) {
                // Some clients persisted {"monitoring": {...}}; unwrap if present
                Map<String,Object> raw = objectMapper.readValue(json, Map.class);
                Object candidate = raw.getOrDefault("monitoring", raw);
                MonitoringSettings s = objectMapper.convertValue(candidate, MonitoringSettings.class);
                if (s.prometheusIngressClass == null || s.prometheusIngressClass.isBlank()) {
                    s.prometheusIngressClass = "nginx";
                }
                if (s.thanosIngressClass == null || s.thanosIngressClass.isBlank()) {
                    s.thanosIngressClass = "nginx";
                }
                return s;
            }
        } catch (Exception e) {
            LOG.warn("Failed to read monitoring settings, using defaults: {}", e.getMessage());
        }
        MonitoringSettings defaults = new MonitoringSettings();
        defaults.prometheusIngressClass = "nginx";
        defaults.thanosIngressClass = "nginx";
        defaults.allowMetricsServerFallback = false;
        return defaults;
    }

    private void updateMonitoringBootstrapState(String state, String message) {
        try {
            viewContext.putInstanceData("monitoring.bootstrap.state", state);
            if (message != null) {
                viewContext.putInstanceData("monitoring.bootstrap.message", message);
            }
        } catch (Exception e) {
            LOG.warn("Failed to persist monitoring bootstrap state {}: {}", state, e.getMessage());
        }
    }

    /**
     * Ensure a repo exists for monitoring bootstrap. Auto-creation is only triggered when explicitly enabled.
     */
    private void ensureMonitoringRepoPresent() {
        Collection<HelmRepoEntity> repos = repositoryService.list();
        if (repos != null && !repos.isEmpty()) {
            return;
        }
        boolean autoCreate = Boolean.parseBoolean(viewContext.getAmbariProperty(MONITORING_DEFAULT_REPO_AUTO_CREATE_PROP));
        if (!autoCreate) {
            LOG.debug("Monitoring repository auto-creation disabled ({} not set to true)", MONITORING_DEFAULT_REPO_AUTO_CREATE_PROP);
            return;
        }

        String repoUrl = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.url"))
                .filter(s -> !s.isBlank()).orElse(null);
        if (repoUrl == null) {
            LOG.warn("Auto-creation requested but monitoring.defaultRepo.url is missing");
            return;
        }
        String repoId = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.id"))
                .filter(s -> !s.isBlank()).orElse(MONITORING_DEFAULT_REPO_FALLBACK_ID);
        String repoName = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.name"))
                .filter(s -> !s.isBlank()).orElse(MONITORING_DEFAULT_REPO_FALLBACK_NAME);
        String repoType = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.type"))
                .filter(s -> !s.isBlank()).orElse("OCI");
        String imageProject = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.imageProject"))
                .filter(s -> !s.isBlank()).orElse(null);
        String username = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.username"))
                .filter(s -> !s.isBlank()).orElse(null);
        String password = Optional.ofNullable(viewContext.getAmbariProperty("monitoring.defaultRepo.password"))
                .filter(s -> !s.isBlank()).orElse(null);

        if (repoType == null || repoType.isBlank()) {
            repoType = "OCI";
        }

        try {
            HelmRepoEntity entity = new HelmRepoEntity();
            entity.setId(repoId);
            entity.setName(repoName);
            entity.setType(repoType);
            entity.setUrl(repoUrl);
            if (imageProject != null) {
                entity.setImageProject(imageProject);
            }
            entity.setAuthMode(username != null ? "basic" : "anonymous");
            entity.setUsername(username);
            if (username != null) {
                LOG.info("Monitoring repo auto-create will use basic auth for user {}", username);
            }
            repositoryService.save(entity, password);
            LOG.info("Created monitoring repository id={} url={}", repoId, repoUrl);
        } catch (Exception e) {
            LOG.warn("Failed to create monitoring repository: {}", e.getMessage());
        }
    }

    /**
     * Schedule monitoring bootstrap asynchronously (run once per view instance).
     */
    private void scheduleMonitoringBootstrapAsync() {
        if (!isConfigured) return;
        if (!monitoringBootstrapScheduled.compareAndSet(false, true)) {
            return;
        }
        BOOTSTRAP_POOL.submit(() -> {
            updateMonitoringBootstrapState("RUNNING", "Starting monitoring bootstrap");
            try {
                ensureMonitoringRepoPresent();
                MonitoringInfo info = ensureMonitoringInstalled(null);
                if (info != null) {
                    updateMonitoringBootstrapState("COMPLETED",
                            "Monitoring present: " + info.release() + " in " + info.namespace());
                    LOG.info("Monitoring bootstrap completed: release={} namespace={}", info.release(), info.namespace());
                } else {
                    updateMonitoringBootstrapState("FAILED", "Monitoring stack not found and bootstrap skipped/failed");
                }
            } catch (Exception ex) {
                LOG.warn("Monitoring bootstrap failed: {}", ex.toString());
                updateMonitoringBootstrapState("FAILED", ex.getMessage());
            }
        });
    }

    /**
     * Discover monitoring stack (kube-prometheus-stack) by checking for a Prometheus service in the configured namespace.
     * Returns namespace, release, and URL (ClusterIP service) if found.
     */
    public MonitoringInfo discoverMonitoringPrometheus() {
        checkConfiguration();
        String ns = viewContext.getAmbariProperty("monitoring.namespace");
        if (ns == null || ns.isBlank()) ns = DEFAULT_MONITORING_NAMESPACE;
        String release = viewContext.getAmbariProperty("monitoring.release");
        if (release == null || release.isBlank()) release = DEFAULT_MONITORING_RELEASE;

        // Explicit override: use a URL provided in Ambari properties (useful when Prometheus is exposed via ingress/NodePort)
        String overrideUrl = viewContext.getAmbariProperty("monitoring.prometheus.url");
        if (overrideUrl != null && !overrideUrl.isBlank()) {
            LOG.info("Monitoring discovery: using overridden Prometheus URL {}", overrideUrl);
            return new MonitoringInfo(ns, release, overrideUrl.trim());
        }
        // Persisted override set by the view itself (e.g., after bootstrap enabling ingress/NodePort)
        try {
            String persistedUrl = viewContext.getInstanceData("monitoring.prometheus.url");
            if (persistedUrl != null && !persistedUrl.isBlank()) {
                LOG.info("Monitoring discovery: using persisted Prometheus URL {}", persistedUrl);
                return new MonitoringInfo(ns, release, persistedUrl.trim());
            }
        } catch (Exception e) {
            LOG.warn("Monitoring discovery: failed to read persisted Prometheus URL: {}", e.getMessage());
        }

        String svcName = release + "-prometheus";
        try {
            var svc = client.services().inNamespace(ns).withName(svcName).get();
            if (svc == null || svc.getSpec() == null) {
                LOG.info("Monitoring discovery: service {} not found in namespace {}", svcName, ns);
                return null;
            }
            Integer port = svc.getSpec().getPorts() != null && !svc.getSpec().getPorts().isEmpty()
                    ? svc.getSpec().getPorts().get(0).getPort()
                    : 9090;
            String url = "http://" + svcName + "." + ns + ".svc:" + port;
            LOG.info("Monitoring discovery: found {} in namespace {} (url={})", svcName, ns, url);
            return new MonitoringInfo(ns, release, url);
        } catch (Exception e) {
            LOG.warn("Monitoring discovery failed: {}", e.getMessage());
            return null;
        }
    }

    private MonitoringInfo discoverOpenShiftMonitoring() {
        if (!isOpenShift(client)) return null;
        String url = viewContext.getAmbariProperty("openshift.thanos.url");
        if (url == null || url.isBlank()) url = OPENSHIFT_THANOS_URL_DEFAULT;
        return new MonitoringInfo("openshift-monitoring", "openshift-monitoring", url);
    }

    /**
     * Ensure monitoring stack is installed; if not present, install using configured repo/chart.
     * @param repoIdOverride optional repoId to use for installation
     * @return discovered info after ensuring install.
     */
    public MonitoringInfo ensureMonitoringInstalled(String repoIdOverride) {
        MonitoringSettings settings = loadMonitoringSettings();
        MonitoringInfo info = discoverMonitoringPrometheus();
        if (info != null) {
            LOG.info("Monitoring stack already present: release={}, namespace={}, url={}", info.release(), info.namespace(), info.url());
            return info;
        }

        String enabledProp = viewContext.getAmbariProperty("monitoring.enabled");
        boolean enabled = enabledProp == null || Boolean.parseBoolean(enabledProp);
        if (!enabled) {
            LOG.info("Monitoring bootstrap disabled via monitoring.enabled=false");
            return null;
        }
        if (!settings.autoBootstrap) {
            LOG.info("Monitoring bootstrap skipped because autoBootstrap=false");
            updateMonitoringBootstrapState("SKIPPED", "Auto-bootstrap disabled");
            return null;
        }
        if (settings.preferOpenShiftMonitoring && isOpenShift(client)) {
            LOG.info("OpenShift detected and preferOpenShiftMonitoring=true -> will not install private kube-prometheus-stack.");
            MonitoringInfo osInfo = discoverOpenShiftMonitoring();
            if (osInfo != null) {
                return osInfo;
            }
        }

        String ns = viewContext.getAmbariProperty("monitoring.namespace");
        if (ns == null || ns.isBlank()) ns = DEFAULT_MONITORING_NAMESPACE;
        String release = viewContext.getAmbariProperty("monitoring.release");
        if (release == null || release.isBlank()) release = DEFAULT_MONITORING_RELEASE;
        String repoId = repoIdOverride;
        if (repoId == null || repoId.isBlank()) {
            // Prefer persisted user choice if available
            try {
                String persistedRepoId = viewContext.getInstanceData("monitoring.repoId");
                if (persistedRepoId != null && !persistedRepoId.isBlank()) {
                    repoId = persistedRepoId;
                }
            } catch (Exception ex) {
                LOG.warn("Could not read persisted monitoring repoId: {}", ex.toString());
            }
        }
        if ((repoId == null || repoId.isBlank()) && settings.repoId != null && !settings.repoId.isBlank()) {
            repoId = settings.repoId;
        }
        if (repoId == null || repoId.isBlank()) {
            repoId = viewContext.getAmbariProperty("monitoring.repoId");
        }
        String chart = viewContext.getAmbariProperty("monitoring.chart");
        if (chart == null || chart.isBlank()) chart = DEFAULT_MONITORING_CHART;
        String version = viewContext.getAmbariProperty("monitoring.version");
        LOG.info("Monitoring bootstrap: resolved settings ns={}, release={}, repoId={}, chart={}, version={}, host={}, ingressClass={}, nodePort={}",
                ns, release, repoId, chart, version,
                settings.prometheusHost, settings.prometheusIngressClass, settings.prometheusNodePort);

        // If a prior run cached a URL but the release/service is gone, clear cached data to allow re-bootstrap
        try {
            String promServiceName = release + "-prometheus";
            var promSvc = client.services().inNamespace(ns).withName(promServiceName).get();
            if (promSvc == null) {
                LOG.info("Monitoring service {} not found in namespace {}. Clearing cached monitoring instance data to allow re-bootstrap.", promServiceName, ns);
                viewContext.removeInstanceData("monitoring.prometheus.url");
                viewContext.removeInstanceData("monitoring.repoId");
            }
        } catch (Exception ex) {
            LOG.warn("Failed to verify monitoring service existence, proceeding anyway: {}", ex.toString());
        }

        if (repoId == null || repoId.isBlank()) {
            // best-effort: use first available repo
            repoId = repositoryService.firstRepoIdOrNull();
            if (repoId == null) {
                LOG.warn("Cannot bootstrap monitoring: monitoring.repoId is not set and no repos are configured.");
                return null;
            }
        }

        MonitoringExposure promExposure = null;
        try {
            if (isOpenShift(client)) {
                String skipOnOcpProp = viewContext.getAmbariProperty("monitoring.skipOnOpenShift");
                if (skipOnOcpProp != null && Boolean.parseBoolean(skipOnOcpProp)) {
                    LOG.info("OpenShift detected; monitoring bootstrap skipped due to monitoring.skipOnOpenShift=true");
                    updateMonitoringBootstrapState("SKIPPED", "Skipped on OpenShift (using built-in monitoring)");
                    return discoverMonitoringPrometheus();
                }
            }

            LOG.info("Bootstrapping monitoring stack: release={}, namespace={}, repoId={}, chart={}, version={}",
                    release, ns, repoId, chart, version);
            // Minimal overrides
            Map<String, Object> overrides = new HashMap<>();
            // Enable node-exporter and kube-state-metrics to get node/pod metrics
            overrides.put("nodeExporter.enabled", true);
            overrides.put("nodeExporter.hostNetwork", true);
            overrides.put("prometheus-node-exporter.serviceMonitor.enabled", true);
            overrides.put("kube-state-metrics.enabled", true);
            overrides.put("kube-state-metrics.serviceMonitor.enabled", true);
            overrides.put("kubelet.serviceMonitor.enabled", true);
            overrides.put("kubelet.serviceMonitor.https", true);
            overrides.put("kubelet.serviceMonitor.cAdvisor", true);
            overrides.put("kubelet.serviceMonitor.resource", "https");
            overrides.put("kubelet.enabled", true);
            overrides.put("kubelet.serviceMonitor.interval", "30s");

            // Expose Prometheus externally (ingress or NodePort) so Ambari can query it directly.
            promExposure = buildPrometheusExposureOverrides(settings, ns, overrides);
            if (settings.thanos != null && settings.thanos.enabled) {
                overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.type", "S3");
                if (settings.thanos.bucket != null) {
                    overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.bucket", settings.thanos.bucket);
                }
                if (settings.thanos.endpoint != null) {
                    overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.endpoint", settings.thanos.endpoint);
                }
                if (settings.thanos.region != null) {
                    overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.region", settings.thanos.region);
                }
                if (settings.thanos.accessKey != null) {
                    overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.access_key", settings.thanos.accessKey);
                }
                if (settings.thanos.secretKey != null) {
                    overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.secret_key", settings.thanos.secretKey);
                }
                overrides.put("prometheus.prometheusSpec.thanos.objectStorageConfig.secret.config.insecure", settings.thanos.insecure);
                applyThanosExposureOverrides(settings, ns, overrides);
            }
            // Ensure image pull secret for private registries and inject into values
            try {
                String pullSecretName = "regcred";
                this.helmService.ensureImagePullSecretFromRepo(repoId, ns, pullSecretName, null);
                List<Map<String, String>> imagePullSecretRefs = List.of(Map.of("name", pullSecretName));
                overrides.put("imagePullSecrets", imagePullSecretRefs);
                Map<String, Object> global = (Map<String, Object>) overrides.computeIfAbsent("global", k -> new LinkedHashMap<String, Object>());
                global.put("imagePullSecrets", imagePullSecretRefs);
                // Some subcharts do not honor global.imagePullSecrets; set explicit paths to avoid image pull failures.
                List<String> imagePullSecretOverrideKeys = List.of(
                        "kube-state-metrics.imagePullSecrets",
                        "kube-state-metrics.serviceAccount.imagePullSecrets",
                        "prometheus-node-exporter.imagePullSecrets",
                        "prometheus-node-exporter.serviceAccount.imagePullSecrets",
                        "prometheus-windows-exporter.imagePullSecrets",
                        "prometheus-windows-exporter.serviceAccount.imagePullSecrets",
                        "grafana.imagePullSecrets"
                );
                for (String overrideKey : imagePullSecretOverrideKeys) {
                    overrides.put(overrideKey, imagePullSecretRefs);
                }
                LOG.info("Injected imagePullSecrets override for monitoring bootstrap repoId={} secret={} keys={}",
                        repoId, pullSecretName, imagePullSecretOverrideKeys);
            } catch (Exception ex) {
                LOG.warn("Failed to ensure image pull secret for monitoring repoId {}: {}", repoId, ex.getMessage());
            }
            // Keep service account defaults; users can override via view properties later.
            // Install without waiting to avoid long blocks
            LOG.info("Installing/upgrading monitoring stack via Helm... and using overrides: {}", overrides.keySet());
            helmService.deployOrUpgrade(
                    chart,
                    release,
                    ns,
                    overrides,
                    Collections.emptyMap(),
                    configurationService.getKubeconfigContents(),
                    repoId,
                    version,
                    600,
                    true,
                    false,
                    false
            );
        } catch (Exception e) {
            LOG.warn("Failed to bootstrap monitoring stack: {}", e.toString());
            return null;
        }
        try {
            viewContext.putInstanceData("monitoring.repoId", repoId);
            if (promExposure != null && promExposure.url() != null && !promExposure.url().isBlank()) {
                viewContext.putInstanceData("monitoring.prometheus.url", promExposure.url());
                LOG.info("Persisted Prometheus URL override for future discovery: {}", promExposure.url());
            }
        } catch (Exception ex) {
            LOG.warn("Failed to persist monitoring.repoId: {}", ex.toString());
        }
        MonitoringInfo discovered = discoverMonitoringPrometheus();
        if (discovered != null) {
            updateMonitoringBootstrapState("COMPLETED",
                    "Monitoring present: " + discovered.release() + " in " + discovered.namespace());
        } else {
            updateMonitoringBootstrapState("FAILED", "Monitoring stack not found after install attempt");
        }
        return discovered;
    }

    /**
     * Container for the Prometheus exposure decision (ingress or NodePort) and the resulting URL.
     */
    private record MonitoringExposure(String url) { }

    /**
     * Parsed service endpoint information for an in-cluster Prometheus service.
     */
    private record PrometheusServiceEndpoint(String namespace, String serviceName, int servicePort) { }

    /**
     * Resolve a Prometheus URL that is reachable from this Ambari process.
     * When Ambari is running outside the cluster and the URL points to a *.svc
     * service, the Kubernetes API proxy is used.
     *
     * @param monitoringInfo discovered monitoring info (namespace/release/url)
     * @return a reachable Prometheus URL or {@code null} if no URL is available
     */
    private String resolvePrometheusReachableUrl(MonitoringInfo monitoringInfo) {
        if (monitoringInfo == null || monitoringInfo.url() == null || monitoringInfo.url().isBlank()) {
            return null;
        }
        String prometheusUrl = monitoringInfo.url().trim();
        if (isRunningInsideKubernetes()) {
            LOG.info("Prometheus URL will use in-cluster endpoint {}", prometheusUrl);
            return prometheusUrl;
        }
        PrometheusServiceEndpoint prometheusServiceEndpoint = extractPrometheusServiceEndpoint(prometheusUrl);
        if (prometheusServiceEndpoint == null) {
            LOG.info("Prometheus URL is external; using {}", prometheusUrl);
            return prometheusUrl;
        }
        String proxyUrl = buildPrometheusServiceProxyUrl(prometheusServiceEndpoint);
        if (proxyUrl == null) {
            LOG.warn("Could not build Kubernetes API proxy URL for Prometheus; falling back to {}", prometheusUrl);
            return prometheusUrl;
        }
        logPrometheusProxyUrlOnce(proxyUrl, prometheusUrl);
        return proxyUrl;
    }

    /**
     * Detect whether Ambari is running inside a Kubernetes pod by checking
     * standard in-cluster environment variables and the service account token.
     *
     * @return {@code true} if Ambari is running inside Kubernetes
     */
    private boolean isRunningInsideKubernetes() {
        Boolean cachedResult = runningInsideKubernetesCache.get();
        if (cachedResult != null) {
            return cachedResult;
        }
        String serviceHost = System.getenv(KUBERNETES_SERVICE_HOST_ENV);
        String servicePort = System.getenv(KUBERNETES_SERVICE_PORT_ENV);
        boolean hasServiceEnvironment = serviceHost != null && !serviceHost.isBlank()
                && servicePort != null && !servicePort.isBlank();
        boolean hasServiceAccountToken = Files.isReadable(KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH);
        boolean runningInsideKubernetes = hasServiceEnvironment && hasServiceAccountToken;
        runningInsideKubernetesCache.compareAndSet(null, runningInsideKubernetes);
        LOG.info("In-cluster detection: envPresent={} tokenPresent={} runningInsideKubernetes={}",
                hasServiceEnvironment, hasServiceAccountToken, runningInsideKubernetes);
        return runningInsideKubernetes;
    }

    /**
     * Parse a Prometheus URL that targets a Kubernetes service (e.g., http://svc.ns.svc:9090).
     *
     * @param prometheusUrl candidate URL to parse
     * @return service endpoint metadata or {@code null} if not a cluster service URL
     */
    private PrometheusServiceEndpoint extractPrometheusServiceEndpoint(String prometheusUrl) {
        if (prometheusUrl == null || prometheusUrl.isBlank()) {
            return null;
        }
        try {
            URI parsedUri = URI.create(prometheusUrl);
            String host = parsedUri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            String[] hostSegments = host.split("\\.");
            if (hostSegments.length < 3 || !"svc".equals(hostSegments[2])) {
                return null;
            }
            String serviceName = hostSegments[0];
            String namespace = hostSegments[1];
            int servicePort = parsedUri.getPort() > 0 ? parsedUri.getPort() : 9090;
            return new PrometheusServiceEndpoint(namespace, serviceName, servicePort);
        } catch (Exception parseException) {
            LOG.warn("Unable to parse Prometheus URL for service proxying ({}): {}", prometheusUrl, parseException.getMessage());
            return null;
        }
    }

    /**
     * Build a Kubernetes API proxy URL for a Prometheus service.
     *
     * @param prometheusServiceEndpoint service endpoint metadata
     * @return API proxy base URL or {@code null} if the API server URL is unavailable
     */
    private String buildPrometheusServiceProxyUrl(PrometheusServiceEndpoint prometheusServiceEndpoint) {
        if (prometheusServiceEndpoint == null || client == null) {
            return null;
        }
        String apiServerUrl = client.getConfiguration().getMasterUrl();
        if (apiServerUrl == null || apiServerUrl.isBlank()) {
            LOG.warn("Kubernetes API server URL is not configured; cannot proxy Prometheus");
            return null;
        }
        String normalizedApiServerUrl = apiServerUrl.endsWith("/")
                ? apiServerUrl.substring(0, apiServerUrl.length() - 1)
                : apiServerUrl;
        String proxyPath = String.format(
                "/api/v1/namespaces/%s/services/http:%s:%d/proxy",
                prometheusServiceEndpoint.namespace(),
                prometheusServiceEndpoint.serviceName(),
                prometheusServiceEndpoint.servicePort()
        );
        return normalizedApiServerUrl + proxyPath;
    }

    /**
     * Log the Prometheus API proxy URL only once per unique URL to avoid log spam.
     *
     * @param proxyUrl resolved Kubernetes API proxy URL
     * @param originalUrl original in-cluster Prometheus URL
     */
    private void logPrometheusProxyUrlOnce(String proxyUrl, String originalUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return;
        }
        String previouslyLoggedProxyUrl = lastPrometheusProxyUrlLogged.get();
        if (proxyUrl.equals(previouslyLoggedProxyUrl)) {
            return;
        }
        if (lastPrometheusProxyUrlLogged.compareAndSet(previouslyLoggedProxyUrl, proxyUrl)) {
            LOG.info("Prometheus URL '{}' will be accessed via Kubernetes API proxy '{}'", originalUrl, proxyUrl);
        }
    }

    /**
     * Compute and apply ingress/NodePort overrides for Prometheus to ensure Ambari can reach it from outside the cluster.
     * @param settings monitoring settings (may carry ingress/nodePort hints)
     * @param namespace target namespace for monitoring
     * @param overrides helm override map to mutate
     * @return resolved exposure info (may be null if no usable URL could be built)
     */
    private MonitoringExposure buildPrometheusExposureOverrides(MonitoringSettings settings, String namespace, Map<String, Object> overrides) {
        String desiredHost = settings.prometheusHost != null ? settings.prometheusHost : viewContext.getAmbariProperty("monitoring.prometheus.host");
        String ingressClassName = settings.prometheusIngressClass != null ? settings.prometheusIngressClass : viewContext.getAmbariProperty("monitoring.ingress.class");
        String desiredNodePortString = settings.prometheusNodePort != null ? String.valueOf(settings.prometheusNodePort) : viewContext.getAmbariProperty("monitoring.prometheus.nodePort");
        Integer desiredNodePort = null;
        LOG.info("Prometheus exposure config: host='{}', ingressClass='{}', nodePort='{}'",
                desiredHost, ingressClassName, desiredNodePortString);
        if (desiredNodePortString != null && !desiredNodePortString.isBlank()) {
            try {
                desiredNodePort = Integer.parseInt(desiredNodePortString.trim());
            } catch (NumberFormatException nfe) {
                LOG.warn("Ignoring monitoring.prometheus.nodePort (not an integer): {}", desiredNodePortString);
            }
        }

        String effectiveIngressClass = (ingressClassName != null && !ingressClassName.isBlank()) ? ingressClassName : "nginx";

        if (desiredHost != null && !desiredHost.isBlank()) {
            LOG.info("Configuring Prometheus ingress for host {}", desiredHost);
            overrides.put("prometheus.ingress.enabled", true);
            overrides.put("prometheus.ingress.ingressClassName", effectiveIngressClass);
            overrides.put("prometheus.ingress.hosts", List.of(desiredHost));
            overrides.put("prometheus.ingress.path", "/");
            overrides.put("prometheus.ingress.pathType", "Prefix");

            String externalUrl = desiredHost.startsWith("http") ? desiredHost : "http://" + desiredHost;
            overrides.put("prometheus.prometheusSpec.externalUrl", externalUrl);
            return new MonitoringExposure(externalUrl);
        }

        // Fallback: NodePort exposure. This is best-effort; requires a reachable node IP from Ambari.
        LOG.info("Configuring Prometheus service as NodePort (no ingress host configured)");
        overrides.put("prometheus.service.type", "NodePort");
        overrides.put("prometheus.service.port", 9090);
        if (desiredNodePort != null) {
            overrides.put("prometheus.service.nodePort", desiredNodePort);
        }
        String nodeIp = findReachableNodeIp();
        if (nodeIp != null && desiredNodePort != null) {
            String externalUrl = "http://" + nodeIp + ":" + desiredNodePort;
            overrides.put("prometheus.prometheusSpec.externalUrl", externalUrl);
            LOG.info("Prometheus NodePort exposure will use {}", externalUrl);
            return new MonitoringExposure(externalUrl);
        }
        // As a last resort, still create an ingress with the default class and no host to give operators something to bind later.
        LOG.info("Enabling Prometheus ingress with default class {} (no host provided)", effectiveIngressClass);
        overrides.put("prometheus.ingress.enabled", true);
        overrides.put("prometheus.ingress.ingressClassName", effectiveIngressClass);
        overrides.put("prometheus.ingress.hosts", List.of(""));
        overrides.put("prometheus.ingress.path", "/");
        overrides.put("prometheus.ingress.pathType", "Prefix");
        LOG.warn("Could not compute Prometheus NodePort URL (missing node IP or nodePort); ingress created with catch-all rule.");
        return null;
    }

    /**
     * Apply Thanos exposure (ingress or NodePort) when Thanos is enabled so external tools (and future view features)
     * can reach the Thanos query API.
     * @param namespace target namespace for monitoring
     * @param overrides helm override map to mutate
     */
    private void applyThanosExposureOverrides(MonitoringSettings settings, String namespace, Map<String, Object> overrides) {
        String desiredHost = settings.thanosHost != null ? settings.thanosHost : viewContext.getAmbariProperty("monitoring.thanos.host");
        String ingressClassName = settings.thanosIngressClass != null ? settings.thanosIngressClass : viewContext.getAmbariProperty("monitoring.thanos.ingress.class");
        String desiredNodePortString = settings.thanosNodePort != null ? String.valueOf(settings.thanosNodePort) : viewContext.getAmbariProperty("monitoring.thanos.nodePort");
        Integer desiredNodePort = null;
        if (desiredNodePortString != null && !desiredNodePortString.isBlank()) {
            try {
                desiredNodePort = Integer.parseInt(desiredNodePortString.trim());
            } catch (NumberFormatException nfe) {
                LOG.warn("Ignoring monitoring.thanos.nodePort (not an integer): {}", desiredNodePortString);
            }
        }

        // Always ensure the Thanos discovery service exists when Thanos is enabled.
        overrides.put("prometheus.thanosService.enabled", true);

        String effectiveIngressClass = (ingressClassName != null && !ingressClassName.isBlank()) ? ingressClassName : "nginx";

        if (desiredHost != null && !desiredHost.isBlank()) {
            LOG.info("Configuring Thanos sidecar ingress for host {}", desiredHost);
            overrides.put("prometheus.thanosIngress.enabled", true);
            overrides.put("prometheus.thanosIngress.ingressClassName", effectiveIngressClass);
            overrides.put("prometheus.thanosIngress.hosts", List.of(desiredHost));
            overrides.put("prometheus.thanosIngress.paths", List.of("/"));
            overrides.put("prometheus.thanosIngress.pathType", "Prefix");
        } else {
            LOG.info("Configuring Thanos sidecar service as NodePort (no ingress host configured)");
            overrides.put("prometheus.thanosService.type", "NodePort");
            overrides.put("prometheus.thanosIngress.enabled", false);
            if (desiredNodePort != null) {
                overrides.put("prometheus.thanosService.nodePort", desiredNodePort);
            }
            // If no nodePort either, still expose an ingress with default class so operators can bind hosts later.
            if (desiredNodePort == null) {
                overrides.put("prometheus.thanosIngress.enabled", true);
                overrides.put("prometheus.thanosIngress.ingressClassName", effectiveIngressClass);
                overrides.put("prometheus.thanosIngress.paths", List.of("/"));
                overrides.put("prometheus.thanosIngress.pathType", "Prefix");
            }
        }
    }

    /**
     * Clears cached monitoring instance data so that a subsequent bootstrap is forced.
     */
    public void resetMonitoringCache() {
        try {
            viewContext.removeInstanceData("monitoring.prometheus.url");
            viewContext.removeInstanceData("monitoring.repoId");
            updateMonitoringBootstrapState("RESET", "Monitoring cache cleared; next install will bootstrap");
            LOG.info("Monitoring cache cleared (prometheus.url and repoId).");
        } catch (Exception ex) {
            LOG.warn("Failed to clear monitoring cache: {}", ex.toString());
            throw ex;
        }
    }

    /**
     * Find a node InternalIP that is most likely reachable from the Ambari host to build NodePort URLs.
     * @return node IP as string or null if none found
     */
    private String findReachableNodeIp() {
        try {
            List<Node> nodes = client.nodes().list().getItems();
            if (nodes == null || nodes.isEmpty()) {
                LOG.warn("No Kubernetes nodes found while looking for a NodePort endpoint");
                return null;
            }
            for (Node node : nodes) {
                if (node.getStatus() == null || node.getStatus().getAddresses() == null) {
                    continue;
                }
                for (NodeAddress address : node.getStatus().getAddresses()) {
                    if ("InternalIP".equalsIgnoreCase(address.getType()) || "ExternalIP".equalsIgnoreCase(address.getType())) {
                        return address.getAddress();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to select a node IP for NodePort exposure: {}", e.getMessage());
        }
        return null;
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
        
        return new ClusterNode(node.getMetadata().getUid(), node.getMetadata().getName(), nodeStatus, nodeRoles, 0.0, 0.0);
    }
    
    /**
     * Return node metrics for the provided page nodes, preferring Prometheus and falling back to metrics-server.
     */
    private Map<String, ClusterMetrics> fetchNodeMetrics(List<Node> nodes, Map<String, NodeCapacity> capacities) {
        MonitoringSettings monitoringSettings = loadMonitoringSettings();
        String promUrl = resolvePrometheusUrl();
        if (promUrl != null) {
            LOG.info("Collecting node metrics from Prometheus at {}", promUrl);
            Map<String, ClusterMetrics> prom = queryPrometheusNodeUsage(promUrl, nodes, capacities);
            if (!prom.isEmpty()) {
                metricsSource.set("prometheus");
                return prom;
            }
            LOG.warn("Prometheus node metrics query returned no data{}", monitoringSettings.allowMetricsServerFallback ? ", falling back to metrics-server" : " and metrics-server fallback disabled");
        }
        if (!monitoringSettings.allowMetricsServerFallback) {
            LOG.info("Metrics-server fallback is disabled; returning empty node metrics.");
            return Collections.emptyMap();
        }
        LOG.info("Collecting node metrics from metrics-server for {} nodes", nodes.size());
        Map<String, ClusterMetrics> metricsServer = loadMetricsServerMetrics(nodes, capacities);
        if (!metricsServer.isEmpty()) {
            metricsSource.set("metrics-server");
        }
        return metricsServer;
    }

    /**
     * Fall back to metrics-server when Prometheus is unavailable.
     */
    private Map<String, ClusterMetrics> loadMetricsServerMetrics(List<Node> nodes, Map<String, NodeCapacity> capacities) {
        Map<String, ClusterMetrics> result = new LinkedHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Node node : nodes) {
            String nodeName = node.getMetadata().getName();
            if (nodeName == null || nodeName.isBlank()) continue;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    var nodeMetrics = client.top().nodes().metrics(nodeName);
                    if (nodeMetrics == null || nodeMetrics.getUsage() == null) return;
                    double cpuUsage = quantityToDouble(nodeMetrics.getUsage().get("cpu"));
                    double memoryUsageBytes = quantityToDouble(nodeMetrics.getUsage().get("memory"));
                    NodeCapacity cap = capacities.get(nodeName);
                    if (cap == null) return;
                    double cpuPercent = cap.cpuCores > 0 ? Math.min(1.0, cpuUsage / cap.cpuCores) : 0.0;
                    double memUsedGiB = memoryUsageBytes / (1024 * 1024 * 1024);
                    double memoryPercent = cap.memoryGiB > 0 ? Math.min(1.0, memUsedGiB / cap.memoryGiB) : 0.0;
                    synchronized (result) {
                        result.put(nodeName, new ClusterMetrics(cpuPercent, memoryPercent));
                    }
                } catch (Exception ex) {
                    LOG.warn("Unable to fetch metrics for node {}: {}", nodeName, ex.getMessage());
                }
            }, METRICS_POOL));
        }
        for (CompletableFuture<Void> future : futures) {
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignore) {
                // best effort
            }
        }
        LOG.info("Loaded {} node metrics via metrics-server", result.size());
        return result;
    }

    private double capacityQuantity(Node node, String key) {
        if (node.getStatus() == null || node.getStatus().getCapacity() == null) {
            return 0;
        }
        Quantity quantity = node.getStatus().getCapacity().get(key);
        return quantityToDouble(quantity);
    }

    private double quantityToDouble(Quantity q) {
        if (q == null) {
            return 0.0;
        }
        try {
            return q.getNumericalAmount().doubleValue();
        } catch (Exception e) {
            LOG.debug("Failed to convert Quantity {}: {}", q, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Simple holder for node-level CPU/memory usage percentages.
     */
    private static final class ClusterMetrics {
        final double cpuUsagePercent;
        final double memoryUsagePercent;

        ClusterMetrics(double cpuUsagePercent, double memoryUsagePercent) {
            this.cpuUsagePercent = cpuUsagePercent;
            this.memoryUsagePercent = memoryUsagePercent;
        }
    }

    /**
     * Captures node capacity values needed to compute utilization percentages.
     */
    private static final class NodeCapacity {
        final double cpuCores;
        final double memoryGiB;

        NodeCapacity(double cpuCores, double memoryGiB) {
            this.cpuCores = cpuCores;
            this.memoryGiB = memoryGiB;
        }
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
        try {
            return client.isAdaptable(OpenShiftClient.class);
        } catch (Exception e) {
            LOG.warn("OpenShift detection failed (likely vanilla k8s): {}", e.toString());
            return false;
        }
    }

    /**
     * Query Prometheus (or Thanos) for cluster CPU/memory usage.
     * @return double[]{cpuCoresUsed, memGiBUsed} or null on failure.
     */
    private double[] queryPrometheusClusterUsage(String promUrl) {
        try {
            HttpClient prometheusHttpClient = buildPrometheusHttpClient(promUrl);
            String cpuQuery = java.net.URLEncoder.encode("sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[2m]))", StandardCharsets.UTF_8);
            String memQuery = java.net.URLEncoder.encode("sum(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes)", StandardCharsets.UTF_8);
            String cpuUrl = promUrl + "/api/v1/query?query=" + cpuQuery;
            String memUrl = promUrl + "/api/v1/query?query=" + memQuery;

            HttpRequest.Builder cpuRequestBuilder = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(cpuUrl))
                    .timeout(Duration.ofSeconds(8));
            HttpRequest.Builder memoryRequestBuilder = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(memUrl))
                    .timeout(Duration.ofSeconds(8));
            // Add auth headers if kubeconfig provides a token.
            applyPrometheusAuth(cpuRequestBuilder);
            applyPrometheusAuth(memoryRequestBuilder);
            HttpResponse<String> cpuResponse = prometheusHttpClient.send(cpuRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> memoryResponse = prometheusHttpClient.send(memoryRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (cpuResponse.statusCode() >= 300 || memoryResponse.statusCode() >= 300) {
                LOG.warn("Prometheus query failed (codes {} / {}), url {} body(cpu)={} body(mem)={}",
                        cpuResponse.statusCode(), memoryResponse.statusCode(), promUrl, cpuResponse.body(), memoryResponse.body());
                return null;
            }
            double cpuUsed = parsePrometheusSingleValue(cpuResponse.body());
            double memUsedBytes = parsePrometheusSingleValue(memoryResponse.body());
            if (Double.isNaN(cpuUsed) || Double.isNaN(memUsedBytes)) return null;
            double memUsedGiB = memUsedBytes / (1024 * 1024 * 1024.0);
            return new double[]{cpuUsed, memUsedGiB};
        } catch (Exception e) {
            LOG.warn("Prometheus usage query failed for {}: {}", promUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resolve the monitoring Prometheus/Thanos URL used by the view while honoring the
     * {@link MonitoringSettings#preferPrometheus} flag and handling OpenShift defaults.
     *
     * @return the resolved URL string or {@code null} if Prometheus should be skipped or cannot be determined
     */
    private String resolvePrometheusUrl() {
        try {
            MonitoringSettings settings = loadMonitoringSettings();
            if (!settings.preferPrometheus) {
                LOG.info("Prometheus usage disabled in monitoring settings, skipping Prometheus sampling");
                return null;
            }
            String promUrl;
            if (isOpenShift(client)) {
                promUrl = viewContext.getAmbariProperty("openshift.thanos.url");
                if (promUrl == null || promUrl.isBlank()) {
                    promUrl = OPENSHIFT_THANOS_URL_DEFAULT;
                }
                // Prefer a reachable URL; out-of-cluster Ambari uses the API proxy.
                MonitoringInfo openShiftMonitoringInfo = new MonitoringInfo("openshift-monitoring", "openshift-monitoring", promUrl);
                promUrl = resolvePrometheusReachableUrl(openShiftMonitoringInfo);
            } else {
                MonitoringInfo info = discoverMonitoringPrometheus();
                promUrl = resolvePrometheusReachableUrl(info);
            }
            return (promUrl != null && !promUrl.isBlank()) ? promUrl : null;
        } catch (Exception e) {
            LOG.warn("Could not resolve Prometheus URL for node metrics: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Query Prometheus for per-node CPU/memory usage and convert to percentages based on node capacities.
     */
    private Map<String, ClusterMetrics> queryPrometheusNodeUsage(String promUrl, List<Node> nodes, Map<String, NodeCapacity> capacities) {
        if (promUrl == null || promUrl.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Double> cpuVector = queryPrometheusVector(promUrl, "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[2m])) by (instance)");
        Map<String, Double> memVector = queryPrometheusVector(promUrl, "sum(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) by (instance)");
        if (cpuVector.isEmpty() && memVector.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> metricKeys = buildInstanceKeys(nodes);
        Map<String, ClusterMetrics> result = new LinkedHashMap<>();
        for (Node node : nodes) {
            String nodeName = node.getMetadata().getName();
            if (nodeName == null || nodeName.isBlank()) continue;
            NodeCapacity cap = capacities.get(nodeName);
            if (cap == null) continue;
            List<String> keys = metricKeys.getOrDefault(nodeName, Collections.singletonList(nodeName));
            double cpuValue = resolveMetricForNode(cpuVector, keys);
            double memValue = resolveMetricForNode(memVector, keys);
            if (Double.isNaN(cpuValue) && Double.isNaN(memValue)) continue;
            double cpuPercent = cap.cpuCores > 0 && !Double.isNaN(cpuValue) ? Math.min(1.0, cpuValue / cap.cpuCores) : 0.0;
            double memoryGiB = cap.memoryGiB;
            double memoryPercent = memoryGiB > 0 && !Double.isNaN(memValue) ? Math.min(1.0, (memValue / (1024 * 1024 * 1024)) / memoryGiB) : 0.0;
            LOG.info("Prometheus node metrics: {} cpuPercent={} memoryPercent={}", nodeName, cpuPercent, memoryPercent);
            result.put(nodeName, new ClusterMetrics(cpuPercent, memoryPercent));
        }
        return result;
    }

    /**
     * Run a Prometheus vector query and index the results by instance label (plus normalized host).
     */
    private Map<String, Double> queryPrometheusVector(String promUrl, String query) {
        try {
            HttpClient http = buildPrometheusHttpClient(promUrl);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(promUrl + "/api/v1/query?query=" + encoded))
                    .timeout(Duration.ofSeconds(8));
            applyPrometheusAuth(builder);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.warn("Prometheus vector query failed ({}): {}", response.statusCode(), response.body());
                return Collections.emptyMap();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("data").path("result");
            if (!results.isArray()) {
                return Collections.emptyMap();
            }
            Map<String, Double> map = new LinkedHashMap<>();
            for (JsonNode item : results) {
                String instance = item.path("metric").path("instance").asText(null);
                if (instance == null || instance.isBlank()) continue;
                JsonNode valueArray = item.path("value");
                if (!valueArray.isArray() || valueArray.size() < 2) continue;
                double val = valueArray.get(1).asDouble(Double.NaN);
                if (Double.isNaN(val)) continue;
                map.put(instance, val);
                map.put(normalizeInstanceKey(instance), val);
            }
            LOG.info("Prometheus vector query '{}' returned {} entries", query, map.size());
            return map;
        } catch (Exception e) {
            LOG.warn("Prometheus vector query failed for {}: {}", promUrl, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Construct an HTTP client that can optionally trust the Kubernetes cluster CA for HTTPS endpoints.
     */
    private HttpClient buildPrometheusHttpClient(String promUrl) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        // Kubernetes API proxy is known to close HTTP/2 streams (GOAWAY); force HTTP/1.1 for stability.
        if (promUrl != null && promUrl.contains("/api/v1/namespaces/") && promUrl.contains("/proxy")) {
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        if (promUrl.startsWith("https")) {
            try {
                // Use the full kubeconfig TLS material (CA + optional client cert/key).
                builder.sslContext(SSLUtils.sslContext(client.getConfiguration()));
            } catch (Exception sslException) {
                // Fall back to CA-only trust if client cert parsing fails.
                String caData = client.getConfiguration().getCaCertData();
                if (caData != null && !caData.isBlank()) {
                    builder.sslContext(buildSslContextFromCaData(caData));
                }
                LOG.warn("Prometheus HTTPS client setup fell back to CA-only trust: {}", sslException.getMessage());
            }
        }
        return builder.build();
    }

    /**
     * Apply bearer tokens to Prometheus requests if the kubeconfig provides one.
     */
    private void applyPrometheusAuth(HttpRequest.Builder builder) {
        Config currentConfig = client.getConfiguration();
        String token = currentConfig.getOauthToken();
        if (token == null || token.isBlank()) {
            token = currentConfig.getAutoOAuthToken();
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
            return;
        }
        String username = currentConfig.getUsername();
        String password = currentConfig.getPassword();
        if (username != null && !username.isBlank() && password != null) {
            String basicAuthValue = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basicAuthValue);
        }
    }

    /**
     * Build candidate Prometheus instance keys for each node (name/IP and optional port).
     */
    private Map<String, List<String>> buildInstanceKeys(List<Node> nodes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Node node : nodes) {
            String nodeName = node.getMetadata().getName();
            if (nodeName == null || nodeName.isBlank()) continue;
            List<String> keys = new ArrayList<>();
            keys.add(nodeName);
            if (node.getStatus() != null && node.getStatus().getAddresses() != null) {
                for (NodeAddress addr : node.getStatus().getAddresses()) {
                    if (addr.getAddress() != null && !addr.getAddress().isBlank()) {
                        keys.add(addr.getAddress());
                        keys.add(addr.getAddress() + ":9100");
                    }
                }
            }
            result.put(nodeName, keys);
        }
        return result;
    }

    /**
     * Return the first matching metric value for the given keys, allowing normalized forms.
     */
    private double resolveMetricForNode(Map<String, Double> vector, List<String> keys) {
        for (String key : keys) {
            if (key == null) continue;
            Double val = vector.get(key);
            if (val != null) return val;
            Double normalized = vector.get(normalizeInstanceKey(key));
            if (normalized != null) return normalized;
        }
        return Double.NaN;
    }

    /**
     * Strip the port portion from an instance identifier (e.g. 10.0.0.1:9100 -> 10.0.0.1).
     */
    private String normalizeInstanceKey(String instance) {
        if (instance == null || instance.isBlank()) return "";
        int colon = instance.lastIndexOf(':');
        if (colon > 0) {
            return instance.substring(0, colon);
        }
        return instance;
    }
    private double parsePrometheusSingleValue(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            var data = root.path("data").path("result");
            if (data.isArray() && data.size() > 0) {
                var valArr = data.get(0).path("value");
                if (valArr.isArray() && valArr.size() > 1) {
                    return valArr.get(1).asDouble(Double.NaN);
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not parse Prometheus response: {}", e.getMessage());
        }
        return Double.NaN;
    }
    private SSLContext buildSslContextFromCaData(String caData) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(caData);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate caCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ca", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        return sslContext;
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
     * Reinitialize the Kubernetes client from the currently saved kubeconfig if it was previously unconfigured.
     * @return true if the client was reinitialized.
     */
    public synchronized boolean reloadClientIfConfigured() {
        try {
            if (this.client != null && this.isConfigured) {
                return true;
            }
            this.configurationService = new ViewConfigurationService(viewContext);
            String kubeconfigPath = configurationService.getKubeconfigPath();
            if (kubeconfigPath == null) {
                LOG.warn("reloadClientIfConfigured: kubeconfig path is null");
                return false;
            }
            File configFile = new File(kubeconfigPath);
            if (!configFile.exists()) {
                LOG.warn("reloadClientIfConfigured: kubeconfig file missing at {}", kubeconfigPath);
                return false;
            }
            EncryptionService encryptionService = new EncryptionService();
            byte[] encryptedBytes = Files.readAllBytes(Paths.get(kubeconfigPath));
            byte[] decryptedBytes = encryptionService.decrypt(encryptedBytes);
            String kubeconfigContent = new String(decryptedBytes, StandardCharsets.UTF_8);

            Config finalConfiguration = Config.fromKubeconfig(kubeconfigContent);
            loadK8sPropsAsSystemProperties(viewContext);
            this.client = new DefaultKubernetesClient(finalConfiguration);
            this.isConfigured = true;
            LOG.info("reloadClientIfConfigured: Kubernetes client reinitialized successfully");
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to reinitialize Kubernetes client: {}", e.getMessage());
            return false;
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

    /**
     * Best-effort relabel of ingress resources to ensure discovery works even if
     * the chart didn't set app.kubernetes.io/instance. Idempotent.
     */
    public void ensureReleaseLabelsOnIngresses(String namespace, String releaseName) {
        if (client == null) return;
        try {
            client.network().v1().ingresses()
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .stream()
                    .filter(ing -> ing.getMetadata() != null
                            && ing.getMetadata().getName() != null
                            && ing.getMetadata().getName().toLowerCase(Locale.ROOT).contains(releaseName.toLowerCase(Locale.ROOT)))
                    .forEach(ing -> {
                        if (ing.getMetadata().getLabels() == null) {
                            ing.getMetadata().setLabels(new HashMap<>());
                        }
                        ing.getMetadata().getLabels().putIfAbsent("app.kubernetes.io/instance", releaseName);
                        ing.getMetadata().getLabels().putIfAbsent("app", releaseName);
                        client.network().v1().ingresses()
                                .inNamespace(namespace)
                                .withName(ing.getMetadata().getName())
                                .patch(ing);
                        LOG.info("Patched ingress {}/{} with instance label={}", namespace, ing.getMetadata().getName(), releaseName);
                    });
        } catch (Exception ex) {
            LOG.warn("ensureReleaseLabelsOnIngresses failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }
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

    /**
     * public methods for build config mapg or secret based on hadoop configs
     */
    /**
     * Create or update a list of "rendered" configuration resources (ConfigMap(s) or Secret(s)),
     * each capable of holding multiple files as entries.
     *
     * <p>Typical usage: create a Secret with multiple XML files (e.g., core-site.xml, hdfs-site.xml),
     * or a ConfigMap with several plugin property files, and then mount them in your Helm chart pods.</p>
     *
     * @param namespace Kubernetes namespace where resources will be created.
     * @param releaseName Optional Helm release name to stamp ownership metadata (set null to skip).
     * @param specs List of resources to create or update.
     */
    public void ensureRenderedConfigs(String namespace, String releaseName, List<RenderConfigSpec> specs) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(specs, "specs");
        checkConfiguration();

        for (RenderConfigSpec spec : specs) {
            createOrUpdateRenderedConfig(namespace, releaseName, spec);
        }
    }

    /**
     * Create or update a single "rendered" configuration resource (ConfigMap or Secret) with multiple files.
     *
     * @param namespace Kubernetes namespace where the resource will be created.
     * @param releaseName Optional Helm release name to stamp ownership metadata (set null to skip).
     * @param spec The resource specification.
     */
    public void createOrUpdateRenderedConfig(String namespace, String releaseName, RenderConfigSpec spec) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(spec, "spec");
        checkConfiguration();

        switch (spec.getResourceType()) {
            case CONFIG_MAP -> upsertConfigMap(namespace, releaseName, spec);
            case SECRET     -> upsertOpaqueSecret(namespace, releaseName, spec);
            default -> throw new IllegalArgumentException("Unsupported resource type: " + spec.getResourceType());
        }
    }

    /**
     * kubernetes config map renderer
     */
    private void upsertConfigMap(String namespace, String releaseName, RenderConfigSpec spec) {
        final String name = spec.getResourceName();
        LOG.info("Upserting ConfigMap '{}/{}' with {} file(s)", namespace, name, spec.getFiles().size());

        // Build metadata
        ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations("managed-by", "ambari-k8s-view")
                .addToAnnotations("managed-at", java.time.Instant.now().toString())
                .build();

        if (!spec.getLabels().isEmpty()) {
            if (meta.getLabels() == null) meta.setLabels(new HashMap<>());
            meta.getLabels().putAll(spec.getLabels());
        }
        if (!spec.getAnnotations().isEmpty()) {
            if (meta.getAnnotations() == null) meta.setAnnotations(new HashMap<>());
            meta.getAnnotations().putAll(spec.getAnnotations());
        }

        // Data map: ConfigMap expects plain strings (not base64)
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spec.getFiles().entrySet()) {
            String key = Objects.requireNonNull(e.getKey(), "file key");
            String value = e.getValue() == null ? "" : e.getValue();
            data.put(key, value);
        }

        ConfigMap desired = new ConfigMapBuilder()
                .withMetadata(meta)
                .withData(data)
                .build();

        // Optional immutable handling for ConfigMap if spec.getImmutable() != null
        if (spec.getImmutable() != null) {
            desired.setImmutable(spec.getImmutable());
        }

        // Stamp Helm ownership if requested
        if (releaseName != null && !releaseName.isBlank()) {
            ensureHelmOwnership(desired, releaseName, namespace);
        }

        ConfigMap existing = client.configMaps().inNamespace(namespace).withName(name).get();
        if (existing == null) {
            client.configMaps().inNamespace(namespace).resource(desired).create();
            LOG.info("Created ConfigMap '{}/{}'", namespace, name);
            return;
        }

        // If immutable and content changed → must delete & recreate
        boolean existingImmutable = Boolean.TRUE.equals(existing.getImmutable());
        boolean sameData = Objects.equals(existing.getData(), desired.getData());

        if (existingImmutable && !sameData) {
            LOG.info("ConfigMap '{}/{}' is immutable and data changed → delete & recreate", namespace, name);
            client.configMaps().inNamespace(namespace).withName(name).delete();
            client.configMaps().inNamespace(namespace).resource(desired).create();
            LOG.info("Recreated immutable ConfigMap '{}/{}'", namespace, name);
            return;
        }

        client.configMaps().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Updated ConfigMap '{}/{}'", namespace, name);
    }

    /**
     * k8s secret renderer
     */

    private void upsertOpaqueSecret(String namespace, String releaseName, RenderConfigSpec spec) {
        final String name = spec.getResourceName();
        LOG.info("Upserting Opaque Secret '{}/{}' with {} file(s)", namespace, name, spec.getFiles().size());

        // Build metadata
        ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .addToAnnotations("managed-by", "ambari-k8s-view")
                .addToAnnotations("managed-at", java.time.Instant.now().toString())
                .build();

        if (!spec.getLabels().isEmpty()) {
            if (meta.getLabels() == null) meta.setLabels(new HashMap<>());
            meta.getLabels().putAll(spec.getLabels());
        }
        if (!spec.getAnnotations().isEmpty()) {
            if (meta.getAnnotations() == null) meta.setAnnotations(new HashMap<>());
            meta.getAnnotations().putAll(spec.getAnnotations());
        }

        // Secret data must be base64-encoded (Fabric8 wants the value already encoded when using withData)
        Map<String, String> encodedData = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spec.getFiles().entrySet()) {
            String key = Objects.requireNonNull(e.getKey(), "file key");
            String value = e.getValue() == null ? "" : e.getValue();
            encodedData.put(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }

        Secret desired = new SecretBuilder()
                .withMetadata(meta)
                .withType("Opaque")
                .withData(encodedData)
                .withImmutable(spec.getImmutable()) // may be null
                .build();

        // Stamp Helm ownership if requested
        if (releaseName != null && !releaseName.isBlank()) {
            ensureHelmOwnership(desired, releaseName, namespace);
        }

        Secret existing = client.secrets().inNamespace(namespace).withName(name).get();
        if (existing == null) {
            client.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Created Opaque Secret '{}/{}'", namespace, name);
            return;
        }

        boolean existingImmutable = Boolean.TRUE.equals(existing.getImmutable());
        boolean sameData = Objects.equals(existing.getData(), desired.getData());

        if (existingImmutable && !sameData) {
            LOG.info("Secret '{}/{}' is immutable and data changed → delete & recreate", namespace, name);
            client.secrets().inNamespace(namespace).withName(name).delete();
            client.secrets().inNamespace(namespace).resource(desired).create();
            LOG.info("Recreated immutable Opaque Secret '{}/{}'", namespace, name);
            return;
        }

        client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
        LOG.info("Updated Opaque Secret '{}/{}'", namespace, name);
    }


    /**
     * Create or update a ConfigMap with string data entries.
     * If immutable and content changed, we delete & recreate (mirrors Secret logic).
     */
    public void createOrUpdateConfigMap(String namespace,
                                        String configMapName,
                                        Map<String, String> data,
                                        Map<String, String> labels,
                                        Map<String, String> annotations) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(configMapName, "configMapName");
        Objects.requireNonNull(data, "data");
        checkConfiguration();

        try {
            ObjectMeta meta = new ObjectMetaBuilder()
                    .withName(configMapName)
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

            ConfigMap desired = new ConfigMapBuilder()
                    .withMetadata(meta)
                    .withData(new LinkedHashMap<>(data))
                    .build();

            ConfigMap existing = client.configMaps().inNamespace(namespace).withName(configMapName).get();

            if (existing == null) {
                client.configMaps().inNamespace(namespace).resource(desired).create();
                LOG.info("Created ConfigMap '{}/{}'", namespace, configMapName);
                return;
            }

            // If immutable (rare), delete & recreate on content change.
            boolean existingImmutable = Boolean.TRUE.equals(existing.getImmutable());
            boolean sameData = Objects.equals(existing.getData(), desired.getData());

            if (existingImmutable && !sameData) {
                LOG.info("ConfigMap '{}/{}' is immutable and data changed → delete & recreate", namespace, configMapName);
                client.configMaps().inNamespace(namespace).withName(configMapName).delete();
                client.configMaps().inNamespace(namespace).resource(desired).create();
                LOG.info("Recreated immutable ConfigMap '{}/{}'", namespace, configMapName);
                return;
            }

            client.configMaps().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated ConfigMap '{}/{}'", namespace, configMapName);

        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            throw new RuntimeException("Failed to create/update ConfigMap " + namespace + "/" + configMapName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Restart a deployment by deleting its pods so the controller re-pulls the current image/tag.
     * No-op if the deployment does not exist.
     */
    public void restartDeployment(String namespace, String deploymentName) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(deploymentName, "deploymentName");
        checkConfiguration();
        try {
            var deploy = client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
            if (deploy == null) {
                LOG.warn("Deployment {}/{} not found; cannot restart", namespace, deploymentName);
                return;
            }
            // Delete pods owned by this deployment to force a restart
            var selector = deploy.getSpec().getSelector();
            if (selector == null || selector.getMatchLabels() == null || selector.getMatchLabels().isEmpty()) {
                LOG.warn("Deployment {}/{} has no selector; skipping restart", namespace, deploymentName);
                return;
            }
            var labels = selector.getMatchLabels();
            client.pods().inNamespace(namespace).withLabels(labels).delete();
            LOG.info("Requested restart of deployment {}/{} by deleting pods", namespace, deploymentName);
        } catch (Exception e) {
            LOG.warn("Failed to restart deployment {}/{}: {}", namespace, deploymentName, e.toString());
        }
    }

    /**
     * Convenience overload: create/update a Secret with multiple keys (binary).
     * Keys are base64-encoded under the hood by Fabric8 (we pass already-encoded values).
     */
    public void createOrUpdateOpaqueSecret(String namespace, String secretName, Map<String, byte[]> binaryData) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(binaryData, "binaryData");
        checkConfiguration();

        try {
            Map<String, String> b64 = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : binaryData.entrySet()) {
                b64.put(e.getKey(), Base64.getEncoder().encodeToString(e.getValue()));
            }

            ObjectMeta meta = new ObjectMetaBuilder()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToAnnotations("managed-by", "ambari-k8s-view")
                    .addToAnnotations("managed-at", java.time.Instant.now().toString())
                    .build();

            Secret desired = new SecretBuilder()
                    .withMetadata(meta)
                    .withType("Opaque")
                    .withData(b64)
                    .build();

            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (existing == null) {
                client.secrets().inNamespace(namespace).resource(desired).create();
                LOG.info("Created Opaque Secret '{}/{}'", namespace, secretName);
                return;
            }
            client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated Opaque Secret '{}/{}'", namespace, secretName);

        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            throw new RuntimeException("Failed to create/update Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Create or update a TLS Secret (type kubernetes.io/tls) with provided certificate and key bytes.
     *
     * @param namespace   Kubernetes namespace where the Secret should reside.
     * @param secretName  Secret name to create or replace.
     * @param certData    Certificate PEM bytes.
     * @param keyData     Private key PEM bytes.
     * @param labels      Optional labels to attach to the Secret (nullable).
     * @param annotations Optional annotations to attach to the Secret (nullable).
     */
    public void createOrUpdateTlsSecret(String namespace,
                                        String secretName,
                                        byte[] certData,
                                        byte[] keyData,
                                        Map<String, String> labels,
                                        Map<String, String> annotations) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(certData, "certData");
        Objects.requireNonNull(keyData, "keyData");
        checkConfiguration();

        try {
            Map<String, String> b64 = new LinkedHashMap<>();
            b64.put("tls.crt", Base64.getEncoder().encodeToString(certData));
            b64.put("tls.key", Base64.getEncoder().encodeToString(keyData));

            ObjectMeta meta = new ObjectMetaBuilder()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToAnnotations("managed-by", "ambari-k8s-view")
                    .addToAnnotations("managed-at", java.time.Instant.now().toString())
                    .addToAnnotations(annotations)
                    .addToLabels(labels)
                    .build();

            Secret desired = new SecretBuilder()
                    .withMetadata(meta)
                    .withType("kubernetes.io/tls")
                    .withData(b64)
                    .build();

            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (existing == null) {
                client.secrets().inNamespace(namespace).resource(desired).create();
                LOG.info("Created TLS Secret '{}/{}'", namespace, secretName);
                return;
            }
            client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Updated TLS Secret '{}/{}'", namespace, secretName);
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            throw new RuntimeException("Failed to create/update TLS Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read a single key from an Opaque Secret as raw bytes.
     *
     * @param namespace  Kubernetes namespace
     * @param secretName Secret name
     * @param dataKey    key inside .data of the Secret
     * @return Optional containing the decoded bytes, or empty if the Secret
     *         or key does not exist, or if the value is not valid base64.
     */
    public Optional<byte[]> readOpaqueSecretKeyAsBytes(String namespace,
                                                       String secretName,
                                                       String dataKey) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(dataKey, "dataKey");
        checkConfiguration();

        try {
            Secret secret = client.secrets()
                    .inNamespace(namespace)
                    .withName(secretName)
                    .get();

            if (secret == null) {
                LOG.info("Opaque Secret '{}/{}' not found when reading key '{}'",
                        namespace, secretName, dataKey);
                return Optional.empty();
            }

            Map<String, String> data = secret.getData();
            if (data == null || !data.containsKey(dataKey)) {
                LOG.info("Key '{}' not found in Opaque Secret '{}/{}'",
                        dataKey, namespace, secretName);
                return Optional.empty();
            }

            String b64 = data.get(dataKey);
            if (b64 == null || b64.isBlank()) {
                LOG.info("Key '{}' in Opaque Secret '{}/{}' is empty",
                        dataKey, namespace, secretName);
                return Optional.empty();
            }

            try {
                return Optional.of(Base64.getDecoder().decode(b64));
            } catch (IllegalArgumentException ex) {
                LOG.warn("Value for key '{}' in Secret '{}/{}' is not valid base64: {}",
                        dataKey, namespace, secretName, ex.toString());
                return Optional.empty();
            }

        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            // Treat 404 as "not found" → empty
            if (e.getCode() == 404) {
                LOG.info("Opaque Secret '{}/{}' not found (HTTP 404) when reading key '{}'",
                        namespace, secretName, dataKey);
                return Optional.empty();
            }
            throw new RuntimeException(
                    "Failed to read Secret " + namespace + "/" + secretName +
                            " while accessing key '" + dataKey + "': " + e.getMessage(), e);
        }
    }

    /**
     * Read a single key from an Opaque Secret as a UTF-8 string.
     */
    public Optional<String> readOpaqueSecretKeyAsString(String namespace,
                                                        String secretName,
                                                        String dataKey) {
        return readOpaqueSecretKeyAsBytes(namespace, secretName, dataKey)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Ensure or rotate a Secret containing a Java truststore (JKS/PKCS12) sourced from a local file.
     * <p>
     * Behavior:
     * <ul>
     *   <li>If the target Secret does not exist → create it from the local truststore file.</li>
     *   <li>If it exists → compare existing bytes with the local file. If different OR the earliest certificate
     *       in the local truststore expires within {@code minDaysLeft} → replace the Secret.</li>
     * </ul>
     *
     * <p>Annotations set on the Secret:
     * <ul>
     *   <li><b>managed-by</b>=ambari-k8s-view</li>
     *   <li><b>clemlab.com/not-after</b>=earliest certificate NotAfter</li>
     *   <li><b>clemlab.com/keystore-type</b>=JKS|PKCS12</li>
     * </ul>
     *
     * @param namespace           Kubernetes namespace
     * @param secretName          target Secret name to (create|replace)
     * @param truststorePath      absolute path to local truststore file (e.g. /etc/trino/ranger/truststore.jks)
     * @param truststoreType      "JKS" (default) or "PKCS12"
     * @param truststorePassword  keystore password
     * @param secretDataKey       key name inside the Secret (e.g. "truststore.jks"); default if null/blank: "truststore.jks"
     * @param minDaysLeft         rotate if earliest cert expires within this many days
     * @param extraLabels         optional labels to merge
     */
    public void ensureOrRotateTruststoreSecretFromLocalFile(String namespace,
                                                            String secretName,
                                                            String truststorePath,
                                                            String truststoreType,
                                                            char[] truststorePassword,
                                                            String secretDataKey,
                                                            int minDaysLeft,
                                                            Map<String,String> extraLabels) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(truststorePath, "truststorePath");
        if (truststorePassword == null || truststorePassword.length == 0) {
            throw new IllegalArgumentException("truststorePassword must be provided");
        }

        LOG.info("Creating secret:{} from local truststore file:{} and inserting as key:{} data in the secret", secretName, truststorePath, secretDataKey);
        final Path path = Paths.get(truststorePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("Local truststore not found: " + path);
        }

        final String storeType = (truststoreType == null || truststoreType.isBlank()) ? "JKS" : truststoreType;
        final String dataKey   = (secretDataKey == null || secretDataKey.isBlank()) ? "truststore.jks" : secretDataKey;

        try {
            // 1) Read local truststore bytes
            byte[] localBytes = Files.readAllBytes(path);
            // 2) Parse local truststore to compute earliest NotAfter (for rotation signal + annotation)
            KeyStore ks = WebHookConfigurationService.loadKeyStore(path, truststorePassword, storeType);
            List<X509Certificate> certs = WebHookConfigurationService.extractX509FromTrustStore(ks);
            if (certs.isEmpty()) {
                LOG.warn("No certificates found in local truststore {}; continuing but rotation-by-expiry will be disabled.", path);
            }
            Instant earliest = certs.isEmpty()
                    ? Instant.EPOCH
                    : WebHookConfigurationService.earliestExpiry(certs); // re-use your existing helper

            // 3) Check current Secret
            Secret existing = this.client.secrets().inNamespace(namespace).withName(secretName).get();
            LOG.info("Secret {} does exists: {}", existing);
            boolean needUpdate = (existing == null);
            if (!needUpdate) {
                LOG.info("Checking if secret does need to be updated");
                String curB64 = (existing.getData() != null) ? existing.getData().get(dataKey) : null;
                byte[] curBytes = (curB64 != null) ? Base64.getDecoder().decode(curB64) : null;

                boolean contentDiffers = (curBytes == null) || !Arrays.equals(curBytes, localBytes);
                boolean expiringSoon   = !certs.isEmpty() &&
                        earliest.isBefore(Instant.now().plus(minDaysLeft, java.time.temporal.ChronoUnit.DAYS));

                if (contentDiffers || expiringSoon) {
                    needUpdate = true;
                    LOG.info("Truststore Secret needs update: differs={} expiringSoon={} (earliestNotAfter={}) {}/{}",
                            contentDiffers, expiringSoon, earliest, namespace, secretName);
                } else {
                    LOG.info("Truststore Secret up-to-date (earliestNotAfter={}) {}/{}", earliest, namespace, secretName);
                }
            }

            if (!needUpdate) {
                return;
            }

            // 4) Build Secret with labels/annotations
            Map<String,String> labels = new LinkedHashMap<>();
            labels.put("managed-by", "ambari-k8s-view");
            labels.put("component", "ambari-truststore");
            if (extraLabels != null && !extraLabels.isEmpty()) labels.putAll(extraLabels);

            Map<String,String> ann = new LinkedHashMap<>();
            ann.put("managed-by", "ambari-k8s-view");
            ann.put("clemlab.com/keystore-type", storeType);
            if (!certs.isEmpty()) {
                ann.put("clemlab.com/not-after", earliest.toString());
            }

            Secret desired = new SecretBuilder()
                    .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .addToAnnotations(ann)
                    .endMetadata()
                    .withType("Opaque")
                    .addToData(dataKey, Base64.getEncoder().encodeToString(localBytes))
                    .build();

            this.client.secrets().inNamespace(namespace).resource(desired).createOrReplace();
            LOG.info("Created/rotated Truststore Secret {}/{} (key={}, earliestNotAfter={}, type={})",
                    namespace, secretName, dataKey, earliest, storeType);
            LOG.info("String equivalent truststore.jks is {}", Base64.getEncoder().encodeToString(localBytes));

        } catch (IOException e) {
            throw new RuntimeException("Failed to read local truststore file: " + path + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create/rotate truststore Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }


    /**
     * Lists Kubernetes Services matching a specific label selector.
     * Returns a list of maps suitable for the UI {label: "...", value: "..."}
     */
    public List<Map<String, String>> listServicesByLabel(String labelKey) {
        // Guard: ensure client is configured
        if (client == null) return Collections.emptyList();

        // Assumption: labelKey passed from UI is "trino".
        // We look for 'app.kubernetes.io/name=trino' OR 'app=trino'
        // Ideally, the UI passes the full selector, but let's handle simple names.

        String selector = labelKey.contains("=") ? labelKey : "app.kubernetes.io/name=" + labelKey;
        return serviceCache.get(selector, k -> fetchServicesFromK8s(k));
    }
    private List<Map<String, String>> fetchServicesFromK8s(String selector) {
        try {
            // Fetch services across ALL namespaces
            List<io.fabric8.kubernetes.api.model.Service> svcList = client.services()
                    .inAnyNamespace()
                    .withLabelSelector(selector)
                    .list()
                    .getItems();

            List<Map<String, String>> results = new ArrayList<>();
            for (io.fabric8.kubernetes.api.model.Service svc : svcList) {
                String name = svc.getMetadata().getName();
                String ns = svc.getMetadata().getNamespace();

                // Find a usable port (prefer 8080, 80, or the first one)
                Integer port = 80;
                if (svc.getSpec().getPorts() != null && !svc.getSpec().getPorts().isEmpty()) {
                    port = svc.getSpec().getPorts().get(0).getPort();
                }

                // Construct internal DNS: service.namespace.svc.cluster.local
                String internalDns = String.format("%s.%s.svc.cluster.local", name, ns);

                // Format for UI
                Map<String, String> item = new HashMap<>();
                item.put("label", String.format("%s (%s)", name, ns)); // Text shown in dropdown
                item.put("value", internalDns); // Value put into values.yaml

                // Optional: You could pass port separately if needed,
                // but usually connection strings take "host:port" or just host.
                // item.put("port", String.valueOf(port));

                results.add(item);
            }
            return results;
        } catch (Exception e) {
            LOG.error("Error listing K8s services with label {}", selector, e);
            throw new RuntimeException("K8s Service Discovery failed", e);
        }
    }

    /**
     * Lists Kubernetes Secrets matching a label selector.
     * Used for discovering Managed Configurations.
     * Returns a list of maps {label: "Name (ns)", value: "secretName", ...metadata}
     */
    public List<Map<String, String>> listSecretsByLabel(String labelKey, String labelValue) {
        checkConfiguration();
        try {
            // If labelValue is null/wildcard, just check for existence of key
            var filter = client.secrets().inAnyNamespace();

            List<Secret> secrets;
            if (labelValue != null && !labelValue.equals("*")) {
                secrets = filter.withLabel(labelKey, labelValue).list().getItems();
            } else {
                secrets = filter.withLabel(labelKey).list().getItems();
            }

            List<Map<String, String>> results = new ArrayList<>();
            for (Secret s : secrets) {
                Map<String, String> item = new HashMap<>();
                String name = s.getMetadata().getName();
                String ns = s.getMetadata().getNamespace();

                // Read annotations for metadata (language, description, filename)
                Map<String, String> ann = s.getMetadata().getAnnotations();
                if (ann == null) ann = Collections.emptyMap();

                item.put("name", name);
                item.put("namespace", ns);
                item.put("filename", ann.getOrDefault("ambari.clemlab.com/filename", "config"));
                item.put("description", ann.getOrDefault("ambari.clemlab.com/description", ""));
                item.put("type", ann.getOrDefault("ambari.clemlab.com/config-type", "generic"));
                item.put("language", ann.getOrDefault("ambari.clemlab.com/language", "plaintext"));

                // For the dropdown value, we usually just need the name, but sometimes we need ns/name
                // For k8s-discovery binding, usually just the name is sufficient if in the same namespace
                item.put("value", name);
                item.put("label", String.format("%s (%s)", name, ns));

                results.add(item);
            }
            return results;
        } catch (Exception e) {
            LOG.error("Error listing secrets", e);
            throw new RuntimeException("Secret discovery failed", e);
        }
    }

    /**
     * Reads the content of a managed configuration secret to display in the UI editor.
     */
    public String getManagedConfigContent(String namespace, String name, String filename) {
        return readOpaqueSecretKeyAsString(namespace, name, filename).orElse("");
    }

    public void deleteSecret(String namespace, String name) {
        checkConfiguration();
        client.secrets().inNamespace(namespace).withName(name).delete();
    }

    /**
     * Retrieves the user-supplied configuration (values.yaml) for a specific Helm release
     * by decoding the Kubernetes Secret managed by Helm.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getHelmReleaseValues(String namespace, String releaseName) {
        checkConfiguration();
        try {
            // 1. Find the secret for the 'deployed' release
            // Helm secrets have labels: name=<release>, owner=helm, status=deployed
            List<Secret> secrets = client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .withLabel("name", releaseName)
                    .withLabel("status", "deployed")
                    .list().getItems();

            if (secrets.isEmpty()) {
                // Fallback: get the latest version if status is not deployed (e.g. failed)
                secrets = client.secrets().inNamespace(namespace)
                        .withLabel("owner", "helm")
                        .withLabel("name", releaseName)
                        .list().getItems();

                if (secrets.isEmpty()) {
                    LOG.warn("No Helm release secret found for {}/{}", namespace, releaseName);
                    return Collections.emptyMap();
                }

                // Sort by version descending (v2 > v1)
                secrets.sort((s1, s2) -> {
                    int v1 = getHelmVersionFromSecret(s1);
                    int v2 = getHelmVersionFromSecret(s2);
                    return Integer.compare(v2, v1);
                });
            }

            // 2. Decode the latest secret
            Secret latestSecret = secrets.get(0);
            return decodeHelmValues(latestSecret);

        } catch (Exception e) {
            LOG.error("Failed to decode Helm values for {}/{}", namespace, releaseName, e);
            throw new RuntimeException("Could not retrieve configuration: " + e.getMessage());
        }
    }

    private int getHelmVersionFromSecret(Secret s) {
        try {
            String v = s.getMetadata().getLabels().get("version");
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeHelmValues(Secret secret) throws IOException {
        // Helm v3 Secret Storage Format:
        // 1. K8s Secret Data "release" -> Base64 Encoded (standard K8s)
        // 2. Decoded Payload -> Another Base64 Encoded String (Helm's internal encoding)
        // 3. Decoded Payload -> Gzipped Bytes
        // 4. Gunzipped Bytes -> JSON String (Protobuf Release object)
        // 5. JSON Object -> "config" field contains the user values

        if (secret.getData() == null || !secret.getData().containsKey("release")) {
            return Collections.emptyMap();
        }

        // Layer 1: Standard K8s Base64
        String k8sBase64 = secret.getData().get("release");
        byte[] helmPayloadBytes = Base64.getDecoder().decode(k8sBase64);
        String helmPayloadStr = new String(helmPayloadBytes, StandardCharsets.UTF_8);

        // Layer 2: Helm Internal Base64
        byte[] gzippedBytes = Base64.getDecoder().decode(helmPayloadStr);

        // Layer 3: GZIP -> JSON
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzippedBytes));
             InputStreamReader reader = new InputStreamReader(gis, StandardCharsets.UTF_8)) {

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> releaseData = mapper.readValue(reader, Map.class);

            // Layer 4: Extract "config"
            Object config = releaseData.get("config");
            if (config instanceof Map) {
                return (Map<String, Object>) config;
            }
            return Collections.emptyMap();
        }
    }

    /**
     * Shutdown static executor services gracefully.
     * Should be called during application shutdown.
     */
    public static void shutdownStaticExecutors() {
        LOG.info("Shutting down KubernetesService static executor pools");
        
        if (METRICS_POOL != null) {
            METRICS_POOL.shutdown();
            try {
                if (!METRICS_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                    METRICS_POOL.shutdownNow();
                    LOG.warn("METRICS_POOL did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                METRICS_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (BOOTSTRAP_POOL != null) {
            BOOTSTRAP_POOL.shutdown();
            try {
                if (!BOOTSTRAP_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                    BOOTSTRAP_POOL.shutdownNow();
                    LOG.warn("BOOTSTRAP_POOL did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                BOOTSTRAP_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        LOG.info("KubernetesService static executor pools shutdown complete");
    }
}
