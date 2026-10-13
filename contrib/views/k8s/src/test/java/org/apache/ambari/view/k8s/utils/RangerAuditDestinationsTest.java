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

package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.k8s.model.RangerTrinoConfigDefaults;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit destinations of a Ranger plugin in a pod follow the platform context's Ranger, with the wizard able to
 * override each of them, and the pod log kept as an extra.
 */
class RangerAuditDestinationsTest {

    private static Map<String, String> clusterAuditingToSolrAndHdfs() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("ranger.auditSolrEnabled", "true");
        context.put("ranger.auditSolrUrls", "http://solr01:8886/solr/ranger_audits");
        context.put("ranger.auditSolrCollection", "ranger_audits");
        context.put("ranger.auditHdfsEnabled", "true");
        context.put("ranger.auditHdfsDir", "hdfs://nn01:8020/ranger/audit");
        return context;
    }

    private static Map<String, Object> wizard(Object... pairs) {
        Map<String, Object> audit = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            audit.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return new LinkedHashMap<>(Map.of("ranger", new LinkedHashMap<>(Map.of("audit", audit))));
    }

    private static Map<String, String> apply(Map<String, String> context, Map<String, Object> values,
                                             List<String> warnings) {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());
        warnings.addAll(RangerAuditDestinations.apply(audit, context, values, null, null, null, true));
        return audit;
    }

    @Test
    void withoutAContextThePluginAuditsToThePodLogOnly() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, String> audit = apply(null, null, warnings);

        assertEquals("true", audit.get("xasecure.audit.destination.log4j"));
        assertEquals("false", audit.get("xasecure.audit.destination.solr"));
        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void theDestinationsOfTheContextsRangerAreFollowedAndTheLogStaysOn() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(), null, warnings);

        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals("true", audit.get("xasecure.audit.solr.is.enabled"));
        assertEquals("http://solr01:8886/solr/ranger_audits", audit.get("xasecure.audit.destination.solr.urls"));
        assertEquals("", audit.get("xasecure.audit.destination.solr.zookeepers"));
        assertEquals("ranger_audits", audit.get("xasecure.audit.destination.solr.collection"));
        assertEquals("true", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("true", audit.get("xasecure.audit.hdfs.is.enabled"));
        assertEquals("hdfs://nn01:8020/ranger/audit", audit.get("xasecure.audit.destination.hdfs.dir"));
        assertEquals("hdfs://nn01:8020/ranger/audit", audit.get("xasecure.audit.hdfs.dir"));
        assertEquals("true", audit.get("xasecure.audit.destination.log4j"));
        assertEquals("true", audit.get("xasecure.audit.log4j.is.enabled"));
        assertEquals("true", audit.get("xasecure.audit.is.enabled"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aSolrCloudIsReachedThroughItsZookeeperAlone() {
        Map<String, String> context = clusterAuditingToSolrAndHdfs();
        context.put("ranger.auditSolrZookeepers", "zk01:2181,zk02:2181/infra-solr");

        Map<String, String> audit = apply(context, null, new java.util.ArrayList<>());

        assertEquals("zk01:2181,zk02:2181/infra-solr", audit.get("xasecure.audit.destination.solr.zookeepers"));
        assertEquals("", audit.get("xasecure.audit.destination.solr.urls"));
    }

    @Test
    void aContextThatDoesNotAuditToSolrLeavesItOff() {
        Map<String, String> context = clusterAuditingToSolrAndHdfs();
        context.put("ranger.auditSolrEnabled", "false");
        context.put("ranger.auditHdfsEnabled", "false");

        Map<String, String> audit = apply(context, null, new java.util.ArrayList<>());

        assertEquals("false", audit.get("xasecure.audit.destination.solr"));
        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
    }

    @Test
    void theWizardTurnsADestinationOffAgainstTheContext() {
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(),
                wizard("solrMode", "off", "hdfsMode", "auto"), new java.util.ArrayList<>());

        assertEquals("false", audit.get("xasecure.audit.destination.solr"));
        assertEquals("true", audit.get("xasecure.audit.destination.hdfs"));
    }

    @Test
    void theWizardTurnsADestinationOnWithItsOwnEndpointAgainstAContextWithoutIt() {
        Map<String, String> context = Map.of("ranger.auditSolrEnabled", "false");

        Map<String, String> audit = apply(context, wizard(
                "solrMode", "on", "solrUrls", "http://other:8983/solr/audits",
                "hdfsMode", "on", "hdfsDir", "hdfs://other:8020/audit"), new java.util.ArrayList<>());

        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals("http://other:8983/solr/audits", audit.get("xasecure.audit.destination.solr.urls"));
        assertEquals("true", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("hdfs://other:8020/audit", audit.get("xasecure.audit.destination.hdfs.dir"));
    }

    @Test
    void aWizardEndpointReplacesTheContextsWhenFollowingTheContext() {
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(),
                wizard("solrMode", "auto", "solrUrls", "http://other:8983/solr/audits"), new java.util.ArrayList<>());

        assertEquals("http://other:8983/solr/audits", audit.get("xasecure.audit.destination.solr.urls"));
    }

    @Test
    void aWizardUrlReplacesTheZookeeperOfTheContext() {
        Map<String, String> context = clusterAuditingToSolrAndHdfs();
        context.put("ranger.auditSolrZookeepers", "zk01:2181/infra-solr");

        Map<String, String> audit = apply(context, wizard("solrUrls", "http://other:8983/solr/audits"),
                new java.util.ArrayList<>());

        assertEquals("http://other:8983/solr/audits", audit.get("xasecure.audit.destination.solr.urls"));
        assertEquals("", audit.get("xasecure.audit.destination.solr.zookeepers"));
    }

    @Test
    void aWizardZookeeperReplacesTheUrlsOfTheContext() {
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(), wizard("solrZookeepers", "zk09:2181/solr"),
                new java.util.ArrayList<>());

        assertEquals("zk09:2181/solr", audit.get("xasecure.audit.destination.solr.zookeepers"));
        assertEquals("", audit.get("xasecure.audit.destination.solr.urls"));
    }

    @Test
    void theSolrWriterLogsInWithThePluginsKeytabWhenThereIsOne() {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());

        RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(), null, null,
                "trino-analytics@EXAMPLE.COM", "/etc/security/keytabs/service.keytab", true);

        assertEquals("true", audit.get("xasecure.audit.destination.solr.force.use.inmemory.jaas.config"));
        assertEquals("com.sun.security.auth.module.Krb5LoginModule", audit.get("xasecure.audit.jaas.Client.loginModuleName"));
        assertEquals("required", audit.get("xasecure.audit.jaas.Client.loginModuleControlFlag"));
        assertEquals("true", audit.get("xasecure.audit.jaas.Client.option.useKeyTab"));
        assertEquals("true", audit.get("xasecure.audit.jaas.Client.option.storeKey"));
        assertEquals("solr", audit.get("xasecure.audit.jaas.Client.option.serviceName"));
        assertEquals("/etc/security/keytabs/service.keytab", audit.get("xasecure.audit.jaas.Client.option.keyTab"));
        assertEquals("trino-analytics@EXAMPLE.COM", audit.get("xasecure.audit.jaas.Client.option.principal"));
    }

    @Test
    void aKerberizedClusterIsToldWhichUserSolrAndHdfsMustAccept() {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());

        List<String> warnings = RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(), null, null,
                "trino-analytics@EXAMPLE.COM", "/etc/security/keytabs/service.keytab", true);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Infra Solr") && warnings.get(0).contains("'trino-analytics'"),
                warnings.get(0));
        assertTrue(warnings.get(1).contains("hdfs://nn01:8020/ranger/audit/trino")
                && warnings.get(1).contains("'trino-analytics'"), warnings.get(1));
    }

    @Test
    void noAccessWarningWithoutKerberosOrForADestinationThatIsOff() {
        List<String> plain = new java.util.ArrayList<>();
        apply(clusterAuditingToSolrAndHdfs(), null, plain);
        assertTrue(plain.isEmpty(), plain.toString());

        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());
        List<String> off = RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(),
                wizard("solrMode", "off", "hdfsMode", "off"), null,
                "trino-analytics@EXAMPLE.COM", "/etc/security/keytabs/service.keytab", true);
        assertTrue(off.isEmpty(), off.toString());
    }

    @Test
    void noSolrLoginIsWrittenWithoutKerberosOrWithSolrOff() {
        Map<String, String> plain = apply(clusterAuditingToSolrAndHdfs(), null, new java.util.ArrayList<>());
        assertTrue(plain.keySet().stream().noneMatch(k -> k.startsWith("xasecure.audit.jaas.")), plain.toString());

        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());
        RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(), wizard("solrMode", "off"), null,
                "trino-analytics@EXAMPLE.COM", "/etc/security/keytabs/service.keytab", true);
        assertTrue(audit.keySet().stream().noneMatch(k -> k.startsWith("xasecure.audit.jaas.")), audit.toString());
    }

    @Test
    void hdfsFollowingTheContextStaysOffWithoutHadoopConfigurationAndIsReported() {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());

        List<String> warnings = RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(), null, null,
                null, null, false);

        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("core-site and hdfs-site"), warnings.get(0));
    }

    @Test
    void hdfsTurnedOnInTheWizardStaysOnWithoutHadoopConfigurationButIsReported() {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());

        List<String> warnings = RangerAuditDestinations.apply(audit, clusterAuditingToSolrAndHdfs(),
                wizard("hdfsMode", "on"), null, null, null, false);

        assertEquals("true", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aDestinationAskedForWithoutAnywhereToWriteStaysOffAndIsReported() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, String> audit = apply(Map.of("ranger.auditSolrEnabled", "true", "ranger.auditHdfsEnabled", "true"),
                wizard("hdfsMode", "on"), warnings);

        assertEquals("false", audit.get("xasecure.audit.destination.solr"));
        assertEquals("false", audit.get("xasecure.audit.destination.hdfs"));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Solr"), warnings.get(0));
        assertTrue(warnings.get(1).contains("HDFS"), warnings.get(1));
    }

    @Test
    void theWizardMayTurnThePodLogOff() {
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(), wizard("log4j", false),
                new java.util.ArrayList<>());

        assertEquals("false", audit.get("xasecure.audit.destination.log4j"));
        assertEquals("false", audit.get("xasecure.audit.log4j.is.enabled"));
        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
    }

    @Test
    void anUnknownModeFollowsTheContext() {
        Map<String, String> audit = apply(clusterAuditingToSolrAndHdfs(), wizard("solrMode", "sometimes"),
                new java.util.ArrayList<>());

        assertEquals("true", audit.get("xasecure.audit.destination.solr"));
    }

    @Test
    void settingsTheClusterCouldNotBeAskedForAreReported() {
        Map<String, String> audit = new LinkedHashMap<>(RangerTrinoConfigDefaults.audit());

        List<String> warnings = RangerAuditDestinations.apply(audit, Map.of(), null, List.of("ranger.auditSolrEnabled"),
                null, null, true);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("false", audit.get("xasecure.audit.destination.solr"));

        // With both destinations decided in the wizard nothing was needed from the cluster.
        assertTrue(RangerAuditDestinations.apply(audit, Map.of(),
                wizard("solrMode", "off", "hdfsMode", "off"), List.of("ranger.auditSolrEnabled"), null, null, true).isEmpty());
    }
}
