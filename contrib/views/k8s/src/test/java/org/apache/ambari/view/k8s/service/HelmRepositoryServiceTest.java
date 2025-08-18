package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;

import org.apache.ambari.view.PersistenceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HelmRepositoryServiceTest {

  ViewContext ctx;
  DataStore ds;
  HelmClient helm;

  Map<String, HelmRepoEntity> table;
  Map<String, String> instanceData;

  @BeforeEach
  void setUp() throws PersistenceException{
    ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp));

    ds = mock(DataStore.class);
    when(ctx.getDataStore()).thenReturn(ds);
    helm = mock(HelmClient.class);

    table = new HashMap<>();
    when(ds.find(eq(HelmRepoEntity.class), anyString())).thenAnswer(inv -> table.get(inv.getArgument(1)));
    when(ds.findAll(eq(HelmRepoEntity.class), any())).thenAnswer(inv -> table.values());
    doAnswer(inv -> { HelmRepoEntity e = inv.getArgument(0); table.put(e.getId(), e); return null; })
        .when(ds).store(any(HelmRepoEntity.class));
    doAnswer(inv -> { HelmRepoEntity e = inv.getArgument(0); table.remove(e.getId()); return null; })
        .when(ds).remove(any(HelmRepoEntity.class));

    instanceData = new HashMap<>();
    when(ctx.getInstanceData(anyString())).thenAnswer(inv -> instanceData.get(inv.getArgument(0)));
    doAnswer(inv -> { instanceData.put(inv.getArgument(0), inv.getArgument(1)); return null; })
        .when(ctx).putInstanceData(anyString(), anyString());
    doAnswer(inv -> { instanceData.remove(inv.getArgument(0)); return null; })
        .when(ctx).removeInstanceData(anyString());
  }

  @Test
  void save_minimal_http_repo() throws PersistenceException {
    var svc = new HelmRepositoryService(ctx, helm);

    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("promcom");
    e.setType("HTTP");
    e.setName("prometheus-community");
    e.setUrl("https://prometheus-community.github.io/helm-charts");
    e.setAuthMode("anonymous");

    svc.save(e, null);

    assertNotNull(table.get("promcom"));
    assertNotNull(table.get("promcom").getUpdatedAt());
  }

  @Test
  void save_with_secret_stores_encrypted_ref() throws PersistenceException{
    var svc = new HelmRepositoryService(ctx, helm);

    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("harbor");
    e.setType("OCI");
    e.setName("harbor");
    e.setUrl("harbor.example.com/helm");
    e.setAuthMode("basic");
    e.setUsername("ci");

    svc.save(e, "s3cr3t");

    HelmRepoEntity stored = table.get("harbor");
    assertNotNull(stored.getSecretRef());
    assertTrue(instanceData.containsKey(stored.getSecretRef()));
  }

  @Test
  void ensureHttpRepo_calls_client_and_clears_authInvalid() throws PersistenceException {
    var svc = new HelmRepositoryService(ctx, helm);

    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("prom");
    e.setType("HTTP");
    e.setName("prometheus-community");
    e.setUrl("https://prometheus-community.github.io/helm-charts");
    e.setAuthMode("anonymous");
    e.setAuthInvalid(true);
    table.put(e.getId(), e);

    svc.ensureHttpRepo("prom");

    verify(helm).ensureHttpRepo(any(Path.class), eq("prometheus-community"),
        eq(URI.create(e.getUrl())), isNull(), isNull());
    assertFalse(table.get("prom").isAuthInvalid());
  }

  @Test
  void ociLogin_calls_client() throws PersistenceException{
    var svc = new HelmRepositoryService(ctx, helm);

    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("harbor");
    e.setType("OCI");
    e.setName("harbor");
    e.setUrl("harbor.example.com/helm");
    e.setAuthMode("basic");
    e.setUsername("ci");
    table.put(e.getId(), e);

    svc.ociLogin("harbor");
    verify(helm).ociLogin(eq("harbor.example.com/helm"), eq("ci"), isNull(), any(Path.class));
  }
}
