/*
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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Host resolution used by the ingress-TLS planning steps (self-sign / cert-manager / external-secret). */
public class CommandServiceIngressHostTest {

    @Test
    void prefersFlatThenNestedIngressHost() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("ingress.host", "superset.example.com");
        assertEquals("superset.example.com", CommandService.resolveIngressHostForTls(flat, null));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("ingress", new LinkedHashMap<>(Map.of("host", " trino.example.com ")));
        assertEquals("trino.example.com", CommandService.resolveIngressHostForTls(nested, null));
    }

    @Test
    void readsZ2jhHostsListAndBitnamiShapes() {
        Map<String, Object> z2jh = new LinkedHashMap<>();
        z2jh.put("ingress", new LinkedHashMap<>(Map.of("hosts", List.of("jupyterhub.apps-crc.testing"))));
        assertEquals("jupyterhub.apps-crc.testing", CommandService.resolveIngressHostForTls(z2jh, null));

        Map<String, Object> bitnamiHosts = new LinkedHashMap<>();
        bitnamiHosts.put("ingress", new LinkedHashMap<>(Map.of("hosts", List.of(Map.of("host", "om.example.com", "paths", List.of())))));
        assertEquals("om.example.com", CommandService.resolveIngressHostForTls(bitnamiHosts, null));

        Map<String, Object> bitnamiHostname = new LinkedHashMap<>();
        bitnamiHostname.put("ingress", new LinkedHashMap<>(Map.of("hostname", "gitlab.example.com")));
        assertEquals("gitlab.example.com", CommandService.resolveIngressHostForTls(bitnamiHostname, null));
    }

    @Test
    void fallsBackToStrippedFormFieldsThenNull() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ingress", new LinkedHashMap<>(Map.of("enabled", true)));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("jupyterHost", "hub.example.com");
        assertEquals("hub.example.com", CommandService.resolveIngressHostForTls(values, form));

        assertNull(CommandService.resolveIngressHostForTls(values, Map.of("unrelated", "x")));
        assertNull(CommandService.resolveIngressHostForTls(null, null));
    }
}
