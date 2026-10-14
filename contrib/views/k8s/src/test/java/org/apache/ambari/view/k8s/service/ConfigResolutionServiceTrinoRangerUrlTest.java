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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDBC URL Ranger Admin uses to look up Trino resources must verify the coordinator certificate, and must point at
 * a port the coordinator Service really exposes whether or not internal HTTPS is on.
 */
class ConfigResolutionServiceTrinoRangerUrlTest {

    @Test
    void withInternalHttpsTheUrlVerifiesTheCertificateOnTheHttpsPort() throws Exception {
        Map<String, Object> values = Map.of(
                "server", Map.of("config", Map.of("https", Map.of("enabled", true, "port", 8443))),
                "service", Map.of("port", 8080));

        assertEquals("jdbc:trino://trino-trino-coordinator.analytics.svc.cluster.local:8443?SSL=true&SSLVerification=FULL",
                jdbcUrl(values));
    }

    @Test
    void withoutInternalHttpsTheUrlUsesThePlainServicePort() throws Exception {
        Map<String, Object> values = Map.of(
                "server", Map.of("config", Map.of("https", Map.of("enabled", false, "port", 8443))),
                "service", Map.of("port", 8080));

        String url = jdbcUrl(values);
        assertEquals("jdbc:trino://trino-trino-coordinator.analytics.svc.cluster.local:8080?SSL=false", url);
        assertTrue(!url.contains("8443"));
        assertTrue(!url.contains("SSLVerification"));
    }

    @Test
    void anOperatorSuppliedCertificateIsCheckedAgainstItsChainOnly() throws Exception {
        Map<String, Object> values = Map.of(
                "server", Map.of("config", Map.of("https", Map.of("enabled", true, "port", 8443))),
                "service", Map.of("port", 8080));
        assertTrue(jdbcUrl(values, "CA").endsWith("SSL=true&SSLVerification=CA"));

        HelmDeployRequest supplied = new HelmDeployRequest();
        supplied.setFormValues(Map.of("tls", Map.of("https", Map.of("autoGenerate", false))));
        assertEquals("CA", CommandService.rangerSslVerification(supplied));
        HelmDeployRequest issued = new HelmDeployRequest();
        issued.setFormValues(Map.of("tls", Map.of("https", Map.of("autoGenerate", true))));
        assertEquals("FULL", CommandService.rangerSslVerification(issued));
        assertEquals("FULL", CommandService.rangerSslVerification(new HelmDeployRequest()));
    }

    @Test
    void theTlsSpecDecidesTheCheckWhenThereIsNoForm() {
        HelmDeployRequest supplied = new HelmDeployRequest();
        supplied.setTls(Map.of("https", Map.of("autoGenerate", false)));
        assertEquals("CA", CommandService.rangerSslVerification(supplied));
        assertEquals("&SSLVerification=CA", CommandService.rangerSslVerificationSuffix(supplied,
                Map.of("server", Map.of("config", Map.of("https", Map.of("enabled", true))))));
    }

    @Test
    void aKeystoreSecretTheViewDidNotWriteIsTakenAsOperatorSupplied(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        org.apache.ambari.view.k8s.service.KubernetesService k8s =
                org.mockito.Mockito.mock(org.apache.ambari.view.k8s.service.KubernetesService.class);
        CommandService service = new CommandService(
                new org.apache.ambari.view.k8s.store.PerUserViewContext("ranger-ssl", dir).context,
                org.mockito.Mockito.mock(HelmService.class), k8s);
        Map<String, Object> values = Map.of("server", Map.of("config", Map.of("https",
                Map.of("keystore", Map.of("secretName", "t-https")))));
        io.fabric8.kubernetes.api.model.Secret own = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("t-https").addToAnnotations("managed-by", "ambari-k8s-view").endMetadata().build();
        io.fabric8.kubernetes.api.model.Secret theirs = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("t-https").endMetadata().build();

        org.mockito.Mockito.doReturn(own).when(k8s).getSecret("ns", "t-https");
        assertTrue(!service.operatorSuppliedHttpsKeystore("ns", values));
        org.mockito.Mockito.doReturn(theirs).when(k8s).getSecret("ns", "t-https");
        assertTrue(service.operatorSuppliedHttpsKeystore("ns", values));
        assertTrue(!service.operatorSuppliedHttpsKeystore("ns", Map.of()));
    }

