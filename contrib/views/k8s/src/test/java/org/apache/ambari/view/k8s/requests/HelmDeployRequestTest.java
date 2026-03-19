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

package org.apache.ambari.view.k8s.requests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HelmDeployRequestTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void settersAndGetters_work() {
    HelmDeployRequest req = new HelmDeployRequest();
    req.setChart("bitnami/spark");
    req.setReleaseName("spark");
    req.setNamespace("apps");
    req.setValues(Map.of("replicaCount", 2));

    assertEquals("bitnami/spark", req.getChart());
    assertEquals("spark", req.getReleaseName());
    assertEquals("apps", req.getNamespace());
    assertEquals(2, req.getValues().get("replicaCount"));
  }

  @Test
  void jacksonRoundTrip_preservesFields() throws Exception {
    HelmDeployRequest in = new HelmDeployRequest();
    in.setChart("bitnami/spark");
    in.setReleaseName("spark");
    in.setNamespace("apps");
    in.setValues(Map.of("replicaCount", 2, "driver.cores", 1));

    String encoded = json.writeValueAsString(in);
    HelmDeployRequest out = json.readValue(encoded, HelmDeployRequest.class);

    assertEquals(in.getChart(), out.getChart());
    assertEquals(in.getReleaseName(), out.getReleaseName());
    assertEquals(in.getNamespace(), out.getNamespace());
    assertEquals(in.getValues(), out.getValues());
  }

  @Test
  void unknownJsonFields_areIgnored() throws Exception {
    String payload = """
      {
        "chart": "bitnami/spark",
        "releaseName": "spark",
        "namespace": "apps",
        "values": { "replicaCount": 1 },
        "unknown": "field",
        "another": 123
      }
      """;

    HelmDeployRequest out = json.readValue(payload, HelmDeployRequest.class);

    assertEquals("bitnami/spark", out.getChart());
    assertEquals("spark", out.getReleaseName());
    assertEquals("apps", out.getNamespace());
    assertEquals(1, out.getValues().get("replicaCount"));
  }
}
