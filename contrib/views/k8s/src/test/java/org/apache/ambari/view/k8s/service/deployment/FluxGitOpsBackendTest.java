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

package org.apache.ambari.view.k8s.service.deployment;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FluxGitOpsBackendTest {

    @Test
    public void applyOverrides_setsNestedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("global.security.kerberos.enabled", "true");
        overrides.put("global.security.kerberos.configMapName", "krb5-cm");

        FluxGitOpsBackend.applyOverridesToValues(values, overrides);

        assertTrue(values.containsKey("global"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) values.get("global");
        @SuppressWarnings("unchecked")
        Map<String, Object> sec = (Map<String, Object>) g.get("security");
        assertNotNull(sec);
        @SuppressWarnings("unchecked")
        Map<String, Object> krb = (Map<String, Object>) sec.get("kerberos");
        assertNotNull(krb);
        assertEquals("true", krb.get("enabled"));
        assertEquals("krb5-cm", krb.get("configMapName"));

    }

    @Test
    public void buildDependsOnYaml_rendersList() {
        List<FluxGitOpsBackend.DepRef> deps = List.of(
                new FluxGitOpsBackend.DepRef("dep1", "ns1"),
                new FluxGitOpsBackend.DepRef("dep2", "ns2")
        );

        String yaml = FluxGitOpsBackend.buildDependsOnYaml(deps);

        assertTrue(yaml.contains("dependsOn"));
        assertTrue(yaml.contains("name: dep1"));
        assertTrue(yaml.contains("namespace: ns1"));
        assertTrue(yaml.contains("name: dep2"));
        assertTrue(yaml.contains("namespace: ns2"));
    }
}