    @Test
    void theOperatorIsToldWhichCaRangerAdminMustTrust() {
        HelmDeployRequest r = new HelmDeployRequest();
        r.setServiceKey("TRINO");
        r.setRanger(Map.of("ranger-plugin-settings", Map.of("x", "y")));
        r.setValues(Map.of("server", Map.of("config", Map.of("https", Map.of("enabled", true)))));
        assertTrue(CommandService.rangerLookupTrustWarning(r).contains("Ambari internal CA"));

        r.setFormValues(Map.of("tls", Map.of("https", Map.of("autoGenerate", false))));
        assertTrue(CommandService.rangerLookupTrustWarning(r).contains("you supplied"));

        r.setValues(Map.of("server", Map.of("config", Map.of("https", Map.of("enabled", false)))));
        assertEquals(null, CommandService.rangerLookupTrustWarning(r));
    }

    @Test
    void anOlderChartKeepsTheCertificateSkipAndDropsTheCaKey() {
        Map<String, Object> md = new LinkedHashMap<>(Map.of("authModes", "bearer"));
        Map<String, Object> auth = new LinkedHashMap<>(Map.of("enabled", true, "caKey", "service-ca.crt"));
        Map<String, Object> values = new LinkedHashMap<>(Map.of("server", Map.of("keda", Map.of(
                "triggerAuthentication", auth, "triggers", java.util.List.of(Map.of("metadata", md))))));

        assertTrue(CommandService.restoreTriggerCertSkipOnOlderChart(values, "1.43.17"));
        assertEquals("true", md.get("unsafeSsl"));
        assertTrue(!auth.containsKey("caKey"));
    }

    @Test
    void aChartWithTheCaKeyKeepsVerificationOn() {
        Map<String, Object> md = new LinkedHashMap<>(Map.of("authModes", "bearer"));
        Map<String, Object> auth = new LinkedHashMap<>(Map.of("caKey", "service-ca.crt"));
        Map<String, Object> values = new LinkedHashMap<>(Map.of("server", Map.of("keda", Map.of(
                "triggerAuthentication", auth, "triggers", java.util.List.of(Map.of("metadata", md))))));

        assertTrue(!CommandService.restoreTriggerCertSkipOnOlderChart(values, "1.43.18"));
        assertTrue(!md.containsKey("unsafeSsl"));
        assertEquals("service-ca.crt", auth.get("caKey"));
    }

    @Test
    void theTrinoKedaTriggersNoLongerSkipCertificateChecks() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes());
            assertTrue(!json.contains("unsafeSsl"));
            assertTrue(json.contains("server.keda.triggerAuthentication.caKey"));
        }
    }

    private String jdbcUrl(Map<String, Object> helmValues) throws Exception {
        return jdbcUrl(helmValues, "FULL");
    }

    private String jdbcUrl(Map<String, Object> helmValues, String sslVerification) throws Exception {
        StackServiceDef def;
        try (InputStream in = getClass().getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            assertNotNull(in);
            def = new ObjectMapper().readValue(in, StackServiceDef.class);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseName", "trino");
        params.put("nameOverride", "trino");
        params.put("namespace", "analytics");
        HelmDeployRequest request = new HelmDeployRequest();
        request.setFormValues(Map.of("tls", Map.of("https", Map.of("autoGenerate", "FULL".equals(sslVerification)))));
        params.put(CommandService.RANGER_SSL_VERIFICATION_PARAM,
                CommandService.rangerSslVerificationSuffix(request, helmValues));
        Map<String, String> configs = new ConfigResolutionService().computeExtraRangerConfigs(
                def.ranger.get("ranger-plugin-settings"), params, helmValues, null, "c1");
        return configs.get("jdbc.url");
    }
}
