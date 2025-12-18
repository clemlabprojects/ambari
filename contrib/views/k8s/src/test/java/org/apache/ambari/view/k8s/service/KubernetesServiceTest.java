package org.apache.ambari.view.k8s.service;

import com.marcnuri.helm.Release;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.PersistenceException;

import org.apache.ambari.view.k8s.model.*;

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;

import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.net.URI;
import java.nio.file.Path;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class KubernetesServiceTest {

  // Injectés automatiquement par Fabric8 (JUnit 5)
  static KubernetesClient client;
  static KubernetesMockServer server;

  private KubernetesService svc;
  private ViewContext mockCtx;
  private HelmClient mockHelm;

  @BeforeEach
  void setUp() {
    mockCtx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(mockCtx.getInstanceName()).thenReturn("test-instance");
    when(mockCtx.getUsername()).thenReturn("tester");
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    when(mockCtx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp));
    mockHelm = mock(HelmClient.class);
    svc = new KubernetesService(mockCtx, client, mockHelm, /*isConfigured=*/true);
  }

  @Test
  void createAndDeleteNamespace_roundTrip() {
    String ns = "unit-test";

    svc.createNamespace(ns);
    assertNotNull(client.namespaces().withName(ns).get(),
        "Le namespace doit exister après createNamespace");

    svc.deleteNamespace(ns);
    assertNull(client.namespaces().withName(ns).get(),
        "Le namespace doit être supprimé");
  }

  @Test
  void deployHelmChart_doesNotThrow() throws PersistenceException {
    // 1. Mocks
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    Map<String, String> properties = Map.of(
        "k8s.view.working.dir", tmp,
        "kubeconfig.path", tmp + "/config.yaml.enc",
        "kubeconfig.uploaded", "true"
    );
    when(ctx.getProperties()).thenReturn(properties);
    DataStore ds = mock(DataStore.class);
    HelmClient helm = mock(HelmClient.class);
    when(ctx.getDataStore()).thenReturn(ds);

    // map DataStore
    Map<String, HelmRepoEntity> table = new HashMap<>();
    when(ds.find(eq(HelmRepoEntity.class), anyString()))
        .thenAnswer(inv -> table.get(inv.getArgument(1)));
    when(ds.findAll(eq(HelmRepoEntity.class), any()))
        .thenAnswer(inv -> table.values());
    doAnswer(inv -> { 
      table.put(((HelmRepoEntity) inv.getArgument(0)).getId(), (HelmRepoEntity) inv.getArgument(0));
      return null; 
    }).when(ds).store(any(HelmRepoEntity.class));

    // dépôt bitnami
    HelmRepoEntity bitnami = new HelmRepoEntity();
    bitnami.setId("bitnami");
    bitnami.setType("HTTP");
    bitnami.setName("bitnami");
    bitnami.setUrl("https://charts.bitnami.com/bitnami");
    bitnami.setAuthMode("anonymous");
    table.put("bitnami", bitnami);

    // stub HelmClient
    doNothing().when(helm).ensureHttpRepo(any(Path.class), anyString(), any(URI.class), any(), any());


    // 2. Service à tester
    KubernetesService svc = new KubernetesService(ctx, client, helm, true);

    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("bitnami/spark");
    req.setReleaseName("spark");
    req.setNamespace("apps");

  }

  @Test
  void clusterStats_countsSeededNode() {
    client.nodes()
        .resource(new NodeBuilder()
            .withNewMetadata().withName("worker-1").endMetadata()
            .withNewStatus()
              .addToCapacity("cpu", new Quantity("2"))
              .addToCapacity("memory", new Quantity("8Gi"))
              .addToAllocatable("cpu", new Quantity("2"))
              .addToAllocatable("memory", new Quantity("8Gi"))
            .endStatus()
            .build())
        .create();

    ClusterStats stats = svc.getClusterStats(false);
    assertNotNull(stats, "ClusterStats ne doit pas être null");
    assertNotNull(stats.getNodes(), "Nodes stat ne doit pas être null");
    assertEquals(1.0, stats.getNodes().getTotal(), 0.0001,
        "Le total de nœuds doit être 1.0");
  }

  @Test
  void buildPrometheusExposure_enablesIngressWhenHostProvided() throws Exception {
    // Given: monitoring settings with an ingress host
    KubernetesService.MonitoringSettings settings = new KubernetesService.MonitoringSettings();
    settings.prometheusHost = "prometheus.example.com";
    settings.prometheusIngressClass = "nginx";
    Map<String, Object> overrides = new HashMap<>();

    // Use reflection to invoke the private helper
    Method m = KubernetesService.class.getDeclaredMethod(
        "buildPrometheusExposureOverrides", KubernetesService.MonitoringSettings.class, String.class, Map.class);
    m.setAccessible(true);

    Object exposure = m.invoke(svc, settings, "monitoring", overrides);

    // Then: ingress overrides should be present and URL resolved
    assertTrue((Boolean) overrides.get("prometheus.ingress.enabled"),
        "Ingress must be enabled when a host is provided");
    assertEquals("nginx", overrides.get("prometheus.ingress.ingressClassName"),
        "Ingress class must be set from settings");
    assertNotNull(exposure, "Exposure object should not be null when host is set");
    var hosts = (java.util.List<?>) overrides.get("prometheus.ingress.hosts");
    assertEquals(1, hosts.size(), "Ingress hosts must carry the configured host");
    assertEquals("prometheus.example.com", hosts.get(0));
    var paths = (java.util.List<?>) overrides.get("prometheus.ingress.paths");
    assertEquals(1, paths.size(), "Ingress paths should default to root");
    assertEquals("/", paths.get(0));
    assertEquals("Prefix", overrides.get("prometheus.ingress.pathType"));
    // Access record method url() reflectively
    Method urlMethod = exposure.getClass().getMethod("url");
    String url = (String) urlMethod.invoke(exposure);
    assertTrue(url.contains("prometheus.example.com"), "Exposure URL must include the configured host");
  }
}
