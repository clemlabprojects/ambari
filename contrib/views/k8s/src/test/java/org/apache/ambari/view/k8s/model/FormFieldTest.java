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

package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the discovery-field host/port auto-fill.
 *
 * A {@code k8s-discovery} / {@code service-select} / {@code hadoop-discovery} field auto-fills the
 * OTHER form fields named by {@code targetHost} / {@code targetPort} with the picked service's
 * host/port. {@link FormField} carries {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so when
 * these two properties were not declared on the model they were silently dropped on load, never
 * reached the UI, and the auto-fill was disabled — leaving e.g. Superset's {@code ui_trino_host}
 * empty so the Trino datasource import was skipped.
 */
class FormFieldTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void formFieldRetainsTargetHostAndPort() throws Exception {
    String json = "{\"name\":\"trinoConnection\",\"type\":\"k8s-discovery\","
        + "\"lookupLabel\":\"app=trino\","
        + "\"targetHost\":\"ui_trino_host\",\"targetPort\":\"ui_trino_port\"}";

    FormField f = mapper.readValue(json, FormField.class);

    assertEquals("ui_trino_host", f.targetHost, "targetHost must survive deserialization");
    assertEquals("ui_trino_port", f.targetPort, "targetPort must survive deserialization");
  }

  @Test
  void supersetTrinoConnectionKeepsAutofillTargets() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/KDPS/services/SUPERSET/service.json")) {
      assertNotNull(in, "SUPERSET/service.json must be on the classpath");
      JsonNode root = mapper.readTree(in);

      JsonNode trinoConnection = findFieldByName(root.get("form"), "trinoConnection");
      assertNotNull(trinoConnection, "trinoConnection field must exist in SUPERSET service.json");

      // The service.json itself declares the targets...
      assertEquals("ui_trino_host", trinoConnection.path("targetHost").asText(null));
      assertEquals("ui_trino_port", trinoConnection.path("targetPort").asText(null));

      // ...and they survive the FormField round-trip (the class that had the bug), i.e. they are
      // still present in the model the /services API serialises to the browser.
      FormField f = mapper.treeToValue(trinoConnection, FormField.class);
      assertEquals("ui_trino_host", f.targetHost);
      assertEquals("ui_trino_port", f.targetPort);
    }
  }

  @Test
  void openMetadataTagSyncFieldsKeepTheirUpgradeSeeds() throws Exception {
    // Same failure mode as targetHost/targetPort: an undeclared property never reaches the wizard,
    // which would then show the TagSync switch off on every upgrade of a release that has it on.
    try (InputStream in = getClass().getResourceAsStream("/KDPS/services/OPENMETADATA/service.json")) {
      assertNotNull(in, "OPENMETADATA/service.json must be on the classpath");
      JsonNode root = mapper.readTree(in);

      FormField toggle = mapper.treeToValue(findFieldByName(root.get("form"), "rangerTagSyncEnabled"), FormField.class);
      assertEquals("ranger.tagSync.enabled", toggle.seedFromValue);

      FormField endpoint = mapper.treeToValue(findFieldByName(root.get("form"), "rangerTagSyncEndpoint"), FormField.class);
      assertEquals("ranger.tagSync.endpoint", endpoint.seedFromValue);
      assertEquals("https://${ingress.host}/api", endpoint.seedUnlessEquals);
    }
  }

  @Test
  void everyReleaseNameFieldWarnsWithHelmsRule() throws Exception {
    // Same failure mode again: an undeclared "pattern" would never reach the wizard, which would then let any name
    // through until the server refuses it.
    for (String service : new String[] {"TRINO", "SUPERSET", "OPENMETADATA", "GITLAB"}) {
      try (InputStream in = getClass().getResourceAsStream("/KDPS/services/" + service + "/service.json")) {
        assertNotNull(in, service);
        JsonNode releaseName = null;
        for (JsonNode field : mapper.readTree(in).get("form")) {
          if ("releaseName".equals(field.path("name").asText(null))) {
            releaseName = field;
          }
        }
        assertNotNull(releaseName, service + " asks for a release name");

        FormField field = mapper.treeToValue(releaseName, FormField.class);
        assertNotNull(field.pattern, service);
        assertNotNull(field.validationHint, service);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(field.pattern);
        for (String name : new String[] {"trino", "trino-prod", "superset.analytics", "a".repeat(53)}) {
          assertTrue(pattern.matcher(name).find(), service + " accepts " + name);
          org.apache.ambari.view.k8s.service.deployment.DeployRequestValidator.checkReleaseName(name);
        }
        for (String name : new String[] {"Trino", "trino_prod", "trino prod", "../x", "-trino", "trino-",
            "a".repeat(54)}) {
          assertFalse(pattern.matcher(name).find(), service + " warns about " + name);
          assertThrows(IllegalArgumentException.class,
              () -> org.apache.ambari.view.k8s.service.deployment.DeployRequestValidator.checkReleaseName(name),
              "the server refuses " + name + " too");
        }
      }
    }
  }

  /** Depth-first search for a field by name, descending into group fields (their nested "fields"). */
  private JsonNode findFieldByName(JsonNode fields, String name) {
    if (fields == null || !fields.isArray()) {
      return null;
    }
    for (JsonNode field : fields) {
      if (name.equals(field.path("name").asText(null))) {
        return field;
      }
      JsonNode nested = findFieldByName(field.get("fields"), name);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }
}
