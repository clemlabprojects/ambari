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
package org.apache.ambari.view.k8s.service.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.marcnuri.helm.ReleaseHistory;

import org.junit.jupiter.api.Test;

/**
 * The history of a release, read in the server's own Helm library, keeps the field names of
 * {@code helm history -o json}.
 */
class HelmClientDefaultHistoryTest {

    @Test
    void theHistoryKeepsTheFieldNamesOfTheHelmCommandLine() throws Exception {
        Constructor<ReleaseHistory> c = ReleaseHistory.class.getDeclaredConstructor(int.class, ZonedDateTime.class,
                String.class, String.class, String.class, String.class);
        c.setAccessible(true);
        ZonedDateTime when = ZonedDateTime.parse("2026-11-26T09:40:00+01:00");
        List<Map<String, Object>> entries = HelmClientDefault.historyEntries(List.of(
                c.newInstance(2, when, "deployed", "redis-23.0.4", "8.2.1", "Upgrade complete"),
                c.newInstance(1, when, "superseded", "redis-23.0.4", "8.2.1", "Install complete")));

        assertEquals(List.of(1, 2), List.of(entries.get(0).get("revision"), entries.get(1).get("revision")));
        assertEquals(Map.of("revision", 2, "updated", "2026-11-26T09:40+01:00", "status", "deployed",
                "chart", "redis-23.0.4", "app_version", "8.2.1", "description", "Upgrade complete"), entries.get(1));
        HelmReleaseHistory history = HelmReleaseHistory.of(entries);
        assertEquals(2, HelmReleaseHistory.of(entries).revisions().size());
        assertTrue(HelmClientDefault.historyEntries(null).isEmpty());
        assertTrue(history.revisions().get(1).status().equals("deployed"));
    }
}
