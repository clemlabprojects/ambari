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

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trino terminates TLS itself, so the keystore KDPS generates must carry SANs that match how
 * clients actually reach it: the coordinator Service DNS and the ingress host. The fallback SAN
 * (&lt;release&gt;.&lt;namespace&gt;.svc) matches neither, and a wrong SAN fails the handshake for
 * every client that verifies — silently. These cover the template rendering only; the wizard does
 * the same resolution at submit time.
 */
class CommandServiceTlsSanTest {

    private static HelmDeployRequest request(Map<String, Object> values, Map<String, Object> tls) {
        HelmDeployRequest r = new HelmDeployRequest();
        r.setReleaseName("trino");
        r.setNamespace("trino-ns-iceberg");
        r.setServiceKey("TRINO");
        r.setValues(values);
        r.setTls(tls);
        return r;
    }

    private static Map<String, Object> values(String nameOverride, String ingressHost) {
        Map<String, Object> ingress = new LinkedHashMap<>();
        if (ingressHost != null) ingress.put("host", ingressHost);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("nameOverride", nameOverride);
        v.put("ingress", ingress);
        return v;
    }

    /** Mirrors KDPS/services/TRINO/service.json tls[0].dnsTemplates. */
    private static final List<String> TEMPLATES = Arrays.asList(
            "{{releaseName}}-{{nameOverride}}-coordinator.{{namespace}}.svc",
            "{{releaseName}}-{{nameOverride}}-coordinator.{{namespace}}.svc.cluster.local",
            "{{ingress.host}}");

    private static String render(String tpl, HelmDeployRequest req) {
        return CommandService.renderTlsDnsTemplate(tpl, req, req.getValues());
    }

    @Test
    void rendersTheServiceDnsAndTheIngressHost() throws Exception {
        HelmDeployRequest req = request(values("clemlab-trino", "trino-test-iceberg.dev.clemlab.com"), new LinkedHashMap<>());
        List<String> out = new ArrayList<>();
        for (String t : TEMPLATES) out.add(render(t, req));
        assertEquals(Arrays.asList(
                "trino-clemlab-trino-coordinator.trino-ns-iceberg.svc",
                "trino-clemlab-trino-coordinator.trino-ns-iceberg.svc.cluster.local",
                "trino-test-iceberg.dev.clemlab.com"), out);
    }

    @Test
    void theIngressHostIsReadFromTheHostsListTheChartActuallyUses() {
        // The wizard's scalar ingress.host becomes ingress.hosts[] before the deploy is submitted.
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("nameOverride", "clemlab-trino");
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("host", "trino-test-iceberg.dev.clemlab.com");
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("hosts", List.of(h));
        v.put("ingress", ingress);
        assertEquals("trino-test-iceberg.dev.clemlab.com", render("{{ingress.host}}", request(v, new LinkedHashMap<>())));
    }

    @Test
    void anUnresolvedTokenDropsTheWholeName() throws Exception {
        // No ingress host yet: emitting "…-coordinator…svc" is right, emitting "" as a SAN is not.
        HelmDeployRequest req = request(values("clemlab-trino", null), new LinkedHashMap<>());
        assertEquals("", render("{{ingress.host}}", req));
        assertNotNull(render(TEMPLATES.get(0), req));
        assertEquals("trino-clemlab-trino-coordinator.trino-ns-iceberg.svc", render(TEMPLATES.get(0), req));
    }
}
