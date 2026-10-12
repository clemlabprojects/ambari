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

import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard: service discovery must not depend solely on the hardcoded catalog.json
 * manifest, and configuration files must not depend on hardcoded file names. A drop-in
 * {@code src/test/resources/KDPS/services/ZZTESTCUSTOM/} (deliberately ABSENT from catalog.json)
 * must be discovered, loaded, and have its arbitrarily-named configuration file picked up.
 */
class StackDefinitionServiceTest {

  private final StackDefinitionService svc = new StackDefinitionService();

  @Test
  void curatedCatalogServicesAreStillDiscoveredInOrder() throws Exception {
    List<String> keys = svc.discoverServiceKeys();
    assertTrue(keys.contains("SUPERSET"), keys.toString());
    assertTrue(keys.contains("TRINO"), keys.toString());
    // Catalog order is preserved. Asserted against catalog.json itself rather than a hardcoded
    // name, so adding a service to the catalog does not fail a test about ordering.
    List<String> catalog;
    try (java.io.InputStream in = getClass().getResourceAsStream("/KDPS/services/catalog.json")) {
      assertNotNull(in, "KDPS/services/catalog.json must be on the classpath");
      catalog = new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(in, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    }
    assertEquals(catalog.get(0), keys.get(0));
    assertEquals(catalog, keys.subList(0, catalog.size()),
        "curated services come first, in catalog.json order: " + keys);
  }

  @Test
  void dropInServiceNotInCatalogIsAutoDiscovered() {
    List<String> keys = svc.discoverServiceKeys();
    assertTrue(keys.contains("ZZTESTCUSTOM"),
        "a KDPS/services/<KEY>/service.json absent from catalog.json must be auto-discovered: " + keys);

    Map<String, StackServiceDef> defs = svc.listServiceDefinitions();
    assertTrue(defs.containsKey("ZZTESTCUSTOM"), defs.keySet().toString());
    assertEquals("ZZTESTCUSTOM", defs.get("ZZTESTCUSTOM").name);
  }

  /**
   * SQL Assistant and dbt are not part of this release. Every service directory is listed, catalog.json or not, so
   * their definitions must be gone as well as their catalog entries.
   */
  @Test
  void sqlAssistantAndDbtAreNotOffered() {
    List<String> keys = svc.discoverServiceKeys();
    assertFalse(keys.contains("SQL-ASSISTANT"), keys.toString());
    assertFalse(keys.contains("DBT"), keys.toString());

    Map<String, StackServiceDef> defs = svc.listServiceDefinitions();
    assertFalse(defs.containsKey("SQL-ASSISTANT"), defs.keySet().toString());
    assertFalse(defs.containsKey("DBT"), defs.keySet().toString());
    assertTrue(defs.containsKey("GITLAB"), "GitLab is still offered, on plain Kubernetes: " + defs.keySet());
  }

  /** The catalog hides GitLab on OpenShift from the platforms its definition lists, so they must reach the UI. */
  @Test
  void gitLabPlatformsReachTheCatalog() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Map<String, Object> served = mapper.readValue(mapper.writeValueAsString(svc.getServiceDefinition("GITLAB")),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

    assertEquals(List.of("kubernetes"), served.get("platforms"));
    assertEquals("GitLab is not supported on OpenShift in this release; register your existing Git server under Git"
        + " Repositories.", served.get("unsupportedPlatformMessage"));
  }

  @Test
  void configurationFilesAreEnumeratedNotHardcoded() {
    // Known service: both legacy-named files still load.
    assertEquals(2, svc.getServiceConfigurations("SUPERSET").size());
    // Drop-in service with an arbitrary file name: previously invisible, now loaded.
    assertEquals(1, svc.getServiceConfigurations("ZZTESTCUSTOM").size(),
        "configurations/*.json must be enumerated, not limited to <svc>-env.json/<svc>-files.json");
  }

  /**
   * The wizard reads the form from the /services JSON, which is this class's model serialized back. A form
   * property the model does not declare is dropped on the way, so the Trino heap check would silently vanish.
   */
  @Test
  @SuppressWarnings("unchecked")
  void trinoResourceChecksReachTheWizard() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Map<String, Object> served = mapper.readValue(mapper.writeValueAsString(svc.getServiceDefinition("TRINO")),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

    Map<String, Object> heap = formField(served, "workerResources", "worker.jvm.maxHeapSize");
    assertEquals(Boolean.TRUE, heap.get("omitWhenBlank"));
    assertEquals("75% of the memory limit", heap.get("placeholder"));
    assertEquals(Map.of("kind", "javaHeap", "limitField", "worker.resources.limits.memory",
        "blankSizedByChartFrom", "1.43.15"), heap.get("resourceCheck"));

    Map<String, Object> request = formField(served, "coordinatorResources", "coordinator.resources.requests.memory");
    assertEquals(Map.of("kind", "memoryRequest", "limitField", "coordinator.resources.limits.memory"),
        request.get("resourceCheck"));
    assertEquals(Boolean.FALSE, request.get("omitWhenBlank"));
  }

  /**
   * The wizard hides the settings of the other platform, and leaves their values out of the chart values, from the
   * {@code capability} of a field or group. Without it on the model, the kube-prometheus monitoring group of
   * OpenMetadata showed on OpenShift and its "monitoring" namespace was sent to the chart.
   */
  @Test
  void platformCapabilitiesReachTheWizard() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Map<String, Object> served = mapper.readValue(
        mapper.writeValueAsString(svc.getServiceDefinition("OPENMETADATA")),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

    assertEquals("kubernetes", formGroup(served, "monitoringGeneric").get("capability"));
    assertEquals("openshift", formGroup(served, "openshiftOptions").get("capability"));
    assertEquals(Boolean.TRUE, formField(served, "monitoringGeneric", "monitoring.namespace").get("excludeFromValues"));
    assertEquals(Boolean.TRUE, formField(served, "monitoringGeneric", "monitoring.release").get("excludeFromValues"));

    Map<String, Object> ingressClass = formField(served, "ingressConfig", "ingress.ingressClassName");
    assertEquals("kubernetes", ingressClass.get("capability"));
    assertEquals(Boolean.TRUE, ingressClass.get("keepWhenHidden"));
    assertEquals(Boolean.FALSE, formField(served, "ingressConfig", "ingress.host").get("keepWhenHidden"));
  }

  /**
   * "Keep current certificate" is offered on Upgrade/Config only. Its {@code upgradeOnly} flag is read by the wizard
   * alone, so it must survive the round trip through the model: without it a first install would offer to keep a
   * certificate that does not exist, and send no certificate at all.
   */
  @Test
  @SuppressWarnings("unchecked")
  void theKeepCurrentCertificateOptionReachesTheWizardAsUpgradeOnly() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (String service : List.of("TRINO", "SUPERSET", "OPENMETADATA")) {
      Map<String, Object> served = mapper.readValue(mapper.writeValueAsString(svc.getServiceDefinition(service)),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      List<Map<String, Object>> options =
          (List<Map<String, Object>>) formField(served, "ingressConfig", "ingress.tlsMode").get("options");
      Map<String, Object> keep = options.stream().filter(o -> "keepCurrent".equals(o.get("value"))).findFirst()
          .orElseThrow(() -> new AssertionError(service + " offers no 'Keep current certificate'"));
      assertEquals(Boolean.TRUE, keep.get("upgradeOnly"), service);
      assertEquals("Keep current certificate", keep.get("label"), service);
      assertTrue(options.stream().filter(o -> !"keepCurrent".equals(o.get("value")))
          .noneMatch(o -> Boolean.TRUE.equals(o.get("upgradeOnly"))), service + ": the install modes stay offered");
    }
  }

  /**
   * The Superset SECRET_KEY is kept out of the values and in extraSecretEnv: an upgrade fills the field back from
   * there (seedFromValues) and drops the copy an earlier deploy left in extraEnv (retiredValues). The Trino catalog
   * credential Secrets are picked several at a time (multiple). All three are read by the wizard only, so they must
   * survive the round trip through the model.
   */
  @Test
  @SuppressWarnings("unchecked")
  void secretHandlingReachesTheWizard() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    Map<String, Object> superset = mapper.readValue(mapper.writeValueAsString(svc.getServiceDefinition("SUPERSET")),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    Map<String, Object> secretKey = formField(superset, "superset", "superset.secretKey");
    assertEquals(Boolean.TRUE, secretKey.get("excludeFromValues"));
    assertEquals(List.of("extraSecretEnv.SUPERSET_SECRET_KEY", "extraEnv.SUPERSET_SECRET_KEY"),
        secretKey.get("seedFromValues"));
    assertTrue(((Map<String, Object>) superset.get("retiredValues")).containsKey("extraEnv.SUPERSET_SECRET_KEY"));

    Map<String, Object> trino = mapper.readValue(mapper.writeValueAsString(svc.getServiceDefinition("TRINO")),
        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    Map<String, Object> credentials = formField(trino, "customCatalogs", "catalogCredentialSecrets");
    assertEquals("secret-discovery", credentials.get("type"));
    assertEquals(Boolean.TRUE, credentials.get("multiple"));
    assertEquals(Boolean.FALSE, credentials.get("excludeFromValues"), "the chart reads the list from the values");
    assertTrue(((Map<String, Object>) trino.get("retiredValues")).containsKey("internalSharedSecret"));
    assertEquals(">=1.43.16", ((Map<String, Object>) trino.get("featureChartVersions")).get("catalogCredentialSecrets"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> formGroup(Map<String, Object> def, String group) {
    for (Object g : (List<Object>) def.get("form")) {
      if (group.equals(((Map<String, Object>) g).get("name"))) return (Map<String, Object>) g;
    }
    throw new AssertionError("no group " + group);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> formField(Map<String, Object> def, String group, String name) {
    for (Object f : (List<Object>) formGroup(def, group).get("fields")) {
      if (name.equals(((Map<String, Object>) f).get("name"))) return (Map<String, Object>) f;
    }
    throw new AssertionError("no field " + name + " in group " + group);
  }

  @Test
  void listResourceChildrenSeesServiceDirectories() {
    List<String> children = svc.listResourceChildren("KDPS/services");
    assertTrue(children.contains("SUPERSET"));
    assertTrue(children.contains("ZZTESTCUSTOM"));
    assertTrue(children.contains("catalog.json"));
  }
}
