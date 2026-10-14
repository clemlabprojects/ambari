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
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The Ranger audit ConfigMap of a Trino release (ranger-trino-audit.xml) sends the audit events where the platform
 * context's Ranger sends them, instead of only to the pod log: Solr and HDFS from the context, the wizard's choices
 * over them, and what the operator must still do when a destination cannot be wired.
 */
class CommandServiceRangerAuditTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino-odp";
    private static final String AUDIT_MAP = RELEASE + "-ranger-trino-audit";

    @TempDir
    Path tmp;

    private KubernetesService k8s;
    private CommandService service;
    private AmbariAliasResolver aliasResolver;
    private ResolvedContext platform;

    @BeforeEach
    void setUp() throws Exception {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        doReturn("ranger-audit-" + Long.toHexString(System.nanoTime())).when(ctx).getInstanceName();
        doReturn(Map.of("k8s.view.working.dir", tmp.resolve("work").toString())).when(ctx).getProperties();
        DataStore ds = mock(DataStore.class);
        doReturn(new ArrayList<>()).when(ds).findAll(eq(K8sReleaseEntity.class), any());
        doReturn(ds).when(ctx).getDataStore();
        k8s = mock(KubernetesService.class);
        platform = new ResolvedContext();
        platform.setId("default");
        platform.setKind(ContextService.KIND_MANAGED);
        // The platform context is the part under test, so the deploy is handed the one built here.
        service = new CommandService(ctx, mock(HelmService.class), k8s) {
            @Override
            ResolvedContext resolvePlatformContextForStep(Map<String, Object> childParams) {
                return platform;
            }
        };
        service.startupSweep.get(30, TimeUnit.SECONDS);
        doReturn(tmp.resolve("truststore.jks").toString()).when(ctx).getAmbariProperty("ssl.trustStore.path");
        doReturn("JKS").when(ctx).getAmbariProperty("ssl.trustStore.type");
        doReturn("changeit").when(ctx).getAmbariProperty("ssl.trustStore.password");

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
        service.shutdown();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> trinoRangerSpec() throws Exception {
        try (InputStream in = CommandServiceRangerAuditTest.class
                .getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            Map<String, Object> definition = new ObjectMapper().readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) definition.get("ranger");
        }
    }

    /** The Hadoop configuration the Trino definition asks the deploy to provide (core-site and hdfs-site). */
    @SuppressWarnings("unchecked")
    private static Object trinoRequiredConfigMaps() throws Exception {
        try (InputStream in = CommandServiceRangerAuditTest.class
                .getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            return new ObjectMapper().readValue(in, Map.class).get("requiredConfigMaps");
        }
    }

    /** Plans the Ranger plugin objects of a deploy with the given wizard values; returns what the operator must hear. */
    private List<String> materialize(Map<String, Object> values) throws Exception {
        return materialize(values, true, new LinkedHashMap<>(), null, null);
    }

    private List<String> materialize(Map<String, Object> values, boolean withHadoopConf, Map<String, Object> params,
                                     org.apache.ambari.view.k8s.utils.AmbariActionClient ambari, String cluster)
            throws Exception {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName(RELEASE);
        request.setNamespace(NS);
        request.setServiceKey("TRINO");
        request.setRanger(trinoRangerSpec());
        if (withHadoopConf) {
            request.setRequiredConfigMaps(trinoRequiredConfigMaps());
        }
        Method method = CommandService.class.getDeclaredMethod("materializeRangerResources",
                HelmDeployRequest.class, Map.class, Map.class, org.apache.ambari.view.k8s.utils.AmbariActionClient.class,
                String.class, AmbariAliasResolver.class);
        method.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) method.invoke(service, request, params,
                    values, ambari, cluster, aliasResolver);
            return warnings;
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** The properties of the audit ConfigMap the deploy wrote, by name. */
    @SuppressWarnings("unchecked")
    private Map<String, String> auditProperties() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s, atLeastOnce()).createOrUpdateConfigMap(eq(NS), names.capture(), data.capture(), anyMap(), anyMap());
        String xml = data.getAllValues().get(names.getAllValues().indexOf(AUDIT_MAP)).get("ranger-trino-audit.xml");
        Map<String, String> properties = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<name>([^<]*)</name>\\s*<value>([^<]*)</value>").matcher(xml);
        while (m.find()) {
            properties.put(m.group(1), m.group(2));
        }
        return properties;
    }

    private void clusterAuditingToSolrAndHdfs() {
        Map<String, String> fields = platform.getResolvedFields();
        fields.put("ranger.auditSolrEnabled", "true");
        fields.put("ranger.auditSolrUrls", "http://solr01:8886/solr/ranger_audits");
        fields.put("ranger.auditHdfsEnabled", "true");
        fields.put("ranger.auditHdfsDir", "hdfs://nn01:8020/ranger/audit");
    }

    @Test
    void theAuditConfigurationFollowsTheContextsSolrAndHdfsAndKeepsThePodLog() throws Exception {
        clusterAuditingToSolrAndHdfs();

        List<String> warnings = materialize(new LinkedHashMap<>());

        Map<String, String> audit = auditProperties();
        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals("http://solr01:8886/solr/ranger_audits", audit.get("xasecure.audit.destination.solr.urls"));
        assertEquals("true", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("hdfs://nn01:8020/ranger/audit", audit.get("xasecure.audit.destination.hdfs.dir"));
        assertEquals("true", audit.get("xasecure.audit.destination.log4j"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aContextWithoutAuditSettingsGivesThePodLogOnly() throws Exception {
        materialize(new LinkedHashMap<>());

        Map<String, String> audit = auditProperties();
        assertEquals("false", audit.get("xasecure.audit.destination.solr"));
        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("true", audit.get("xasecure.audit.destination.log4j"));
    }

    @Test
    void theWizardChoicesInTheReleaseValuesOverrideTheContext() throws Exception {
        clusterAuditingToSolrAndHdfs();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("solrMode", "off");
        audit.put("hdfsMode", "on");
        audit.put("hdfsDir", "hdfs://other:8020/audit");
        audit.put("log4j", false);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ranger", new LinkedHashMap<>(Map.of("audit", audit)));

        materialize(values);

        Map<String, String> written = auditProperties();
        assertEquals("false", written.get("xasecure.audit.destination.solr"));
        assertEquals("true", written.get("xasecure.audit.destination.hdfs"));
        assertEquals("hdfs://other:8020/audit", written.get("xasecure.audit.destination.hdfs.dir"));
        assertEquals("false", written.get("xasecure.audit.destination.log4j"));
    }

    @Test
    void anAuditSettingTheClusterCouldNotBeAskedForIsToldToTheOperator() throws Exception {
        platform.getUnreadFields().add("ranger.auditSolrEnabled");

        List<String> warnings = materialize(new LinkedHashMap<>());

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("audit settings"), warnings.get(0));
    }

    @Test
    void theSpoolFoldersLieOnTheVolumeOfThePolicyCache() throws Exception {
        clusterAuditingToSolrAndHdfs();

        materialize(new LinkedHashMap<>());

        Map<String, String> audit = auditProperties();
        String cache = "/opt/trino/data/ranger-trino-plugin/cache";
        assertEquals(cache + "/audit/solr", audit.get("xasecure.audit.destination.solr.batch.filespool.dir"));
        assertEquals(cache + "/audit/hdfs", audit.get("xasecure.audit.destination.hdfs.batch.filespool.dir"));
    }

    @Test
    void aKerberizedClusterGivesTheSolrWriterTheLoginOfThePlugin() throws Exception {
        clusterAuditingToSolrAndHdfs();
        org.apache.ambari.view.k8s.utils.AmbariActionClient ambari =
                mock(org.apache.ambari.view.k8s.utils.AmbariActionClient.class);
        doReturn("EXAMPLE.COM").when(ambari).getDesiredConfigProperty(any(), eq("kerberos-env"), eq("realm"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kerberosClusterEnabled", true);

        materialize(new LinkedHashMap<>(), true, params, ambari, "odp");

        Map<String, String> audit = auditProperties();
        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals("true", audit.get("xasecure.audit.destination.solr.force.use.inmemory.jaas.config"));
        assertEquals("com.sun.security.auth.module.Krb5LoginModule", audit.get("xasecure.audit.jaas.Client.loginModuleName"));
        assertEquals("/etc/security/keytabs/service.keytab", audit.get("xasecure.audit.jaas.Client.option.keyTab"));
        assertEquals("trino-" + NS + "@EXAMPLE.COM", audit.get("xasecure.audit.jaas.Client.option.principal"));
    }

    @Test
    void aKerberizedClusterIsToldWhichUserSolrAndHdfsMustAccept() throws Exception {
        clusterAuditingToSolrAndHdfs();
        org.apache.ambari.view.k8s.utils.AmbariActionClient ambari =
                mock(org.apache.ambari.view.k8s.utils.AmbariActionClient.class);
        doReturn("EXAMPLE.COM").when(ambari).getDesiredConfigProperty(any(), eq("kerberos-env"), eq("realm"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kerberosClusterEnabled", true);

        List<String> warnings = materialize(new LinkedHashMap<>(), true, params, ambari, "odp");

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Solr") && w.contains("'trino-" + NS + "'")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("HDFS") && w.contains("/ranger/audit/trino")
                && w.contains("'trino-" + NS + "'")), warnings.toString());
    }

    @Test
    void aClusterWithoutKerberosGetsNoSolrLogin() throws Exception {
        clusterAuditingToSolrAndHdfs();

        materialize(new LinkedHashMap<>());

        assertTrue(!auditProperties().containsKey("xasecure.audit.jaas.Client.option.principal"));
    }

    @Test
    void hdfsIsNotSwitchedOnByTheContextWhenThePodsGetNoHadoopConfiguration() throws Exception {
        clusterAuditingToSolrAndHdfs();

        List<String> warnings = materialize(new LinkedHashMap<>(), false, new LinkedHashMap<>(), null, null);

        Map<String, String> audit = auditProperties();
        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("hdfs-site"), warnings.get(0));
    }

    @Test
    void theWizardOffersTheAuditChoicesTheDeployReads() throws Exception {
        try (InputStream in = CommandServiceRangerAuditTest.class
                .getResourceAsStream("/KDPS/services/TRINO/service.json")) {
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String field : List.of("ranger.audit.solrMode", "ranger.audit.solrUrls", "ranger.audit.solrZookeepers",
                    "ranger.audit.hdfsMode", "ranger.audit.hdfsDir", "ranger.audit.log4j")) {
                assertTrue(json.contains("\"name\": \"" + field + "\""), field);
            }
        }
    }
}
