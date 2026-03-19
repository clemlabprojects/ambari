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

import javax.ws.rs.core.MultivaluedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbariActionClientHeadersTest {

    @Test
    void encryptsPersistedHeadersAndRestoresAuthValues() {
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Cookie", "AmbariSession=abc123");
        headers.add("Authorization", "Bearer token-123");
        headers.add("X-Requested-By", "ambari");
        headers.add("Ignored", "drop-me");

        Map<String, Object> persisted = AmbariActionClient.headersToPersistableMap(headers);

        assertEquals("encrypted-v1", persisted.get("_format"));
        assertTrue(persisted.containsKey("payload"));
        assertFalse(String.valueOf(persisted.get("payload")).contains("abc123"));
        assertFalse(String.valueOf(persisted.get("payload")).contains("token-123"));

        Map<String, String> restored = AmbariActionClient.toAuthHeaders(persisted);
        assertEquals("AmbariSession=abc123", restored.get("Cookie"));
        assertEquals("Bearer token-123", restored.get("Authorization"));
    }

    @Test
    void supportsLegacyPlainHeaderMaps() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("cookie", List.of("AmbariSession=legacy"));
        legacy.put("authorization", List.of("Basic abc"));

        Map<String, String> restored = AmbariActionClient.toAuthHeaders(legacy);

        assertEquals("AmbariSession=legacy", restored.get("Cookie"));
        assertEquals("Basic abc", restored.get("Authorization"));
    }
}
