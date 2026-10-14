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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AmbariActionClient#updateClusterConfig} against Ambari's password handling: the attributes
 * a config already has must survive a write (or a custom password property ends up stored as its own
 * masked {@code SECRET:...} reference), and a key declared a password is stored masked.
 */
class AmbariActionClientConfigUpdateTest {

    private static final String TYPE = "ranger-tagsync-site";

    @Test
    void keepsTheAttributesTheConfigAlreadyHas() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        FakeAmbariConfigs.Stored seeded = ambari.seed(TYPE, Map.of("a", "1", "custom.password", "s3cret"));
        seeded.attributes.put("final", new LinkedHashMap<>(Map.of("a", "true")));
        seeded.attributes.put("password", new LinkedHashMap<>(Map.of("custom.password", "true")));

        assertTrue(ambari.client().updateClusterConfig("c1", TYPE, Map.of("b", "2"), "test"));

        FakeAmbariConfigs.Stored stored = ambari.stored(TYPE);
        assertEquals("s3cret", stored.properties.get("custom.password"),
                "the masked reference must resolve back to the secret, not be stored as its value");
        assertEquals("true", stored.attributes.get("final").get("a"));
        assertEquals("true", stored.attributes.get("password").get("custom.password"));
        assertEquals("2", stored.properties.get("b"));
    }

    @Test
    void storesPasswordKeysMaskedAndReadsThemBackMasked() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(TYPE, Map.of("a", "1"));
        AmbariActionClient client = ambari.client();

        assertTrue(client.updateClusterConfig("c1", TYPE, Map.of("tok", "eyJ.secret"), Set.of("tok"), "test"));

        assertEquals("true", ambari.stored(TYPE).attributes.get("password").get("tok"));
        assertEquals("eyJ.secret", ambari.stored(TYPE).properties.get("tok"));
        assertTrue(client.getDesiredConfigProperties("c1", TYPE).get("tok").startsWith("SECRET:"),
                "Ambari's API must not show the token");
        assertFalse(ambari.puts.get(0).isEmpty());
    }

    @Test
    void writesNothingWhenNothingChanged() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(TYPE, Map.of("a", "1"));
        AmbariActionClient client = ambari.client();

        assertTrue(client.updateClusterConfig("c1", TYPE, Map.of("tok", "eyJ.secret"), Set.of("tok"), "test"));
        // The secret left out (unchanged), the rest identical: no new version.
        assertFalse(client.updateClusterConfig("c1", TYPE, Map.of("a", "1"), Set.of("tok"), "test"));
        assertEquals(1, ambari.puts.size());
        assertEquals("eyJ.secret", ambari.stored(TYPE).properties.get("tok"));
    }

    @Test
    void marksASecretStoredInClearEvenWhenItsValueIsUnchanged() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        ambari.seed(TYPE, Map.of("tok", "eyJ.secret"));

        assertTrue(ambari.client().updateClusterConfig("c1", TYPE, Map.of("tok", "eyJ.secret"), Set.of("tok"), "test"));
        assertEquals("true", ambari.stored(TYPE).attributes.get("password").get("tok"));
        assertEquals("eyJ.secret", ambari.stored(TYPE).properties.get("tok"));
    }

    @Test
    void deletingAKeyDropsItsAttributes() throws Exception {
        FakeAmbariConfigs ambari = new FakeAmbariConfigs();
        FakeAmbariConfigs.Stored seeded = ambari.seed(TYPE, Map.of("a", "1", "gone", "x"));
        seeded.attributes.put("final", new LinkedHashMap<>(Map.of("gone", "true", "a", "true")));
        Map<String, String> delete = new LinkedHashMap<>();
        delete.put("gone", null);

        assertTrue(ambari.client().updateClusterConfig("c1", TYPE, delete, "test"));
        assertFalse(ambari.stored(TYPE).properties.containsKey("gone"));
        assertFalse(ambari.stored(TYPE).attributes.get("final").containsKey("gone"));
        assertEquals("true", ambari.stored(TYPE).attributes.get("final").get("a"));
    }
}
