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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The fields holding a secret come from the service definitions and the security profile definition. */
class SensitiveFieldsTest {

    @Test
    void passwordsPastedFilesAndFlaggedTextFieldsOfAServiceAreSensitive() {
        Set<String> trino = SensitiveFields.ofService("TRINO");
        assertTrue(trino.containsAll(List.of("icebergCatalog.clientSecret", "icebergCatalog.s3AccessKey",
                "icebergCatalog.s3SecretKey", "customCatalogs.text", "ingress.tlsUpload.key", "tls.https.password")), trino.toString());
        assertFalse(trino.contains("icebergCatalog.warehouse") || trino.contains("ingress.host"), trino.toString());

        Set<String> superset = SensitiveFields.ofService("superset");
        assertTrue(superset.containsAll(List.of("superset.secretKey", "ingress.tlsUpload.key", "ingress.tlsUpload.cert",
                "hiveDb.ldapPassword", "ui_trino_svc_password")), superset.toString());

        Set<String> openMetadata = SensitiveFields.ofService("OPENMETADATA");
        assertTrue(openMetadata.containsAll(List.of("global.security.auth.ldap.bindPassword", "baseIngestion.hivePassword",
                "rangerTagSyncToken")), openMetadata.toString());
    }

    @Test
    void onlyPasswordFieldsArePasswords() {
        Set<String> passwords = SensitiveFields.passwordsOfService("TRINO");
        assertTrue(passwords.containsAll(List.of("icebergCatalog.s3SecretKey", "tls.https.password")), passwords.toString());
        assertFalse(passwords.contains("ingress.tlsUpload.key") || passwords.contains("icebergCatalog.s3AccessKey")
                || passwords.contains("customCatalogs.text"), passwords.toString());
    }

    @Test
    void anUnknownServiceHasNoKnownSensitiveField() {
        assertEquals(Set.of(), SensitiveFields.ofService("NO-SUCH-SERVICE"));
        assertEquals(Set.of(), SensitiveFields.ofService(null));
    }

    @Test
    void thePasswordFieldsOfTheSecurityProfileAreSensitive() {
        assertTrue(SensitiveFields.ofSecurityProfile().containsAll(Set.of("ldap.bindPassword", "ldap.adBindPassword",
                "oidc.clientSecret")));
        assertFalse(SensitiveFields.ofSecurityProfile().contains("ldap.url"));
    }

    @Test
    void fieldsAreRemovedFromNestedOrDottedFormValuesWithoutChangingThem() {
        Map<String, Object> tlsUpload = new LinkedHashMap<>(Map.of("key", "PEM", "secretName", "tls"));
        Map<String, Object> ingress = new LinkedHashMap<>(Map.of("host", "x.example.org", "tlsUpload", tlsUpload));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("ingress", ingress);
        form.put("icebergCatalog.s3SecretKey", "s3");
        form.put("replicaCount", 2);

        Map<String, Object> kept = SensitiveFields.without(form,
                List.of("ingress.tlsUpload.key", "icebergCatalog.s3SecretKey", "not.there"));

        assertEquals(Map.of("ingress", Map.of("host", "x.example.org", "tlsUpload", Map.of("secretName", "tls")),
                "replicaCount", 2), kept);
        assertEquals("PEM", tlsUpload.get("key"), "the form passed in is left as it is");
        assertEquals("s3", form.get("icebergCatalog.s3SecretKey"));
        assertNull(SensitiveFields.without(null, List.of("a")));
    }
}
