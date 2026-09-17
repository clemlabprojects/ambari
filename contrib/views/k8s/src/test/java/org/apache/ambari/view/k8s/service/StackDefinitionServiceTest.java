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
  void curatedCatalogServicesAreStillDiscoveredInOrder() {
    List<String> keys = svc.discoverServiceKeys();
    assertTrue(keys.contains("SUPERSET"), keys.toString());
    assertTrue(keys.contains("TRINO"), keys.toString());
    // catalog order is preserved: GITLAB is first in catalog.json
    assertEquals("GITLAB", keys.get(0));
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

  @Test
  void configurationFilesAreEnumeratedNotHardcoded() {
    // Known service: both legacy-named files still load.
    assertEquals(2, svc.getServiceConfigurations("SUPERSET").size());
    // Drop-in service with an arbitrary file name: previously invisible, now loaded.
    assertEquals(1, svc.getServiceConfigurations("ZZTESTCUSTOM").size(),
        "configurations/*.json must be enumerated, not limited to <svc>-env.json/<svc>-files.json");
  }

  @Test
  void listResourceChildrenSeesServiceDirectories() {
    List<String> children = svc.listResourceChildren("KDPS/services");
    assertTrue(children.contains("SUPERSET"));
    assertTrue(children.contains("ZZTESTCUSTOM"));
    assertTrue(children.contains("catalog.json"));
  }
}
