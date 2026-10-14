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

package org.apache.ambari.view.k8s.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The truststores selected for a release are kept with it, next to its git metadata. */
class K8sReleaseEntityTruststoreRefsTest {

    @Test
    void theSelectionRoundTripsAndLeavesGitMetadataAlone() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setGitCommitSha("abc123");
        assertNull(e.getTruststoreRefs(), "never recorded");

        e.setTruststoreRefs(List.of("company-ca", "partner, with comma"));
        assertEquals(List.of("company-ca", "partner, with comma"), e.getTruststoreRefs());
        assertEquals("abc123", e.getGitCommitSha());

        e.setGitCommitSha(null);
        assertEquals(List.of("company-ca", "partner, with comma"), e.getTruststoreRefs(), "clearing git data keeps the selection");
    }

    @Test
    void anEmptySelectionIsAChoiceAndNullForgetsIt() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setTruststoreRefs(List.of());
        assertEquals(List.of(), e.getTruststoreRefs());
        e.setTruststoreRefs(null);
        assertNull(e.getTruststoreRefs());
    }

    @Test
    void theSelectionSurvivesReloadingTheStoredColumn() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setTruststoreRefs(List.of("company-ca"));
        K8sReleaseEntity reloaded = new K8sReleaseEntity();
        reloaded.setGitMetaJson(e.getGitMetaJson());
        assertEquals(List.of("company-ca"), reloaded.getTruststoreRefs());
    }
}
