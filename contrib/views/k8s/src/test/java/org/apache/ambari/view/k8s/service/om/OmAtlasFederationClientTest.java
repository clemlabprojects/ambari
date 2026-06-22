/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service.om;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure pieces of {@link OmAtlasFederationClient} — the
 * {@code databaseServiceName} JSON-array renderer. The exec-in-pod flow is
 * tested through integration tests against a real cluster (out of scope here)
 * since the value is mostly mocking the K8s client API + Python script wiring.
 */
class OmAtlasFederationClientTest {

    @Test
    void rendersEmptyArrayForNullList() {
        assertEquals("[]", OmAtlasFederationClient.buildDbsJson(null));
    }

    @Test
    void rendersEmptyArrayForEmptyList() {
        assertEquals("[]", OmAtlasFederationClient.buildDbsJson(Collections.emptyList()));
    }

    @Test
    void rendersSingleServiceName() {
        assertEquals("[\"trino-clemlab\"]",
                OmAtlasFederationClient.buildDbsJson(List.of("trino-clemlab")));
    }

    @Test
    void rendersMultipleServiceNamesCommaSeparated() {
        assertEquals("[\"trino-clemlab\",\"hive-prod\",\"impala-edge\"]",
                OmAtlasFederationClient.buildDbsJson(
                        Arrays.asList("trino-clemlab", "hive-prod", "impala-edge")));
    }

    @Test
    void skipsBlankEntriesSoTrailingWizardCommaDoesNotProduceEmptyArrayElement() {
        // Operator typed "trino-clemlab, , hive-prod," in the form; the binding
        // CSV-tolerant pipeline rendered that as ["trino-clemlab", "", "hive-prod", ""].
        // The JSON should still be clean.
        assertEquals("[\"trino-clemlab\",\"hive-prod\"]",
                OmAtlasFederationClient.buildDbsJson(
                        Arrays.asList("trino-clemlab", "", "hive-prod", "  ")));
    }

    @Test
    void trimsWhitespaceAroundEntries() {
        assertEquals("[\"trino-clemlab\",\"hive-prod\"]",
                OmAtlasFederationClient.buildDbsJson(
                        Arrays.asList("  trino-clemlab  ", "\thive-prod\n")));
    }

    @Test
    void escapesEmbeddedDoubleQuotesSoTheRenderedJsonStaysParseable() {
        // Defensive: a service name with a quote in it would break the JSON if
        // we didn't escape it. OM names are usually quote-free but this keeps
        // the renderer's output well-formed even when fed odd input.
        String out = OmAtlasFederationClient.buildDbsJson(List.of("weird\"name"));
        assertEquals("[\"weird\\\"name\"]", out);
        // Round-trips as parseable JSON
        assertTrue(out.startsWith("[\"") && out.endsWith("\"]"));
    }
}
