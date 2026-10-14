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

import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The Ranger branch of {@link ManagedContextResolver} that reads the audit destinations of the cluster: the Solr and
 * HDFS switches from ranger-env and the Solr endpoints from ranger-admin-site, which a Ranger plugin outside the
 * cluster follows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagedContextResolverRangerAuditTest {

    private static final String CLUSTER = "c1";

    @Mock AmbariActionClient ambari;

    private void rangerInstalled() throws Exception {
        lenient().when(ambari.getComponentHosts(CLUSTER, "RANGER", "RANGER_ADMIN")).thenReturn(List.of("ranger01"));
    }

    private void config(String type, String key, String value) throws Exception {
        lenient().when(ambari.getDesiredConfigProperty(CLUSTER, type, key)).thenReturn(value);
    }

    private ManagedContextResolver resolver() {
        return new ManagedContextResolver(ambari, CLUSTER);
    }

    @Test
    void theClustersAuditDestinationsAreReadFromRangerEnvAndRangerAdminSite() throws Exception {
        rangerInstalled();
        config("ranger-env", "xasecure.audit.destination.solr", "true");
        config("ranger-env", "xasecure.audit.destination.hdfs", "TRUE ");
        config("ranger-env", "xasecure.audit.destination.hdfs.dir", "hdfs://nn01:8020/ranger/audit");
        config("ranger-env", "ranger_solr_collection_name", "ranger_audits");
        config("ranger-admin-site", "ranger.audit.solr.urls", "http://solr01:8886/solr/ranger_audits");
        config("ranger-admin-site", "ranger.audit.solr.zookeepers", "zk01:2181/infra-solr");

        ManagedContextResolver r = resolver();
        assertEquals("true", r.resolve("ranger.auditSolrEnabled"));
        assertEquals("true", r.resolve("ranger.auditHdfsEnabled"));
        assertEquals("hdfs://nn01:8020/ranger/audit", r.resolve("ranger.auditHdfsDir"));
        assertEquals("ranger_audits", r.resolve("ranger.auditSolrCollection"));
        assertEquals("http://solr01:8886/solr/ranger_audits", r.resolve("ranger.auditSolrUrls"));
        assertEquals("zk01:2181/infra-solr", r.resolve("ranger.auditSolrZookeepers"));
    }

    @Test
    void aSwitchTurnedOffResolvesToFalse() throws Exception {
        rangerInstalled();
        config("ranger-env", "xasecure.audit.destination.solr", "false");
        assertEquals("false", resolver().resolve("ranger.auditSolrEnabled"));
    }

    @Test
    void anUnsetSwitchResolvesToNothingRatherThanOff() throws Exception {
        rangerInstalled();
        assertNull(resolver().resolve("ranger.auditSolrEnabled"));
        assertNull(resolver().resolve("ranger.auditHdfsEnabled"));
    }

    @Test
    void noZookeeperIsNotAnEnsembleCalledNone() throws Exception {
        // Ambari writes NONE when Solr does not run as a cloud.
        rangerInstalled();
        config("ranger-admin-site", "ranger.audit.solr.zookeepers", "NONE");
        assertNull(resolver().resolve("ranger.auditSolrZookeepers"));
    }

    @Test
    void aFileSystemWithoutDirectoryGetsTheRangerAuditDirectory() throws Exception {
        rangerInstalled();
        config("ranger-env", "xasecure.audit.destination.hdfs.dir", "hdfs://nn01:8020");
        assertEquals("hdfs://nn01:8020/ranger/audit", resolver().resolve("ranger.auditHdfsDir"));
        config("ranger-env", "xasecure.audit.destination.hdfs.dir", "hdfs://nameservice1/");
        assertEquals("hdfs://nameservice1/ranger/audit", resolver().resolve("ranger.auditHdfsDir"));
        config("ranger-env", "xasecure.audit.destination.hdfs.dir", "hdfs://nameservice1/audits/ranger");
        assertEquals("hdfs://nameservice1/audits/ranger", resolver().resolve("ranger.auditHdfsDir"));
    }

    @Test
    void withoutRangerNothingIsResolved() throws Exception {
        lenient().when(ambari.getComponentHosts(CLUSTER, "RANGER", "RANGER_ADMIN")).thenReturn(List.of());
        config("ranger-env", "xasecure.audit.destination.solr", "true");
        ManagedContextResolver r = resolver();
        for (String key : List.of("ranger.auditSolrEnabled", "ranger.auditSolrUrls", "ranger.auditSolrZookeepers",
                "ranger.auditSolrCollection", "ranger.auditHdfsEnabled", "ranger.auditHdfsDir")) {
            assertNull(r.resolve(key), key);
        }
    }

    @Test
    void aClusterThatCannotBeAskedIsReportedAsUnreadNotAsOff() throws Exception {
        lenient().when(ambari.getComponentHosts(CLUSTER, "RANGER", "RANGER_ADMIN"))
                .thenThrow(new RuntimeException("Ambari unreachable"));
        ManagedContextResolver r = resolver();
        assertNull(r.resolve("ranger.auditSolrEnabled"));
        assertTrue(r.unread("ranger.auditSolrEnabled"));
    }

    @Test
    void everyAuditFieldOfTheContextSchemaIsResolvedByTheManagedResolver() throws Exception {
        // The deploy reads these keys from the context; a field the schema names but the resolver does not know
        // would stay blank on a managed cluster, and the release would audit to the pod log only.
        rangerInstalled();
        for (String key : List.of("xasecure.audit.destination.solr", "xasecure.audit.destination.hdfs")) {
            config("ranger-env", key, "true");
        }
        config("ranger-env", "xasecure.audit.destination.hdfs.dir", "hdfs://nn01:8020/ranger/audit");
        config("ranger-env", "ranger_solr_collection_name", "ranger_audits");
        config("ranger-admin-site", "ranger.audit.solr.urls", "http://solr01:8886/solr/ranger_audits");
        config("ranger-admin-site", "ranger.audit.solr.zookeepers", "zk01:2181");
        ManagedContextResolver r = resolver();
        int checked = 0;
        for (org.apache.ambari.view.k8s.model.ContextCapabilitySchema cap : new ContextSchemaService().loadSchema()) {
            if (!"ranger".equals(cap.capability)) {
                continue;
            }
            for (org.apache.ambari.view.k8s.model.ContextCapabilitySchema.ContextFieldDef f : cap.fields) {
                if (f.name.startsWith("audit")) {
                    assertEquals("ranger." + f.name, f.managedResolver, f.name);
                    assertTrue(r.resolve(f.managedResolver) != null, f.name);
                    checked++;
                }
            }
        }
        assertEquals(6, checked);
    }
}
