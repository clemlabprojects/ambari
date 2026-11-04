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

package org.apache.ambari.view.k8s.service.gitops;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A chart's values.yaml with a deploy's values merged in as Helm merges them. */
class ChartValuesTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    @Test
    void mapsAreMergedKeyByKeyAndAnyOtherValueGivenReplacesTheDefault() {
        Map<String, Object> defaults = map(
                "image", map("registry", "", "repository", "trino", "tag", "476"),
                "server", map("workers", 1, "config", map("https", map("enabled", false, "port", 8443))),
                "env", List.of("A=1", "B=2"),
                "ingress", map("enabled", false));
        List<Object> env = new ArrayList<>(List.of("C=3"));
        Map<String, Object> given = map(
                "image", map("registry", "registry.example.org"),
                "server", map("workers", 3, "config", map("https", map("enabled", true))),
                "env", env,
                "global", map("imagePullSecrets", List.of("regcred")));

        Map<String, Object> merged = ChartValues.coalesce(defaults, given);

        assertEquals(map("registry", "registry.example.org", "repository", "trino", "tag", "476"), merged.get("image"));
        assertEquals(map("workers", 3, "config", map("https", map("enabled", true, "port", 8443))), merged.get("server"));
        assertEquals(List.of("C=3"), merged.get("env"), "a list given replaces the default list as a whole");
        assertEquals(map("enabled", false), merged.get("ingress"), "a default not given stays");
        assertEquals(map("imagePullSecrets", List.of("regcred")), merged.get("global"), "a key the chart lacks is added");
        assertEquals(List.of("image", "server", "env", "ingress", "global"), new ArrayList<>(merged.keySet()),
                "the chart's order, then the keys it lacks");
        assertEquals(map("registry", "", "repository", "trino", "tag", "476"), defaults.get("image"), "defaults unchanged");
    }

    @Test
    void aKeyGivenAsNullRemovesTheDefaultAsHelmDoes() {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("resources", null);

        Map<String, Object> merged = ChartValues.coalesce(map("resources", map("limits", map("cpu", "2")), "a", 1), given);

        assertFalse(merged.containsKey("resources"));
        assertEquals(1, merged.get("a"));
    }

    @Test
    void thePublishedFileIsWrittenWithTheValuesMergedInAndSaysSo() {
        byte[] published = ("# The chart's own comment\nimage:\n  registry: \"\"\n  tag: \"476\"\nserver:\n  workers: 1\n")
                .getBytes(StandardCharsets.UTF_8);

        String merged = new String(ChartValues.merged(published,
                map("image", map("registry", "registry.example.org"), "server", map("workers", 2)),
                "Written by KDPS for analytics/trino."), StandardCharsets.UTF_8);

        assertTrue(merged.startsWith("# Written by KDPS for analytics/trino.\n"), merged);
        assertTrue(merged.contains("registry: \"registry.example.org\""), merged);
        assertTrue(merged.contains("tag: \"476\""), merged);
        assertTrue(merged.contains("workers: 2"), merged);
    }

    @Test
    void aChartWithoutValuesGetsTheDeploysValues() {
        String merged = new String(ChartValues.merged(null, map("server", map("workers", 2))), StandardCharsets.UTF_8);

        assertEquals("server:\n  workers: 2\n", merged);
    }

    @Test
    void aValuesFileThatIsNotAMapIsRefused() {
        byte[] list = "- a\n- b\n".getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ChartValues.merged(list, map("a", 1)));

        assertTrue(refused.getMessage().contains("not a YAML map"), refused.getMessage());
    }
}
