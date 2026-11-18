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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Helm installs or upgrades to the newest version of a chart unless it is given {@code --version}: a release must run
 * the chart version KDPS planned and records for it, not whatever the repository holds last.
 */
class HelmClientDefaultChartVersionTest {

    private static final String CHART = "oci://registry.example.com/charts/trino";
    private static final Path VALUES = Path.of("values.yaml");

    /** The version the helm-java command will pass to Helm (it keeps it in a private field). */
    private static String versionOf(Object command) throws Exception {
        Field version = command.getClass().getDeclaredField("version");
        version.setAccessible(true);
        return (String) version.get(command);
    }

    @Test
    void anInstallRunsTheChartVersionItWasGiven() throws Exception {
        assertEquals("1.44.1", versionOf(HelmClientDefault.installCommand(CHART, " 1.44.1 ", "t", "ns", null, null,
                VALUES, 60, false, false, false, false)));
    }

    @Test
    void anUpgradeRunsTheChartVersionItWasGiven() throws Exception {
        assertEquals("1.44.1", versionOf(HelmClientDefault.upgradeCommand(CHART, "1.44.1", "t", "ns", null, null,
                VALUES, 60, false, false, false)));
    }

    @Test
    void withoutAVersionHelmPicksTheNewest() throws Exception {
        assertNull(versionOf(HelmClientDefault.installCommand(CHART, null, "t", "ns", null, null,
                VALUES, 60, false, false, false, false)));
        assertNull(versionOf(HelmClientDefault.upgradeCommand(CHART, " ", "t", "ns", null, null,
                VALUES, 60, false, false, false)));
    }
}
