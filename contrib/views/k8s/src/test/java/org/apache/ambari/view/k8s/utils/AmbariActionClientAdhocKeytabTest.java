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

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Ambari only issues an ad-hoc keytab for a principal named after the Kubernetes namespace that
 * receives it, so every keytab request names that namespace.
 */
class AmbariActionClientAdhocKeytabTest {

    @Test
    void keytabRequestNamesTheNamespaceReceivingTheKeytab() {
        JsonObject request = AmbariActionClient.adhocKeytabRequestBody(
                "c1", "trino-analytics@EXAMPLE.COM", "analytics", "IPA", "EXAMPLE.COM", 600, null);

        JsonObject body = request.getAsJsonObject("Body");
        assertEquals("c1", body.get("Clusters/cluster_name").getAsString());
        assertEquals("trino-analytics@EXAMPLE.COM", body.get("AdhocKeytab/principal").getAsString());
        assertEquals("analytics", body.get("AdhocKeytab/namespace").getAsString());
        assertEquals("IPA", body.get("AdhocKeytab/kdc_type").getAsString());
        assertEquals("EXAMPLE.COM", body.get("AdhocKeytab/default_realm").getAsString());
        assertEquals(600, body.get("AdhocKeytab/timeout").getAsInt());

        JsonObject requestInfo = request.getAsJsonObject("RequestInfo");
        assertEquals("Generate ad-hoc keytab for trino-analytics@EXAMPLE.COM", requestInfo.get("context").getAsString());
        assertEquals("c1", requestInfo.getAsJsonObject("operation_level").get("cluster_name").getAsString());
    }

    @Test
    void optionalFieldsAreLeftOut() {
        JsonObject body = AmbariActionClient.adhocKeytabRequestBody(
                "c1", "trino-analytics@EXAMPLE.COM", "analytics", null, null, null, "Deploy trino").getAsJsonObject("Body");

        assertFalse(body.has("AdhocKeytab/kdc_type"));
        assertFalse(body.has("AdhocKeytab/default_realm"));
        assertFalse(body.has("AdhocKeytab/timeout"));
        assertEquals("analytics", body.get("AdhocKeytab/namespace").getAsString());
    }
}
