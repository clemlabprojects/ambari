package org.apache.ambari.view.k8s.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HelmServiceTest {

  ViewContext ctx;
  HelmClient helm;
  HelmService service;

  @BeforeEach
  void setUp() {
    ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp));

    helm = mock(HelmClient.class, RETURNS_DEEP_STUBS);
    service = new HelmService(ctx, helm);
  }

  @Test
  void deployInstallsWhenReleaseAbsent() throws PersistenceException {
    // given
    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("prometheus");
    req.setReleaseName("prom");
    req.setNamespace("apps");
    req.setValues(Map.of("replicaCount", 1));

    when(helm.list("apps", "KC", true)).thenReturn(List.of());

    Release rel = mock(Release.class);

    when(helm.install(
        anyString(), anyString(),anyBoolean(), anyString(), anyString(),
        any(Path.class), anyString(),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(), anyBoolean(), anyBoolean(), anyBoolean()
    )).thenReturn(rel);

    // when
    Release out = service.deployOrUpgrade(req, "KC", null, null);

    // then
    assertNotNull(out);
    verify(helm).install(
        contains("prometheus"),
        any(),            // chart version (may be null)
        anyBoolean(),     // isOci
        eq("prom"),
        eq("apps"),
        any(Path.class),
        eq("KC"),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(),
        anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()
    );
    verify(helm, never()).upgrade(
        anyString(), anyString(), anyBoolean(), anyString(), anyString(),
        any(Path.class), anyString(),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(), anyBoolean(), anyBoolean()
    );
  }

  @Test
  void deployUpgradesWhenReleasePresent() {
    // given
    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("prometheus");
    req.setReleaseName("prom");
    req.setNamespace("apps");
    // pas de values -> null

    Release existing = mock(Release.class);
    when(existing.getName()).thenReturn("prom");
    when(helm.list("apps", "KC", true)).thenReturn(List.of(existing));

    Release rel = mock(Release.class);
    when(helm.upgrade(
        anyString(), anyString(), anyBoolean(), anyString(), anyString(),
        any(Path.class), anyString(),
        ArgumentMatchers.<Map<String,Object>>any(), // null accepté
        anyInt(), anyBoolean(), anyBoolean()
    )).thenReturn(rel);

    // when
    Release out = service.deployOrUpgrade(req, "KC", null, null);

    // then
    assertNotNull(out);
    verify(helm).upgrade(
        contains("prometheus"),
        any(), anyBoolean(),
        eq("prom"),
        eq("apps"),
        any(Path.class),
        eq("KC"),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(),
        anyBoolean(), anyBoolean(), anyBoolean()
    );
    verify(helm, never()).install(
        anyString(), anyString(), anyBoolean(),anyString(),anyString(),
        any(Path.class), anyString(),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(), anyBoolean(), anyBoolean(), anyBoolean()
    );
  }

  @Test
  void repoIdHttpPrefixesChart() throws PersistenceException {
    // given: repo HTTP disponible en DataStore
    HelmRepoEntity repo = new HelmRepoEntity();
    repo.setId("prometheus-community");
    repo.setType("HTTP");
    repo.setName("prometheus-community");
    repo.setUrl("https://prometheus-community.github.io/helm-charts");
    when(ctx.getDataStore().find(HelmRepoEntity.class, "prometheus-community")).thenReturn(repo);

    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("prometheus");
    req.setReleaseName("prom");
    req.setNamespace("apps");
    // values null par défaut

    when(helm.list("apps", "KC", true)).thenReturn(List.of());

    Release rel = mock(Release.class);
    when(helm.install(
        anyString(), anyString(), anyBoolean(),anyString(), anyString(),
        any(Path.class), anyString(),
        ArgumentMatchers.<Map<String,Object>>any(),
        anyInt(), anyBoolean(), anyBoolean(), anyBoolean()
    )).thenReturn(rel);

    // when
    Release out = new HelmService(ctx, helm).deployOrUpgrade(req, "KC", "prometheus-community", null);

    // then
    assertNotNull(out);
    verify(helm).install(
            argThat(ref -> ref.contains("prometheus-community/")), // chartRef must be prefixed
            isNull(),                                              // chartVersion (none passed)
            eq(false),                                             // isOci (HTTP repo)
            eq("prom"),                                            // release
            eq("apps"),                                            // namespace
            any(Path.class),                                       // repositories.yaml
            eq("KC"),                                              // kubeconfig
            ArgumentMatchers.<Map<String,Object>>any(),            // values (may be null)
            anyInt(),                                              // timeout
            anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean() // createNs, wait, atomic, dryRun
    );
  }
}
