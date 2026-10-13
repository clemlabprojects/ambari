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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;

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

  private static final EncryptionService MASTER_KEY = TestMasterKeys.encryptionWith("a-master-key");

  ViewContext ctx;
  DataStore ds;
  HelmClient helm;

  Map<String, HelmRepoEntity> table;
  Map<String, KdpsSettingEntity> settings;
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

    settings = new HashMap<>();
    when(ds.find(eq(KdpsSettingEntity.class), anyString())).thenAnswer(inv -> settings.get(inv.getArgument(1)));
    doAnswer(inv -> { KdpsSettingEntity e = inv.getArgument(0); settings.put(e.getId(), e); return null; })
        .when(ds).store(any(KdpsSettingEntity.class));

    instanceData = new HashMap<>();
    when(ctx.getInstanceData(anyString())).thenAnswer(inv -> instanceData.get(inv.getArgument(0)));
    doAnswer(inv -> { instanceData.put(inv.getArgument(0), inv.getArgument(1)); return null; })
        .when(ctx).putInstanceData(anyString(), anyString());
    doAnswer(inv -> { instanceData.remove(inv.getArgument(0)); return null; })
        .when(ctx).removeInstanceData(anyString());
  }

  @Test
  void save_minimal_http_repo() throws PersistenceException {
    var svc = new HelmRepositoryService(ctx, helm, MASTER_KEY);

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
    var svc = new HelmRepositoryService(ctx, helm, MASTER_KEY);

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
    // Kept in the view instance's settings, shared by every user, and encrypted.
    assertTrue(settings.containsKey(stored.getSecretRef()));
    assertNotEquals("s3cr3t", settings.get(stored.getSecretRef()).getValue());
    assertFalse(instanceData.containsKey(stored.getSecretRef()), "not in the saving user's own instance data");
    assertEquals("s3cr3t", svc.readPlainSecret(stored));
  }

  @Test
  void save_keeps_the_gitops_secrets_and_truststores_and_refuses_a_name_kubernetes_would_not_accept() {
    var svc = new HelmRepositoryService(ctx, helm);
    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("harbor");
    e.setType("OCI");
    e.setName("harbor");
    e.setUrl("harbor.example.com/helm");
    e.setAuthMode("basic");
    e.setFluxSecretName("harbor-pull");
    e.setFluxCertSecretName("corporate-ca.v2");
    e.setCaTruststores("corp-ca, partner-ca");

    svc.save(e, null);

    HelmRepoEntity stored = table.get("harbor");
    assertEquals("harbor-pull", stored.getFluxSecretName());
    assertEquals("corporate-ca.v2", stored.getFluxCertSecretName());
    assertEquals("corp-ca, partner-ca", stored.getCaTruststores());
    var dto = org.apache.ambari.view.k8s.model.HelmRepoDTO.fromEntity(stored);
    assertEquals("harbor-pull", dto.fluxSecretName);
    assertEquals("corporate-ca.v2", dto.fluxCertSecretName);
    assertEquals("corp-ca, partner-ca", dto.caTruststores);

    e.setFluxSecretName("Harbor_Pull");
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> svc.save(e, null));
    assertTrue(refused.getMessage().startsWith("GitOps credentials Secret 'Harbor_Pull' is not a valid Secret name"),
        refused.getMessage());
    e.setFluxSecretName("");
    e.setFluxCertSecretName("ca\n---");
    assertThrows(IllegalArgumentException.class, () -> svc.save(e, null));
  }

  @Test
  void ensureHttpRepo_calls_client_and_clears_authInvalid() throws PersistenceException {
    var svc = new HelmRepositoryService(ctx, helm, MASTER_KEY);

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
    var svc = new HelmRepositoryService(ctx, helm, MASTER_KEY);

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

  @Test
  void withoutAMasterKeyARepositoryWithASecretIsNotSaved() {
    var svc = new HelmRepositoryService(ctx, helm, TestMasterKeys.encryptionWithoutKey());

    HelmRepoEntity e = new HelmRepoEntity();
    e.setId("harbor");
    e.setType("OCI");
    e.setName("harbor");
    e.setUrl("harbor.example.com/helm");
    e.setAuthMode("basic");
    e.setUsername("ci");

    assertThrows(MasterKeyUnavailableException.class, () -> svc.save(e, "s3cr3t"));

    assertTrue(table.isEmpty(), "no repository row");
    assertTrue(settings.isEmpty(), "no secret");
  }
}
