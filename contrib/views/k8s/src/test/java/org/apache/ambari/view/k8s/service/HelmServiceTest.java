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
  @SuppressWarnings("unchecked")
  void expandDotPathsKeepsFileContentMapKeysLiteral() {
    // extraConfigs is keyed by FILENAME — "import_datasources.yaml" must NOT be split into a
    // nested {import_datasources:{yaml:...}} map (which breaks the chart's `tpl $config`).
    String yamlBody = "databases:\n- database_name: Platform Hive\n  sqlalchemy_uri: hive://h:10001/default\n";
    Map<String, Object> in = Map.of(
        "extraConfigs", new java.util.LinkedHashMap<>(Map.of("import_datasources.yaml", yamlBody)));
    Map<String, Object> out = service.expandDotPaths(in);
    Object extra = out.get("extraConfigs");
    assertTrue(extra instanceof Map, "extraConfigs should be a map");
    Object val = ((Map<String, Object>) extra).get("import_datasources.yaml");
    assertTrue(val instanceof String, "file-content value must stay a String, got " + (val == null ? "null" : val.getClass()));
    assertEquals(yamlBody, val);
    assertNull(((Map<String, Object>) extra).get("import_datasources"), "dotted filename key must not be expanded");
  }

  @Test
  @SuppressWarnings("unchecked")
  void expandDotPathsStillExpandsOrdinaryDottedKeys() {
    // Non-file-content dotted keys must still expand into nested maps.
    Map<String, Object> out = service.expandDotPaths(new java.util.LinkedHashMap<>(Map.of("ingress.host", "x.example")));
    Object ingress = out.get("ingress");
    assertTrue(ingress instanceof Map);
    assertEquals("x.example", ((Map<String, Object>) ingress).get("host"));
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
