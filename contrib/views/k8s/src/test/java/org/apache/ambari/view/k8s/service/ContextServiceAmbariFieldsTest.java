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
package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a context tells a service definition about its Ambari server: ambari.clusterName and ambari.apiUrl. */
class ContextServiceAmbariFieldsTest {

    @Test
    void aManagedContextIsTheLocalAmbari() {
        assertEquals(Map.of("ambari.clusterName", "prod", "ambari.apiUrl", "https://master01.example.com:8442/api/v1"),
                ContextService.ambariFields("MANAGED", "prod", "https://master01.example.com:8442/api/v1", null));
    }

    @Test
    void aRemoteContextIsTheAmbariItWasGiven() {
        Map<String, String> expected = Map.of("ambari.clusterName", "lake", "ambari.apiUrl", "https://ambari.remote.example.com:8443/api/v1");
        // As typed by the operator: with or without a trailing slash, with or without the API path.
        for (String typed : new String[] {"https://ambari.remote.example.com:8443", "https://ambari.remote.example.com:8443/",
                "https://ambari.remote.example.com:8443/api/v1", " https://ambari.remote.example.com:8443/api/v1/ "}) {
            assertEquals(expected, ContextService.ambariFields("REMOTE", "lake", "https://local.example.com:8442/api/v1", typed), typed);
        }
    }

    @Test
    void whatIsNotKnownIsLeftOut() {
        assertEquals(Map.of("ambari.apiUrl", "http://m1:8080/api/v1"), ContextService.ambariFields("MANAGED", " ", "http://m1:8080/api/v1", null));
        assertEquals(Map.of("ambari.clusterName", "prod"), ContextService.ambariFields("MANAGED", "prod", null, null));
        assertEquals(Map.of("ambari.clusterName", "lake"), ContextService.ambariFields("REMOTE", "lake", null, " "));
    }

    @Test
    void aContextWithoutAmbariGivesNeither() {
        for (String kind : new String[] {"EXTERNAL", "CDP", "NONE", null}) {
            assertTrue(ContextService.ambariFields(kind, "cluster", "https://local.example.com:8442/api/v1",
                    "https://ambari.remote.example.com:8443").isEmpty(), String.valueOf(kind));
        }
    }
}
