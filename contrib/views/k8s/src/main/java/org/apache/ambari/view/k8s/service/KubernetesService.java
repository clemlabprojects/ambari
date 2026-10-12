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
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
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
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.WatchEvent;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.ComponentCondition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;


import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
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
import org.apache.ambari.view.k8s.store.InstanceSettings;

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
import java.util.function.Supplier;
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
    
    // Volatile: replaced by a fully built client on reconnection while other request threads read it.
    private volatile KubernetesClient client;
    private volatile boolean isConfigured;
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
    // Cached OpenShift-vs-vanilla-k8s detection for the current client; reset on client reload.
    private final AtomicReference<Boolean> openShiftDetectionCache = new AtomicReference<>(null);
    /** After a failed OpenShift check, when to ask again (epoch ms). */
    private final java.util.concurrent.atomic.AtomicLong openShiftDetectionRetryAt = new java.util.concurrent.atomic.AtomicLong();
    static final long OPENSHIFT_DETECTION_RETRY_MS = 30_000L;
    // Namespace-by-namespace fallback for accounts limited to their own projects; reads this.client on every call.
    private final NamespaceScope namespaceScope = new NamespaceScope(
            () -> this.client, this::isOpenShiftCluster, this::knownNamespaceNames,
            this::executeWithAuthRetry, System::currentTimeMillis);

    // Auto-minted OpenShift monitoring bearer token (for kubeconfigs that authenticate with a client
    // certificate and therefore carry no token — Thanos Querier's oauth-proxy requires a Bearer token
    // with cluster-monitoring-view). Cached with its expiry and renewed lazily before it expires.
    private final AtomicReference<MintedToken> monitoringTokenCache = new AtomicReference<>(null);
    private final Object monitoringTokenMintLock = new Object();
    private static final String MONITORING_VIEWER_CLUSTER_ROLE = "cluster-monitoring-view";
    private static final String MONITORING_VIEWER_CRB_NAME = "ambari-monitoring-viewer";
    private static final long MONITORING_TOKEN_TTL_SECONDS = 3600L;          // request 1-hour tokens
    private static final long MONITORING_TOKEN_RENEW_BUFFER_MS = 300_000L;   // renew when < 5 min remain

    /** A bearer token together with the epoch-millis instant at which it expires. */
    private static final class MintedToken {
        final String token;
        final long expiresAtMs;
        MintedToken(String token, long expiresAtMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private ViewConfigurationService configurationService;
    /** View settings and monitoring state, shared by every user of the view instance; see {@link #settings()}. */
    private volatile InstanceSettings instanceSettings;

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
    private static final String PROMETHEUS_CRD = "prometheuses.monitoring.coreos.com";
    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext PROMETHEUS_RDC =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup("monitoring.coreos.com")
                    .withVersion("v1")
                    .withKind("Prometheus")
                    .withPlural("prometheuses")
                    .withNamespaced(true)
                    .build();
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
    // Bounded pool for metrics calls. The caller already enforces a 2-second
    // per-future timeout, but the underlying fabric8/OkHttp call can block
    // much longer (TCP read timeout). An unbounded CachedThreadPool would grow
    // without limit if the metrics-server or Prometheus is slow. Cap at 64 so
    // that at most 64 blocking calls accumulate before new tasks are queued.
    private static final ExecutorService METRICS_POOL = new java.util.concurrent.ThreadPoolExecutor(
            0, 64,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "k8s-metrics-pool");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ExecutorService BOOTSTRAP_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "k8s-monitoring-bootstrap");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownStaticExecutors();
            CommandService.shutdownAll();
        }, "k8s-view-shutdown"));
    }

    // Cached per-instance Prometheus HTTP clients keyed by promUrl.
    // Cleared whenever the underlying k8s client is reloaded (new TLS material).
    private final java.util.concurrent.ConcurrentHashMap<String, HttpClient> prometheusClientCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns (or creates) the singleton {@code KubernetesService} for the given view instance,
     * and schedules a one-time async monitoring bootstrap if the service is configured. The service is
     * kept even when it could not connect yet, so that later requests retry under its back-off.
     *
     * @param ctx the Ambari view context identifying the view instance
     * @return the shared {@code KubernetesService} for this view instance
     */
    public static KubernetesService get(ViewContext ctx) {
        KubernetesService svc = INSTANCES.computeIfAbsent(ctx.getInstanceName(), k -> new KubernetesService(ctx));
        svc.scheduleMonitoringBootstrapAsync();
        return svc;
    }

    /**
     * Constructs a {@code KubernetesService} from an existing client, primarily for testing.
     *
     * @param client       the Kubernetes client to use
     * @param isConfigured whether the service should be considered configured
     */
    public KubernetesService(KubernetesClient client, boolean isConfigured) {
        this.viewContext = null; // Default constructor for testing
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(null, helmClient);
        this.helmService = new HelmService(null, helmClient, () -> namespaceScope);
        this.configurationService = null;
    }

    /**
     * Constructs a {@code KubernetesService} with a view context and a pre-built Kubernetes client.
     *
     * @param viewContext  the Ambari view context
     * @param client       the Kubernetes client to use
     * @param isConfigured whether the service should be considered configured
     */
    public KubernetesService(ViewContext viewContext, KubernetesClient client, boolean isConfigured) {
        this.viewContext = viewContext;
        this.client = client;
        this.isConfigured = isConfigured;
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
        this.helmService = new HelmService(viewContext, helmClient, () -> namespaceScope);
        this.configurationService = new ViewConfigurationService(viewContext);
    }

    /**
     * Constructs a {@code KubernetesService} with explicit Kubernetes and Helm clients, typically for testing.
     *
     * @param ctx          the Ambari view context
     * @param k8s          the Kubernetes client to use
     * @param helmClient   the Helm client to use
     * @param isConfigured whether the service should be considered configured
     */
    public KubernetesService(ViewContext ctx, KubernetesClient k8s, HelmClient helmClient, boolean isConfigured) {
        this.viewContext = ctx;
        this.client = k8s;
        this.isConfigured = isConfigured;
        // Internal services with the same mocked HelmClient
        this.helmClient = helmClient;
        this.repositoryService = new HelmRepositoryService(ctx, helmClient);
        this.helmService = new HelmService(ctx, helmClient, () -> namespaceScope);
    }

    /**
     * Primary constructor: connects from the configuration saved for the view instance (kubeconfig file or OpenShift
     * login). It does not fail when it cannot connect (cluster or OAuth server unreachable, password refused, unreadable
     * kubeconfig): the service then reports the view as configured but not connected, and connects on a later
     * request, under the single attempt and back-off of {@link #connectIfConfigured()}.
     *
     * @param viewContext the Ambari view context providing configuration properties and the instance settings
     */
    public KubernetesService(ViewContext viewContext) {
        this.viewContext = viewContext;
        this.configurationService = new ViewConfigurationService(viewContext);
        this.helmClient = new HelmClientDefault();
        this.repositoryService = new HelmRepositoryService(viewContext, helmClient);
        this.helmService = new HelmService(viewContext, helmClient, () -> namespaceScope);

        String kubeconfigPath = configurationService.getKubeconfigPath();
        boolean openShiftLogin = configurationService.isOpenShiftLogin();
        if (kubeconfigPath == null && !openShiftLogin) {
            LOG.info("View is not configured. Kubernetes client will not be initialized.");
        } else if (!openShiftLogin && !new File(kubeconfigPath).exists()) {
            LOG.warn("Stale configuration detected. Kubeconfig file not found at path: {}. Resetting configuration.", kubeconfigPath);
            configurationService.removeKubeconfigPath(); // Auto-healing: remove stale entry
        } else {
            // Failing here would leave nothing cached for the view instance (see get()), so every request of every
            // user would build the service again: with an OpenShift login, one password login per request.
            connectFromSavedConfiguration();
        }
    }

    /**
     * Thrown by the cluster calls when the view instance is configured but has no connection to the cluster: the last
     * attempt to connect with the saved configuration failed. It is not the "not configured" error: callers answer
     * 503, so the UI shows the view disconnected and recovers once a later attempt connects, instead of showing the
     * first-run configuration page to every user.
     */
    public static final class NotConnectedException extends IllegalStateException {
        public NotConnectedException(String message) {
            super(message);
        }
    }

    private void checkConfiguration() {
        connectIfConfigured();
        if (!isConfigured) {
            if (hasSavedConfiguration()) {
                throw new NotConnectedException(savedLoginRefused
                        ? "KDPS is not connected to the cluster: the cluster refused the saved OpenShift username and "
                                + "password. To avoid locking the account, KDPS does not log in with them again until "
                                + "an administrator saves the OpenShift login again on the Cluster settings page."
                        : "KDPS is not connected to the cluster: it could not connect with the saved configuration and "
                                + "tries again " + nextAttemptIn() + ".");
            }
            throw new IllegalStateException("The view is not configured with a kubeconfig.");
        }
    }

    /**
     * Whether a configuration was saved for the view instance, connected or not. A service built around a given client
     * has none. When the saved configuration cannot be read, the view is taken as configured: it is then reported
     * disconnected, which the UI recovers from, rather than unconfigured, which shows the first-run configuration page.
     */
    private boolean hasSavedConfiguration() {
        ViewConfigurationService configuration = this.configurationService;
        if (configuration == null || viewContext == null) {
            return false;
        }
        try {
            return configuration.isConfigured();
        } catch (RuntimeException e) {
            LOG.warn("Could not read the saved configuration: {}", e.toString());
            return true;
        }
    }

    /** After a failed attempt to connect from the saved configuration, when to try again (epoch ms). */
    private final java.util.concurrent.atomic.AtomicLong connectRetryAt = new java.util.concurrent.atomic.AtomicLong();
    /** Failed attempts in a row since the service last connected or the configuration was saved; guarded by {@code this}. */
    private int connectFailures;
    /**
     * The OAuth server refused the saved OpenShift username and password. The same password cannot succeed later and
     * every refused login counts towards the directory's lockout, so no attempt is made until the configuration is
     * saved again ({@link #forceReloadClient()}).
     */
    private volatile boolean savedLoginRefused;
    /** Wait after the first failed attempt; it doubles after each further failure, up to {@link #CONNECT_RETRY_MAX_MS}. */
    static final long CONNECT_RETRY_MS = 30_000L;
    static final long CONNECT_RETRY_MAX_MS = 15 * 60_000L;
    /** Clock of the connection back-off; replaced by tests only. */
    java.util.function.LongSupplier connectClock = System::currentTimeMillis;

    /**
     * Connects from the saved configuration when this service has no client although the view instance is
     * configured. The service is built once per view instance by whichever request comes first: after an Ambari
     * restart that may be a request made while the cluster could not be reached, or before anyone had saved a
     * configuration. Without this the view stayed unconfigured for every user until someone saved the configuration
     * again. The configuration is shared by all users ({@link InstanceSettings}), so it does not matter whose
     * request or background task gets here first.
     *
     * <p>Only one attempt runs at a time. With an OpenShift login every attempt is a password login, and directories
     * lock an account after a few refused ones, so: requests that arrive during an attempt (the dashboard sends several
     * at once) wait for it and use its outcome instead of each logging in again in turn; after a failed attempt, the
     * first one made while building the service included, the next one waits {@link #CONNECT_RETRY_MS}, twice as long
     * after each further failure ({@link #connectRetryDelayMs}); and once the OAuth server has refused the password,
     * no attempt is made at all until the configuration is saved again. Waiting rather than giving up at once also
     * means those requests get the connection when the attempt succeeds, instead of reporting the view as not
     * connected.
     */
    private void connectIfConfigured() {
        if (client != null && isConfigured) {
            return;
        }
        ViewConfigurationService configuration = this.configurationService;
        if (configuration == null || viewContext == null) {
            return; // built around a given client
        }
        if (savedLoginRefused || connectClock.getAsLong() < connectRetryAt.get()) {
            return;
        }
        boolean configured;
        try {
            configured = configuration.isConfigured();
        } catch (RuntimeException e) {
            LOG.warn("Could not read the saved configuration: {}", e.toString());
            connectRetryAt.set(connectClock.getAsLong() + CONNECT_RETRY_MS);
            return;
        }
        if (configured) {
            connectFromSavedConfiguration();
        }
    }

    /**
     * One attempt to connect from the saved configuration, unless the attempt this call waited for has connected, or
     * failed and its wait is not over. Its outcome is recorded by {@link #rebuildClient()}.
     */
    private void connectFromSavedConfiguration() {
        // The same monitor as forceReloadClient(), so a save or a token renewal never builds a client alongside.
        synchronized (this) {
            if (client != null && isConfigured) {
                return;
            }
            if (savedLoginRefused || connectClock.getAsLong() < connectRetryAt.get()) {
                return;
            }
            LOG.info("The view instance is configured but not connected; connecting from the saved configuration.");
            try {
                reloadClientIfConfigured();
            } catch (RuntimeException e) {
                LOG.warn("Could not connect from the saved configuration: {}", e.toString());
                recordConnectFailure();
            }
        }
    }

    /**
     * Records a failed attempt to connect: stops the attempts when the OAuth server refused the saved password,
     * otherwise sets when the next one may be made. Called holding {@code this}.
     */
    private void recordConnectFailure() {
        if (savedLoginWasRefused()) {
            savedLoginRefused = true;
            LOG.warn("The cluster refused the saved OpenShift username and password. KDPS will not log in with them "
                    + "again until the OpenShift login is saved again on the Cluster settings page.");
            return;
        }
        connectFailures = Math.min(connectFailures + 1, 32);
        long wait = connectRetryDelayMs(connectFailures);
        LOG.warn("Not connected to the cluster; the next attempt is in {} s at the earliest.", wait / 1000);
        connectRetryAt.set(connectClock.getAsLong() + wait);
    }

    /** Lets the next attempt to connect run at once; called holding {@code this}. */
    private void resetConnectBackOff() {
        connectFailures = 0;
        connectRetryAt.set(0);
        savedLoginRefused = false;
    }

    /**
     * The wait before the next attempt to connect after {@code failures} failed attempts in a row: 30 s, then
     * doubled after each further failure, never more than {@link #CONNECT_RETRY_MAX_MS}.
     *
     * @param failures failed attempts in a row, at least 1
     * @return the wait in milliseconds
     */
    static long connectRetryDelayMs(int failures) {
        long wait = CONNECT_RETRY_MS;
        for (int i = 1; i < failures && wait < CONNECT_RETRY_MAX_MS; i++) {
            wait *= 2;
        }
        return Math.min(wait, CONNECT_RETRY_MAX_MS);
    }

    /** "in N s" until the next attempt to connect, or "shortly" when it may run now. */
    private String nextAttemptIn() {
        long wait = connectRetryAt.get() - connectClock.getAsLong();
        return wait > 0 ? "in " + ((wait + 999) / 1000) + " s" : "shortly";
    }

    /**
     * Whether the OAuth server refused the saved OpenShift username and password on the last login, as opposed to a
     * login that could not be made (server unreachable, timeout). Reads the provider of the configuration the last
     * attempt used.
     */
    private boolean savedLoginWasRefused() {
        ViewConfigurationService configuration = this.configurationService;
        if (configuration == null) {
            return false;
        }
        try {
            if (!configuration.isOpenShiftLogin()) {
                return false;
            }
            OpenShiftLoginProvider provider = configuration.getOpenShiftLoginProvider();
            return provider != null && provider.isCredentialsRefused();
        } catch (RuntimeException e) {
            LOG.debug("Could not tell whether the saved OpenShift login was refused: {}", e.toString());
            return false;
        }
    }

    /** The settings of the view instance, shared by every user; created on first use. */
    private InstanceSettings settings() {
        InstanceSettings current = instanceSettings;
        if (current == null) {
            current = new InstanceSettings(viewContext);
            instanceSettings = current;
        }
        return current;
    }

    /**
     * Returns a paginated list of cluster nodes enriched with CPU and memory usage metrics.
     *
     * @param limit  the maximum number of nodes to return; must be at least 1
     * @param offset the zero-based starting index into the full node list
     * @return list of {@link ClusterNode} objects for the requested page
     */
    public List<ClusterNode> getNodes(int limit, int offset) {
        checkConfiguration();
        LOG.info("Fetching node list from Kubernetes API.");
        var items = executeWithAuthRetry("list nodes", () -> client.nodes().list().getItems());
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

    /**
     * Returns the total number of nodes in the cluster.
     *
     * @return node count
     */
    public int countNodes() {
        checkConfiguration();
        return client.nodes().list().getItems().size();
    }

    /**
     * True when the given exception is an HTTP 401 (Unauthorized) from the Kubernetes/OpenShift API —
     * i.e. the API server answered but rejected our bearer token. This is the signal that the stored
     * credentials are no longer valid (typically an OpenShift token revoked early or expired), as
     * opposed to a 403 (authenticated but not permitted) or a transport failure.
     *
     * @param e the client exception raised by a fabric8 call
     * @return {@code true} if the exception carries HTTP status 401
     */
    private boolean isAuthFailure(KubernetesClientException e) {
        return e != null && e.getCode() == 401;
    }

    /** Serializes token renewals so simultaneous 401s renew once. */
    private final Object authRenewLock = new Object();

    /**
     * Executes a Kubernetes API call and, on an authentication failure (HTTP 401), transparently
     * re-authenticates and retries the call exactly ONCE.
     *
     * <p>This heals the common OpenShift scenario the operator hits after logging into the console
     * directly: the console rotates/revokes the API token, so the token the view cached is refused
     * with 401 <em>before</em> its nominal expiry — and nothing in the normal request path would
     * otherwise notice until the token's expiry timestamp passed.
     *
     * <p>Re-authentication is only possible in the {@code openshift-login} auth mode, where the view
     * holds the username/password: {@link #forceReloadClient()} rebuilds the fabric8 client with a new
     * token provider, which logs in once for a brand-new token. For a statically uploaded kubeconfig whose
     * token cannot be renewed, the original 401 is rethrown so callers can surface a
     * "disconnected / re-login required" state rather than silently failing.
     *
     * <p>The {@code call} supplier MUST read {@code this.client} at invocation time (e.g.
     * {@code () -> client.nodes().list()}) so that, after a reload, the retry runs against the freshly
     * built client rather than a stale captured reference.
     *
     * @param operation short human-readable label used in the logs (e.g. "list nodes")
     * @param call      the API call to execute, and to retry once after re-authentication
     * @param <T>       the call's return type
     * @return the result of the call (first attempt, or the retry after a successful re-authentication)
     */
    private <T> T executeWithAuthRetry(String operation, Supplier<T> call) {
        final KubernetesClient clientUsed = this.client;
        try {
            return call.get();
        } catch (KubernetesClientException e) {
            if (!isAuthFailure(e)) {
                throw e;
            }
            LOG.warn("Kubernetes API call '{}' was rejected with HTTP 401 — the stored token is revoked or expired.",
                    operation);
            if (!configurationService.isOpenShiftLogin()) {
                LOG.warn("Auth mode is not 'openshift-login', so the token cannot be auto-renewed for '{}'. "
                        + "The operator must re-upload valid credentials on the Cluster settings page.", operation);
                throw e;
            }
            synchronized (authRenewLock) {
                // Several requests refused at the same moment: the first renews, the others find a new client
                // and just retry with it (outside the lock), instead of each minting a token and building a client.
                KubernetesClient current = this.client;
                if (current != null && current != clientUsed) {
                    LOG.info("Credentials were renewed meanwhile — retrying '{}' with the new client.", operation);
                } else if (current == null) {
                    // Another request's renewal failed meanwhile. Logging in again would only add a refused login
                    // towards the account's lockout; the next attempt follows the back-off of connectIfConfigured().
                    LOG.warn("Credentials could not be renewed for another request; not retrying '{}'.", operation);
                    throw e;
                } else {
                    renewCredentials(operation, e);
                }
            }
            return call.get();
        }
    }

    /**
     * Rebuilds the client, which logs in once through the saved OpenShift login for a new token; called under
     * {@code authRenewLock}. When the login fails, the client is dropped and the back-off of
     * {@link #connectIfConfigured()} applies, so a refused password is not tried again on the next request.
     *
     * @param operation the refused call, for the logs
     * @param refusal   the original 401, rethrown when the renewal fails
     */
    private void renewCredentials(String operation, KubernetesClientException refusal) {
        LOG.info("Auth mode is 'openshift-login' — attempting to re-authenticate and retry '{}' once.", operation);
        try {
            // No separate refresh() first: the rebuilt client gets a new token provider, which logs in anyway, and
            // a second login would double the refused logins when the password was changed.
            boolean reloaded = forceReloadClient();
            if (!reloaded) {
                LOG.error("Client reload after re-authentication reported not-configured; cannot retry '{}'.", operation);
                throw refusal;
            }
        } catch (KubernetesClientException reconnectKce) {
            throw reconnectKce;
        } catch (Exception reconnect) {
            LOG.error("Auto-reconnect before retrying '{}' failed: {}", operation, reconnect.toString());
            throw refusal; // surface the original 401 to the caller
        }
        LOG.info("Re-authentication succeeded — retrying '{}' with the refreshed token.", operation);
    }

    /**
     * Actively probes the target cluster's API server to determine whether the stored credentials
     * still authenticate RIGHT NOW.
     *
     * <p>Unlike {@link ViewConfigurationService#isConfigured()} — which only reports whether credentials
     * were ever stored — this performs a real, cheap, authenticated call (a one-item namespace list) and
     * therefore reflects live reachability, including a token that has been revoked or has expired. The
     * probe is wrapped in {@link #executeWithAuthRetry} so an early-revoked OpenShift token is renewed
     * transparently before the probe is allowed to report a failure.
     *
     * <p>Result mapping:
     * <ul>
     *   <li>call succeeds (HTTP 200) or is forbidden (HTTP 403) → {@link ConnectionHealth#connected()} —
     *       a 403 still proves the token authenticated; it is merely not allowed to list namespaces.</li>
     *   <li>HTTP 401 (even after a renew attempt) → {@link ConnectionHealth#unauthenticated(String)}.</li>
     *   <li>anything else (timeout, TLS, connection refused) → {@link ConnectionHealth#unreachable(String)}.</li>
     *   <li>view configured but not connected, because the last attempt to connect with the saved configuration
     *       failed → {@link ConnectionHealth#notConnected(String)}, or {@link ConnectionHealth#unauthenticated(String)}
     *       when the cluster refused the saved OpenShift password.</li>
     *   <li>view never configured → {@link ConnectionHealth#unconfigured()}.</li>
     * </ul>
     *
     * @return a {@link ConnectionHealth} describing the live state of the cluster connection
     */
    public ConnectionHealth pingCluster() {
        connectIfConfigured();
        if (!isConfigured || client == null) {
            if (!hasSavedConfiguration()) {
                LOG.debug("pingCluster: view is not configured; reporting UNCONFIGURED.");
                return ConnectionHealth.unconfigured();
            }
            if (savedLoginRefused) {
                LOG.debug("pingCluster: the saved OpenShift login was refused; reporting UNAUTHENTICATED.");
                return ConnectionHealth.unauthenticated("KDPS does not log in with the refused password again, "
                        + "so that the account is not locked.");
            }
            LOG.debug("pingCluster: view is configured but not connected; reporting UNREACHABLE.");
            return ConnectionHealth.notConnected("KDPS tries again " + nextAttemptIn() + ".");
        }
        try {
            // Cheapest authenticated round-trip that reliably distinguishes 401 (bad token) from
            // 403 (authenticated but not permitted): a namespace list capped to a single item.
            executeWithAuthRetry("liveness probe",
                    () -> client.namespaces().list(new ListOptionsBuilder().withLimit(1L).build()));
            LOG.debug("pingCluster: cluster reachable and credentials valid (CONNECTED).");
            return ConnectionHealth.connected();
        } catch (KubernetesClientException e) {
            if (isAuthFailure(e)) {
                LOG.warn("pingCluster: cluster rejected the credentials with 401 even after a renew attempt "
                        + "(UNAUTHENTICATED): {}", e.getMessage());
                return ConnectionHealth.unauthenticated(e.getMessage());
            }
            if (e.getCode() == 403 && e.getMessage() != null && e.getMessage().contains("User \"system:anonymous\"")) {
                // No credentials were accepted: the API server fell back to the anonymous user.
                LOG.warn("pingCluster: the cluster treated the request as anonymous (UNAUTHENTICATED): {}", e.getMessage());
                return ConnectionHealth.unauthenticated(e.getMessage());
            }
            if (e.getCode() == 403) {
                LOG.debug("pingCluster: token authenticated but forbidden to list namespaces (treated as CONNECTED).");
                return ConnectionHealth.connected();
            }
            LOG.warn("pingCluster: cluster API returned an error (UNREACHABLE): {}", e.getMessage());
            return ConnectionHealth.unreachable(e.getMessage());
        } catch (Exception e) {
            LOG.warn("pingCluster: cluster API is unreachable (UNREACHABLE): {}", e.toString());
            return ConnectionHealth.unreachable(e.toString());
        }
    }

    /**
     * Returns the health status of all core Kubernetes components.
     *
     * @return list of {@link ComponentStatus} objects
     */
    public List<ComponentStatus> getComponentStatuses() {
        checkConfiguration();
        LOG.info("Fetching component statuses from Kubernetes API.");
        try {
            return executeWithAuthRetry("list component statuses",
                    () -> client.componentstatuses().list().getItems().stream()
                            .map(this::toComponentStatus)
                            .collect(Collectors.toList()));
        } catch (RuntimeException e) {
            if (!NamespaceScope.isForbidden(e)) throw e;
            LOG.debug("Component statuses are not readable by this account: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Returns the 20 most-recent cluster events across all namespaces, sorted newest first.
     *
     * @return list of {@link ClusterEvent} objects
     */
    public List<ClusterEvent> getClusterEvents() {
        return getClusterEvents(null);
    }

    /**
     * Returns the 20 most-recent events of the given namespaces, sorted newest first.
     *
     * @param onlyNamespaces the namespaces to keep, or {@code null} for every namespace
     * @return list of {@link ClusterEvent} objects
     */
    public List<ClusterEvent> getClusterEvents(Set<String> onlyNamespaces) {
        checkConfiguration();
        LOG.info("Fetching recent events from Kubernetes API.");

        List<Event> eventItems = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.EVENTS,
                () -> client.v1().events().inAnyNamespace().list().getItems(),
                ns -> client.v1().events().inNamespace(ns).list().getItems());
        if (onlyNamespaces != null) {
            eventItems = eventItems.stream()
                .filter(e -> e.getMetadata() != null && onlyNamespaces.contains(e.getMetadata().getNamespace()))
                .collect(Collectors.toList());
        }

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

    /**
     * Returns aggregated cluster statistics (CPU, memory, pods, nodes, Helm releases).
     * Results are cached for 30 seconds unless {@code forceRefresh} is set.
     *
     * @param forceRefresh when {@code true} bypasses the cache and recomputes stats from the cluster
     * @return the current {@link ClusterStats}
     */
    public ClusterStats getClusterStats(boolean forceRefresh) {
        ClusterStats cachedStats = statsCache.getIfPresent("clusterStats");
        if (cachedStats != null && !forceRefresh) {
            return cachedStats;
        }
        checkConfiguration();
        LOG.info("Calculating cluster stats from Kubernetes API.");

        NodeList nodeList;
        try {
            nodeList = executeWithAuthRetry("list nodes (stats)", () -> client.nodes().list());
        } catch (RuntimeException e) {
            if (!NamespaceScope.isForbidden(e)) throw e;
            LOG.debug("Nodes are not readable by this account; node and capacity figures stay empty");
            nodeList = new NodeList();
            nodeList.setItems(new ArrayList<>());
        }
        List<Pod> podItems = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.PODS,
                () -> client.pods().inAnyNamespace().list().getItems(),
                ns -> client.pods().inNamespace(ns).list().getItems());
        List<String> runningPods = podItems.stream()
            .filter(pod -> "Running".equalsIgnoreCase(pod.getStatus().getPhase()))
            .map(pod -> pod.getMetadata().getName())
            .collect(Collectors.toList());

        double totalCpuCapacity = 0;
        double totalMemoryCapacity = 0;
        double totalPodCapacity = 0;
        long readyNodesCount = 0;

        for (Node node : nodeList.getItems()) {
            Map<String, Quantity> nodeCapacity = node.getStatus().getCapacity();
            if (nodeCapacity.containsKey("cpu")) {
                totalCpuCapacity += nodeCapacity.get("cpu").getNumericalAmount().doubleValue();
            }
            if (nodeCapacity.containsKey("memory")) {
                totalMemoryCapacity += nodeCapacity.get("memory").getNumericalAmount().doubleValue() / (1024 * 1024 * 1024); // To GiB
            }
            if (nodeCapacity.containsKey("pods")) {
                totalPodCapacity += nodeCapacity.get("pods").getNumericalAmount().doubleValue();
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
                    f.cancel(true); // interrupt the blocked fabric8 call to free the pool thread
                    LOG.warn("Timeout fetching node metrics");
                } catch (Exception ex) {
                    LOG.warn("Could not fetch node usage: {}", ex.getMessage());
                }
            }
            if (metricsFound.get()) {
                metricsSource.set("metrics-server");
            }
        }
        
        // An account limited to its own projects reads neither node metrics nor cluster-wide monitoring: show the
        // usage of its projects, measured against what they are allotted, rather than N/A.
        ProjectUsage.Result projectUsage = null;
        if (!metricsFound.get() && namespaceScope.isRefused(NamespaceScope.Resource.PODS)) {
            List<Pod> running = podItems.stream()
                    .filter(pod -> pod.getStatus() != null && "Running".equalsIgnoreCase(pod.getStatus().getPhase()))
                    .collect(Collectors.toList());
            try {
                projectUsage = ProjectUsage.measure(client, namespaceScope.namespacesToWalk(), running);
            } catch (RuntimeException e) {
                LOG.warn("Could not measure the usage of this account's projects: {}", e.toString());
            }
            if (projectUsage != null) {
                usedCpuTotal = projectUsage.cpu().used();
                usedMemoryTotal = projectUsage.memory().used();
                totalCpuCapacity = projectUsage.cpu().total();
                totalMemoryCapacity = projectUsage.memory().total();
                metricsFound.set(true);
                metricsSource.set("pod-metrics");
            }
        }
        if (!metricsFound.get()) {
            usedCpuTotal = -1;
            usedMemoryTotal = -1;
        }
        
        ClusterStats.ResourceStat cpuStatistics = new ClusterStats.ResourceStat(usedCpuTotal, totalCpuCapacity); // Usage requires metrics
        ClusterStats.ResourceStat memoryStatistics = new ClusterStats.ResourceStat(usedMemoryTotal, totalMemoryCapacity);
        // Pods card denominator is schedulable pod CAPACITY (sum of node "pods" capacity), NOT the count
        // of all pod objects — on OpenShift the latter includes thousands of Completed/Failed pods and is
        // meaningless as a denominator. Fall back to the object count only if capacity is unavailable.
        double podDenominator = totalPodCapacity > 0 ? totalPodCapacity : podItems.size();
        if (projectUsage != null) {
            // Node pod capacity says nothing about a project's allotment: use its pod quota, else its pods.
            podDenominator = projectUsage.pods().basis() == ProjectUsage.Basis.QUOTA ? projectUsage.pods().total() : podItems.size();
        }
        ClusterStats.ResourceStat podStatistics = new ClusterStats.ResourceStat(
                // Project scope: count the pods of the projects measured, the same ones the pod total covers.
                projectUsage != null ? projectUsage.pods().used() : runningPods.size(), podDenominator);
        ClusterStats.ResourceStat nodeStatistics = new ClusterStats.ResourceStat(readyNodesCount, nodeList.getItems().size());
        
        // Helm stats (best-effort): list releases using the helm client. If it fails, keep zeros.
        int deployed = 0, pending = 0, failed = 0, total = 0;
        try {
            String kubeconfigContent = getConfigurationService().getKubeconfigContents();
            List<com.marcnuri.helm.Release> releases = helmService.list(null, kubeconfigContent);
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
        if (projectUsage != null) {
            result.setProjectScope(projectUsage.projectsMeasured(), projectUsage.cpu().basis().label(),
                    projectUsage.memory().basis().label(), projectUsage.pods().basis().label());
        }
        statsCache.put("clusterStats", result);
        return result;
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
    public record MonitoringInfo(String namespace, String release, String url,
                                 Map<String, String> serviceMonitorLabels, String warning, boolean inCluster) {
        public MonitoringInfo(String namespace, String release, String url) {
            this(namespace, release, url, Map.of(), null, false);
        }
    }
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
            String json = settings().get(SETTINGS_KEY);
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
            settings().put("monitoring.bootstrap.state", state);
            if (message != null) {
                // A long failure (a whole Helm error) must not cost the state its message: keep what fits.
                settings().put("monitoring.bootstrap.message", message.length() > InstanceSettings.MAX_VALUE_LENGTH
                        ? message.substring(0, InstanceSettings.MAX_VALUE_LENGTH) : message);
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
            // If no repos are configured and auto-creation is disabled, mark as PENDING rather
            // than RUNNING→FAILED. The view hasn't had a chance to be configured yet.
            Collection<HelmRepoEntity> existingRepos = repositoryService.list();
            boolean hasRepos = existingRepos != null && !existingRepos.isEmpty();
            boolean autoCreate = Boolean.parseBoolean(
                    viewContext.getAmbariProperty(MONITORING_DEFAULT_REPO_AUTO_CREATE_PROP));
            if (!hasRepos && !autoCreate) {
                updateMonitoringBootstrapState("PENDING",
                        "No Helm repository configured — add one to enable monitoring bootstrap.");
                return;
            }
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
            String persistedUrl = settings().get("monitoring.prometheus.url");
            if (persistedUrl != null && !persistedUrl.isBlank()) {
                LOG.info("Monitoring discovery: using persisted Prometheus URL {}", persistedUrl);
                return new MonitoringInfo(ns, release, persistedUrl.trim());
            }
        } catch (Exception e) {
            LOG.warn("Monitoring discovery: failed to read persisted Prometheus URL: {}", e.getMessage());
        }

        // Any Prometheus run by the Prometheus operator, whatever namespace or release name it was installed under.
        // Not on OpenShift: its own monitoring stack is used there (see discoverAndHealMonitoringState).
        Optional<PrometheusDiscovery.Instance> instance = isOpenShift(client) ? Optional.empty()
                : PrometheusDiscovery.pick(listPrometheusInstances(), ns);
        if (instance.isPresent()) {
            PrometheusDiscovery.Instance found = instance.get();
            LOG.info("Monitoring discovery: found Prometheus {}/{} (url={}, ServiceMonitor labels={})",
                    found.namespace(), found.name(), found.url(), found.serviceMonitorLabels());
            return new MonitoringInfo(found.namespace(), found.release(), found.url(), found.serviceMonitorLabels(),
                    found.scrapeWarning(), true);
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
            return new MonitoringInfo(ns, release, url, Map.of("release", release), null, true);
        } catch (Exception e) {
            logDiscoveryFailure("Monitoring discovery", e);
            return null;
        }
    }

    /** The Services of a namespace, or none when they cannot be read (the caller then keeps its fallback). */
    private List<Service> servicesOrEmpty(String namespace) {
        try {
            return client.services().inNamespace(namespace).list().getItems();
        } catch (Exception e) {
            logDiscoveryFailure("list services in " + namespace, e);
            return List.of();
        }
    }

    /**
     * The Prometheus instances run by the Prometheus operator that this account can see, in any namespace.
     *
     * @return the instances; empty when the operator is not installed or nothing is readable
     */
    public List<PrometheusDiscovery.Instance> listPrometheusInstances() {
        if (client == null) {
            return List.of();
        }
        try {
            if (!crdExists(PROMETHEUS_CRD)) {
                return List.of();
            }
            var op = client.genericKubernetesResources(PROMETHEUS_RDC);
            List<GenericKubernetesResource> resources = namespaceScope.listAcrossNamespaces(
                    NamespaceScope.Resource.PROMETHEUSES,
                    () -> op.inAnyNamespace().list().getItems(),
                    namespace -> op.inNamespace(namespace).list().getItems());
            Map<String, List<Service>> servicesByNamespace = new HashMap<>();
            List<PrometheusDiscovery.Instance> instances = new ArrayList<>();
            for (GenericKubernetesResource resource : resources) {
                PrometheusDiscovery.fromResource(resource).ifPresent(found -> instances.add(
                        PrometheusDiscovery.withOwnService(found, resource,
                                servicesByNamespace.computeIfAbsent(found.namespace(), this::servicesOrEmpty))));
            }
            return instances;
        } catch (Exception e) {
            logDiscoveryFailure("listPrometheusInstances", e);
            return List.of();
        }
    }

    /**
     * Discover monitoring and self-heal a stale FAILED bootstrap state.
     * If the service is found healthy but the stored state is FAILED (e.g. because
     * a prior bootstrap timed out waiting for pods), update the state to COMPLETED.
     * This is the method the GET discovery endpoint should call — it never triggers installs.
     */
    public MonitoringInfo discoverAndHealMonitoringState() {
        // On OpenShift the view uses the built-in monitoring stack, so monitoring is always "present" —
        // heal any stale FAILED/PENDING state (e.g. left by a Trino/KEDA kube-prometheus-stack attempt)
        // to COMPLETED and never surface a private-stack bootstrap failure on the dashboard.
        if (isOpenShift(client)) {
            try {
                String storedState = settings().get("monitoring.bootstrap.state");
                if (!"COMPLETED".equals(storedState) && !"SKIPPED".equals(storedState)) {
                    updateMonitoringBootstrapState("COMPLETED", "Using OpenShift built-in monitoring (openshift-monitoring)");
                }
            } catch (Exception e) {
                LOG.warn("Could not heal OpenShift monitoring state: {}", e.getMessage());
            }
            // Surface the platform's built-in monitoring (openshift-monitoring + the Thanos querier) so the
            // "Discover monitoring stack" selector on step 3 offers it and the operator can reuse it rather
            // than being handed an empty picker. There is no kube-prometheus-stack Helm release to auto-fill,
            // but the OpenShift stack IS the monitoring here, so advertise it explicitly.
            return discoverOpenShiftMonitoring();
        }
        MonitoringInfo info = discoverMonitoringPrometheus();
        if (info != null) {
            try {
                String storedState = settings().get("monitoring.bootstrap.state");
                if (!"COMPLETED".equals(storedState)) {
                    LOG.info("Monitoring service found healthy; healing bootstrap state from '{}' to COMPLETED", storedState);
                    updateMonitoringBootstrapState("COMPLETED",
                            "Monitoring present: " + info.release() + " in " + info.namespace());
                }
            } catch (Exception e) {
                LOG.warn("Could not heal monitoring bootstrap state: {}", e.getMessage());
            }
        }
        return info;
    }

    /**
     * Whether OpenShift's user workload monitoring is on, i.e. whether ServiceMonitors in user projects are scraped
     * (cluster-monitoring-config, {@code enableUserWorkload}). Most project-limited accounts may not read it.
     *
     * @return TRUE or FALSE; FALSE when the setting was never made; {@code null} when it cannot be read
     */
    public Boolean userWorkloadMonitoringEnabled() {
        if (client == null || !isOpenShift(client)) {
            return null;
        }
        try {
            var cm = client.configMaps().inNamespace("openshift-monitoring").withName("cluster-monitoring-config").get();
            return cm == null || cm.getData() == null ? Boolean.FALSE : userWorkloadEnabled(cm.getData().get("config.yaml"));
        } catch (Exception e) {
            logDiscoveryFailure("Read cluster-monitoring-config", e);
            return null;
        }
    }

    /** {@code enableUserWorkload} from cluster-monitoring-config's config.yaml; FALSE when absent or unreadable. */
    static Boolean userWorkloadEnabled(String configYaml) {
        if (configYaml == null || configYaml.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            Map<?, ?> config = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(configYaml, Map.class);
            return config != null && Boolean.parseBoolean(String.valueOf(config.get("enableUserWorkload")));
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }

    private MonitoringInfo discoverOpenShiftMonitoring() {
        if (!isOpenShift(client)) return null;
        String url = viewContext.getAmbariProperty("openshift.thanos.url");
        if (url == null || url.isBlank()) url = OPENSHIFT_THANOS_URL_DEFAULT;
        return new MonitoringInfo("openshift-monitoring", "openshift-monitoring", url);
    }

    /**
     * Detect an existing KEDA operator anywhere on the cluster so the Trino deploy can reuse it
     * rather than installing a conflicting Helm KEDA. Detection is CRD/operator-based, not
     * release-name/namespace based, so it finds KEDA whatever namespace the initial operators put
     * it in ("keda", "keda-system", "openshift-keda" for the OpenShift Custom Metrics Autoscaler, …).
     *
     * Presence is keyed on the {@code scaledobjects.keda.sh} CRD being served (that is what actually
     * lets the chart's ScaledObject reconcile). When present, we best-effort locate the keda-operator
     * Deployment to report its namespace, the Helm release name (from the meta.helm.sh annotation, if
     * Helm-installed), and whether it came from OLM/OpenShift.
     *
     * @return discovery result; {@code present=false} when no KEDA CRD is served.
     */
    public KedaDiscoveryResponse discoverKeda() {
        checkConfiguration();
        boolean crdServed;
        try {
            crdServed = crdExists("scaledobjects.keda.sh");
        } catch (Exception e) {
            LOG.warn("KEDA discovery: CRD check failed: {}", e.getMessage());
            return new KedaDiscoveryResponse(false, null, null, "unknown",
                    "Could not query the cluster for the KEDA CRD: " + e.getMessage());
        }
        if (!crdServed) {
            return new KedaDiscoveryResponse(false, null, null, "unknown",
                    "No KEDA detected (scaledobjects.keda.sh CRD is not served).");
        }

        // CRD is served — locate the operator Deployment to report where it lives and how it was installed.
        String ns = null;
        String release = null;
        String source = "unknown";
        try {
            var deployments = client.apps().deployments().inAnyNamespace().list().getItems();
            var operator = deployments.stream()
                    .filter(d -> {
                        String n = d.getMetadata() != null ? d.getMetadata().getName() : null;
                        return n != null && (n.equals("keda-operator") || n.startsWith("keda-operator"));
                    })
                    .findFirst()
                    .orElse(null);
            if (operator != null && operator.getMetadata() != null) {
                ns = operator.getMetadata().getNamespace();
                var ann = operator.getMetadata().getAnnotations();
                var labels = operator.getMetadata().getLabels();
                if (ann != null && ann.get("meta.helm.sh/release-name") != null) {
                    release = ann.get("meta.helm.sh/release-name");
                    source = "helm";
                } else if ((ns != null && ns.startsWith("openshift-"))
                        || (labels != null && labels.containsKey("operators.coreos.com/openshift-custom-metrics-autoscaler-operator." + ns))
                        || (ann != null && ann.keySet().stream().anyMatch(k -> k.startsWith("operators.coreos.com/")))) {
                    source = "olm";
                }
            }
        } catch (Exception e) {
            LOG.warn("KEDA discovery: operator lookup failed (CRD is served, so KEDA is present): {}", e.getMessage());
        }

        String msg = "KEDA is present"
                + (ns != null ? " in namespace '" + ns + "'" : "")
                + ("olm".equals(source) ? " (OpenShift/OLM operator)" : "helm".equals(source) ? " (Helm release '" + release + "')" : "")
                + ".";
        return new KedaDiscoveryResponse(true, ns, release, source, msg);
    }

    /**
     * Ensure monitoring stack is installed; if not present, install using configured repo/chart.
     * @param repoIdOverride optional repoId to use for installation
     * @return discovered info after ensuring install.
     */
    public MonitoringInfo ensureMonitoringInstalled(String repoIdOverride) {
        return ensureMonitoringInstalled(repoIdOverride, false);
    }

    public MonitoringInfo ensureMonitoringInstalled(String repoIdOverride, boolean force) {
        MonitoringSettings settings = loadMonitoringSettings();
        MonitoringInfo info = discoverMonitoringPrometheus();
        if (info != null && !force) {
            LOG.info("Monitoring stack already present: release={}, namespace={}, url={}", info.release(), info.namespace(), info.url());
            return info;
        }
        if (info != null) {
            LOG.info("Force reinstall requested: monitoring stack present but will reinstall. release={}, namespace={}", info.release(), info.namespace());
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
        if (isOpenShift(client)) {
            // The view's OWN dashboard metrics come from OpenShift's built-in monitoring (Thanos). Never
            // install — nor report the failure of — a private kube-prometheus-stack for the view's
            // monitoring here, and never fall through to the install path below (that used to let a
            // Trino/KEDA kube-prometheus-stack dependency attempt flip this state to FAILED). A
            // kube-prometheus-stack needed by KEDA is installed separately by the deploy pipeline.
            LOG.info("OpenShift detected -> view monitoring uses the built-in stack; skipping private kube-prometheus-stack bootstrap.");
            MonitoringInfo osInfo = discoverOpenShiftMonitoring();
            return osInfo != null ? osInfo
                    : new MonitoringInfo("openshift-monitoring", "openshift-monitoring", OPENSHIFT_THANOS_URL_DEFAULT);
        }

        String ns = viewContext.getAmbariProperty("monitoring.namespace");
        if (ns == null || ns.isBlank()) ns = DEFAULT_MONITORING_NAMESPACE;
        String release = viewContext.getAmbariProperty("monitoring.release");
        if (release == null || release.isBlank()) release = DEFAULT_MONITORING_RELEASE;
        String repoId = repoIdOverride;
        if (repoId == null || repoId.isBlank()) {
            // Prefer persisted user choice if available
            try {
                String persistedRepoId = settings().get("monitoring.repoId");
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
                LOG.info("Monitoring service {} not found in namespace {}. Clearing cached monitoring settings to allow re-bootstrap.", promServiceName, ns);
                settings().remove("monitoring.prometheus.url");
                settings().remove("monitoring.repoId");
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

        if (force) {
            LOG.info("Force reinstall: uninstalling monitoring release={} in namespace={} before reinstall", release, ns);
            try {
                helmService.uninstall(ns, release, configurationService.getKubeconfigContents());
            } catch (Exception uninstEx) {
                LOG.warn("Pre-uninstall for force monitoring reinstall failed (ignored): {}", uninstEx.toString());
            }
            try {
                settings().remove("monitoring.prometheus.url");
                settings().remove("monitoring.repoId");
            } catch (Exception ignored) { /* best-effort */ }
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
            // Install without waiting — Helm submits manifests and returns immediately;
            // pods start asynchronously. We poll below for the service to appear.
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
                    false,  // wait=false: don't block until pods are ready — we poll below
                    false,
                    false
            );
        } catch (Exception e) {
            LOG.warn("Failed to bootstrap monitoring stack: {}", e.toString());
            return null;
        }
        try {
            settings().put("monitoring.repoId", repoId);
            if (promExposure != null && promExposure.url() != null && !promExposure.url().isBlank()) {
                settings().put("monitoring.prometheus.url", promExposure.url());
                LOG.info("Persisted Prometheus URL override for future discovery: {}", promExposure.url());
            }
        } catch (Exception ex) {
            LOG.warn("Failed to persist monitoring.repoId: {}", ex.toString());
        }
        // Poll for the Prometheus service to appear (pods take time to start after Helm returns).
        // State remains RUNNING during this window so the UI shows progress, not failure.
        MonitoringInfo discovered = null;
        int pollIntervalSec = 15;
        int maxAttempts = 40; // 40 × 15s = 10 minutes
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            discovered = discoverMonitoringPrometheus();
            if (discovered != null) break;
            LOG.info("Monitoring service not yet available after Helm install (attempt {}/{}), waiting {}s...",
                    attempt + 1, maxAttempts, pollIntervalSec);
            try { Thread.sleep(pollIntervalSec * 1000L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (discovered != null) {
            updateMonitoringBootstrapState("COMPLETED",
                    "Monitoring present: " + discovered.release() + " in " + discovered.namespace());
        } else {
            updateMonitoringBootstrapState("FAILED", "Monitoring service not found after 10 minutes — pods may still be starting");
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
    private record PrometheusServiceEndpoint(String namespace, String serviceName, int servicePort, String scheme) { }

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
            // Preserve the scheme: OpenShift Thanos/Prometheus services serve HTTPS (behind oauth-proxy),
            // vanilla kube-prometheus-stack serves HTTP. The API service-proxy path encodes this as
            // services/<scheme>:<name>:<port>/proxy.
            String scheme = "https".equalsIgnoreCase(parsedUri.getScheme()) ? "https" : "http";
            return new PrometheusServiceEndpoint(namespace, serviceName, servicePort, scheme);
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
                "/api/v1/namespaces/%s/services/%s:%s:%d/proxy",
                prometheusServiceEndpoint.namespace(),
                prometheusServiceEndpoint.scheme(),
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
            overrides.put("prometheus.ingress.paths", List.of("/"));
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
        overrides.put("prometheus.ingress.paths", List.of("/"));
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
     * Clears cached monitoring settings so that a subsequent bootstrap is forced.
     */
    public void resetMonitoringCache() {
        try {
            settings().remove("monitoring.prometheus.url");
            settings().remove("monitoring.repoId");
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
            } catch (TimeoutException te) {
                future.cancel(true); // interrupt the blocked fabric8 call to free the pool thread
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

    /**
     * Propagates all view properties prefixed with {@code k8sview.} into JVM system properties,
     * stripping the prefix before setting each one.
     *
     * @param context the Ambari view context supplying the properties to propagate
     */
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
    
    /**
     * Loads the {@code charts.json} catalog from the configured directory or from the classpath fallback.
     *
     * @return the raw JSON string of available charts
     * @throws IOException if the file cannot be found or read
     */
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
     * Install the view-wide outbound proxy (for internet Helm/Git repos), keeping the Kubernetes API
     * host DIRECT so cluster traffic never goes through the corporate proxy. Safe to call repeatedly.
     */
    public void applyProxySettings() {
        try {
            String apiHost = null;
            if (client != null && client.getConfiguration() != null && client.getConfiguration().getMasterUrl() != null) {
                try { apiHost = java.net.URI.create(client.getConfiguration().getMasterUrl()).getHost(); }
                catch (Exception ignored) { /* leave null */ }
            }
            getConfigurationService().buildProxySupport(apiHost).install();
        } catch (Exception e) {
            LOG.warn("Failed to apply outbound proxy settings: {}", e.toString());
        }
    }

    /**
     * Whether the connected account may create ClusterRoleBindings, i.e. grant cluster-wide roles such as
     * {@code cluster-monitoring-view}. Accounts limited to their own projects may not.
     */
    public boolean canBindClusterRoles() {
        return canI("create", "rbac.authorization.k8s.io", "clusterrolebindings", null, null, null);
    }

    /**
     * "Can I?" — run a SelfSubjectAccessReview (the API behind {@code oc auth can-i}) for the connected
     * credential. Returns true if the API server says the verb is allowed on the resource/namespace.
     * Never throws: a failure (or no client) is reported as "not allowed".
     *
     * @param subresource the subresource ({@code proxy} of {@code services}), or null
     * @param name        the object the check is about, or null for any object. A grant limited to named objects
     *                    (a Role with {@code resourceNames}, as the ClusterRole of each OpenShift SCC) is seen only
     *                    when the name is given.
     * @param namespace   the namespace, or null for cluster-scoped resources and cluster-wide grants
     */
    public boolean canI(String verb, String group, String resource, String subresource, String name, String namespace) {
        if (client == null) return false;
        try {
            io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview review =
                new io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder()
                    .withNewSpec()
                        .withNewResourceAttributes()
                            .withVerb(verb)
                            .withGroup(group == null ? "" : group)
                            .withResource(resource)
                            .withSubresource(subresource)
                            .withName(name)
                            .withNamespace(namespace)
                        .endResourceAttributes()
                    .endSpec()
                    .build();
            var result = client.authorization().v1().selfSubjectAccessReview().create(review);
            return result != null && result.getStatus() != null && Boolean.TRUE.equals(result.getStatus().getAllowed());
        } catch (Exception e) {
            LOG.warn("can-i {} {}/{}{}{} in ns={} failed: {}", verb, group, resource,
                    subresource != null ? "/" + subresource : "", name != null ? " " + name : "", namespace,
                    e.toString());
            return false;
        }
    }

    /** Public accessor: whether the connected cluster is OpenShift (API-group probe, cached). */
    /** Tests: ends the retry window after a failed OpenShift check. */
    void expireOpenShiftDetectionRetryForTest() {
        openShiftDetectionRetryAt.set(0);
    }

    public boolean isOpenShiftCluster() {
        return client != null && isOpenShift(client);
    }

    private boolean isOpenShift(KubernetesClient client) {
        // Detect OpenShift by probing for an OpenShift-only API group (e.g. route.openshift.io).
        // NOTE: isAdaptable(OpenShiftClient.class) is NOT used — only openshift-client-api (interfaces)
        // is on the classpath, not the openshift-client impl that registers the adapter, so that call
        // always throws "No adapter available". The API-group probe needs no extra dependency and works
        // over the same authenticated/trusted connection.
        Boolean cached = openShiftDetectionCache.get();
        if (cached != null) {
            return cached;
        }
        // A recent failure: answer "not OpenShift" again without another request until the retry window ends.
        if (System.currentTimeMillis() < openShiftDetectionRetryAt.get()) {
            return false;
        }
        boolean result = false;
        try {
            io.fabric8.kubernetes.api.model.APIGroupList apiGroups = client.getApiGroups();
            if (apiGroups != null && apiGroups.getGroups() != null) {
                for (io.fabric8.kubernetes.api.model.APIGroup group : apiGroups.getGroups()) {
                    String name = group.getName();
                    if (name != null && name.endsWith("openshift.io")) {
                        result = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Not remembered as an answer: a transient failure (expired token, network) must not make every later
            // request treat an OpenShift cluster as plain Kubernetes. Asked again after a short window, so a cluster
            // that keeps failing is not queried (and logged) on every request.
            openShiftDetectionRetryAt.set(System.currentTimeMillis() + OPENSHIFT_DETECTION_RETRY_MS);
            LOG.warn("OpenShift detection via API groups failed (treating as vanilla k8s for {} s): {}",
                    OPENSHIFT_DETECTION_RETRY_MS / 1000, e.toString());
            return false;
        }
        LOG.info("OpenShift detection: cluster {} OpenShift (*.openshift.io API group present={}).",
                result ? "IS" : "is NOT", result);
        openShiftDetectionCache.compareAndSet(null, result);
        return result;
    }

    /** What this account may list, and the namespace-by-namespace fallback when it may not list the cluster. */
    public NamespaceScope namespaceScope() {
        return namespaceScope;
    }

    /**
     * Namespaces this account can work in, with lightweight metadata (see {@link NamespaceScope#listNamespaces()}).
     *
     * @return namespace descriptions
     */
    public List<KubeNamespace> listNamespaces() {
        checkConfiguration();
        return namespaceScope.listNamespaces();
    }

    /** Namespaces KDPS can name without listing: the kubeconfig's namespace and those of recorded releases. */
    private Collection<String> knownNamespaceNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        KubernetesClient c = this.client;
        if (c != null && c.getNamespace() != null && !c.getNamespace().isBlank()) {
            names.add(c.getNamespace());
        }
        if (viewContext != null) {
            try {
                for (var rel : new ReleaseMetadataService(viewContext).findAll()) {
                    if (rel.getNamespace() != null && !rel.getNamespace().isBlank()) {
                        names.add(rel.getNamespace());
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not read recorded releases for the namespace list: {}", e.toString());
            }
        }
        return names;
    }

    /**
     * Send a single PromQL query, applying the kubeconfig credential first. If the backend rejects it
     * with 401/403 on an OpenShift target (e.g. the kubeconfig token lacks cluster-monitoring-view, or
     * there was no token at all), retry ONCE with a freshly minted/renewed cluster-monitoring-view token.
     * This keeps working kubeconfigs untouched while auto-recovering for cert-only or under-privileged ones.
     */
    private HttpResponse<String> runMonitoringQuery(HttpClient http, String promUrl, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create(promUrl + "/api/v1/query?query=" + encoded);
        HttpRequest.Builder builder = HttpRequest.newBuilder().GET().uri(uri).timeout(Duration.ofSeconds(8));
        applyPrometheusAuth(builder);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && isOpenShift(client) && monitoringAutoTokenEnabled()) {
            String mintedToken = getMonitoringBearerToken();
            if (mintedToken != null && !mintedToken.isBlank()) {
                LOG.info("Monitoring query returned {} with the kubeconfig credential; retrying with a minted "
                        + "cluster-monitoring-view token.", response.statusCode());
                HttpRequest retry = HttpRequest.newBuilder().GET().uri(uri).timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + mintedToken)
                        .build();
                response = http.send(retry, HttpResponse.BodyHandlers.ofString());
            }
        }
        return response;
    }

    /**
     * Query Prometheus (or Thanos) for cluster CPU/memory usage.
     * @return double[]{cpuCoresUsed, memGiBUsed} or null on failure.
     */
    private double[] queryPrometheusClusterUsage(String promUrl) {
        try {
            HttpClient prometheusHttpClient = buildPrometheusHttpClient(promUrl);
            HttpResponse<String> cpuResponse = runMonitoringQuery(prometheusHttpClient, promUrl,
                    "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[2m]))");
            HttpResponse<String> memoryResponse = runMonitoringQuery(prometheusHttpClient, promUrl,
                    "sum(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes)");
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
            HttpResponse<String> response = runMonitoringQuery(http, promUrl, query);
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
     * Return a cached {@link HttpClient} for the given Prometheus URL, building one on first use.
     * The cache is keyed by promUrl so that clients configured for different endpoints (e.g. direct
     * vs. k8s API proxy) are kept separate. The cache is invalidated whenever the underlying
     * Kubernetes client is reloaded (which may bring new TLS material).
     */
    private HttpClient buildPrometheusHttpClient(String promUrl) throws Exception {
        HttpClient cached = prometheusClientCache.get(promUrl);
        if (cached != null) {
            return cached;
        }
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
        HttpClient built = builder.build();
        // putIfAbsent is atomic: if another thread raced us, we discard our copy and use theirs.
        prometheusClientCache.putIfAbsent(promUrl, built);
        return prometheusClientCache.get(promUrl);
    }

    /**
     * Return a valid bearer token for querying OpenShift cluster monitoring (Thanos), minting a fresh
     * one via the TokenRequest API when the cache is empty or within the renewal buffer of expiry.
     * Thread-safe: concurrent callers share a single mint under {@link #monitoringTokenMintLock}.
     *
     * @return a currently-valid bearer token, or {@code null} if one could not be obtained
     */
    private boolean monitoringAutoTokenEnabled() {
        String value = viewContext.getAmbariProperty("openshift.monitoring.autoToken");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);  // default: enabled
    }

    private String getMonitoringBearerToken() {
        MintedToken cached = monitoringTokenCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs - now > MONITORING_TOKEN_RENEW_BUFFER_MS) {
            return cached.token;
        }
        synchronized (monitoringTokenMintLock) {
            // Re-check under the lock: another thread may have just minted one.
            cached = monitoringTokenCache.get();
            now = System.currentTimeMillis();
            if (cached != null && cached.expiresAtMs - now > MONITORING_TOKEN_RENEW_BUFFER_MS) {
                return cached.token;
            }
            try {
                MintedToken fresh = mintMonitoringToken();
                if (fresh != null && fresh.token != null && !fresh.token.isBlank()) {
                    monitoringTokenCache.set(fresh);
                    return fresh.token;
                }
            } catch (Exception e) {
                LOG.warn("Failed to mint OpenShift monitoring token: {}", e.toString());
            }
            return null;
        }
    }

    /**
     * Resolve the monitoring service account (namespace/name), ensure it exists and is bound to
     * cluster-monitoring-view, then mint a short-lived token for it via the TokenRequest API.
     *
     * @return the minted token plus its expiry, or {@code null} on failure
     */
    private MintedToken mintMonitoringToken() {
        String saRef = viewContext.getAmbariProperty("openshift.monitoring.serviceaccount");
        if (saRef == null || saRef.isBlank()) {
            saRef = "default/" + MONITORING_VIEWER_CRB_NAME;
        }
        String[] parts = saRef.split("/", 2);
        String saNamespace = parts.length == 2 ? parts[0].trim() : "default";
        String saName = parts.length == 2 ? parts[1].trim() : parts[0].trim();

        ensureMonitoringServiceAccount(saNamespace, saName);

        TokenRequest request = new TokenRequestBuilder()
                .withNewSpec()
                .withExpirationSeconds(MONITORING_TOKEN_TTL_SECONDS)
                .endSpec()
                .build();
        TokenRequest response = client.serviceAccounts()
                .inNamespace(saNamespace)
                .withName(saName)
                .tokenRequest(request);
        if (response == null || response.getStatus() == null || response.getStatus().getToken() == null) {
            LOG.warn("TokenRequest for {}/{} returned no token", saNamespace, saName);
            return null;
        }
        String token = response.getStatus().getToken();
        long expiresAtMs = System.currentTimeMillis() + (MONITORING_TOKEN_TTL_SECONDS * 1000L);
        String expTs = response.getStatus().getExpirationTimestamp();
        if (expTs != null && !expTs.isBlank()) {
            try {
                expiresAtMs = Instant.parse(expTs).toEpochMilli();
            } catch (Exception parseEx) {
                LOG.debug("Could not parse token expirationTimestamp '{}', using TTL default: {}", expTs, parseEx.toString());
            }
        }
        LOG.info("Minted OpenShift monitoring token for service account {}/{} (expires {}).",
                saNamespace, saName, expTs != null ? expTs : "in " + MONITORING_TOKEN_TTL_SECONDS + "s");
        return new MintedToken(token, expiresAtMs);
    }

    /**
     * Best-effort: create the monitoring service account and its cluster-monitoring-view
     * ClusterRoleBinding if they do not already exist. Requires the kubeconfig identity to have
     * permission to create these objects (cluster-admin does); logged and ignored otherwise.
     */
    private void ensureMonitoringServiceAccount(String namespace, String name) {
        try {
            ServiceAccount existing = client.serviceAccounts().inNamespace(namespace).withName(name).get();
            if (existing == null) {
                client.serviceAccounts().inNamespace(namespace).resource(
                        new ServiceAccountBuilder()
                                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                                .build()
                ).create();
                LOG.info("Created monitoring service account {}/{}", namespace, name);
            }
        } catch (Exception e) {
            LOG.warn("Could not ensure monitoring service account {}/{} (may already exist or lack RBAC): {}",
                    namespace, name, e.toString());
        }
        try {
            ClusterRoleBinding crb = client.rbac().clusterRoleBindings().withName(MONITORING_VIEWER_CRB_NAME).get();
            if (crb == null) {
                client.rbac().clusterRoleBindings().resource(
                        new ClusterRoleBindingBuilder()
                                .withNewMetadata().withName(MONITORING_VIEWER_CRB_NAME).endMetadata()
                                .withNewRoleRef()
                                    .withApiGroup("rbac.authorization.k8s.io")
                                    .withKind("ClusterRole")
                                    .withName(MONITORING_VIEWER_CLUSTER_ROLE)
                                .endRoleRef()
                                .addNewSubject()
                                    .withKind("ServiceAccount")
                                    .withName(name)
                                    .withNamespace(namespace)
                                .endSubject()
                                .build()
                ).create();
                LOG.info("Bound {} to {}/{} via ClusterRoleBinding {}",
                        MONITORING_VIEWER_CLUSTER_ROLE, namespace, name, MONITORING_VIEWER_CRB_NAME);
            }
        } catch (Exception e) {
            LOG.warn("Could not ensure ClusterRoleBinding {} -> {} for {}/{} (may already exist or lack RBAC): {}",
                    MONITORING_VIEWER_CRB_NAME, MONITORING_VIEWER_CLUSTER_ROLE, namespace, name, e.toString());
        }
    }

    /**
     * KEDA/Thanos autoscaling on OpenShift needs a bearer token to read the platform's built-in
     * (user-workload) monitoring via the thanos-querier tenancy endpoint. The chart's KEDA
     * TriggerAuthentication reads that token from a Secret in the release namespace. This ensures the
     * whole chain exists — a ServiceAccount bound (cluster-scoped) to {@code cluster-monitoring-view}
     * plus a long-lived {@code kubernetes.io/service-account-token} Secret the platform keeps populated
     * (unlike a TokenRequest token, which would expire out from under a long-running ScaledObject).
     *
     * <p>"Detect, else request": when the connected credential is allowed to create the objects they
     * are created idempotently and this returns {@code null} (deploy may proceed). When it is not — or
     * the monitoring stack (and thus {@code cluster-monitoring-view}) is absent — this returns a
     * human-readable message describing exactly what a cluster admin must create first, so the caller
     * can block the deploy with actionable guidance instead of shipping a broken autoscaler.
     *
     * <p>With {@code projectScoped} the triggers use the Thanos tenancy port (see {@link KedaThanosScope}): the
     * token then needs no ClusterRoleBinding, only the namespaced Role allowing it to read the project's pods.
     *
     * @return {@code null} when the token Secret is present/created; otherwise an operator instruction.
     */
    public String ensureKedaThanosTokenSecret(String namespace, String secretName, String saName, boolean projectScoped) {
        if (client == null) return "No cluster connection.";
        if (namespace == null || namespace.isBlank() || secretName == null || secretName.isBlank()) {
            return "Namespace and secret name are required for the KEDA/Thanos monitoring token.";
        }
        if (saName == null || saName.isBlank()) saName = secretName;

        // Already provisioned? A populated (or freshly-created) service-account-token Secret is enough, once the
        // tenancy Role is current: a release deployed before that Role gained the pod-metrics rule keeps failing
        // its queries with 403 until it is updated.
        try {
            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (existing != null) {
                boolean hasToken = existing.getData() != null && existing.getData().containsKey("token");
                if (hasToken || "kubernetes.io/service-account-token".equals(existing.getType())) {
                    try {
                        ensureKedaTenancyRole(namespace, saName + "-thanos-tenancy");
                    } catch (Exception e) {
                        // Without the pod-metrics rule the autoscaler's queries are refused (403): stop with what to
                        // run rather than deploy an autoscaler that never scales.
                        LOG.warn("Could not bring the KEDA/Thanos tenancy Role {}/{}-thanos-tenancy up to date: {}",
                                namespace, saName, e.toString());
                        return "The autoscaler's monitoring token cannot read this project's pod metrics, and this account "
                                + "could not update its Role (" + e.getMessage() + "). Ask an administrator of project "
                                + namespace + " to run:\n"
                                + "  oc -n " + namespace + " create role " + saName + "-thanos-tenancy --verb=get,list,watch "
                                + "--resource=pods,pods.metrics.k8s.io --dry-run=client -o yaml | oc apply -f -";
                    }
                    // A release first deployed by a project-limited account has no cluster binding; when an account
                    // allowed to create one deploys it with cluster-port triggers, add the binding.
                    try {
                        if (!projectScoped && canBindClusterRoles()) {
                            ensureMonitoringViewBinding(namespace, saName, "kdps-monview-" + namespace + "-" + saName);
                        }
                    } catch (Exception e) {
                        LOG.warn("Could not bind cluster-monitoring-view for the KEDA/Thanos token {}/{}: {}",
                                namespace, saName, e.toString());
                    }
                    return null;
                }
            }
        } catch (Exception ignore) { /* fall through to (re)create */ }

        // cluster-monitoring-view is created by the monitoring operator; if it is absent, monitoring is
        // disabled and Thanos autoscaling cannot work regardless of RBAC. Project-scoped tokens do not use it, and
        // a project-limited account may not even read ClusterRoles, so the check only applies to the cluster port.
        boolean roleExists = projectScoped;
        if (!projectScoped) {
            try { roleExists = client.rbac().clusterRoles().withName(MONITORING_VIEWER_CLUSTER_ROLE).get() != null; }
            catch (Exception ignore) { }
        }
        if (!roleExists) {
            return "Cluster monitoring is not enabled on this cluster (ClusterRole '" + MONITORING_VIEWER_CLUSTER_ROLE
                 + "' not found). Enable the built-in monitoring stack and user-workload monitoring, then retry.";
        }

        boolean canSa  = canI("create", "", "serviceaccounts", null, null, namespace);
        boolean canSec = canI("create", "", "secrets", null, null, namespace);
        boolean canCrb = projectScoped || canBindClusterRoles();
        boolean canRole = canI("create", "rbac.authorization.k8s.io", "roles", null, null, namespace);
        boolean canRb  = canI("create", "rbac.authorization.k8s.io", "rolebindings", null, null, namespace);
        String crbName = "kdps-monview-" + namespace + "-" + saName;
        String tenancyRoleName = saName + "-thanos-tenancy";
        if (!(canSa && canSec && canCrb && canRole && canRb)) {
            StringBuilder missing = new StringBuilder();
            if (!canSa)   missing.append("create serviceaccounts in ").append(namespace).append("; ");
            if (!canSec)  missing.append("create secrets in ").append(namespace).append("; ");
            if (!canCrb)  missing.append("create clusterrolebindings (cluster-scoped); ");
            if (!canRole) missing.append("create roles in ").append(namespace).append("; ");
            if (!canRb)   missing.append("create rolebindings in ").append(namespace).append("; ");
            return "The view's account cannot provision the KEDA/Thanos monitoring token (missing: "
                 + missing.toString().trim()
                 + "). Ask a cluster administrator to run, before deploying:\n"
                 + "  oc -n " + namespace + " create serviceaccount " + saName + "\n"
                 + (projectScoped ? "" : "  oc create clusterrolebinding " + crbName
                     + " --clusterrole=" + MONITORING_VIEWER_CLUSTER_ROLE
                     + " --serviceaccount=" + namespace + ":" + saName + "\n")
                 // The thanos-querier tenancy endpoint (:9092) authorizes each query with a
                 // `get pods` SubjectAccessReview in the tenant namespace; cluster-monitoring-view
                 // grants only `get namespaces`, so a namespaced pods-read binding is also required.
                 + "  oc -n " + namespace + " create role " + tenancyRoleName + " --verb=get,list,watch --resource=pods,pods.metrics.k8s.io\n"
                 + "  oc -n " + namespace + " create rolebinding " + tenancyRoleName
                 + " --role=" + tenancyRoleName + " --serviceaccount=" + namespace + ":" + saName + "\n"
                 + "  oc -n " + namespace + " apply -f - <<'EOF'\n"
                 + "  apiVersion: v1\n  kind: Secret\n  metadata:\n    name: " + secretName + "\n"
                 + "    annotations: { kubernetes.io/service-account.name: " + saName + " }\n"
                 + "  type: kubernetes.io/service-account-token\n  EOF";
        }

        // We hold the rights — create everything idempotently.
        try {
            if (client.serviceAccounts().inNamespace(namespace).withName(saName).get() == null) {
                client.serviceAccounts().inNamespace(namespace).resource(
                        new ServiceAccountBuilder()
                                .withNewMetadata().withName(saName).withNamespace(namespace).endMetadata()
                                .build()).create();
                LOG.info("Created KEDA monitoring service account {}/{}", namespace, saName);
            }
            if (!projectScoped) {
                ensureMonitoringViewBinding(namespace, saName, crbName);
            }
            ensureKedaTenancyRole(namespace, tenancyRoleName);
            if (client.rbac().roleBindings().inNamespace(namespace).withName(tenancyRoleName).get() == null) {
                client.rbac().roleBindings().inNamespace(namespace).resource(
                        new RoleBindingBuilder()
                                .withNewMetadata().withName(tenancyRoleName).withNamespace(namespace).endMetadata()
                                .withNewRoleRef()
                                    .withApiGroup("rbac.authorization.k8s.io")
                                    .withKind("Role")
                                    .withName(tenancyRoleName)
                                .endRoleRef()
                                .addNewSubject()
                                    .withKind("ServiceAccount").withName(saName).withNamespace(namespace)
                                .endSubject()
                                .build()).create();
                LOG.info("Bound {}/{} to tenancy Role {} via RoleBinding", namespace, saName, tenancyRoleName);
            }
            if (client.secrets().inNamespace(namespace).withName(secretName).get() == null) {
                Map<String, String> ann = new LinkedHashMap<>();
                ann.put("kubernetes.io/service-account.name", saName);
                client.secrets().inNamespace(namespace).resource(
                        new SecretBuilder()
                                .withNewMetadata().withName(secretName).withNamespace(namespace)
                                    .withAnnotations(ann)
                                .endMetadata()
                                .withType("kubernetes.io/service-account-token")
                                .build()).create();
                LOG.info("Created KEDA/Thanos service-account-token Secret {}/{}", namespace, secretName);
            }
            return null;
        } catch (Exception e) {
            return "Failed to provision the KEDA/Thanos monitoring token in namespace '" + namespace + "': " + e.getMessage();
        }
    }

    /** Binds the KEDA monitoring service account to {@code cluster-monitoring-view} unless it already is. */
    private void ensureMonitoringViewBinding(String namespace, String saName, String crbName) {
        if (client.rbac().clusterRoleBindings().withName(crbName).get() != null) {
            return;
        }
        client.rbac().clusterRoleBindings().resource(
                new ClusterRoleBindingBuilder()
                        .withNewMetadata().withName(crbName).endMetadata()
                        .withNewRoleRef()
                            .withApiGroup("rbac.authorization.k8s.io")
                            .withKind("ClusterRole")
                            .withName(MONITORING_VIEWER_CLUSTER_ROLE)
                        .endRoleRef()
                        .addNewSubject()
                            .withKind("ServiceAccount").withName(saName).withNamespace(namespace)
                        .endSubject()
                        .build()).create();
        LOG.info("Bound {}/{} to {} via ClusterRoleBinding {}", namespace, saName, MONITORING_VIEWER_CLUSTER_ROLE, crbName);
    }

    /**
     * Creates, or brings up to date, the Role that lets the KEDA monitoring token query the Thanos tenancy port
     * for {@code namespace}: read pods and pod metrics.
     */
    private void ensureKedaTenancyRole(String namespace, String tenancyRoleName) {
        Role desiredTenancyRole = new RoleBuilder()
                .withNewMetadata().withName(tenancyRoleName).withNamespace(namespace).endMetadata()
                .addNewRule()
                    .withApiGroups("")
                    .withResources("pods")
                    .withVerbs("get", "list", "watch")
                .endRule()
                .addNewRule()
                    .withApiGroups("metrics.k8s.io")
                    .withResources("pods")
                    .withVerbs("get", "list", "watch")
                .endRule()
                .build();
        Role existingTenancyRole = client.rbac().roles().inNamespace(namespace).withName(tenancyRoleName).get();
        if (existingTenancyRole == null) {
            client.rbac().roles().inNamespace(namespace).resource(desiredTenancyRole).create();
            LOG.info("Created KEDA/Thanos tenancy Role {}/{} (pods and pod metrics read)", namespace, tenancyRoleName);
        } else if (!desiredTenancyRole.getRules().equals(existingTenancyRole.getRules())) {
            client.rbac().roles().inNamespace(namespace).withName(tenancyRoleName)
                    .edit(r -> new RoleBuilder(r).withRules(desiredTenancyRole.getRules()).build());
            LOG.info("Updated KEDA/Thanos tenancy Role {}/{} to read pod metrics", namespace, tenancyRoleName);
        }
    }

    /** Labels of the RoleBindings that grant the service accounts of one release an OpenShift SCC. */
    static final String SCC_LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    static final String SCC_MANAGED_BY_KDPS = "ambari-kdps";
    static final String SCC_LABEL_RELEASE = "ambari.clemlab.com/release";
    static final String SCC_LABEL_SCC = "ambari.clemlab.com/scc";

    /**
     * The SCCs the KDPS services ask for in their {@code openshiftScc}. The one-time grant that lets the account grant
     * SCCs itself names all of them, so the next service that needs one does not stop on the same missing right.
     */
    public static final List<String> SERVICE_SCCS = List.of("anyuid", "nonroot-v2");

    /** The ClusterRole a platform team creates so that the KDPS account may grant those SCCs in a project. */
    static final String SCC_GRANT_ROLE = "kdps-grant-scc";

    private static final String RBAC_API_GROUP = "rbac.authorization.k8s.io";
    private static final String SCC_API_GROUP = "security.openshift.io";

    /**
     * OpenShift's PodSecurityPolicySubjectReview: create-only, and its model class has no metadata, so it is sent as a
     * generic resource.
     */
    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext SCC_SUBJECT_REVIEW =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup(SCC_API_GROUP)
                    .withVersion("v1")
                    .withKind("PodSecurityPolicySubjectReview")
                    .withPlural("podsecuritypolicysubjectreviews")
                    .withNamespaced(true)
                    .build();

    /**
     * The non-root UID the probe pod of a PodSecurityPolicySubjectReview runs as. No project UID range contains it, so
     * restricted-v2, which every service account may use, refuses the probe.
     */
    private static final long SCC_PROBE_UID = 1000L;

    /**
     * The security context of the probe pod for the SCCs the services ask for. The review names only the one SCC
     * admission picks (the highest priority, then the most restrictive), so each probe is one that SCC admits and that
     * the other built-in SCCs a service account may also hold, picked before it, refuse:
     * <ul>
     *   <li>anyuid: root, which nonroot-v2 and restricted-v2 refuse;</li>
     *   <li>nonroot-v2: a non-root UID with the RuntimeDefault seccomp profile, which anyuid refuses although its
     *       priority puts it first, as it allows no seccomp profile. JupyterHub's {@code default} service account
     *       holds both.</li>
     * </ul>
     */
    private static final Map<String, Map<String, Object>> SCC_PROBE_SECURITY_CONTEXTS = Map.of(
            "anyuid", Map.of("runAsUser", 0L),
            "nonroot-v2", Map.of(
                    "runAsUser", SCC_PROBE_UID,
                    "allowPrivilegeEscalation", false,
                    "capabilities", Map.of("drop", List.of("ALL")),
                    "seccompProfile", Map.of("type", "RuntimeDefault")));

    /** An SCC a release needs, and the service accounts of the release that need it. */
    record SccNeed(String scc, List<String> serviceAccounts) { }

    /**
     * A RoleBinding of the project that grants an SCC: one KDPS made for a release ({@code release} set), the shared one
     * of earlier KDPS versions, or one made outside KDPS such as by {@code oc adm policy add-scc-to-user}.
     *
     * @param wholeProject whether it grants every service account of the project, through a group
     */
    private record SccBinding(String name, String release, String scc, Set<String> serviceAccounts,
                              boolean wholeProject) {
        boolean covers(String sa) {
            return wholeProject || serviceAccounts.contains(sa);
        }

        boolean ofRelease(String releaseName) {
            return release != null && release.equals(releaseName);
        }

        /** The shared {@code kdps-scc-<scc>} binding earlier versions made for every release of the project. */
        boolean legacy() {
            return release == null && name.equals(LEGACY_SCC_BINDING_PREFIX + scc);
        }

        /** Whether KDPS made it, for a release or, in earlier versions, for the whole project. */
        boolean byKdps() {
            return release != null || legacy();
        }
    }

    private static final String LEGACY_SCC_BINDING_PREFIX = "kdps-scc-";

    /**
     * The SCCs a service declares ({@code openshiftScc}), merged by SCC, with {@code {{releaseName}}} expanded in the
     * service account names.
     *
     * <p>Deliberately PER-SERVICEACCOUNT, never a namespace-group grant. Granting {@code anyuid} to
     * {@code system:serviceaccounts:<ns>} also makes SAs that rely on the platform injecting a
     * namespace-range UID (e.g. the OpenMetadata server, which sets {@code runAsNonRoot} with no
     * UID) prefer {@code anyuid} — which injects nothing, so the image runs as root and the pod
     * fails "container has runAsNonRoot and image will run as root". An entry without service
     * accounts is therefore skipped.
     */
    static List<SccNeed> sccNeeds(String releaseName, List<Map<String, Object>> sccGrants) {
        Map<String, Set<String>> bySccName = new LinkedHashMap<>();
        String rel = releaseName == null ? "" : releaseName;
        if (sccGrants != null) {
            for (Map<String, Object> grant : sccGrants) {
                if (grant == null) continue;
                Object sccObj = grant.get("scc");
                String scc = sccObj == null ? "" : sccObj.toString().trim();
                if (scc.isBlank()) continue;
                Set<String> sas = new LinkedHashSet<>();
                if (grant.get("serviceAccounts") instanceof List<?> declared) {
                    for (Object o : declared) {
                        if (o == null) continue;
                        String sa = o.toString().replace("{{releaseName}}", rel).replace("{{release}}", rel).trim();
                        if (!sa.isBlank()) sas.add(sa);
                    }
                }
                if (sas.isEmpty()) {
                    LOG.warn("openshiftScc grant for '{}' declares no serviceAccounts; skipping (refusing a "
                            + "namespace-wide grant)", scc);
                    continue;
                }
                bySccName.computeIfAbsent(scc, k -> new LinkedHashSet<>()).addAll(sas);
            }
        }
        List<SccNeed> needs = new ArrayList<>();
        bySccName.forEach((scc, sas) -> needs.add(new SccNeed(scc, List.copyOf(sas))));
        return needs;
    }

    /** The ClusterRole OpenShift defines for each SCC, whose only rule is {@code use} on that SCC. */
    static String sccClusterRole(String scc) {
        return "system:openshift:scc:" + scc;
    }

    /**
     * Name of the RoleBinding that grants {@code scc} to the service accounts of {@code releaseName}:
     * {@code kdps-scc-<scc>-<release>}, shortened to 63 characters with a hash of the full name when longer. One per
     * release, so that deploying or removing one release never changes the grants of another.
     */
    static String sccBindingName(String scc, String releaseName) {
        String name = "kdps-scc-" + scc + "-" + releaseName;
        if (name.length() <= 63) return name;
        String hash;
        try {
            hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        String head = name.substring(0, 63 - hash.length() - 1).replaceAll("[-.]+$", "");
        return head + "-" + hash;
    }

    /**
     * Direct deploy, OpenShift SCC preflight: detect, then grant when this account may, else stop with what the
     * platform team can do. Pods that pin a UID/GID or run as root (bundled OpenSearch, Airflow, the JupyterHub
     * Kerberos sidecar) are admitted only when their service account may use the SCC they need.
     *
     * <ol>
     *   <li><b>Detect.</b> Each service account that may already use its SCC needs nothing: when all of them may, no
     *       object is written. That covers {@code oc adm policy add-scc-to-user}, so a deploy blocked once goes
     *       through after the platform team ran the printed command.</li>
     *   <li><b>Grant</b> only when this account may (see {@link #canGrantScc}): the release's own RoleBinding
     *       ({@link #sccBindingName}) is created, or gets the service accounts it lacks. Nothing is ever deleted or
     *       removed from a binding, so a write that fails leaves every existing grant in place. The shared
     *       {@code kdps-scc-<scc>} binding of earlier versions is left untouched.</li>
     *   <li><b>Block</b> otherwise, with both remedies: the platform team grants the SCC to the service accounts, or
     *       lets this account grant SCCs in the project.</li>
     * </ol>
     *
     * <p>A service account that may use its SCC only through a binding KDPS made for another release, or the shared
     * binding of earlier versions, also gets this release's binding when this account may grant it, so that it does not
     * depend on a grant made for someone else. When this account may not, the deploy goes on (its pods are admitted
     * today) with a warning naming that binding and the platform team's command.
     *
     * @param sccGrants the service's {@code openshiftScc} declarations
     * @param warnings  receives what the operator should know although the deploy may go on
     * @return {@code null} when every service account may use its SCCs (or off OpenShift); otherwise an actionable
     *         message
     */
    public String ensureSccGrants(String namespace, String releaseName, List<Map<String, Object>> sccGrants,
                                  List<String> warnings) {
        return reconcileSccGrants(namespace, releaseName, sccGrants, true, warnings);
    }

    /**
     * GitOps deploy, OpenShift SCC preflight: the detection of {@link #ensureSccGrants} without the grant. What runs
     * in the project comes from Git, so nothing is granted from here; a missing grant stops the deploy with the
     * command for the platform team, before anything is committed.
     *
     * @param warnings receives what the operator should know although the deploy may go on
     * @return {@code null} when every service account may use its SCCs (or off OpenShift); otherwise an actionable
     *         message
     */
    public String checkSccGrants(String namespace, String releaseName, List<Map<String, Object>> sccGrants,
                                 List<String> warnings) {
        return reconcileSccGrants(namespace, releaseName, sccGrants, false, warnings);
    }

    private String reconcileSccGrants(String namespace, String releaseName, List<Map<String, Object>> sccGrants,
                                      boolean mayGrant, List<String> warnings) {
        if (client == null) return "No cluster connection.";
        if (!isOpenShiftCluster()) return null;              // no-op on vanilla Kubernetes
        if (namespace == null || namespace.isBlank()) return null;
        List<SccNeed> needs = sccNeeds(releaseName, sccGrants);
        if (needs.isEmpty()) return null;
        if (releaseName == null || releaseName.isBlank()) {
            return "A release name is required to check the OpenShift security context constraints of its "
                    + "service accounts.";
        }

        List<SccBinding> bindings = sccBindings(namespace);
        Map<String, List<String>> unresolved = new LinkedHashMap<>();
        boolean unchecked = false;
        for (SccNeed need : needs) {
            List<String> missing = new ArrayList<>();
            List<String> borrowed = new ArrayList<>();
            Set<String> lenders = new LinkedHashSet<>();
            for (String sa : need.serviceAccounts()) {
                Boolean mayUse = serviceAccountMayUseScc(namespace, sa, need.scc());
                if (mayUse == null) unchecked = true;
                if (!Boolean.TRUE.equals(mayUse)) {
                    missing.add(sa);
                    continue;
                }
                // Held only through what KDPS made for someone else: gone when that release is uninstalled, or when
                // the platform team deletes the shared binding of earlier versions.
                List<SccBinding> grantedBy = bindings.stream()
                        .filter(b -> b.scc().equals(need.scc()) && b.covers(sa)).collect(Collectors.toList());
                boolean ownOrOutsideKdps = grantedBy.stream().anyMatch(b -> b.ofRelease(releaseName) || !b.byKdps());
                if (!grantedBy.isEmpty() && !ownOrOutsideKdps) {
                    borrowed.add(sa);
                    grantedBy.forEach(b -> lenders.add(b.legacy()
                            ? b.name() + " (made by an earlier KDPS version for the whole project)"
                            : b.name() + " (made by KDPS for release " + b.release() + ")"));
                }
            }
            if (missing.isEmpty() && borrowed.isEmpty()) {
                LOG.info("Service accounts {} may already use SCC '{}' in {}: nothing to grant for release {}",
                        need.serviceAccounts(), need.scc(), namespace, releaseName);
                continue;
            }
            if (mayGrant && canGrantScc(namespace, need.scc())) {
                try {
                    grantScc(namespace, releaseName, need.scc(), need.serviceAccounts());
                    continue;
                } catch (Exception e) {
                    // Typically RBAC escalation prevention despite the access review; nothing was removed.
                    LOG.warn("Could not grant SCC '{}' in {}: {}", need.scc(), namespace, e.getMessage());
                }
            }
            if (missing.isEmpty()) {
                // Admitted today, so not a reason to stop; but the operator should know what the grant hangs on.
                String warning = "In project " + namespace + ", service account(s) " + String.join(", ", borrowed)
                        + " of " + releaseName + " may use SCC " + need.scc() + " only through RoleBinding "
                        + String.join(", ", lenders) + ", not through a grant of their own. KDPS keeps that binding "
                        + "while " + releaseName + " needs it; for a grant of their own, ask the platform team to run: "
                        + addSccCommand(namespace, need.scc(), borrowed);
                LOG.warn(warning);
                if (warnings != null) warnings.add(warning);
                continue;
            }
            List<String> toGrant = new ArrayList<>(need.serviceAccounts());
            toGrant.removeIf(sa -> !missing.contains(sa) && !borrowed.contains(sa));
            unresolved.put(need.scc(), toGrant);
        }
        return unresolved.isEmpty() ? null : sccRemedy(namespace, releaseName, unresolved, mayGrant, unchecked);
    }

    /**
     * Whether this account may grant {@code scc} in {@code namespace}: create the RoleBinding, and get past RBAC
     * escalation prevention, which requires {@code bind} on the SCC's ClusterRole or {@code use} of the SCC in that
     * namespace. A project admin has neither until the platform team grants one.
     */
    public boolean canGrantScc(String namespace, String scc) {
        return canI("create", RBAC_API_GROUP, "rolebindings", null, null, namespace)
                && (canI("bind", RBAC_API_GROUP, "clusterroles", null, sccClusterRole(scc), namespace)
                    || canI("use", SCC_API_GROUP, "securitycontextconstraints", null, scc, namespace));
    }

    /**
     * The commands with which a platform team lets the KDPS account grant {@code sccs} in {@code namespace}: a
     * ClusterRole allowing {@code bind} on their ClusterRoles (created once per cluster) and a RoleBinding of it in the
     * project.
     */
    public static String sccGrantRightCommands(String namespace, java.util.Collection<String> sccs, String indent) {
        StringBuilder sb = new StringBuilder(indent).append("oc create clusterrole ").append(SCC_GRANT_ROLE)
                .append(" --verb=bind --resource=clusterroles");
        for (String scc : sccs) sb.append(" --resource-name=").append(sccClusterRole(scc));
        sb.append("\n").append(indent).append("oc -n ").append(namespace).append(" create rolebinding ")
                .append(SCC_GRANT_ROLE).append(" --clusterrole=").append(SCC_GRANT_ROLE)
                .append(" --user=<account KDPS connects with>");
        return sb.toString();
    }

    private static String addSccCommand(String namespace, String scc, List<String> serviceAccounts) {
        return sccPolicyCommand("add-scc-to-user", namespace, scc, serviceAccounts);
    }

    private static String sccPolicyCommand(String verb, String namespace, String scc,
                                           java.util.Collection<String> serviceAccounts) {
        StringBuilder sb = new StringBuilder("oc adm policy ").append(verb).append(' ').append(scc);
        for (String sa : serviceAccounts) sb.append(" -z ").append(sa);
        return sb.append(" -n ").append(namespace).toString();
    }

    /** What the operator is told when service accounts lack an SCC this account does not grant. */
    private static String sccRemedy(String namespace, String releaseName, Map<String, List<String>> unresolved,
                                    boolean grantRightHelps, boolean unchecked) {
        StringBuilder sb = new StringBuilder("In project ").append(namespace).append(", service accounts of ")
                .append(releaseName).append(" may not use the SCC(s) ").append(String.join(", ", unresolved.keySet()));
        if (grantRightHelps) {
            sb.append(", and the account KDPS connects with may not grant them.");
        } else {
            sb.append(". A GitOps deploy does not grant SCCs.");
        }
        if (unchecked) {
            sb.append(" This account could not check the existing grants either: it needs to create "
                    + "LocalSubjectAccessReviews in the project, which the project admin role allows.");
        }
        sb.append(grantRightHelps
                ? "\nEither:\n  1. ask the platform team to grant them; the next deploy finds the grant and changes "
                        + "nothing:\n"
                : "\nAsk the platform team to grant them; the next deploy finds the grant:\n");
        String indent = grantRightHelps ? "       " : "  ";
        unresolved.forEach((scc, sas) -> sb.append(indent).append(addSccCommand(namespace, scc, sas)).append("\n"));
        if (grantRightHelps) {
            Set<String> sccs = new LinkedHashSet<>(unresolved.keySet());
            sccs.addAll(SERVICE_SCCS);
            sb.append("  2. or let this account grant them itself, once per project (the ClusterRole once per "
                            + "cluster):\n")
                    .append(sccGrantRightCommands(namespace, sccs, indent));
        }
        return sb.toString().trim();
    }

    /**
     * Whether the pods of service account {@code sa} may run under {@code scc} in {@code namespace}. RBAC answers
     * first: a LocalSubjectAccessReview for the service account and its groups sees every RoleBinding, including the
     * one {@code oc adm policy add-scc-to-user} writes. A PodSecurityPolicySubjectReview then also sees grants written
     * into the SCC's own users and groups fields, which RBAC does not know about.
     *
     * @return the answer, or {@code null} when this account may not make the LocalSubjectAccessReview and the
     *         PodSecurityPolicySubjectReview does not tell either
     */
    Boolean serviceAccountMayUseScc(String namespace, String sa, String scc) {
        String user = "system:serviceaccount:" + namespace + ":" + sa;
        List<String> groups = List.of("system:serviceaccounts", "system:serviceaccounts:" + namespace,
                "system:authenticated");
        Boolean answer = null;
        try {
            io.fabric8.kubernetes.api.model.authorization.v1.LocalSubjectAccessReview review =
                new io.fabric8.kubernetes.api.model.authorization.v1.LocalSubjectAccessReviewBuilder()
                    .withNewMetadata().withNamespace(namespace).endMetadata()
                    .withNewSpec()
                        .withUser(user)
                        .withGroups(groups)
                        .withNewResourceAttributes()
                            .withVerb("use")
                            .withGroup(SCC_API_GROUP)
                            .withResource("securitycontextconstraints")
                            .withName(scc)
                            .withNamespace(namespace)
                        .endResourceAttributes()
                    .endSpec()
                    .build();
            var result = client.authorization().v1().localSubjectAccessReview().inNamespace(namespace).create(review);
            if (result != null && result.getStatus() != null && Boolean.TRUE.equals(result.getStatus().getAllowed())) {
                return true;
            }
            answer = false;
        } catch (Exception e) {
            LOG.warn("Could not ask RBAC whether {} may use SCC '{}': {}", user, scc, e.toString());
        }
        String admittedBy;
        try {
            admittedBy = sccAdmittingProbe(namespace, sa, user, groups, scc);
        } catch (Exception e) {
            LOG.warn("Could not ask OpenShift which SCC admits the pods of {}: {}", user, e.toString());
            return answer;
        }
        if (scc.equals(admittedBy)) return true;
        // Nothing admits a probe that this SCC admits: the service account may not use it.
        if (admittedBy == null && SCC_PROBE_SECURITY_CONTEXTS.containsKey(scc)) return false;
        // Admission picked another SCC the service account may use, which hides whether it may use this one as well
        // (or this SCC has no probe of its own). Reading that as "no" would block a deploy the platform team already
        // granted, again on every retry.
        LOG.info("OpenShift admits the probe pod of {} under {}, which does not tell whether it may use SCC '{}'",
                user, admittedBy == null ? "no SCC" : "SCC '" + admittedBy + "'", scc);
        return answer;
    }

    /**
     * The SCC OpenShift admission picks for a probe pod of service account {@code sa}, or {@code null} when none
     * admits it. The probe is the one built for {@code scc} (see {@link #SCC_PROBE_SECURITY_CONTEXTS}), else a plain
     * non-root UID outside every project range.
     */
    private String sccAdmittingProbe(String namespace, String sa, String user, List<String> groups, String scc) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "probe");
        container.put("image", "probe");
        container.put("securityContext",
                SCC_PROBE_SECURITY_CONTEXTS.getOrDefault(scc, Map.of("runAsUser", SCC_PROBE_UID)));
        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("serviceAccountName", sa);
        podSpec.put("containers", List.of(container));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("user", user);
        spec.put("groups", groups);
        spec.put("template", Map.of("spec", podSpec));
        GenericKubernetesResource review = new GenericKubernetesResource();
        review.setApiVersion(SCC_API_GROUP + "/v1");
        review.setKind("PodSecurityPolicySubjectReview");
        review.setMetadata(new ObjectMetaBuilder().withNamespace(namespace).build());
        review.setAdditionalProperty("spec", spec);
        GenericKubernetesResource result = client.genericKubernetesResources(SCC_SUBJECT_REVIEW)
                .inNamespace(namespace).resource(review).create();
        Object allowedBy = result == null ? null : result.get("status", "allowedBy", "name");
        return allowedBy == null ? null : allowedBy.toString();
    }

    /**
     * The bindings that grant an SCC (bound to its {@code system:openshift:scc:<scc>} ClusterRole) to service accounts
     * of {@code namespace}, whoever made them: the project's RoleBindings and, when this account may list them, the
     * ClusterRoleBindings. Best effort: what cannot be read is left out.
     */
    private List<SccBinding> sccBindings(String namespace) {
        List<SccBinding> out = new ArrayList<>();
        try {
            for (RoleBinding rb : client.rbac().roleBindings().inNamespace(namespace).list().getItems()) {
                String scc = sccOf(rb.getRoleRef());
                if (scc == null) continue;
                Map<String, String> labels = rb.getMetadata().getLabels() == null
                        ? Map.of() : rb.getMetadata().getLabels();
                String release = SCC_MANAGED_BY_KDPS.equals(labels.get(SCC_LABEL_MANAGED_BY))
                        ? labels.get(SCC_LABEL_RELEASE) : null;
                out.add(new SccBinding(rb.getMetadata().getName(), release, scc,
                        serviceAccountSubjects(rb.getSubjects(), namespace),
                        grantsWholeProject(rb.getSubjects(), namespace)));
            }
        } catch (Exception e) {
            LOG.warn("Could not list the SCC grants in {}: {}", namespace, e.toString());
        }
        try {
            for (ClusterRoleBinding crb : client.rbac().clusterRoleBindings().list().getItems()) {
                String scc = sccOf(crb.getRoleRef());
                if (scc == null) continue;
                out.add(new SccBinding("clusterrolebinding/" + crb.getMetadata().getName(), null, scc,
                        serviceAccountSubjects(crb.getSubjects(), namespace),
                        grantsWholeProject(crb.getSubjects(), namespace)));
            }
        } catch (Exception e) {
            // A project admin may not list them; the access reviews still see their grants.
            LOG.debug("Could not list the cluster-wide SCC grants: {}", e.toString());
        }
        return out;
    }

    /** The SCC a binding grants, or {@code null} when its role is not an SCC's ClusterRole. */
    private static String sccOf(io.fabric8.kubernetes.api.model.rbac.RoleRef roleRef) {
        String prefix = sccClusterRole("");
        if (roleRef == null || !"ClusterRole".equals(roleRef.getKind()) || roleRef.getName() == null
                || !roleRef.getName().startsWith(prefix)) {
            return null;
        }
        return roleRef.getName().substring(prefix.length());
    }

    /** Whether the subjects of a binding include a group every service account of {@code namespace} belongs to. */
    private static boolean grantsWholeProject(List<io.fabric8.kubernetes.api.model.rbac.Subject> subjects,
                                              String namespace) {
        if (subjects == null) return false;
        Set<String> groups = Set.of("system:serviceaccounts", "system:serviceaccounts:" + namespace,
                "system:authenticated");
        return subjects.stream().anyMatch(s -> "Group".equals(s.getKind()) && groups.contains(s.getName()));
    }

    /** The service accounts of {@code namespace} the subjects of a binding name (a RoleBinding's may omit it). */
    private static Set<String> serviceAccountSubjects(List<io.fabric8.kubernetes.api.model.rbac.Subject> subjects,
                                                      String namespace) {
        Set<String> out = new LinkedHashSet<>();
        if (subjects == null) return out;
        for (io.fabric8.kubernetes.api.model.rbac.Subject s : subjects) {
            if ("ServiceAccount".equals(s.getKind()) && s.getName() != null
                    && (s.getNamespace() == null || namespace.equals(s.getNamespace()))) {
                out.add(s.getName());
            }
        }
        return out;
    }

    /**
     * Creates the release's RoleBinding for {@code scc}, or adds the service accounts it lacks. Never deletes and never
     * removes a subject: a write that fails leaves every existing grant in place.
     */
    private void grantScc(String namespace, String releaseName, String scc, List<String> serviceAccounts) {
        String name = sccBindingName(scc, releaseName);
        RoleBinding existing = client.rbac().roleBindings().inNamespace(namespace).withName(name).get();
        if (existing == null) {
            RoleBindingBuilder rbb = new RoleBindingBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels(SCC_LABEL_MANAGED_BY, SCC_MANAGED_BY_KDPS)
                        .addToLabels(SCC_LABEL_RELEASE, releaseName)
                        .addToLabels(SCC_LABEL_SCC, scc)
                    .endMetadata()
                    .withNewRoleRef()
                        .withApiGroup(RBAC_API_GROUP)
                        .withKind("ClusterRole")
                        .withName(sccClusterRole(scc))
                    .endRoleRef();
            for (String sa : serviceAccounts) {
                rbb.addNewSubject().withKind("ServiceAccount").withName(sa).withNamespace(namespace).endSubject();
            }
            client.rbac().roleBindings().inNamespace(namespace).resource(rbb.build()).create();
            LOG.info("Granted SCC '{}' to service accounts {} of release {} in {} (RoleBinding {})",
                    scc, serviceAccounts, releaseName, namespace, name);
            return;
        }
        Set<String> present = serviceAccountSubjects(existing.getSubjects(), namespace);
        List<String> added = serviceAccounts.stream().filter(sa -> !present.contains(sa)).collect(Collectors.toList());
        if (added.isEmpty()) return;
        client.rbac().roleBindings().inNamespace(namespace).withName(name).edit(rb -> {
            RoleBindingBuilder b = new RoleBindingBuilder(rb);
            for (String sa : added) {
                b.addNewSubject().withKind("ServiceAccount").withName(sa).withNamespace(namespace).endSubject();
            }
            return b.build();
        });
        LOG.info("Granted SCC '{}' to service accounts {} of release {} in {} (added to RoleBinding {})",
                scc, added, releaseName, namespace, name);
    }

    /**
     * After an uninstall: takes back the SCC grants KDPS made for the release, by deleting the RoleBindings labelled
     * with it. Best effort, never throws; a binding already gone or one this account may not delete is skipped.
     *
     * <p>The other KDPS releases of the project keep what they use. When a service account another release declares
     * holds the SCC only through one of this release's bindings, that release first gets its own binding for it, when
     * this account may grant it; otherwise the binding stays, and the note says what the platform team grants before
     * deleting it.
     *
     * <p>Grants made outside KDPS are left in place. When the release's service accounts still hold one that no other
     * release declares, the commands that take it back are returned for the platform team; so is the deletion of the
     * shared binding of earlier versions, once no other release depends on it.
     *
     * @param sccGrants     the service's {@code openshiftScc} declarations, or {@code null} when unknown (then nothing
     *                      is listed)
     * @param otherReleases the {@code openshiftScc} declarations of the other KDPS releases of the project, by release
     *                      name, or {@code null} when they are not known
     * @return what the platform team may still do, or {@code null}
     */
    public String removeSccGrants(String namespace, String releaseName, List<Map<String, Object>> sccGrants,
                                  Map<String, List<Map<String, Object>>> otherReleases) {
        if (client == null || namespace == null || namespace.isBlank()
                || releaseName == null || releaseName.isBlank()) {
            return null;
        }
        if (!isOpenShiftCluster()) return null;
        // By SCC: the service accounts the other releases declare, with the releases that declare each.
        Map<String, Map<String, Set<String>>> stillDeclared = new HashMap<>();
        if (otherReleases != null) {
            otherReleases.forEach((other, grants) -> {
                if (other == null || other.equals(releaseName)) return;
                for (SccNeed need : sccNeeds(other, grants)) {
                    for (String sa : need.serviceAccounts()) {
                        stillDeclared.computeIfAbsent(need.scc(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(sa, k -> new TreeSet<>()).add(other);
                    }
                }
            });
        }

        List<SccBinding> before = sccBindings(namespace);
        List<String> kept = new ArrayList<>();
        StringBuilder stillUsed = new StringBuilder();
        for (SccBinding binding : before) {
            if (!binding.ofRelease(releaseName)) continue;
            // The service accounts of other releases that would lose the SCC with this binding, by release.
            Map<String, List<String>> dependents = new TreeMap<>();
            stillDeclared.getOrDefault(binding.scc(), Map.of()).forEach((sa, releases) -> {
                boolean grantedOtherwise = before.stream().anyMatch(b -> !b.ofRelease(releaseName)
                        && b.scc().equals(binding.scc()) && b.covers(sa));
                if (binding.covers(sa) && !grantedOtherwise) {
                    releases.forEach(r -> dependents.computeIfAbsent(r, k -> new ArrayList<>()).add(sa));
                }
            });
            if (!dependents.isEmpty() && !handOverScc(namespace, binding.scc(), dependents)) {
                Set<String> sas = new LinkedHashSet<>();
                dependents.values().forEach(sas::addAll);
                stillUsed.append("RoleBinding ").append(binding.name()).append(" stays: it also grants SCC ")
                        .append(binding.scc()).append(" to ")
                        .append(dependents.entrySet().stream()
                                .map(e -> String.join(", ", e.getValue()) + " of release " + e.getKey())
                                .collect(Collectors.joining("; ")))
                        .append(", and the account KDPS connects with may not grant it to them. Once the platform "
                                + "team has granted it, the binding can be deleted:\n  ")
                        .append(addSccCommand(namespace, binding.scc(), new ArrayList<>(sas)))
                        .append("\n  oc -n ").append(namespace).append(" delete rolebinding ").append(binding.name())
                        .append("\n");
                LOG.info("Kept RoleBinding {}/{} of uninstalled release {}: releases {} still use SCC '{}' through it",
                        namespace, binding.name(), releaseName, dependents.keySet(), binding.scc());
                continue;
            }
            try {
                client.rbac().roleBindings().inNamespace(namespace).withName(binding.name()).delete();
                LOG.info("Removed SCC '{}' from the service accounts of release {} in {} (RoleBinding {})",
                        binding.scc(), releaseName, namespace, binding.name());
            } catch (Exception e) {
                if (e instanceof KubernetesClientException kce && kce.getCode() == 404) continue;   // already gone
                LOG.warn("Could not remove RoleBinding {}/{} granting SCC '{}' to release {}: {}",
                        namespace, binding.name(), binding.scc(), releaseName, e.toString());
                kept.add(binding.name());
            }
        }

        List<SccNeed> needs = sccNeeds(releaseName, sccGrants);
        if (needs.isEmpty() && kept.isEmpty() && stillUsed.length() == 0) return null;
        // Not the platform team's to take back: what KDPS granted (the bindings of other releases, any of this
        // release's that stayed, the shared binding of earlier versions while another release depends on it), and the
        // grant of a service account another release still declares.
        List<SccBinding> remaining = sccBindings(namespace);
        Map<String, List<String>> outsideKdps = new LinkedHashMap<>();
        Set<String> legacy = new LinkedHashSet<>();
        for (SccNeed need : needs) {
            Map<String, Set<String>> declared = stillDeclared.getOrDefault(need.scc(), Map.of());
            for (SccBinding shared : remaining) {
                if (!shared.legacy() || !shared.scc().equals(need.scc())
                        || need.serviceAccounts().stream().noneMatch(shared::covers)) {
                    continue;
                }
                boolean dependedOn = declared.keySet().stream().anyMatch(sa -> shared.covers(sa)
                        && remaining.stream().noneMatch(b -> !b.name().equals(shared.name()) && !b.ofRelease(releaseName)
                                && b.scc().equals(shared.scc()) && b.covers(sa)));
                if (!dependedOn) legacy.add(shared.name());
            }
            for (String sa : need.serviceAccounts()) {
                if (declared.containsKey(sa)) continue;
                boolean byKdps = remaining.stream().anyMatch(b -> b.byKdps() && b.scc().equals(need.scc()) && b.covers(sa));
                if (!byKdps && Boolean.TRUE.equals(serviceAccountMayUseScc(namespace, sa, need.scc()))) {
                    outsideKdps.computeIfAbsent(need.scc(), k -> new ArrayList<>()).add(sa);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!outsideKdps.isEmpty() || !legacy.isEmpty() || !kept.isEmpty()) {
            sb.append("The service accounts of ").append(releaseName).append(" in project ").append(namespace)
                    .append(" still hold security context constraints (SCC) that KDPS did not remove. ")
                    .append(otherReleases == null
                            ? "Once no other service in the project needs them, the platform team can take them back:\n"
                            : "No other KDPS release of the project uses them; unless a workload deployed outside KDPS "
                                    + "does, the platform team can take them back:\n");
            outsideKdps.forEach((scc, sas) ->
                    sb.append("  ").append(sccPolicyCommand("remove-scc-from-user", namespace, scc, sas)).append("\n"));
            for (String name : legacy) {
                sb.append("  oc -n ").append(namespace).append(" delete rolebinding ").append(name)
                        .append("   (granted by an earlier KDPS version, for every release of the project)\n");
            }
            for (String name : kept) {
                sb.append("  oc -n ").append(namespace).append(" delete rolebinding ").append(name)
                        .append("   (this account could not delete it)\n");
            }
        }
        sb.append(stillUsed);
        String note = sb.toString().trim();
        return note.isEmpty() ? null : note;
    }

    /**
     * Grants {@code scc} to the service accounts of other releases through their own RoleBindings, so that a binding of
     * an uninstalled release can go without taking it from them.
     *
     * @param dependents the service accounts to grant, by release
     * @return whether every grant was made; {@code false} when this account may not grant the SCC
     */
    private boolean handOverScc(String namespace, String scc, Map<String, List<String>> dependents) {
        if (!canGrantScc(namespace, scc)) return false;
        try {
            dependents.forEach((release, sas) -> grantScc(namespace, release, scc, sas));
            return true;
        } catch (Exception e) {
            // Typically RBAC escalation prevention despite the access review; nothing was removed.
            LOG.warn("Could not grant SCC '{}' in {} to releases {}: {}", scc, namespace, dependents.keySet(),
                    e.getMessage());
            return false;
        }
    }

    /**
     * Apply bearer tokens to Prometheus requests if the kubeconfig provides one.
     */
    private void applyPrometheusAuth(HttpRequest.Builder builder) {
        Config currentConfig = client.getConfiguration();
        // Prefer the kubeconfig's own credential when it carries a token (no regression for kubeconfigs
        // that already work). For a client-certificate-only kubeconfig there is no token, so on OpenShift
        // fall back to a minted, auto-renewed cluster-monitoring-view token. If a present token turns out
        // to lack monitoring RBAC, the query layer retries once with a minted token (see runMonitoringQuery).
        String token = currentConfig.getOauthToken();
        if (token == null || token.isBlank()) {
            token = currentConfig.getAutoOAuthToken();
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
            return;
        }
        if (isOpenShift(client) && monitoringAutoTokenEnabled()) {
            String mintedToken = getMonitoringBearerToken();
            if (mintedToken != null && !mintedToken.isBlank()) {
                builder.header("Authorization", "Bearer " + mintedToken);
                return;
            }
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
     * Decode the base64-encoded CA from a kubeconfig's {@code certificate-authority-data}
     * field and install it into the JVM's default {@link SSLContext} via a
     * {@link CompositeTrustManager} that ALSO retains the platform default truststore.
     *
     * <p>Process-global side effect: subsequent {@code HttpsURLConnection} / {@code HttpClient}
     * code in this JVM that does not specify its own SSL context will trust both the platform
     * CAs and the Kubernetes cluster CA.  Required by call paths that don't flow through the
     * Fabric8 client's internal SSL stack (webhook bootstrap helpers, the Prometheus client,
     * Ambari REST callbacks, etc.).
     *
     * <p>Called from both the constructor (on Ambari Server startup, when a kubeconfig was
     * already on disk from a previous lifecycle) AND {@link #reloadClientIfConfigured()} (on
     * fresh upload via POST /cluster/config).  Without the second call site, the first upload
     * after a server restart silently leaves the JVM default SSLContext stale and any
     * downstream HTTPS call to the apiserver fails with {@code PKIX path building failed}.
     *
     * @param certificateAuthorityData base64-encoded PEM CA cert from the kubeconfig
     */
    /**
     * Resolve the {@code certificate-authority-data} for the kubeconfig's current-context cluster.
     * A kubeconfig may define several clusters (e.g. CRC + docker-desktop); blindly taking
     * {@code clusters[0]} can grab the wrong CA. Falls back to the first cluster's CA when the
     * current-context cannot be resolved.
     */
    /**
     * Build the fabric8 Config for the operator-selected kubeconfig context (persisted per view
     * instance). Falls back to the kubeconfig's current-context when no context has been selected.
     */
    private Config buildConfigForSelectedContext(String kubeconfigContent) {
        String selectedContext = configurationService.getSelectedContext();
        Config cfg;
        if (selectedContext != null && !selectedContext.isBlank()) {
            LOG.info("Building Kubernetes client for selected kubeconfig context '{}'.", selectedContext);
            cfg = Config.fromKubeconfig(selectedContext, kubeconfigContent, null);
        } else {
            LOG.info("No kubeconfig context selected; using the kubeconfig current-context.");
            cfg = Config.fromKubeconfig(kubeconfigContent);
        }
        // Pin the target cluster's CA onto the Config as INLINE caCertData so the fabric8 client's
        // OWN SSLContext trusts the api-server. Two reasons this is required:
        //  (1) the fabric8 OkHttp client does NOT use the JVM-default SSLContext we set elsewhere;
        //  (2) Config.fromKubeconfig may stage the CA into a temp caCertFile the sandboxed view
        //      cannot read — leaving the client with no usable trust and failing with PKIX.
        // Setting caCertData inline (and clearing caCertFile) makes the CA authoritative.
        try {
            io.fabric8.kubernetes.api.model.Config model =
                    Serialization.unmarshal(kubeconfigContent, io.fabric8.kubernetes.api.model.Config.class);
            String caData = clusterCaForContext(model, selectedContext);
            if (caData != null && !caData.isBlank()) {
                cfg = new ConfigBuilder(cfg).withCaCertFile(null).withCaCertData(caData).build();
                LOG.info("Pinned target-cluster CA onto the client Config (caCertData {} base64 chars).", caData.length());
            } else {
                LOG.warn("No certificate-authority-data for the target context; relying on Config defaults.");
            }
        } catch (Exception e) {
            LOG.warn("Could not pin CA onto the client Config: {}", e.toString());
        }
        return cfg;
    }

    /**
     * Build the Kubernetes client with the kubeconfig CA(s) injected DIRECTLY into the HTTP client's
     * SSLContext. Necessary because fabric8's own Config-based trust (caCertData / caCertFile) and the
     * JVM-default SSLContext are both NOT honored by the OkHttp client in this sandboxed view — TLS
     * kept failing with PKIX despite the CA being present. Here we hand fabric8 an explicit
     * TrustManager (JVM defaults + every kubeconfig cluster CA, each possibly a bundle) and preserve
     * client-certificate auth via the Config's key managers.
     */
    private KubernetesClient buildTrustingClient(Config config, java.util.List<String> caDataList) throws Exception {
        final TrustManager[] trustManagers;
        int i = 0;
        if (config.isTrustCerts()) {
            // insecure-skip-tls-verify: trust everything and never touch a trust store. The Ambari
            // server JVM's default trust store may be unreadable from the view context, which would
            // otherwise fail even insecure connections with "problem accessing trust store".
            LOG.warn("Building Kubernetes client in insecure (trust-all) TLS mode.");
            trustManagers = new TrustManager[]{ trustAllManager() };
        } else {
            KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
            ts.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String caData : caDataList) {
                for (java.security.cert.Certificate c :
                        cf.generateCertificates(new ByteArrayInputStream(Base64.getDecoder().decode(caData)))) {
                    if (c instanceof X509Certificate) {
                        LOG.info("Trusting kubeconfig CA subject: {}", ((X509Certificate) c).getSubjectX500Principal().getName());
                    }
                    ts.setCertificateEntry("k8s-ca-" + (i++), c);
                }
            }
            TrustManagerFactory customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            customTmf.init(ts);
            X509TrustManager customTm = (X509TrustManager) customTmf.getTrustManagers()[0];
            // Compose with the JVM default trust store so public CAs still validate — but fall back
            // to the injected CA(s) alone if that store is unreadable from the view context
            // ("problem accessing trust store"), rather than failing the whole client build.
            X509TrustManager tm = customTm;
            try {
                TrustManagerFactory defTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                defTmf.init((KeyStore) null);
                tm = new CompositeTrustManager((X509TrustManager) defTmf.getTrustManagers()[0], customTm);
            } catch (Exception e) {
                LOG.warn("Default trust store unavailable ({}); trusting only the {} injected CA cert(s).", e.toString(), i);
            }
            trustManagers = new TrustManager[]{ tm };
        }

        KeyManager[] km = null;
        try {
            km = SSLUtils.keyManagers(config);
        } catch (Exception e) {
            LOG.debug("No client key managers from kubeconfig (token auth?): {}", e.toString());
        }
        final KeyManager[] keyManagers = km;

        LOG.info("Building Kubernetes client with {} kubeconfig CA cert(s) injected into the HTTP client SSLContext.", i);
        return new KubernetesClientBuilder()
                .withConfig(config)
                .withHttpClientBuilderConsumer(b -> b.sslContext(keyManagers, trustManagers))
                .build();
    }

    /** Trust-all X509 manager for insecure (skip-tls-verify) mode — never touches a trust store. */
    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    /** CA data of the cluster referenced by {@code contextName} (or the current-context when null). */
    private String clusterCaForContext(io.fabric8.kubernetes.api.model.Config model, String contextName) {
        if (model == null) {
            return null;
        }
        String ctxName = (contextName != null && !contextName.isBlank()) ? contextName : model.getCurrentContext();
        String clusterName = null;
        if (ctxName != null && model.getContexts() != null) {
            for (io.fabric8.kubernetes.api.model.NamedContext nc : model.getContexts()) {
                if (ctxName.equals(nc.getName()) && nc.getContext() != null) {
                    clusterName = nc.getContext().getCluster();
                    break;
                }
            }
        }
        if (clusterName != null && model.getClusters() != null) {
            for (io.fabric8.kubernetes.api.model.NamedCluster ncl : model.getClusters()) {
                if (clusterName.equals(ncl.getName()) && ncl.getCluster() != null) {
                    return ncl.getCluster().getCertificateAuthorityData();
                }
            }
        }
        return null;
    }

    /**
     * List the contexts available in the uploaded kubeconfig so the operator can pick which cluster
     * this view instance targets. Each entry carries: name, cluster, namespace, server, {@code current}
     * (the kubeconfig's current-context) and {@code selected} (the persisted pick for this instance).
     */
    public java.util.List<java.util.Map<String, Object>> listAvailableContexts() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        String content;
        try {
            // The file as uploaded: its own current-context is reported as such, the selection separately.
            content = configurationService.getRawKubeconfigContents();
        } catch (Exception e) {
            LOG.warn("listAvailableContexts: kubeconfig not available: {}", e.toString());
            return out;
        }
        io.fabric8.kubernetes.api.model.Config model =
                Serialization.unmarshal(content, io.fabric8.kubernetes.api.model.Config.class);
        if (model == null || model.getContexts() == null) {
            return out;
        }
        String current = model.getCurrentContext();
        String selected = configurationService.getSelectedContext();
        java.util.Map<String, io.fabric8.kubernetes.api.model.Cluster> clusters = new java.util.HashMap<>();
        if (model.getClusters() != null) {
            for (io.fabric8.kubernetes.api.model.NamedCluster nc : model.getClusters()) {
                if (nc.getName() != null && nc.getCluster() != null) {
                    clusters.put(nc.getName(), nc.getCluster());
                }
            }
        }
        for (io.fabric8.kubernetes.api.model.NamedContext nc : model.getContexts()) {
            if (nc.getContext() == null) {
                continue;
            }
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", nc.getName());
            m.put("cluster", nc.getContext().getCluster());
            m.put("namespace", nc.getContext().getNamespace());
            io.fabric8.kubernetes.api.model.Cluster cl = clusters.get(nc.getContext().getCluster());
            m.put("server", cl != null ? cl.getServer() : null);
            m.put("current", nc.getName() != null && nc.getName().equals(current));
            m.put("selected", nc.getName() != null && nc.getName().equals(selected));
            out.add(m);
        }
        return out;
    }

    /**
     * Collect the {@code certificate-authority-data} of EVERY cluster defined in the kubeconfig.
     * A kubeconfig may define multiple clusters (e.g. CRC + docker-desktop); trusting all of their
     * CAs means TLS to any selected context/cluster succeeds — not just the current-context one.
     */
    private java.util.List<String> collectAllClusterCaData(io.fabric8.kubernetes.api.model.Config kubeconfigModel) {
        java.util.List<String> caDataList = new java.util.ArrayList<>();
        if (kubeconfigModel == null || kubeconfigModel.getClusters() == null) {
            return caDataList;
        }
        for (io.fabric8.kubernetes.api.model.NamedCluster ncl : kubeconfigModel.getClusters()) {
            if (ncl.getCluster() != null) {
                String ca = ncl.getCluster().getCertificateAuthorityData();
                if (ca != null && !ca.isEmpty()) {
                    caDataList.add(ca);
                    LOG.info("Kubeconfig cluster '{}' contributes a certificate-authority-data entry.", ncl.getName());
                }
            }
        }
        return caDataList;
    }

    private void installKubeconfigCasIntoJvmDefaultSslContext(java.util.List<String> caDataList)
            throws java.security.NoSuchAlgorithmException,
                   java.security.KeyStoreException,
                   java.security.cert.CertificateException,
                   java.security.KeyManagementException,
                   IOException {
        LOG.info("Adding {} kubeconfig cluster CA entry(ies) to the JVM's default SSL context.", caDataList.size());

        KeyStore customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        customTrustStore.load(null, null);

        // Each certificate-authority-data may itself be a BUNDLE of multiple PEM certs (OpenShift/CRC
        // ships the ingress cert plus several kube-apiserver signers). generateCertificates (plural)
        // loads them ALL — generateCertificate (singular) would trust only the first and miss the
        // api-server's actual signer, causing "PKIX path building failed" on the API handshake.
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        int caIndex = 0;
        for (String caData : caDataList) {
            byte[] decoded = Base64.getDecoder().decode(caData);
            for (java.security.cert.Certificate cert :
                    certificateFactory.generateCertificates(new ByteArrayInputStream(decoded))) {
                customTrustStore.setCertificateEntry("k8s-ca-" + (caIndex++), cert);
            }
        }
        LOG.info("Loaded {} CA certificate(s) from the kubeconfig into the trust store.", caIndex);

        TrustManagerFactory customTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        customTrustManagerFactory.init(customTrustStore);
        X509TrustManager customTrustManager = (X509TrustManager) customTrustManagerFactory.getTrustManagers()[0];

        // Preserve the platform cacerts bundle by composing with the JVM default trust manager — but
        // the Ambari server JVM's default trust store may be unreadable / password-mismatched from the
        // view context (KeyStoreException: "Keystore was tampered with, or password was incorrect").
        // If so, install ONLY the cluster CA(s) rather than failing every HTTPS path in the JVM.
        X509TrustManager effectiveTrustManager = customTrustManager;
        try {
            TrustManagerFactory defaultTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaultTrustManagerFactory.init((KeyStore) null);
            X509TrustManager defaultTrustManager = (X509TrustManager) defaultTrustManagerFactory.getTrustManagers()[0];
            effectiveTrustManager = new CompositeTrustManager(defaultTrustManager, customTrustManager);
        } catch (Exception e) {
            LOG.warn("Default trust store unavailable ({}); JVM default SSL context will trust ONLY the {} cluster CA cert(s).", e.toString(), caIndex);
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{ effectiveTrustManager }, null);
        SSLContext.setDefault(sslContext);

        LOG.info("JVM default SSL context updated to trust {} kubeconfig CA certificate(s).", caIndex);
    }

    /**
     * Ensure a namespace exists and is ready for new resources.
     *
     * <p>Handles the common <em>uninstall-then-reinstall</em> race:
     * when a previous {@code helm uninstall} or {@code kubectl delete namespace}
     * is still running, the namespace appears in {@code Terminating} phase.
     * Creating resources in it would fail with HTTP 403
     * {@code NamespaceTerminating} (typically surfaced several steps later in
     * the deploy plan — Krb5 ConfigMap, image pull Secret, etc.) and the operator
     * sees a confusing K8s error instead of a clear "wait for cleanup" message.
     *
     * <p>Behavior:
     * <ol>
     *   <li>If the namespace doesn't exist → create it.</li>
     *   <li>If it exists and is {@code Active} → no-op.</li>
     *   <li>If it exists and is {@code Terminating} → poll until it's gone
     *       (up to {@code NAMESPACE_TERMINATING_TIMEOUT_SECONDS}), then create
     *       it fresh. Throws with a clear "still terminating" message on
     *       timeout so the operator can rerun the deploy.</li>
     * </ol>
     */
    public void createNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        try {
            io.fabric8.kubernetes.api.model.Namespace existing;
            try {
                existing = client.namespaces().withName(namespace).get();
            } catch (KubernetesClientException e) {
                if (e.getCode() == 403) {
                    // Namespaces are cluster-scoped. On OpenShift sites where projects are requested from
                    // the platform team, the view's ServiceAccount may only hold rights INSIDE the
                    // pre-provisioned project, not on the namespace object itself. Treat the namespace as
                    // pre-provisioned; if it really is missing, the following namespaced call fails with
                    // a clear error.
                    LOG.info("Not allowed to read namespace '{}' (403); assuming it is pre-provisioned", namespace);
                    return;
                }
                throw e;
            }
            if (existing != null) {
                String phase = existing.getStatus() == null ? null : existing.getStatus().getPhase();
                if ("Terminating".equalsIgnoreCase(phase)) {
                    LOG.info("Namespace '{}' is Terminating — waiting up to {}s for cleanup to complete before re-creating",
                            namespace, NAMESPACE_TERMINATING_TIMEOUT_SECONDS);
                    waitForNamespaceGone(namespace, NAMESPACE_TERMINATING_TIMEOUT_SECONDS);
                    // Fall through to the create below — namespace is gone now.
                } else {
                    LOG.debug("Namespace '{}' already exists (phase={}); no-op", namespace, phase);
                    return;
                }
            }
            client.namespaces().createOrReplace(new NamespaceBuilder()
                    .withNewMetadata().withName(namespace).endMetadata()
                    .build());
            namespaceScope.invalidateNamespaces();
        } catch (KubernetesClientException e) {
            throw new RuntimeException("Failed to create namespace: " + namespace, e);
        }
    }

    /** Upper bound for the Terminating-phase wait. Most K8s namespace cleanups
     *  finish in &lt;30s but stuck finalizers (PVCs, webhooks) can drag it out. */
    private static final int NAMESPACE_TERMINATING_TIMEOUT_SECONDS = 120;

    /**
     * Polls the API server until the namespace disappears (cleanup finished) or
     * the deadline elapses. Throws {@link RuntimeException} on timeout with a
     * pointer to the typical culprit (stuck finalizers) so operators know where
     * to look.
     */
    private void waitForNamespaceGone(String namespace, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int pollMs = 2000;
        while (System.currentTimeMillis() < deadline) {
            io.fabric8.kubernetes.api.model.Namespace ns = client.namespaces().withName(namespace).get();
            if (ns == null) {
                LOG.info("Namespace '{}' cleanup completed; safe to re-create now", namespace);
                return;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for namespace '" + namespace + "' to terminate", ie);
            }
        }
        throw new RuntimeException("Timed out after " + timeoutSeconds + "s waiting for namespace '" + namespace
                + "' to finish terminating. Check for stuck finalizers: `kubectl get ns " + namespace
                + " -o yaml | grep -A5 finalizers` — common culprits are pending CRD instances or admission webhooks.");
    }

    /**
     * How long a replaced client stays open so that calls already running on it can finish. Longer than the longest
     * call made with the client (commands run inside pods during deploys allow up to 120 s); an idle client costs
     * little.
     */
    static final long RETIRED_CLIENT_GRACE_SECONDS = 300;

    /** Closes replaced clients once their grace period is over. */
    private static final java.util.concurrent.ScheduledExecutorService CLIENT_RETIREMENT =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "k8s-client-retirement");
                t.setDaemon(true);
                return t;
            });

    /** Grace period applied by this instance; shortened by tests only. */
    long retiredClientGraceSeconds = RETIRED_CLIENT_GRACE_SECONDS;

    /**
     * Rebuilds the client from the currently saved configuration, even if one already exists (new kubeconfig,
     * another context, OpenShift login, token renewal). It runs at once, whatever failed before: the configuration
     * was just saved, so a refused password or the wait after failed attempts no longer applies.
     *
     * @return {@code true} when a client was built
     */
    public synchronized boolean forceReloadClient() {
        resetConnectBackOff();
        return rebuildClient();
    }

    /**
     * Builds the client from the currently saved configuration if there is none yet.
     *
     * @return {@code true} when a client exists afterwards
     */
    public synchronized boolean reloadClientIfConfigured() {
        if (this.client != null && this.isConfigured) {
            return true;
        }
        return rebuildClient();
    }

    /**
     * Builds a client from the saved configuration and only then swaps it in, so calls made meanwhile keep using the
     * previous client instead of finding the view unconfigured. The previous client is closed after
     * {@link #retiredClientGraceSeconds}. When no client can be built, the view is left without a connection: keeping
     * the previous one would leave it talking to an account the saved configuration no longer names (Helm reads the
     * saved kubeconfig on every call). The outcome sets the back-off of {@link #connectIfConfigured()}. Called holding
     * {@code this}.
     */
    private boolean rebuildClient() {
        KubernetesClient previous = this.client;
        KubernetesClient fresh;
        try {
            fresh = buildClientFromSavedConfiguration();
        } catch (Exception e) {
            LOG.warn("Failed to reinitialize Kubernetes client: {}", e.getMessage());
            fresh = null;
        }
        if (fresh == null) {
            this.isConfigured = false;
            this.client = null;
            retire(previous);
            recordConnectFailure();
            return false;
        }
        this.client = fresh;
        this.isConfigured = true;
        resetConnectBackOff();
        // Cached answers about the previous cluster (a new kubeconfig or context can point elsewhere).
        this.externalSecretsApiVersion = null;
        this.statsCache.invalidateAll();
        this.serviceCache.invalidateAll();
        this.prometheusClientCache.clear();
        this.openShiftDetectionCache.set(null);
        this.openShiftDetectionRetryAt.set(0);
        this.monitoringTokenCache.set(null);
        this.namespaceScope.reset();
        try {
            applyProxySettings();
        } catch (RuntimeException e) {
            LOG.warn("Could not apply proxy settings to the new client: {}", e.toString());
        }
        retire(previous);
        LOG.info("reloadClientIfConfigured: Kubernetes client reinitialized successfully");
        return true;
    }

    /** Closes a replaced client once its grace period is over. */
    private void retire(KubernetesClient previous) {
        if (previous == null) {
            return;
        }
        Runnable close = () -> {
            try {
                previous.close();
            } catch (RuntimeException e) {
                LOG.debug("Closing a replaced Kubernetes client failed: {}", e.toString());
            }
        };
        try {
            CLIENT_RETIREMENT.schedule(close, retiredClientGraceSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            close.run(); // the view is shutting down
        }
    }

    /**
     * Builds a Kubernetes client from the saved configuration without installing it.
     *
     * @return the client, or {@code null} when no kubeconfig is saved
     * @throws Exception when the saved configuration cannot be read or turned into a client
     */
    KubernetesClient buildClientFromSavedConfiguration() throws Exception {
        this.configurationService = new ViewConfigurationService(viewContext);
        boolean openShiftLogin = configurationService.isOpenShiftLogin();
        String kubeconfigContent;
        if (openShiftLogin) {
            // Synthesized kubeconfig with a freshly-minted OpenShift token (username/password login).
            kubeconfigContent = configurationService.getKubeconfigContents();
        } else {
            String kubeconfigPath = configurationService.getKubeconfigPath();
            if (kubeconfigPath == null) {
                LOG.warn("reloadClientIfConfigured: kubeconfig path is null");
                return null;
            }
            if (!new File(kubeconfigPath).exists()) {
                LOG.warn("reloadClientIfConfigured: kubeconfig file missing at {}", kubeconfigPath);
                return null;
            }
            EncryptionService encryptionService = new EncryptionService();
            byte[] encryptedBytes = Files.readAllBytes(Paths.get(kubeconfigPath));
            byte[] decryptedBytes = encryptionService.decrypt(encryptedBytes);
            kubeconfigContent = new String(decryptedBytes, StandardCharsets.UTF_8);
        }

        // Mirror the constructor's CA-loading step.  Without this, the JVM default
        // SSLContext is never updated with the kubeconfig CA and any post-upload HTTPS
        // call that doesn't go through the Fabric8 client's own SSL stack (e.g. the
        // webhook bootstrap below, or the Prometheus HTTP client built later from the
        // same view) fails with `PKIX path building failed`.  Bug only manifested on
        // FIRST upload after server restart (when the constructor's "View is not
        // configured" path had skipped this work).  See KubernetesService(ViewContext).
        io.fabric8.kubernetes.api.model.Config kubeconfigModel =
            Serialization.unmarshal(kubeconfigContent, io.fabric8.kubernetes.api.model.Config.class);
        java.util.List<String> allClusterCaData = collectAllClusterCaData(kubeconfigModel);
        if (!allClusterCaData.isEmpty()) {
            installKubeconfigCasIntoJvmDefaultSslContext(allClusterCaData);
        }

        Config finalConfiguration = buildConfigForSelectedContext(kubeconfigContent);
        loadK8sPropsAsSystemProperties(viewContext);
        if (configurationService.isOpenShiftLogin()) {
            finalConfiguration.setOauthTokenProvider(configurationService.getOpenShiftLoginProvider());
        }
        return buildTrustingClient(finalConfiguration, allClusterCaData);
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
     * Browse-view list of Deployments. Returns one row per Deployment with the
     * shape the workloads tab expects: name, ready replicas, desired replicas,
     * image, and age. Cluster-wide when {@code namespace} is null/blank.
     */
    public List<Map<String, Object>> listDeployments(String namespace) {
        try {
            checkConfiguration();
            var deployList = (namespace != null && !namespace.isBlank())
                    ? client.apps().deployments().inNamespace(namespace).list().getItems()
                    : namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.DEPLOYMENTS,
                            () -> client.apps().deployments().inAnyNamespace().list().getItems(),
                            ns -> client.apps().deployments().inNamespace(ns).list().getItems());
            List<Map<String, Object>> out = new ArrayList<>();
            for (var d : deployList) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("namespace", d.getMetadata() != null ? d.getMetadata().getNamespace() : null);
                r.put("name", d.getMetadata() != null ? d.getMetadata().getName() : null);
                r.put("replicas", d.getSpec() != null ? d.getSpec().getReplicas() : null);
                r.put("readyReplicas", d.getStatus() != null ? d.getStatus().getReadyReplicas() : null);
                r.put("availableReplicas", d.getStatus() != null ? d.getStatus().getAvailableReplicas() : null);
                String image = null;
                if (d.getSpec() != null && d.getSpec().getTemplate() != null
                        && d.getSpec().getTemplate().getSpec() != null
                        && d.getSpec().getTemplate().getSpec().getContainers() != null
                        && !d.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                    image = d.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
                }
                r.put("image", image);
                r.put("creationTimestamp", d.getMetadata() != null ? d.getMetadata().getCreationTimestamp() : null);
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("listDeployments failed: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Browse-view list of ConfigMaps. Includes a small summary (key count, total
     * byte size of all values) rather than the values themselves so the wire is
     * compact for a 100-row listing.
     */
    public List<Map<String, Object>> listConfigMaps(String namespace) {
        try {
            checkConfiguration();
            var cms = (namespace != null && !namespace.isBlank())
                    ? client.configMaps().inNamespace(namespace).list().getItems()
                    : namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.CONFIGMAPS,
                            () -> client.configMaps().inAnyNamespace().list().getItems(),
                            ns -> client.configMaps().inNamespace(ns).list().getItems());
            List<Map<String, Object>> out = new ArrayList<>();
            for (var cm : cms) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("namespace", cm.getMetadata() != null ? cm.getMetadata().getNamespace() : null);
                r.put("name", cm.getMetadata() != null ? cm.getMetadata().getName() : null);
                int keys = cm.getData() == null ? 0 : cm.getData().size();
                long bytes = cm.getData() == null ? 0L :
                        cm.getData().values().stream().mapToLong(v -> v == null ? 0 : v.length()).sum();
                r.put("keys", keys);
                r.put("bytes", bytes);
                r.put("creationTimestamp", cm.getMetadata() != null ? cm.getMetadata().getCreationTimestamp() : null);
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("listConfigMaps failed: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Browse-view list of Ingresses. Returns one row per Ingress with host(s),
     * tls-secret count, ingress class, and address. Used by the Networking tab.
     */
    public List<Map<String, Object>> listIngresses(String namespace) {
        try {
            checkConfiguration();
            var items = (namespace != null && !namespace.isBlank())
                    ? client.network().v1().ingresses().inNamespace(namespace).list().getItems()
                    : namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.INGRESSES,
                            () -> client.network().v1().ingresses().inAnyNamespace().list().getItems(),
                            ns -> client.network().v1().ingresses().inNamespace(ns).list().getItems());
            List<Map<String, Object>> out = new ArrayList<>();
            for (var ing : items) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("namespace", ing.getMetadata() != null ? ing.getMetadata().getNamespace() : null);
                r.put("name", ing.getMetadata() != null ? ing.getMetadata().getName() : null);
                String cls = null;
                if (ing.getSpec() != null) {
                    cls = ing.getSpec().getIngressClassName();
                }
                r.put("ingressClass", cls);
                List<String> hosts = new ArrayList<>();
                if (ing.getSpec() != null && ing.getSpec().getRules() != null) {
                    for (var rule : ing.getSpec().getRules()) {
                        if (rule.getHost() != null) hosts.add(rule.getHost());
                    }
                }
                r.put("hosts", hosts);
                int tlsCount = ing.getSpec() != null && ing.getSpec().getTls() != null ? ing.getSpec().getTls().size() : 0;
                r.put("tlsSecrets", tlsCount);
                String address = null;
                if (ing.getStatus() != null && ing.getStatus().getLoadBalancer() != null
                        && ing.getStatus().getLoadBalancer().getIngress() != null
                        && !ing.getStatus().getLoadBalancer().getIngress().isEmpty()) {
                    var lb = ing.getStatus().getLoadBalancer().getIngress().get(0);
                    address = lb.getHostname() != null ? lb.getHostname() : lb.getIp();
                }
                r.put("address", address);
                r.put("creationTimestamp", ing.getMetadata() != null ? ing.getMetadata().getCreationTimestamp() : null);
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("listIngresses failed: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext ROUTE_RDC =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup("route.openshift.io").withVersion("v1")
                    .withKind("Route").withPlural("routes").withNamespaced(true).build();

    private static Map<String, Object> asMap(Object o) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (o instanceof Map) ? (Map<String, Object>) o : java.util.Collections.emptyMap();
        return m;
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** Whether the router admitted a Route (status.ingress[].conditions[type=Admitted].status == True). */
    private static boolean routeAdmitted(Map<String, Object> status) {
        Object ingress = status.get("ingress");
        if (ingress instanceof List<?> list) {
            for (Object entry : list) {
                Object conds = asMap(entry).get("conditions");
                if (conds instanceof List<?> cl) {
                    for (Object c : cl) {
                        Map<String, Object> cm = asMap(c);
                        if ("Admitted".equals(str(cm.get("type"))) && "True".equals(str(cm.get("status")))) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Browse-view list of OpenShift Routes in a namespace. Empty on non-OpenShift clusters. Read via the
     * generic resource API (no openshift-client impl needed on the classpath). One row per Route with the
     * reachable URL, target service:port, TLS termination and whether the router admitted it — this is
     * how the view surfaces "where's my UI?" on OpenShift, where charts emit Routes instead of Ingresses.
     */
    public List<Map<String, Object>> listRoutes(String namespace) {
        try {
            checkConfiguration();
            if (!isOpenShiftCluster()) return java.util.Collections.emptyList();
            var op = client.genericKubernetesResources(ROUTE_RDC);
            var items = (namespace != null && !namespace.isBlank())
                    ? op.inNamespace(namespace).list().getItems()
                    : namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.ROUTES,
                            () -> op.inAnyNamespace().list().getItems(),
                            ns -> op.inNamespace(ns).list().getItems());
            List<Map<String, Object>> out = new ArrayList<>();
            for (var route : items) {
                Map<String, Object> props = route.getAdditionalProperties();
                Map<String, Object> spec = asMap(props.get("spec"));
                Map<String, Object> status = asMap(props.get("status"));
                String host = str(spec.get("host"));
                String path = str(spec.get("path"));
                String termination = str(asMap(spec.get("tls")).get("termination"));
                boolean secure = termination != null && !termination.isBlank();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("namespace", route.getMetadata() != null ? route.getMetadata().getNamespace() : null);
                r.put("name", route.getMetadata() != null ? route.getMetadata().getName() : null);
                r.put("host", host);
                r.put("path", path);
                r.put("url", (host == null || host.isBlank()) ? null
                        : (secure ? "https://" : "http://") + host + (path != null && !path.isBlank() ? path : ""));
                r.put("service", str(asMap(spec.get("to")).get("name")));
                r.put("port", asMap(spec.get("port")).get("targetPort"));
                r.put("tls", termination);
                r.put("admitted", routeAdmitted(status));
                r.put("creationTimestamp", route.getMetadata() != null ? route.getMetadata().getCreationTimestamp() : null);
                out.add(r);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("listRoutes failed: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Lightweight snapshot of a CustomResourceDefinition for the Operators UI.
     * Carries just enough to render a row and a tooltip: name, group, kind, the
     * served versions, and whether the CRD is established yet. We deliberately
     * do NOT return the full schema (kB of openAPIV3Schema) because the
     * Operators page renders 50+ rows and the wire size matters.
     */
    public static class CrdSummary {
        public String name;
        public String group;
        public String kind;
        public String scope;
        public List<String> versions;
        public boolean established;
    }

    /**
     * List every CRD currently registered on the cluster. Used by the Operators
     * page to show "what extra CRDs is the operator providing" alongside the
     * curated capability probe. Returns an empty list rather than throwing
     * when the cluster client can't reach the API (the UI will show an empty
     * state instead of a stack trace).
     */
    public List<CrdSummary> listCrds() {
        try {
            checkConfiguration();
            return client.apiextensions().v1().customResourceDefinitions().list().getItems().stream()
                    .map(c -> {
                        CrdSummary s = new CrdSummary();
                        s.name = c.getMetadata() != null ? c.getMetadata().getName() : null;
                        s.group = c.getSpec() != null ? c.getSpec().getGroup() : null;
                        if (c.getSpec() != null && c.getSpec().getNames() != null) {
                            s.kind = c.getSpec().getNames().getKind();
                        }
                        s.scope = c.getSpec() != null ? c.getSpec().getScope() : null;
                        if (c.getSpec() != null && c.getSpec().getVersions() != null) {
                            s.versions = c.getSpec().getVersions().stream()
                                    .filter(v -> Boolean.TRUE.equals(v.getServed()))
                                    .map(v -> v.getName())
                                    .toList();
                        } else {
                            s.versions = java.util.Collections.emptyList();
                        }
                        if (c.getStatus() != null && c.getStatus().getConditions() != null) {
                            s.established = c.getStatus().getConditions().stream()
                                    .anyMatch(cond -> "Established".equals(cond.getType()) && "True".equals(cond.getStatus()));
                        }
                        return s;
                    })
                    .filter(s -> s.name != null)
                    .sorted(java.util.Comparator
                            .comparing((CrdSummary x) -> x.group == null ? "" : x.group)
                            .thenComparing(x -> x.name == null ? "" : x.name))
                    .toList();
        } catch (Exception e) {
            logDiscoveryFailure("listCrds", e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Snapshot of a single Secret used as an outbound truststore. The Operators
     * tab and Truststores page both render this. Includes a per-cert summary
     * (subject, issuer, notAfter, daysUntilExpiry) so the operator can spot a
     * CA that's about to expire without opening kubectl.
     */
    public static class TruststoreSummary {
        public String namespace;
        public String name;
        /** Owning Helm release name, derived from the trailing `-truststore` suffix. */
        public String releaseName;
        /** Number of PEM cert blocks found in {@code ca.crt}. */
        public int caCount;
        /** Has both {@code truststore.jks} and {@code truststore.password}. */
        public boolean jvmReady;
        /** Has {@code ca.crt} (PEM bundle). */
        public boolean pemReady;
        /** Parsed certs in {@code ca.crt}; one entry per cert block. */
        public List<Map<String, Object>> certificates;
        /** Trusted by every release (annotation {@code ambari.clemlab.com/trust-default=true}). */
        public boolean isDefault;
        /** Created via the view's Truststores tab (label {@code resource-type=truststore}); can be edited/deleted. */
        public boolean managed;
    }

    /**
     * Enumerate Secrets matching the {@code *-truststore} convention used by
     * the security-profile step in CommandService. Each matching Secret is
     * inspected: the {@code ca.crt} PEM bundle is parsed into per-cert
     * summaries (subject, issuer, notAfter, daysUntilExpiry) so operators
     * can see "which CAs are we trusting per release."
     *
     * @param namespace optional namespace filter; null/blank = cluster-wide
     */
    public List<TruststoreSummary> listTruststores(String namespace) {
        try {
            checkConfiguration();
            List<Secret> secrets;
            if (namespace != null && !namespace.isBlank()) {
                secrets = client.secrets().inNamespace(namespace).list().getItems();
            } else {
                secrets = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.SECRETS,
                        () -> client.secrets().inAnyNamespace().list().getItems(),
                        ns -> client.secrets().inNamespace(ns).list().getItems());
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Instant now = Instant.now();
            List<TruststoreSummary> out = new ArrayList<>();
            for (Secret s : secrets) {
                if (s.getMetadata() == null || s.getMetadata().getName() == null) continue;
                String name = s.getMetadata().getName();
                if (!name.endsWith("-truststore")) continue;
                TruststoreSummary ts = new TruststoreSummary();
                ts.namespace = s.getMetadata().getNamespace();
                ts.name = name;
                ts.releaseName = name.substring(0, name.length() - "-truststore".length());
                Map<String, String> tsAnnotations = s.getMetadata().getAnnotations();
                Map<String, String> tsLabels = s.getMetadata().getLabels();
                ts.isDefault = tsAnnotations != null
                        && Boolean.parseBoolean(tsAnnotations.getOrDefault("ambari.clemlab.com/trust-default", "false"));
                ts.managed = tsLabels != null
                        && "truststore".equals(tsLabels.get("ambari.clemlab.com/resource-type"));
                Map<String, String> data = s.getData() != null ? s.getData() : java.util.Collections.emptyMap();
                ts.jvmReady = data.containsKey("truststore.jks") && data.containsKey("truststore.password");
                ts.pemReady = data.containsKey("ca.crt");
                List<Map<String, Object>> certs = new ArrayList<>();
                if (ts.pemReady) {
                    byte[] pemBytes = getDecoder().decode(data.get("ca.crt"));
                    try {
                        Collection<? extends java.security.cert.Certificate> parsed =
                                cf.generateCertificates(new ByteArrayInputStream(pemBytes));
                        for (java.security.cert.Certificate cert : parsed) {
                            if (!(cert instanceof X509Certificate x509)) continue;
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("subject", x509.getSubjectX500Principal().getName());
                            c.put("issuer", x509.getIssuerX500Principal().getName());
                            Instant notAfter = x509.getNotAfter().toInstant();
                            c.put("notAfter", notAfter.toString());
                            c.put("daysUntilExpiry", Duration.between(now, notAfter).toDays());
                            c.put("serialNumber", x509.getSerialNumber().toString(16));
                            c.put("isCa", x509.getBasicConstraints() >= 0);
                            certs.add(c);
                        }
                    } catch (Exception parseEx) {
                        LOG.warn("listTruststores: failed to parse ca.crt in {}/{}: {}", ts.namespace, ts.name, parseEx.toString());
                    }
                }
                ts.certificates = certs;
                ts.caCount = certs.size();
                out.add(ts);
            }
            out.sort(java.util.Comparator
                    .comparing((TruststoreSummary x) -> x.namespace == null ? "" : x.namespace)
                    .thenComparing(x -> x.name == null ? "" : x.name));
            return out;
        } catch (Exception e) {
            LOG.warn("listTruststores failed: {}", e.toString());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Names of the cluster's IngressClasses, default class first when one is marked
     * {@code ingressclass.kubernetes.io/is-default-class}. Empty when the cluster has no ingress
     * controller, or when listing is not permitted; either way an Ingress would not be served.
     */
    public java.util.List<String> listIngressClasses() {
        checkConfiguration();
        try {
            var classes = client.network().v1().ingressClasses().list().getItems();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (var ic : classes) {
                if (ic.getMetadata() == null || ic.getMetadata().getName() == null) continue;
                var ann = ic.getMetadata().getAnnotations();
                boolean isDefault = ann != null && "true".equalsIgnoreCase(ann.get("ingressclass.kubernetes.io/is-default-class"));
                if (isDefault) names.add(0, ic.getMetadata().getName()); else names.add(ic.getMetadata().getName());
            }
            return names;
        } catch (KubernetesClientException e) {
            LOG.warn("IngressClass listing failed (treating as no ingress controller): {}", e.getMessage());
            return java.util.List.of();
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
            if (e.getCode() == 403) {
                // Accounts limited to their own projects may not read CRD objects; API discovery says the same
                // thing (is the resource served?) and is open to every authenticated account.
                boolean served = namespaceScope.servedByApiDiscovery(crdName);
                LOG.debug("CRD {} not readable by this account; API discovery says served={}", crdName, served);
                return served;
            }
            LOG.error("Could not check CRD {}: {}", crdName, e.getMessage());
            throw e;
        }
    }
    /**
     * Applies a YAML manifest from the filesystem, stamping Helm ownership metadata on CRDs
     * and creating or replacing all other resources.
     *
     * @param yamlPath         path to the YAML file containing one or more Kubernetes resources
     * @param releaseName      the Helm release name used to stamp {@code meta.helm.sh/release-name}
     * @param releaseNamespace the namespace used to stamp {@code meta.helm.sh/release-namespace}
     */
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

    /**
     * Loads a YAML template from the classpath, performs {@code {{KEY}}} token substitution,
     * and applies the resulting resources into the target namespace.
     *
     * @param resourcePathOnClasspath classpath-relative path to the YAML template resource
     * @param releaseName             the Helm release name (used for context/logging)
     * @param targetNamespace         the Kubernetes namespace into which resources are applied
     * @param variables               map of token keys to replacement values; may be {@code null}
     */
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

    /**
     * Convenience overload of {@link #applyYaml(Path, String, String)} that accepts a string path.
     *
     * @param yamlPath         string path to the YAML file
     * @param releaseName      the Helm release name used for ownership metadata
     * @param releaseNamespace the namespace used for ownership metadata
     */
    public void applyYaml(String yamlPath, String releaseName, String releaseNamespace) {
        applyYaml(java.nio.file.Paths.get(Objects.requireNonNull(yamlPath, "yamlPath")), releaseName, releaseNamespace);
    }

    /**
     * Returns the underlying {@link KubernetesClient} for advanced or direct API access, connecting first from the
     * saved configuration when the view instance is configured but not connected yet.
     *
     * @return the Kubernetes client, or {@code null} if the service is not configured or not connected
     */
    public KubernetesClient getClient() {
        connectIfConfigured();
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

    /** Label KDPS stamps on every Helm release Secret it deploys, so the Releases page can identify
     *  "deployed by the KDPS view" from a k8s-native signal that survives view redeploys, DataStore
     *  resets and cluster type (OpenShift/vanilla) — independent of the view's own metadata store. */
    public static final String KDPS_MANAGED_LABEL_KEY = "kdps.clemlab.com/managed-by";
    public static final String KDPS_MANAGED_LABEL_VALUE = "k8s-view";

    /**
     * Stamp the KDPS "managed-by" label onto the Helm release storage Secret(s) of a release. Helm 3
     * keeps release state in Secrets labeled {@code owner=helm,name=<release>} (one per revision); we
     * add {@code kdps.clemlab.com/managed-by=k8s-view} to each so {@link #listKdpsManagedReleaseKeys()}
     * can later recognise the release as KDPS-deployed. Best-effort: a failure here never breaks the
     * deploy (the DataStore metadata is still written as before).
     *
     * @param namespace   the release namespace
     * @param releaseName the Helm release name
     * @return the number of Helm release Secrets found for this release (0 while Helm has not yet
     *         written the release state — the caller can poll on this to tag as soon as it appears,
     *         so pending/failed releases are tagged too, not only successful ones)
     */
    public int tagHelmReleaseSecrets(String namespace, String releaseName) {
        if (client == null || namespace == null || namespace.isBlank() || releaseName == null || releaseName.isBlank()) {
            return 0;
        }
        try {
            List<Secret> releaseSecrets = client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .withLabel("name", releaseName)
                    .list().getItems();
            for (Secret s : releaseSecrets) {
                if (s.getMetadata() == null) continue;
                Map<String, String> labels = s.getMetadata().getLabels();
                if (labels == null) {
                    labels = new HashMap<>();
                    s.getMetadata().setLabels(labels);
                }
                if (KDPS_MANAGED_LABEL_VALUE.equals(labels.get(KDPS_MANAGED_LABEL_KEY))) {
                    continue; // already tagged
                }
                labels.put(KDPS_MANAGED_LABEL_KEY, KDPS_MANAGED_LABEL_VALUE);
                client.secrets().inNamespace(namespace).withName(s.getMetadata().getName()).patch(s);
            }
            if (!releaseSecrets.isEmpty()) {
                LOG.info("Tagged {} Helm release Secret(s) for {}/{} with {}={}",
                        releaseSecrets.size(), namespace, releaseName, KDPS_MANAGED_LABEL_KEY, KDPS_MANAGED_LABEL_VALUE);
            }
            return releaseSecrets.size();
        } catch (Exception ex) {
            LOG.warn("tagHelmReleaseSecrets failed for {}/{}: {}", namespace, releaseName, ex.toString());
            return 0;
        }
    }

    /**
     * The set of Helm releases KDPS has deployed, read from the k8s-native label
     * {@link #KDPS_MANAGED_LABEL_KEY} stamped on their release Secrets (see
     * {@link #tagHelmReleaseSecrets}). Returned as {@code "<namespace>/<releaseName>"} keys, where the
     * release name comes from the Helm {@code name} label on the Secret (its own name is
     * {@code sh.helm.release.v1.<release>.v<rev>}). This is the robust source of truth for the Releases
     * page "deployed by KDPS" default filter — it does not depend on the view's DataStore metadata,
     * which can go missing across a view rebuild/redeploy or on a different cluster.
     *
     * <p>Scoped to the given namespaces (one label-list call per namespace) rather than cluster-wide:
     * the view's ServiceAccount on a real OpenShift is typically namespace-scoped, so a cluster-wide
     * {@code secrets().inAnyNamespace()} would 403 and silently return nothing (the bug that made the
     * Releases page empty at a customer even though the release Secrets WERE labelled). We only ever
     * need the namespaces that the release listing already returned — which the SA can read by
     * definition, since it just listed releases there.
     *
     * @param namespaces the namespaces to check (typically the distinct namespaces of the listed releases)
     * @return set of "namespace/releaseName"; empty on any error (caller degrades to DataStore metadata)
     */
    public java.util.Set<String> listKdpsManagedReleaseKeys(java.util.Collection<String> namespaces) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (client == null || namespaces == null || namespaces.isEmpty()) return keys;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (String ns : namespaces) {
            if (ns != null && !ns.isBlank()) distinct.add(ns);
        }
        for (String ns : distinct) {
            try {
                List<Secret> secrets = client.secrets().inNamespace(ns)
                        .withLabel("owner", "helm")
                        .withLabel(KDPS_MANAGED_LABEL_KEY, KDPS_MANAGED_LABEL_VALUE)
                        .list().getItems();
                for (Secret s : secrets) {
                    if (s.getMetadata() == null || s.getMetadata().getLabels() == null) continue;
                    String rel = s.getMetadata().getLabels().get("name"); // Helm stores the release name here
                    if (rel != null && !rel.isBlank()) {
                        keys.add(ns + "/" + rel);
                    }
                }
            } catch (Exception ex) {
                LOG.warn("listKdpsManagedReleaseKeys: namespace {} failed: {}", ns, ex.toString());
            }
        }
        return keys;
    }

    /**
     * Build a release's Helm revision history from its Helm release Secrets
     * ({@code sh.helm.release.v1.<name>.v<N>}) using the k8s API — no {@code helm} CLI binary (the Ambari
     * server has none, which caused "Cannot run program helm") and no cluster-wide permission
     * (namespace-scoped, like the KDPS label read). Each Secret's {@code release} field is
     * {@code base64(base64(gzip(JSON)))}; we decode it and surface the same fields as
     * {@code helm history -o json}: revision, updated, status, chart, app_version, description.
     *
     * @param namespace   release namespace
     * @param releaseName Helm release name
     * @return revisions ordered ascending by revision number
     */
    public List<Map<String, Object>> helmReleaseHistory(String namespace, String releaseName) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (client == null || namespace == null || namespace.isBlank()
                || releaseName == null || releaseName.isBlank()) {
            return out;
        }
        try {
            List<Secret> secrets = client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm").withLabel("name", releaseName)
                    .list().getItems();
            for (Secret s : secrets) {
                if (s.getData() == null || s.getData().get("release") == null) continue;
                Map<String, Object> rel = decodeHelmReleasePayload(s.getData().get("release"));
                if (rel == null) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                Object version = rel.get("version");
                e.put("revision", version instanceof Number ? ((Number) version).intValue() : version);
                Object infoObj = rel.get("info");
                if (infoObj instanceof Map) {
                    Map<?, ?> info = (Map<?, ?>) infoObj;
                    e.put("status", info.get("status"));
                    e.put("updated", info.get("last_deployed"));
                    e.put("description", info.get("description"));
                }
                Object chartObj = rel.get("chart");
                if (chartObj instanceof Map) {
                    Object metaObj = ((Map<?, ?>) chartObj).get("metadata");
                    if (metaObj instanceof Map) {
                        Map<?, ?> meta = (Map<?, ?>) metaObj;
                        e.put("chart", meta.get("name") + "-" + meta.get("version"));
                        e.put("app_version", meta.get("appVersion"));
                    }
                }
                out.add(e);
            }
            out.sort(java.util.Comparator.comparingInt(x -> {
                Object r = x.get("revision");
                try {
                    return r instanceof Number ? ((Number) r).intValue() : Integer.parseInt(String.valueOf(r));
                } catch (Exception ex) {
                    return 0;
                }
            }));
        } catch (Exception ex) {
            LOG.warn("helmReleaseHistory failed for {}/{}: {}", namespace, releaseName, ex.toString());
            throw new RuntimeException("Failed to read helm history for " + namespace + "/" + releaseName
                    + ": " + ex.getMessage(), ex);
        }
        return out;
    }

    /**
     * The DEPLOYED chart version of a release, read from its latest Helm release Secret's
     * {@code chart.metadata.version} — i.e. what is actually running on the cluster (the source of
     * truth), NOT a remote registry lookup and NOT a stale DB value. Used as the accurate fallback for
     * the Releases list when the version can't be parsed from the {@code helm list} chart string.
     * Namespace-scoped; returns null when unavailable.
     *
     * @param namespace   release namespace
     * @param releaseName Helm release name
     * @return the deployed chart version (e.g. {@code 1.43.6}) or null
     */
    public String deployedChartVersion(String namespace, String releaseName) {
        if (client == null || namespace == null || namespace.isBlank()
                || releaseName == null || releaseName.isBlank()) {
            return null;
        }
        try {
            List<Secret> secrets = client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm").withLabel("name", releaseName)
                    .list().getItems();
            Secret latest = null;
            int maxRev = -1;
            for (Secret s : secrets) {
                if (s.getMetadata() == null || s.getMetadata().getLabels() == null) continue;
                String v = s.getMetadata().getLabels().get("version");
                int rev = -1;
                try { rev = v != null ? Integer.parseInt(v) : -1; } catch (NumberFormatException ignore) { }
                if (rev > maxRev) { maxRev = rev; latest = s; }
            }
            if (latest == null || latest.getData() == null || latest.getData().get("release") == null) {
                return null;
            }
            Map<String, Object> rel = decodeHelmReleasePayload(latest.getData().get("release"));
            if (rel != null && rel.get("chart") instanceof Map) {
                Object metaObj = ((Map<?, ?>) rel.get("chart")).get("metadata");
                if (metaObj instanceof Map) {
                    Object ver = ((Map<?, ?>) metaObj).get("version");
                    return ver != null ? String.valueOf(ver) : null;
                }
            }
        } catch (Exception ex) {
            LOG.warn("deployedChartVersion failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }
        return null;
    }

    /** Decode a Helm release Secret's {@code release} field: k8s-base64 → helm-base64 → gzip → JSON map. */
    private Map<String, Object> decodeHelmReleasePayload(String k8sBase64Value) {
        try {
            byte[] helmB64 = java.util.Base64.getDecoder().decode(k8sBase64Value.trim());
            byte[] gz = java.util.Base64.getMimeDecoder()
                    .decode(new String(helmB64, java.nio.charset.StandardCharsets.UTF_8).trim());
            try (java.util.zip.GZIPInputStream gis =
                         new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(gis.readAllBytes(), Map.class);
                return m;
            }
        } catch (Exception e) {
            LOG.warn("decodeHelmReleasePayload failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Fetch a Secret by name from a namespace, or null if absent.
     * Public helper used by ReleaseTlsService to read tls.crt for cert parsing.
     */
    public io.fabric8.kubernetes.api.model.Secret getSecret(String namespace, String name) {
        checkConfiguration();
        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) return null;
        return client.secrets().inNamespace(namespace).withName(name).get();
    }

    /**
     * Lists all Ingress objects belonging to a Helm release within a namespace.
     * Matches via standard Helm labels first ({@code app.kubernetes.io/instance}),
     * falls back to a name-prefix match for charts that don't set the canonical label.
     */
    public java.util.List<io.fabric8.kubernetes.api.model.networking.v1.Ingress>
            listIngressesForRelease(String namespace, String releaseName) {
        checkConfiguration();
        if (namespace == null || namespace.isBlank() || releaseName == null || releaseName.isBlank()) {
            return java.util.Collections.emptyList();
        }
        var byLabel = client.network().v1().ingresses().inNamespace(namespace)
                .withLabel("app.kubernetes.io/instance", releaseName).list().getItems();
        if (!byLabel.isEmpty()) return byLabel;
        // Helm releases like the bundled bitnami charts don't always set the label.
        // Fall back to a case-insensitive prefix match on metadata.name.
        String prefix = releaseName.toLowerCase(java.util.Locale.ROOT);
        return client.network().v1().ingresses().inNamespace(namespace).list().getItems().stream()
                .filter(i -> i.getMetadata() != null
                        && i.getMetadata().getName() != null
                        && i.getMetadata().getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                .toList();
    }

    // -------------------------------------------------------------------------
    // cert-manager + external-secrets generic CR helpers
    // -------------------------------------------------------------------------
    // These use Fabric8's GenericKubernetesResource API so we don't need to import
    // the cert-manager / external-secrets client libraries — keeps the JAR slim
    // and avoids version coupling. The CR shapes we write are stable since the
    // GA v1 release of each operator.

    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext CERT_MANAGER_CLUSTER_ISSUER_RDC =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup("cert-manager.io")
                    .withVersion("v1")
                    .withKind("ClusterIssuer")
                    .withPlural("clusterissuers")
                    .withNamespaced(false)
                    .build();

    // ESO is v1 GA since 0.15 (March 2025). Older clusters (0.9-0.14) only have v1beta1.
    // We pick at runtime based on which version the cluster's CRD advertises as served.
    private volatile String externalSecretsApiVersion; // "v1" or "v1beta1"

    private String esoApiVersion() {
        String cached = externalSecretsApiVersion;
        if (cached != null) return cached;
        try {
            var crd = client.apiextensions().v1().customResourceDefinitions()
                    .withName("externalsecrets.external-secrets.io").get();
            String picked = "v1beta1";
            if (crd != null && crd.getSpec() != null && crd.getSpec().getVersions() != null) {
                // Prefer v1 (GA) over v1beta1 when both are served.
                boolean hasV1 = crd.getSpec().getVersions().stream().anyMatch(v -> "v1".equals(v.getName()) && Boolean.TRUE.equals(v.getServed()));
                if (hasV1) picked = "v1";
            }
            externalSecretsApiVersion = picked;
            LOG.info("external-secrets API version detected: {}", picked);
            return picked;
        } catch (Exception ex) {
            if (NamespaceScope.isForbidden(ex)) {
                try {
                    String picked = namespaceScope.servedApiVersions("external-secrets.io").contains("v1") ? "v1" : "v1beta1";
                    externalSecretsApiVersion = picked;
                    LOG.info("external-secrets API version detected from API discovery: {}", picked);
                    return picked;
                } catch (RuntimeException discoveryEx) {
                    LOG.debug("API discovery for external-secrets.io failed: {}", discoveryEx.toString());
                }
            }
            LOG.warn("Falling back to external-secrets.io/v1beta1: {}", ex.toString());
            externalSecretsApiVersion = "v1beta1";
            return "v1beta1";
        }
    }

    private io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext externalSecretClusterStoreRdc() {
        return new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                .withGroup("external-secrets.io")
                .withVersion(esoApiVersion())
                .withKind("ClusterSecretStore")
                .withPlural("clustersecretstores")
                .withNamespaced(false)
                .build();
    }

    private io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext externalSecretStoreRdc() {
        return new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                .withGroup("external-secrets.io")
                .withVersion(esoApiVersion())
                .withKind("SecretStore")
                .withPlural("secretstores")
                .withNamespaced(true)
                .build();
    }

    private io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext externalSecretRdc() {
        return new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                .withGroup("external-secrets.io")
                .withVersion(esoApiVersion())
                .withKind("ExternalSecret")
                .withPlural("externalsecrets")
                .withNamespaced(true)
                .build();
    }

    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext CERT_MANAGER_CERT_RDC =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup("cert-manager.io")
                    .withVersion("v1")
                    .withKind("Certificate")
                    .withPlural("certificates")
                    .withNamespaced(true)
                    .build();

    // EXTERNAL_SECRET_RDC is built dynamically via externalSecretRdc() below — see esoApiVersion().

    /**
     * Apply a cert-manager.io/v1 Certificate. Creates or updates by name.
     *
     * @param certName     Certificate metadata name (we use the target Secret name by convention)
     * @param secretName   the Secret cert-manager will write the leaf into
     * @param issuerName   ClusterIssuer or Issuer name in the cluster
     * @param issuerKind   "ClusterIssuer" (default) or "Issuer"
     * @param dnsNames     SANs to put on the leaf
     * @param durationHours leaf validity in hours; cert-manager defaults to 2160 (90d)
     */
    public void applyCertManagerCertificate(String namespace, String certName, String secretName,
                                            String issuerName, String issuerKind,
                                            java.util.List<String> dnsNames, Integer durationHours,
                                            Map<String, Object> subject) {
        checkConfiguration();
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(certName, "certName");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(issuerName, "issuerName");
        if (dnsNames == null || dnsNames.isEmpty()) {
            throw new IllegalArgumentException("dnsNames must include at least one host");
        }

        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("secretName", secretName);
        spec.put("dnsNames", dnsNames);

        // Subject DN. Some CAs enforce an end-entity profile that mandates DN elements (CN, OU, O, or
        // custom fields), so we let the caller supply them. cert-manager's `literalSubject` (needs the
        // LiteralCertificateSubject feature gate) is the only way to emit non-standard DN OIDs (e.g. a
        // custom "Team" attribute); it is mutually exclusive with commonName/subject. Otherwise we set
        // commonName (defaulting to the first host so the ubiquitous "CN is mandatory" rejection is
        // avoided) plus standard organizations / organizationalUnits.
        Map<String, Object> subj = subject == null ? java.util.Collections.emptyMap() : subject;
        String literalSubject = subj.get("literalSubject") == null ? null : String.valueOf(subj.get("literalSubject")).trim();
        if (literalSubject != null && !literalSubject.isEmpty()) {
            spec.put("literalSubject", literalSubject);
        } else {
            String commonName = subj.get("commonName") == null ? null : String.valueOf(subj.get("commonName")).trim();
            if (commonName == null || commonName.isEmpty()) {
                commonName = dnsNames.get(0);
            }
            spec.put("commonName", commonName);
            Map<String, Object> subjectBlock = new java.util.LinkedHashMap<>();
            for (String key : new String[]{"organizations", "organizationalUnits", "countries",
                    "localities", "provinces", "streetAddresses", "postalCodes"}) {
                Object v = subj.get(key);
                if (v instanceof java.util.List<?> l && !l.isEmpty()) {
                    subjectBlock.put(key, l);
                }
            }
            if (!subjectBlock.isEmpty()) {
                spec.put("subject", subjectBlock);
            }
        }

        // duration controls the validity. renewBefore is set to 1/6 of duration so
        // cert-manager rotates well before expiry without thrashing.
        int dur = durationHours == null ? 2160 : durationHours;
        spec.put("duration", dur + "h");
        spec.put("renewBefore", Math.max(dur / 6, 24) + "h");
        Map<String, Object> issuerRef = new java.util.LinkedHashMap<>();
        issuerRef.put("name", issuerName);
        issuerRef.put("kind", issuerKind == null || issuerKind.isBlank() ? "ClusterIssuer" : issuerKind);
        issuerRef.put("group", "cert-manager.io");
        spec.put("issuerRef", issuerRef);

        var resource = buildGenericCr("cert-manager.io/v1", "Certificate",
                certName, namespace,
                Map.of("managed-by", "ambari-k8s-view"),
                Map.of("spec", spec));
        upsertGenericCr(
                client.genericKubernetesResources(CERT_MANAGER_CERT_RDC).inNamespace(namespace).withName(certName),
                resource);
        LOG.info("cert-manager.Certificate {}/{} applied (issuer={}/{}, sans={})", namespace, certName, issuerKind, issuerName, dnsNames);
    }

    /**
     * Build a {@link io.fabric8.kubernetes.api.model.GenericKubernetesResource} with the
     * apiVersion, kind and metadata fields properly initialised (the default constructor
     * leaves them null, so direct mutation of {@code resource.getMetadata()} throws). The
     * extra fields (spec, status, custom fields) come via {@code additionalProperties}.
     */
    private static io.fabric8.kubernetes.api.model.GenericKubernetesResource buildGenericCr(
            String apiVersion, String kind, String name, String namespace,
            Map<String, String> labels, Map<String, Object> additional) {
        var resource = new io.fabric8.kubernetes.api.model.GenericKubernetesResource();
        resource.setApiVersion(apiVersion);
        resource.setKind(kind);
        var meta = new io.fabric8.kubernetes.api.model.ObjectMeta();
        meta.setName(name);
        if (namespace != null && !namespace.isBlank()) meta.setNamespace(namespace);
        if (labels != null && !labels.isEmpty()) meta.setLabels(new java.util.HashMap<>(labels));
        resource.setMetadata(meta);
        if (additional != null) {
            // GenericKubernetesResource stores unknown fields in additionalProperties; we
            // can only put one key at a time, so iterate.
            for (Map.Entry<String, Object> e : additional.entrySet()) {
                resource.setAdditionalProperty(e.getKey(), e.getValue());
            }
        }
        return resource;
    }

    /**
     * Read-or-create-or-replace for a generic CR by name. The withName(String) call on
     * either MixedOperation or NonNamespaceOperation returns the same
     * {@link io.fabric8.kubernetes.client.dsl.Resource} interface — passing a pre-bound
     * Resource sidesteps the parameter-type incompatibility between the two operation
     * types. Caller does: {@code upsertGenericCr(gkr.withName(name), resource)}.
     */
    private static void upsertGenericCr(
            io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.GenericKubernetesResource> handle,
            io.fabric8.kubernetes.api.model.GenericKubernetesResource resource) {
        var existing = handle.get();
        if (existing == null) {
            handle.create(resource);
        } else {
            resource.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            handle.patch(resource);
        }
    }

    /**
     * Patch a Certificate by merging the supplied map (typically annotations) into its metadata.
     * Used by the renew flow to force re-issue.
     *
     * <p>Implementation note: we cannot stuff a {@code metadata} key into the CR's
     * {@code additionalProperties}, because for a {@link io.fabric8.kubernetes.api.model.GenericKubernetesResource}
     * {@code metadata} is a typed top-level field (the resource implements
     * {@code HasMetadata}). Doing so produces a patch body where the API server
     * cannot determine the resource name → HTTP 400 "name must be provided".
     * Instead we update the typed metadata directly and let fabric8 send a
     * proper patch against it.
     */
    public void patchCertManagerCertificate(String namespace, String certName, Map<String, Object> patch) {
        checkConfiguration();
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(certName, "certName");
        var gkr = client.genericKubernetesResources(CERT_MANAGER_CERT_RDC).inNamespace(namespace).withName(certName);
        var existing = gkr.get();
        if (existing == null) {
            throw new IllegalArgumentException("Certificate " + namespace + "/" + certName + " not found");
        }
        // Special-case the common shape {metadata: {annotations: {...}, labels: {...}}}.
        // This is the only shape callers use today and it's what hits the typed
        // metadata path correctly.
        Object metaIncoming = patch.get("metadata");
        if (metaIncoming instanceof Map<?, ?> metaMap) {
            io.fabric8.kubernetes.api.model.ObjectMeta meta = existing.getMetadata();
            if (meta == null) {
                meta = new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().build();
                existing.setMetadata(meta);
            }
            Object annIncoming = metaMap.get("annotations");
            if (annIncoming instanceof Map<?, ?> annMap) {
                Map<String, String> ann = meta.getAnnotations() != null
                        ? new LinkedHashMap<>(meta.getAnnotations()) : new LinkedHashMap<>();
                annMap.forEach((k, v) -> { if (k != null && v != null) ann.put(String.valueOf(k), String.valueOf(v)); });
                meta.setAnnotations(ann);
            }
            Object lblIncoming = metaMap.get("labels");
            if (lblIncoming instanceof Map<?, ?> lblMap) {
                Map<String, String> lbl = meta.getLabels() != null
                        ? new LinkedHashMap<>(meta.getLabels()) : new LinkedHashMap<>();
                lblMap.forEach((k, v) -> { if (k != null && v != null) lbl.put(String.valueOf(k), String.valueOf(v)); });
                meta.setLabels(lbl);
            }
        }
        // Anything else (e.g. spec tweaks) is still expected as additionalProperties.
        Map<String, Object> rest = new LinkedHashMap<>(patch);
        rest.remove("metadata");
        if (!rest.isEmpty()) {
            mergeAdditionalProperties(existing.getAdditionalProperties(), rest);
        }
        gkr.patch(existing);
        LOG.info("cert-manager.Certificate {}/{} patched", namespace, certName);
    }


    /**
     * Apply an external-secrets.io/v1 ExternalSecret. Creates or updates by name.
     *
     * @param esName          ExternalSecret metadata name
     * @param secretName      target K8s Secret name ESO will write
     * @param storeName       SecretStore / ClusterSecretStore name
     * @param storeKind       "ClusterSecretStore" (default) or "SecretStore"
     * @param remoteKey       path/key in the upstream secret store (e.g. "pki/issue/clemlab/foo")
     * @param refreshInterval ESO refresh cadence (e.g. "1h"; "0" disables periodic refresh)
     */
    public void applyExternalSecret(String namespace, String esName, String secretName,
                                    String storeName, String storeKind, String remoteKey,
                                    String refreshInterval) {
        checkConfiguration();
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(esName, "esName");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(storeName, "storeName");
        Objects.requireNonNull(remoteKey, "remoteKey");

        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("refreshInterval", refreshInterval == null || refreshInterval.isBlank() ? "1h" : refreshInterval);
        Map<String, Object> storeRef = new java.util.LinkedHashMap<>();
        storeRef.put("name", storeName);
        storeRef.put("kind", storeKind == null || storeKind.isBlank() ? "ClusterSecretStore" : storeKind);
        spec.put("secretStoreRef", storeRef);
        Map<String, Object> target = new java.util.LinkedHashMap<>();
        target.put("name", secretName);
        target.put("creationPolicy", "Owner");
        spec.put("target", target);
        Map<String, Object> dataFrom = new java.util.LinkedHashMap<>();
        Map<String, Object> extract = new java.util.LinkedHashMap<>();
        extract.put("key", remoteKey);
        dataFrom.put("extract", extract);
        spec.put("dataFrom", java.util.List.of(dataFrom));

        var resource = buildGenericCr("external-secrets.io/" + esoApiVersion(), "ExternalSecret",
                esName, namespace,
                Map.of("managed-by", "ambari-k8s-view"),
                Map.of("spec", spec));
        upsertGenericCr(
                client.genericKubernetesResources(externalSecretRdc()).inNamespace(namespace).withName(esName),
                resource);
        LOG.info("external-secrets.ExternalSecret {}/{} applied (store={}/{}, key={})", namespace, esName, storeKind, storeName, remoteKey);
    }

    /**
     * Create/update a cert-manager.io/v1 ClusterIssuer of kind {@code ca} that wraps an
     * existing {@code kubernetes.io/tls} Secret (typically a Company CA uploaded via the
     * PKI registry). Idempotent: replaces if the ClusterIssuer already exists.
     *
     * @param issuerName     ClusterIssuer name to create (cluster-scoped)
     * @param caNamespace    namespace where the CA Secret lives (must be readable by
     *                       the cert-manager controller — by default cert-manager has
     *                       cluster-wide Secret read perms only in its own namespace; we
     *                       rely on the CA Registry's namespace ({@code ambari-pki}) being
     *                       exposed to cert-manager via the standard installation)
     * @param caSecretName   name of the {@code kubernetes.io/tls} Secret holding tls.crt + tls.key
     */
    public void applyCertManagerClusterIssuerFromCa(String issuerName, String caNamespace, String caSecretName) {
        checkConfiguration();
        Objects.requireNonNull(issuerName, "issuerName");
        Objects.requireNonNull(caNamespace, "caNamespace");
        Objects.requireNonNull(caSecretName, "caSecretName");

        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        // The ca.secretName must be in cert-manager's own namespace (default: cert-manager).
        // The Secret has to be replicated there. We handle that by either:
        //   - mirroring the Secret into cert-manager's namespace, OR
        //   - using a Namespaced Issuer in the source namespace (less general).
        // Mirroring is simpler from a UI perspective; do it here.
        ensureCaSecretMirroredToCertManagerNamespace(caNamespace, caSecretName);
        Map<String, Object> ca = new java.util.LinkedHashMap<>();
        ca.put("secretName", caSecretName);
        spec.put("ca", ca);

        var labels = new java.util.LinkedHashMap<String, String>();
        labels.put("managed-by", "ambari-k8s-view");
        labels.put("ambari.clemlab.com/source-ca-namespace", caNamespace);
        labels.put("ambari.clemlab.com/source-ca-secret", caSecretName);
        var resource = buildGenericCr("cert-manager.io/v1", "ClusterIssuer",
                issuerName, null, labels,
                Map.of("spec", spec));
        upsertGenericCr(
                client.genericKubernetesResources(CERT_MANAGER_CLUSTER_ISSUER_RDC).withName(issuerName),
                resource);
        LOG.info("cert-manager.ClusterIssuer {} applied (CA secret={}/{})", issuerName, caNamespace, caSecretName);
    }

    /**
     * cert-manager only reads ca.secretName from its own namespace (default {@code cert-manager}).
     * Our CA registry stores Secrets in {@code ambari-pki}; we therefore copy the Secret into
     * cert-manager's namespace whenever a CA is promoted. The mirror Secret is read-only —
     * any future update must come via the registry's promote action (idempotent overwrite).
     */
    private void ensureCaSecretMirroredToCertManagerNamespace(String sourceNs, String secretName) {
        final String certManagerNs = "cert-manager";
        Secret src = client.secrets().inNamespace(sourceNs).withName(secretName).get();
        if (src == null) {
            throw new IllegalStateException("Source CA Secret " + sourceNs + "/" + secretName + " not found");
        }
        Secret mirror = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(certManagerNs)
                    .withLabels(Map.of(
                            "managed-by", "ambari-k8s-view",
                            "ambari.clemlab.com/mirror-of-namespace", sourceNs))
                    .withAnnotations(Map.of(
                            "ambari.clemlab.com/mirrored-at", java.time.Instant.now().toString()))
                    .endMetadata()
                .withType(src.getType())
                .withData(src.getData())
                .build();
        try {
            client.secrets().inNamespace(certManagerNs).resource(mirror).create();
        } catch (KubernetesClientException e) {
            // Already exists or namespace missing — try createOrReplace as fallback.
            try {
                createNamespace(certManagerNs);
                client.secrets().inNamespace(certManagerNs).resource(mirror).createOrReplace();
            } catch (Exception fallback) {
                throw new IllegalStateException("Failed to mirror CA secret to " + certManagerNs + ": " + fallback.getMessage(), fallback);
            }
        }
        LOG.info("Mirrored CA Secret {}/{} into {}/{}", sourceNs, secretName, certManagerNs, secretName);
    }

    /**
     * Returns true if the ClusterIssuer's Ready condition is True. Used by promote/discovery
     * to surface live status in the UI.
     */
    @SuppressWarnings("unchecked")
    public boolean isClusterIssuerReady(String issuerName) {
        checkConfiguration();
        try {
            var ci = client.genericKubernetesResources(CERT_MANAGER_CLUSTER_ISSUER_RDC).withName(issuerName).get();
            if (ci == null) return false;
            var add = ci.getAdditionalProperties();
            if (add == null) return false;
            Object status = add.get("status");
            if (status instanceof Map<?, ?> stm && stm.get("conditions") instanceof java.util.List<?> conds) {
                for (Object c : conds) {
                    if (c instanceof Map<?, ?> cm
                            && "Ready".equals(cm.get("type"))
                            && "True".equals(cm.get("status"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    /**
     * Enumerate cert-manager ClusterIssuer resources, optionally filtering to Ready=True only.
     * Returns maps of {name, kind, type, ready} where {@code type} is best-effort inferred from
     * the spec (ca / acme / vault / selfSigned).
     */
    public java.util.List<java.util.Map<String, Object>> listClusterIssuers(boolean includeNotReady) {
        checkConfiguration();
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        try {
            var list = client.genericKubernetesResources(CERT_MANAGER_CLUSTER_ISSUER_RDC).list().getItems();
            for (var ci : list) {
                java.util.Map<String, Object> entry = describeIssuerLike(ci, "ClusterIssuer");
                if (includeNotReady || Boolean.TRUE.equals(entry.get("ready"))) result.add(entry);
            }
        } catch (Exception ex) {
            logDiscoveryFailure("listClusterIssuers", ex);
        }
        return result;
    }

    /**
     * Logs a failed optional discovery (cert-manager issuers, External Secrets stores, CRDs). Not being allowed to read
     * them, or the operator not being installed, is normal for many accounts and clusters: the wizard just offers
     * nothing there. Only other failures are worth a warning; this runs every time the wizard opens.
     */
    private static void logDiscoveryFailure(String what, Exception ex) {
        boolean expected = NamespaceScope.isForbidden(ex)
                || (ex instanceof KubernetesClientException kce && kce.getCode() == 404);
        if (expected) {
            LOG.debug("{}: not available to this account or not installed ({})", what, ex.getMessage());
        } else {
            LOG.warn("{} failed: {}", what, ex.toString());
        }
    }

    /**
     * Enumerate external-secrets ClusterSecretStore + SecretStore resources, optionally
     * filtering to Ready=True only. Maps include {name, namespace, kind, provider, ready}.
     */
    public java.util.List<java.util.Map<String, Object>> listSecretStores(boolean includeNotReady) {
        checkConfiguration();
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        try {
            var clusterStores = client.genericKubernetesResources(externalSecretClusterStoreRdc()).list().getItems();
            for (var s : clusterStores) {
                java.util.Map<String, Object> entry = describeStoreLike(s, "ClusterSecretStore");
                if (includeNotReady || Boolean.TRUE.equals(entry.get("ready"))) result.add(entry);
            }
        } catch (Exception ex) {
            logDiscoveryFailure("listClusterSecretStores", ex);
        }
        try {
            var storeOp = client.genericKubernetesResources(externalSecretStoreRdc());
            var nsStores = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.SECRET_STORES,
                    () -> storeOp.inAnyNamespace().list().getItems(),
                    ns -> storeOp.inNamespace(ns).list().getItems());
            for (var s : nsStores) {
                java.util.Map<String, Object> entry = describeStoreLike(s, "SecretStore");
                if (includeNotReady || Boolean.TRUE.equals(entry.get("ready"))) result.add(entry);
            }
        } catch (Exception ex) {
            logDiscoveryFailure("listSecretStores", ex);
        }
        return result;
    }

    /** Build an issuer description {name, kind, type, ready}. {@code type} inferred from spec keys. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> describeIssuerLike(io.fabric8.kubernetes.api.model.GenericKubernetesResource r, String kind) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", r.getMetadata() != null ? r.getMetadata().getName() : "");
        out.put("kind", kind);
        out.put("type", "unknown");
        out.put("ready", false);
        java.util.Map<String, Object> add = r.getAdditionalProperties();
        if (add == null) return out;
        Object spec = add.get("spec");
        if (spec instanceof java.util.Map<?, ?> sm) {
            // cert-manager issuer spec has exactly one of: acme, ca, vault, selfSigned, venafi.
            for (String k : new String[]{"acme", "ca", "vault", "selfSigned", "venafi"}) {
                if (sm.get(k) != null) { out.put("type", k); break; }
            }
        }
        Object status = add.get("status");
        if (status instanceof java.util.Map<?, ?> stm && stm.get("conditions") instanceof java.util.List<?> conds) {
            for (Object c : conds) {
                if (c instanceof java.util.Map<?, ?> cm
                        && "Ready".equals(cm.get("type"))
                        && "True".equals(cm.get("status"))) {
                    out.put("ready", true);
                    break;
                }
            }
        }
        return out;
    }

    /** Build a secret-store description {name, namespace, kind, provider, ready}. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> describeStoreLike(io.fabric8.kubernetes.api.model.GenericKubernetesResource r, String kind) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", r.getMetadata() != null ? r.getMetadata().getName() : "");
        out.put("namespace", r.getMetadata() != null ? r.getMetadata().getNamespace() : "");
        out.put("kind", kind);
        out.put("provider", "unknown");
        out.put("ready", false);
        java.util.Map<String, Object> add = r.getAdditionalProperties();
        if (add == null) return out;
        Object spec = add.get("spec");
        if (spec instanceof java.util.Map<?, ?> sm && sm.get("provider") instanceof java.util.Map<?, ?> pm) {
            // First non-empty provider sub-key wins (vault / aws / gcp / azurekv / kubernetes / ...).
            for (java.util.Map.Entry<?, ?> e : pm.entrySet()) {
                if (e.getValue() != null) { out.put("provider", String.valueOf(e.getKey())); break; }
            }
        }
        Object status = add.get("status");
        if (status instanceof java.util.Map<?, ?> stm && stm.get("conditions") instanceof java.util.List<?> conds) {
            for (Object c : conds) {
                if (c instanceof java.util.Map<?, ?> cm
                        && "Ready".equals(cm.get("type"))
                        && "True".equals(cm.get("status"))) {
                    out.put("ready", true);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Patch an ExternalSecret by merging metadata (typically a force-sync annotation).
     */
    public void patchExternalSecret(String namespace, String esName, Map<String, Object> patch) {
        checkConfiguration();
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(esName, "esName");
        var gkr = client.genericKubernetesResources(externalSecretRdc()).inNamespace(namespace).withName(esName);
        var existing = gkr.get();
        if (existing == null) {
            throw new IllegalArgumentException("ExternalSecret " + namespace + "/" + esName + " not found");
        }
        mergeAdditionalProperties(existing.getAdditionalProperties(), patch);
        gkr.patch(existing);
        LOG.info("external-secrets.ExternalSecret {}/{} patched", namespace, esName);
    }

    /**
     * Recursive shallow-merge for nested map patches (annotations / labels). Treats
     * leaf values as overwrites; only same-key nested objects get recursed into.
     * Used so a patch like {metadata: {annotations: {foo: bar}}} doesn't wipe existing labels.
     */
    @SuppressWarnings("unchecked")
    private static void mergeAdditionalProperties(Map<String, Object> target, Map<String, Object> patch) {
        if (target == null || patch == null) return;
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object existing = target.get(e.getKey());
            Object incoming = e.getValue();
            if (existing instanceof Map && incoming instanceof Map) {
                mergeAdditionalProperties((Map<String, Object>) existing, (Map<String, Object>) incoming);
            } else {
                target.put(e.getKey(), incoming);
            }
        }
    }

    /**
     * Returns the {@link ViewConfigurationService} for this service instance.
     *
     * @return the view configuration service
     */
    public ViewConfigurationService getConfigurationService(){
        return this.configurationService;
    }

    /**
     * Creates or updates a {@code kubernetes.io/dockerconfigjson} imagePullSecret from explicit credentials,
     * and patches the specified service accounts to reference it.
     *
     * @param repoId                 the repository ID (used for logging only)
     * @param namespace              the Kubernetes namespace where the secret will be created
     * @param secretName             the desired name of the imagePullSecret
     * @param userName               the registry username
     * @param password               the registry password
     * @param registryServer         the registry hostname (e.g. {@code registry.example.com})
     * @param serviceAccountsToPatch list of service account names to patch; may be {@code null}
     */
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

    /**
     * Ensures the persistent-volume mounts described by the spec are provisioned for the given release.
     *
     * @param namespace   the Kubernetes namespace
     * @param releaseName the Helm release name the mounts belong to
     * @param mounts      a map describing the mount configuration (structure is mount-manager specific)
     */
    public void createMounts(String namespace, String releaseName, Map<String,Object> mounts){
        // ensureMounts is idempotent (an existing PVC is left alone), so it runs on every call. It
        // used to run only while creating the manager, i.e. once per service instance: the second
        // deploy in the same JVM silently got no PVCs.
        // Built per call on the current client: a cached manager would keep a replaced connection.
        new MountManager(this.client).ensureMounts(namespace, releaseName, mounts);
    }

    /**
     * Verify that the requested namespace is within the operator-configured allowlist.
     * If the view property {@code k8s.view.allowed.namespaces} is set (comma-separated),
     * any Secret or ConfigMap write to a namespace outside that list is rejected.
     * When the property is absent or blank, all namespaces are permitted (backward-compatible).
     */
    private void checkNamespaceAllowed(String namespace) {
        String allowedProp = (viewContext == null || viewContext.getProperties() == null)
                ? null
                : viewContext.getProperties().get("k8s.view.allowed.namespaces");
        if (allowedProp == null || allowedProp.isBlank()) return;
        Set<String> allowed = Arrays.stream(allowedProp.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.contains(namespace)) {
            throw new SecurityException("Namespace '" + namespace
                    + "' is not in the allowed list (k8s.view.allowed.namespaces). "
                    + "Permitted namespaces: " + allowed);
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
        checkNamespaceAllowed(namespace);
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
     * True when a ConfigMap with the given name exists in the namespace. Used to avoid clobbering an
     * operator-provided ConfigMap (e.g. a hand-populated hadoop-conf) with generated placeholder content.
     */
    public boolean configMapExists(String namespace, String name) {
        checkConfiguration();
        try {
            return client.configMaps().inNamespace(namespace).withName(name).get() != null;
        } catch (Exception ex) {
            LOG.warn("configMapExists check failed for {}/{}: {}", namespace, name, ex.toString());
            return false;
        }
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
        checkNamespaceAllowed(namespace);
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
     * Writes the keys of the Opaque Secret {@code namespace/secretName}: creates it, or sets these keys on the existing
     * one, keeping its other keys, labels and annotations. The labels and annotations given are added. No Helm
     * ownership is set, so a Helm uninstall of the release leaves the Secret in place.
     *
     * @param data key to value, stored as UTF-8
     */
    public void writeOpaqueSecretKeys(String namespace, String secretName, Map<String, String> data,
                                      Map<String, String> labels, Map<String, String> annotations) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(data, "data");
        checkNamespaceAllowed(namespace);
        checkConfiguration();
        Map<String, String> encoded = new LinkedHashMap<>();
        data.forEach((key, value) -> encoded.put(key,
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        try {
            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (existing == null) {
                Secret created = new SecretBuilder()
                        .withNewMetadata()
                            .withName(secretName)
                            .withNamespace(namespace)
                            .withLabels(labels == null ? Map.of() : labels)
                            .withAnnotations(annotations == null ? Map.of() : annotations)
                        .endMetadata()
                        .withType("Opaque")
                        .withData(encoded)
                        .build();
                client.secrets().inNamespace(namespace).resource(created).create();
                LOG.info("Created Secret '{}/{}' with keys {}", namespace, secretName, data.keySet());
                return;
            }
            client.secrets().inNamespace(namespace).withName(secretName).edit(secret -> {
                Map<String, String> merged = new LinkedHashMap<>(secret.getData() == null ? Map.of() : secret.getData());
                merged.putAll(encoded);
                secret.setData(merged);
                if (secret.getStringData() != null) secret.getStringData().keySet().removeAll(encoded.keySet());
                secret.getMetadata().setLabels(withAdded(secret.getMetadata().getLabels(), labels));
                secret.getMetadata().setAnnotations(withAdded(secret.getMetadata().getAnnotations(), annotations));
                return secret;
            });
            LOG.info("Set keys {} of Secret '{}/{}'", data.keySet(), namespace, secretName);
        } catch (KubernetesClientException e) {
            throw new RuntimeException("Failed to write Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Removes one annotation of the Secret {@code namespace/secretName}, leaving the rest of it as it is. A Secret that
     * does not exist, or does not carry the annotation, is left alone.
     */
    public void removeSecretAnnotation(String namespace, String secretName, String annotation) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(secretName, "secretName");
        Objects.requireNonNull(annotation, "annotation");
        checkNamespaceAllowed(namespace);
        checkConfiguration();
        try {
            Secret existing = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (existing == null || existing.getMetadata().getAnnotations() == null
                    || !existing.getMetadata().getAnnotations().containsKey(annotation)) {
                return;
            }
            client.secrets().inNamespace(namespace).withName(secretName).edit(secret -> {
                Map<String, String> annotations = new LinkedHashMap<>(secret.getMetadata().getAnnotations());
                annotations.remove(annotation);
                secret.getMetadata().setAnnotations(annotations);
                return secret;
            });
            LOG.info("Removed annotation {} from Secret '{}/{}'", annotation, namespace, secretName);
        } catch (KubernetesClientException e) {
            throw new RuntimeException("Failed to update Secret " + namespace + "/" + secretName + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> withAdded(Map<String, String> current, Map<String, String> added) {
        Map<String, String> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (added != null) merged.putAll(added);
        return merged;
    }

    /**
     * Whether the persistent volume claim {@code namespace/name} exists, e.g. the volume of a database that keeps the
     * passwords it was initialized with.
     *
     * @return {@code null} when that cannot be read (e.g. no permission to read volume claims)
     */
    public Boolean persistentVolumeClaimExists(String namespace, String name) {
        checkConfiguration();
        try {
            return client.persistentVolumeClaims().inNamespace(namespace).withName(name).get() != null;
        } catch (KubernetesClientException e) {
            LOG.warn("Could not read the volume claim {}/{}: {}", namespace, name, e.getMessage());
            return null;
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
            List<io.fabric8.kubernetes.api.model.Service> svcList = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.SERVICES,
                    () -> client.services().inAnyNamespace().withLabelSelector(selector).list().getItems(),
                    ns -> client.services().inNamespace(ns).withLabelSelector(selector).list().getItems());

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
                item.put("label", String.format("%s (%s)", name, ns));
                item.put("value", internalDns);
                item.put("port", String.valueOf(port));

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
            final boolean byValue = labelValue != null && !labelValue.equals("*");
            List<Secret> secrets = namespaceScope.listAcrossNamespaces(NamespaceScope.Resource.SECRETS,
                    () -> (byValue
                            ? client.secrets().inAnyNamespace().withLabel(labelKey, labelValue)
                            : client.secrets().inAnyNamespace().withLabel(labelKey)).list().getItems(),
                    ns -> (byValue
                            ? client.secrets().inNamespace(ns).withLabel(labelKey, labelValue)
                            : client.secrets().inNamespace(ns).withLabel(labelKey)).list().getItems());

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
     * Lists the Secrets in a single namespace that a human might plausibly select in a form picker —
     * e.g. a manually-created Kerberos keytab Secret for an EXTERNAL platform context (a standalone
     * Ambari, or a CDP backend in a different realm, where KDPS does not issue the keytab itself).
     *
     * <p>Unlike {@link #listSecretsByLabel(String, String)}, this is scoped to ONE namespace and does
     * NOT require a label: the keytab Secret the operator creates by hand (e.g.
     * {@code kubectl -n <ns> create secret generic trino-cdp-keytab ...}) carries no KDPS label, so a
     * label selector could never find it. Infrastructure Secrets that are never a user selection are
     * filtered out (image-pull creds, ServiceAccount tokens, Helm release state). The {@code description}
     * surfaces the Secret's data keys (e.g. "service.keytab, principal") so the operator can confirm at
     * a glance that it holds the expected material.
     *
     * @param namespace the namespace to list Secrets in (typically the release/deploy namespace)
     * @return list of {name, namespace, type, value, label, description} maps; empty if namespace is blank
     */
    public List<Map<String, String>> listSecretsInNamespace(String namespace) {
        checkConfiguration();
        if (namespace == null || namespace.isBlank()) {
            LOG.warn("listSecretsInNamespace called with a blank namespace; returning no secrets.");
            return Collections.emptyList();
        }
        LOG.info("Listing user-selectable secrets in namespace '{}'.", namespace);
        List<Secret> secrets = executeWithAuthRetry("list secrets in namespace " + namespace,
                () -> client.secrets().inNamespace(namespace).list().getItems());
        List<Map<String, String>> results = new ArrayList<>();
        for (Secret s : secrets) {
            if (s.getMetadata() == null) {
                continue;
            }
            String name = s.getMetadata().getName();
            String type = s.getType() == null ? "" : s.getType();
            if (isInfrastructureSecret(name, type)) {
                continue;
            }
            String keys = (s.getData() == null || s.getData().isEmpty())
                    ? "" : String.join(", ", new java.util.TreeSet<>(s.getData().keySet()));
            Map<String, String> item = new HashMap<>();
            item.put("name", name);
            item.put("namespace", namespace);
            item.put("type", type.isBlank() ? "Opaque" : type);
            item.put("value", name);
            item.put("label", name);
            item.put("description", keys.isBlank() ? type : ("keys: " + keys));
            results.add(item);
        }
        LOG.info("Namespace '{}' has {} user-selectable secret(s) after filtering infrastructure secrets.",
                namespace, results.size());
        return results;
    }

    /**
     * True for Secrets that are cluster/infrastructure plumbing and never a valid human selection in a
     * picker: image-pull dockercfg / dockerconfigjson, ServiceAccount tokens, and Helm release state.
     */
    private boolean isInfrastructureSecret(String name, String type) {
        if (type != null) {
            switch (type) {
                case "kubernetes.io/dockercfg":
                case "kubernetes.io/dockerconfigjson":
                case "kubernetes.io/service-account-token":
                    return true;
                default:
                    break;
            }
        }
        return name != null && name.startsWith("sh.helm.release.v1");
    }

    /** Label that marks a Secret as a configuration file managed from the Config Profiles page. */
    public static final String MANAGED_CONFIG_LABEL = "ambari.clemlab.com/managed-config";

    /**
     * The configuration Secret {@code namespace/name}, provided the view manages it as a configuration file
     * (label {@code ambari.clemlab.com/managed-config=true}). Any other Secret is reported as missing, so the
     * configuration endpoints cannot be used to read or delete keytabs, passwords, TLS keys or Helm state, and do
     * not even confirm that such a Secret exists.
     *
     * @param namespace Kubernetes namespace of the Secret
     * @param name      Secret name
     * @return the managed configuration Secret
     * @throws NoSuchElementException when there is no such Secret or it is not a managed configuration
     * @throws SecurityException      when the namespace is outside {@code k8s.view.allowed.namespaces}
     */
    public Secret requireManagedConfig(String namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        checkNamespaceAllowed(namespace);
        checkConfiguration();
        Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
        if (secret == null || !isManagedConfig(secret)) {
            throw new NoSuchElementException("No managed configuration named '" + name + "' in namespace '" + namespace + "'.");
        }
        return secret;
    }

    /**
     * Refuses to write a managed configuration over a Secret the view does not manage as one. Saving rewrites the
     * whole Secret with a single key, so replacing, say, a keytab Secret would destroy it.
     *
     * @param namespace Kubernetes namespace of the Secret
     * @param name      Secret name
     * @throws ManagedConfigConflictException when a Secret of that name exists without the managed-config label
     * @throws SecurityException              when the namespace is outside {@code k8s.view.allowed.namespaces}
     */
    public void requireWritableManagedConfig(String namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        checkNamespaceAllowed(namespace);
        checkConfiguration();
        Secret existing = client.secrets().inNamespace(namespace).withName(name).get();
        if (existing != null && !isManagedConfig(existing)) {
            throw new ManagedConfigConflictException("A Secret named '" + name + "' already exists in namespace '"
                    + namespace + "' and is not a managed configuration. Choose another name.");
        }
    }

    /** A managed configuration would replace a Secret the view does not manage as one. */
    public static class ManagedConfigConflictException extends IllegalStateException {
        public ManagedConfigConflictException(String message) {
            super(message);
        }
    }

    private static boolean isManagedConfig(Secret secret) {
        Map<String, String> labels = secret.getMetadata() == null ? null : secret.getMetadata().getLabels();
        return labels != null && "true".equals(labels.get(MANAGED_CONFIG_LABEL));
    }

    /**
     * Reads the content of a managed configuration secret to display in the UI editor.
     *
     * @return the file content, or an empty string when the Secret has no such key
     * @throws NoSuchElementException when there is no such Secret or it is not a managed configuration
     */
    public String getManagedConfigContent(String namespace, String name, String filename) {
        Objects.requireNonNull(filename, "filename");
        Secret secret = requireManagedConfig(namespace, name);
        String b64 = secret.getData() == null ? null : secret.getData().get(filename);
        if (b64 == null || b64.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Value for key '{}' in managed configuration '{}/{}' is not valid base64: {}",
                    filename, namespace, name, ex.toString());
            return "";
        }
    }

    /**
     * Deletes a managed configuration Secret; any other Secret is left alone.
     *
     * @throws NoSuchElementException when there is no such Secret or it is not a managed configuration
     */
    public void deleteManagedConfig(String namespace, String name) {
        requireManagedConfig(namespace, name);
        client.secrets().inNamespace(namespace).withName(name).delete();
        LOG.info("Deleted managed configuration '{}/{}'", namespace, name);
    }

    /**
     * Deletes a Kubernetes Secret from the given namespace.
     *
     * @param namespace the Kubernetes namespace containing the secret
     * @param name      the name of the secret to delete
     */
    public void deleteSecret(String namespace, String name) {
        checkConfiguration();
        client.secrets().inNamespace(namespace).withName(name).delete();
    }

    private static final io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext FLUX_HELM_RELEASE_RDC =
            new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io").withVersion("v2")
                    .withKind("HelmRelease").withPlural("helmreleases").withNamespaced(true).build();

    /**
     * Asks Flux to reconcile a HelmRelease now, with the {@code reconcile.fluxcd.io/requestedAt} annotation, so it
     * reads again the values it takes from Secrets ({@code spec.valuesFrom}) even though nothing changed in Git.
     *
     * @param namespace the HelmRelease namespace
     * @param name      the HelmRelease name
     * @return true when the HelmRelease was annotated, false when it is not on the cluster
     */
    public boolean requestFluxReconcile(String namespace, String name) {
        if (annotateFluxRequest(namespace, name, false) == null) {
            return false;
        }
        LOG.info("Requested a Flux reconcile of HelmRelease {}/{}", namespace, name);
        return true;
    }

    /**
     * Asks Flux to try a HelmRelease again after it gave up on it (Stalled, retries exhausted): what
     * {@code flux reconcile helmrelease --reset} does, the {@code reconcile.fluxcd.io/resetAt} and
     * {@code reconcile.fluxcd.io/requestedAt} annotations set to the same value. Flux does not retry such a release
     * on its own until its spec or values change.
     *
     * @param namespace the HelmRelease namespace
     * @param name      the HelmRelease name
     * @return the value of the request, which Flux copies to {@code status.lastHandledResetAt} once it handled it;
     *         {@code null} when the HelmRelease is not on the cluster
     */
    public String requestFluxReset(String namespace, String name) {
        String requestedAt = annotateFluxRequest(namespace, name, true);
        if (requestedAt != null) {
            LOG.info("Asked Flux to try HelmRelease {}/{} again (reset {})", namespace, name, requestedAt);
        }
        return requestedAt;
    }

    /** Annotates a HelmRelease with a reconcile request, and a reset request when asked; null when it is not there. */
    private String annotateFluxRequest(String namespace, String name, boolean reset) {
        checkConfiguration();
        var helmRelease = client.genericKubernetesResources(FLUX_HELM_RELEASE_RDC).inNamespace(namespace).withName(name);
        if (helmRelease.get() == null) {
            return null;
        }
        String requestedAt = java.time.Instant.now().toString();
        helmRelease.edit(hr -> {
            Map<String, String> annotations = hr.getMetadata().getAnnotations() == null
                    ? new HashMap<>() : new HashMap<>(hr.getMetadata().getAnnotations());
            annotations.put("reconcile.fluxcd.io/requestedAt", requestedAt);
            if (reset) {
                annotations.put("reconcile.fluxcd.io/resetAt", requestedAt);
            }
            hr.getMetadata().setAnnotations(annotations);
            return hr;
        });
        return requestedAt;
    }

    /**
     * The Flux HelmRelease as it is on the cluster.
     *
     * @param namespace the HelmRelease namespace
     * @param name      the HelmRelease name
     * @return the HelmRelease, or {@code null} when Flux has not applied it (yet)
     */
    public GenericKubernetesResource getFluxHelmRelease(String namespace, String name) {
        checkConfiguration();
        return client.genericKubernetesResources(FLUX_HELM_RELEASE_RDC).inNamespace(namespace).withName(name).get();
    }

    /**
     * The names of the Secrets in a namespace that carry every given label.
     *
     * @param namespace the namespace to look in
     * @param labels    the labels a Secret must carry, with these values
     */
    public List<String> listSecretNames(String namespace, Map<String, String> labels) {
        checkConfiguration();
        return executeWithAuthRetry("list secrets in namespace " + namespace,
                () -> client.secrets().inNamespace(namespace).withLabels(labels).list().getItems()).stream()
                .filter(s -> s.getMetadata() != null && s.getMetadata().getName() != null)
                .map(s -> s.getMetadata().getName())
                .collect(java.util.stream.Collectors.toList());
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
            // Renewed on a revoked OpenShift token: a redeploy refuses to go on when it cannot read these values.
            String operation = "read the Helm values of " + namespace + "/" + releaseName;
            List<Secret> secrets = executeWithAuthRetry(operation, () -> client.secrets().inNamespace(namespace)
                    .withLabel("owner", "helm")
                    .withLabel("name", releaseName)
                    .withLabel("status", "deployed")
                    .list().getItems());

            if (secrets.isEmpty()) {
                // Fallback: get the latest version if status is not deployed (e.g. failed)
                secrets = executeWithAuthRetry(operation, () -> client.secrets().inNamespace(namespace)
                        .withLabel("owner", "helm")
                        .withLabel("name", releaseName)
                        .list().getItems());

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
        HelmService.shutdownExecutor();
        CLIENT_RETIREMENT.shutdownNow(); // the JVM is going away: replaced clients need no orderly close
        
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
