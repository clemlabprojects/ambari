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
package org.apache.ambari.view.k8s.migration;

import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copy of a 1.0.0.7-shaped release row into the current entity (AMBARI ticket: release Git metadata lost). */
public class AccessorCopyConverterTest {

    /** Same accessors as the 1.0.0.7 K8sReleaseEntity: nine flat Git properties, no gitMetaJson. */
    public static class LegacyRelease {
        public String getId() { return "trino-prod/trino"; }
        public String getNamespace() { return "trino-prod"; }
        public String getReleaseName() { return "trino"; }
        public String getServiceKey() { return "TRINO"; }
        public String getVersion() { return "1.43.6"; }
        public String getDeploymentMode() { return "FLUX_GITOPS"; }
        public boolean isManagedByUi() { return true; }
        public String getGitBranch() { return "main"; }
        public String getGitCommitSha() { return "abc1234"; }
        public String getGitRepoUrl() { return "https://git.client.local/kdps.git"; }
        public String getGitPath() { return "clusters/default/trino-prod/trino"; }
        public String getGitPrNumber() { return null; }
        public Integer getVersionAsNumber() { return 7; }
    }

    @Test
    void foldsLegacyGitColumnsIntoTheNewJsonColumn() {
        K8sReleaseEntity target = new K8sReleaseEntity();
        new AccessorCopyConverter().convert(new LegacyRelease(), target);

        assertEquals("trino-prod/trino", target.getId());
        assertEquals("TRINO", target.getServiceKey());
        assertEquals("1.43.6", target.getVersion());
        assertTrue(target.isManagedByUi());
        assertEquals("main", target.getGitBranch());
        assertEquals("abc1234", target.getGitCommitSha());
        assertEquals("https://git.client.local/kdps.git", target.getGitRepoUrl());
        assertEquals("clusters/default/trino-prod/trino", target.getGitPath());
        assertNull(target.getGitPrNumber());
        assertNotNull(target.getGitMetaJson());
        assertTrue(target.getGitMetaJson().contains("\"branch\":\"main\""), target.getGitMetaJson());
        assertNull(target.getPlatformContextId());
    }

    @Test
    void sameVersionCopyKeepsTheJsonColumnIntact() {
        K8sReleaseEntity source = new K8sReleaseEntity();
        source.setId("ns/rel");
        source.setGitBranch("release-1");
        source.setGitCommitSha("ffff");
        source.setPlatformContextId("client-cdp");

        K8sReleaseEntity target = new K8sReleaseEntity();
        new AccessorCopyConverter().convert(source, target);

        assertEquals("ns/rel", target.getId());
        assertEquals("release-1", target.getGitBranch());
        assertEquals("ffff", target.getGitCommitSha());
        assertEquals("client-cdp", target.getPlatformContextId());
        assertEquals(source.getGitMetaJson(), target.getGitMetaJson());
    }

    @Test
    void getterRulesAndTypeMatching() {
        assertEquals("gitBranch", AccessorCopyConverter.propertyOf("getGitBranch", String.class));
        assertEquals("managedByUi", AccessorCopyConverter.propertyOf("isManagedByUi", boolean.class));
        assertNull(AccessorCopyConverter.propertyOf("isSomething", String.class));
        assertNull(AccessorCopyConverter.propertyOf("toString", String.class));
        assertTrue(AccessorCopyConverter.fits(boolean.class, Boolean.class));
        assertTrue(AccessorCopyConverter.fits(Boolean.class, boolean.class));
        assertTrue(!AccessorCopyConverter.fits(Integer.class, String.class));
    }
}
