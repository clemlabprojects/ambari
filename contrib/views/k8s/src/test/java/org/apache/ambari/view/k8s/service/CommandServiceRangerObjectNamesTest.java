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
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.apache.ambari.view.k8s.service.om.FakeRangerAdmin;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.apache.ambari.view.k8s.utils.TlsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Two Trino releases in one namespace each get their own Ranger plugin configuration: the ConfigMaps and Secrets the
 * view writes for a release carry its name, so deploying or reapplying Ranger on one never changes what the other
 * mounts. The keys inside them keep the plain file names the chart mounts by subPath, and the chart values point the
 * release at its own objects, so the coordinator mounts are the same as before. Releases an earlier version deployed
 * keep mounting the shared objects, which are left as they are, until they are upgraded.
 */
class CommandServiceRangerObjectNamesTest {

    private static final String NS = "analytics";

    /** The files the Trino chart mounts from the Ranger ConfigMaps by subPath (deployment-coordinator.yaml). */
    private static final List<String> CHART_SUB_PATHS =
            List.of("ranger-trino-security.xml", "ranger-trino-audit.xml", "ranger-policymgr-ssl-trino.xml");

    @TempDir
    Path tmp;

    private ViewContext ctx;
    private KubernetesService k8s;
    private CommandService service;
    private AmbariAliasResolver aliasResolver;
    private FakeRangerAdmin ranger;

