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

package org.apache.ambari.view.k8s.service.helm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading {@code helm history -o json}: the last revision, whether the release ever served, and which revision an
 * interrupted upgrade returns to.
 */
class HelmReleaseHistoryTest {

    private static Map<String, Object> rev(Object revision, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("revision", revision);
        m.put("status", status);
        m.put("chart", "superset-0.12.42");
        return m;
    }

    @Test
    void anInterruptedUpgradeReturnsToTheRevisionStillDeployed() {
        HelmReleaseHistory h = HelmReleaseHistory.of(List.of(
                rev(1, "superseded"), rev(2, "deployed"), rev(3, "pending-upgrade")));
        assertEquals(3, h.last().number());
        assertTrue(h.last().is(HelmReleaseHistory.PENDING_UPGRADE));
        assertEquals(2, h.lastGood().number());
        assertTrue(h.everDeployed());
    }

    @Test
    void withNoDeployedRevisionTheNewestSupersededOneIsTheOneToReturnTo() {
        HelmReleaseHistory h = HelmReleaseHistory.of(List.of(
                rev(1, "superseded"), rev(2, "superseded"), rev(3, "failed"), rev(4, "pending-rollback")));
        assertEquals(2, h.lastGood().number());
    }

    @Test
    void aFirstInstallThatNeverCompletedHasNeverServed() {
        HelmReleaseHistory h = HelmReleaseHistory.of(List.of(rev(1, "pending-install")));
        assertFalse(h.everDeployed());
        assertNull(h.lastGood());
        assertTrue(h.last().is(HelmReleaseHistory.PENDING_INSTALL));
    }

    @Test
    void readsHelmsJsonWhateverTheOrderAndNumberType() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(rev("3", "PENDING-UPGRADE"));
        entries.add(rev(1L, "superseded"));
        entries.add(rev(2.0, " Deployed "));
        entries.add(rev("not-a-number", "deployed"));
        entries.add(null);
        HelmReleaseHistory h = HelmReleaseHistory.of(entries);
        assertEquals(3, h.revisions().size(), "entries without a revision number are left out");
        assertEquals(3, h.last().number());
        assertEquals("pending-upgrade", h.last().status());
        assertEquals(2, h.lastGood().number());
    }

    @Test
    void anEmptyHistoryHasNothingToReturnTo() {
        HelmReleaseHistory h = HelmReleaseHistory.of(null);
        assertNull(h.last());
        assertNull(h.lastGood());
        assertFalse(h.everDeployed());
    }
}