    @BeforeEach
    void setUp() throws Exception {
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        doReturn("ranger-names-" + Long.toHexString(System.nanoTime())).when(ctx).getInstanceName();
        doReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString())).when(ctx).getProperties();
        DataStore ds = mock(DataStore.class);
        doReturn(new ArrayList<>()).when(ds).findAll(eq(K8sReleaseEntity.class), any());
        doReturn(ds).when(ctx).getDataStore();
        k8s = mock(KubernetesService.class);
        service = new CommandService(ctx, mock(HelmService.class), k8s);
        service.startupSweep.get(30, TimeUnit.SECONDS);
        // The Ambari truststore the plugin objects are made from; the mocked cluster never reads the file.
        doReturn(tmp.resolve("truststore.jks").toString()).when(ctx).getAmbariProperty("ssl.trustStore.path");
        doReturn("JKS").when(ctx).getAmbariProperty("ssl.trustStore.type");
        doReturn("changeit").when(ctx).getAmbariProperty("ssl.trustStore.password");

        ranger = new FakeRangerAdmin();
        service.tagSyncAccounts = new TagSyncRangerAccountTest.MemoryRecords();
        service.tagSyncRangers = access -> contextId ->
                TagSyncRangerAccount.direct(TlsPolicy.JVM_DEFAULT, ranger.url(), "admin", "admin-pw");
        service.tagSyncInstance = () -> "kdps";

        FileBasedCredentialStore jceks = new FileBasedCredentialStore(tmp.resolve("credentials.jceks").toFile());
        jceks.setMasterKeyService(new MasterKeyService() {
            @Override
            public char[] getMasterSecret() {
                return "test-master-key".toCharArray();
            }

            @Override
            public boolean isMasterKeyInitialized() {
                return true;
            }
        });
        aliasResolver = new AmbariAliasResolver(ctx, jceks);
    }

    @AfterEach
    void tearDown() {
        ranger.close();
        service.shutdown();
    }

    /** The Ranger block of the shipped Trino service definition, as the wizard sends it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> trinoRangerSpec() throws Exception {
        try (InputStream in = CommandServiceRangerObjectNamesTest.class
                .getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            Map<String, Object> definition = new ObjectMapper().readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) definition.get("ranger");
        }
    }

    private static HelmDeployRequest request(String release) throws Exception {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName(release);
        request.setNamespace(NS);
        request.setServiceKey("TRINO");
        request.setRanger(trinoRangerSpec());
        return request;
    }

    /** Plans the Ranger plugin objects of a deploy of {@code release} and returns the chart values it set. */
    @SuppressWarnings("unchecked")
    private Map<String, String> materialize(String release) throws Exception {
        Method method = CommandService.class.getDeclaredMethod("materializeRangerResources",
                HelmDeployRequest.class, Map.class, Map.class, org.apache.ambari.view.k8s.utils.AmbariActionClient.class,
                String.class, AmbariAliasResolver.class);
        method.setAccessible(true);
        Map<String, Object> params = new LinkedHashMap<>();
        try {
            method.invoke(service, request(release), params, new LinkedHashMap<String, Object>(), null, null,
                    aliasResolver);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
        Object ov = params.get("_ov");
        return ov == null ? Map.of() : (Map<String, String>) ov;
    }

    /** Every ConfigMap written, by name, with its data. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> configMapsWritten() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s, atLeastOnce()).createOrUpdateConfigMap(eq(NS), names.capture(), data.capture(), anyMap(), anyMap());
        Map<String, Map<String, String>> written = new LinkedHashMap<>();
        for (int i = 0; i < names.getAllValues().size(); i++) {
            written.put(names.getAllValues().get(i), data.getAllValues().get(i));
        }
        return written;
    }

    @Test
    void twoReleasesInOneNamespaceGetDistinctObjectsWithTheSameKeys() throws Exception {
        materialize("trino-cdp");
        materialize("trino-odp");

        Map<String, Map<String, String>> written = configMapsWritten();
        for (String release : List.of("trino-cdp", "trino-odp")) {
            assertEquals(List.of("ranger-trino-security.xml"),
                    List.copyOf(written.get(release + "-ranger-trino-security").keySet()));
            assertEquals(List.of("ranger-trino-audit.xml"),
                    List.copyOf(written.get(release + "-ranger-trino-audit").keySet()));
            assertEquals(List.of("ranger-policymgr-ssl-trino.xml"),
                    List.copyOf(written.get(release + "-ranger-policymgr-ssl").keySet()));
        }
        assertEquals(6, written.size(), "only the releases' own ConfigMaps are written: " + written.keySet());

        // The truststore and credential Secrets are the release's own too.
        for (String release : List.of("trino-cdp", "trino-odp")) {
            verify(k8s).ensureOrRotateTruststoreSecretFromLocalFile(eq(NS), eq(release + "-ranger-admin-truststore"),
                    anyString(), eq("JKS"), any(char[].class), eq("truststore.jks"), anyInt(),
                    eq(Map.of("managed-by", "ambari-k8s-view", "app.kubernetes.io/instance", release)));
            verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(release + "-ranger-admin-ts-pass"), eq("password"),
                    any(byte[].class), eq(Map.of("managed-by", "ambari-k8s-view", "app.kubernetes.io/instance", release)),
                    any(), eq(Boolean.TRUE));
            ArgumentCaptor<Map<String, String>> credLabels = labelsCaptor();
            verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(release + "-ranger-trino-plugin-credential-provider"),
                    eq("cred.jceks"), any(byte[].class), credLabels.capture(), anyMap(), eq(Boolean.TRUE));
            assertEquals(release, credLabels.getValue().get("app.kubernetes.io/instance"));
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> labelsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @Test
    void theNamespaceWideObjectsAnEarlierVersionWroteAreLeftAlone() throws Exception {
        materialize("trino-cdp");

        for (String shared : List.of("ranger-trino-security", "ranger-trino-audit", "ranger-policymgr-ssl")) {
            verify(k8s, never()).createOrUpdateConfigMap(eq(NS), eq(shared), anyMap(), anyMap(), anyMap());
        }
        verify(k8s, never()).ensureOrRotateTruststoreSecretFromLocalFile(eq(NS), eq("ranger-admin-truststore"),
                anyString(), anyString(), any(char[].class), anyString(), anyInt(), any());
        verify(k8s, never()).createOrUpdateOpaqueSecret(eq(NS), eq("ranger-trino-plugin-credential-provider"),
                anyString(), any(byte[].class), any(), any(), any());
        verify(k8s, never()).createOrUpdateOpaqueSecret(eq(NS), eq("ranger-admin-ts-pass"),
                anyString(), any(byte[].class), any(), any(), any());
    }

    @Test
    void theRangerConfigMapsAreLabelledWithTheirRelease() throws Exception {
        materialize("trino-odp");

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> labels = labelsCaptor();
        verify(k8s, atLeastOnce()).createOrUpdateConfigMap(eq(NS), names.capture(), anyMap(), labels.capture(),
                anyMap());
        for (Map<String, String> l : labels.getAllValues()) {
            assertEquals("trino-odp", l.get("app.kubernetes.io/instance"));
            assertEquals("ambari-k8s-view", l.get("managed-by"));
        }
    }

    @Test
    void theChartIsPointedAtTheReleaseObjectsAndMountsTheSameFiles() throws Exception {
        Map<String, String> values = materialize("trino-cdp");

        assertEquals("trino-cdp-ranger-trino-security", values.get("ranger.securityConfigMapName"));
        assertEquals("trino-cdp-ranger-trino-audit", values.get("ranger.auditConfigMapName"));
        assertEquals("trino-cdp-ranger-policymgr-ssl", values.get("ranger.policymgrSslConfigMapName"));
        assertEquals("trino-cdp-ranger-admin-truststore", values.get("ranger.admin.ssl.truststore.secretName"));
        assertEquals("trino-cdp-ranger-admin-ts-pass", values.get("ranger.admin.ssl.truststore.password"));
        assertEquals("trino-cdp-ranger-trino-plugin-credential-provider", values.get("ranger.cred.secretName"));
        // What the coordinator mounts inside those objects is unchanged.
        assertEquals("truststore.jks", values.get("ranger.admin.ssl.truststore.fileKey"));
        assertEquals("cred.jceks", values.get("ranger.cred.providerFile"));
        assertEquals("/etc/trino/ranger/ranger-policymgr-ssl-trino.xml", values.get("ranger.policymgrssl_path"));

        Map<String, Map<String, String>> written = configMapsWritten();
        List<String> keys = new ArrayList<>();
        for (String property : List.of("ranger.securityConfigMapName", "ranger.auditConfigMapName",
                "ranger.policymgrSslConfigMapName")) {
            keys.addAll(written.get(values.get(property)).keySet());
        }
        assertEquals(CHART_SUB_PATHS, keys);
    }

    @Test
    void thePluginPasswordStaysSharedByTheNamespace() throws Exception {
        // Every release logs in to Ranger as the same plugin user, whose password Ranger keeps from its creation.
        materialize("trino-cdp");
        materialize("trino-odp");

        verify(k8s, org.mockito.Mockito.times(2)).readOpaqueSecretKeyAsString(NS,
                "ranger-plugin-rest-client-password", "password");
    }

    @Test
    void objectNamesFillTheReleaseAndKeepThePlainFileName() {
        assertEquals("trino-a-ranger-trino-security",
                CommandService.rangerObjectName("{{releaseName}}-ranger-trino-security", "trino-a", NS));
        assertEquals("ranger-trino-security",
                CommandService.rangerObjectName("{{releaseName}}-ranger-trino-security", null, null));
        assertEquals("trino-a-analytics-cred",
                CommandService.rangerObjectName("{{releaseName}}-{{namespace}}-cred", "trino-a", NS));
        // A service definition without placeholders keeps its name, and its key.
        assertEquals("ranger-trino-audit", CommandService.rangerObjectName("ranger-trino-audit", "trino-a", NS));
        assertNotEquals(CommandService.rangerObjectName("{{releaseName}}-ranger-trino-audit", "trino-a", NS),
                CommandService.rangerObjectName("{{releaseName}}-ranger-trino-audit", "trino-b", NS));
    }

    @Test
    void aReleaseDeployedByAnEarlierVersionIsToldItStillMountsTheSharedObjects() throws Exception {
        Map<String, Object> rangerValues = new LinkedHashMap<>();
        rangerValues.put("securityConfigMapName", "ranger-trino-security");
        rangerValues.put("auditConfigMapName", "ranger-trino-audit");
        rangerValues.put("policymgrSslConfigMapName", "ranger-policymgr-ssl");
        rangerValues.put("cred", new LinkedHashMap<>(Map.of("secretName", "ranger-trino-plugin-credential-provider")));
        rangerValues.put("admin", new LinkedHashMap<>(Map.of("ssl",
                new LinkedHashMap<>(Map.of("truststore", new LinkedHashMap<>(Map.of(
                        "secretName", "ranger-admin-truststore")))))));
        Map<String, Object> legacy = new LinkedHashMap<>(Map.of("ranger", rangerValues));

        assertEquals(List.of("ranger-trino-security", "ranger-trino-audit", "ranger-policymgr-ssl",
                        "ranger-trino-plugin-credential-provider", "ranger-admin-truststore"),
                CommandService.sharedRangerObjectsStillMounted(trinoRangerSpec(), legacy, "trino-cdp", NS));

        // Once upgraded, the release mounts its own objects only.
        Map<String, String> own = materialize("trino-cdp");
        Map<String, Object> upgraded = new LinkedHashMap<>();
        own.forEach((path, value) -> putDotted(upgraded, path, value));
        assertTrue(CommandService.sharedRangerObjectsStillMounted(trinoRangerSpec(), upgraded, "trino-cdp", NS)
                .isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static void putDotted(Map<String, Object> values, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = values;
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
        node.put(parts[parts.length - 1], value);
    }
}
