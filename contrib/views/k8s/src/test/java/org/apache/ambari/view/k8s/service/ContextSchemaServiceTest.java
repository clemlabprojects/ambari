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
package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.k8s.model.ContextCapabilitySchema;
import org.apache.ambari.view.k8s.model.ContextCapabilitySchema.ContextFieldDef;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A platform context keeps every value of every capability in one flat config map, so two fields stored under the
 * same key overwrite each other on save. That is how the Kerberos, single sign-on and Polaris realms, all three named
 * {@code realm}, ended up as one value. Each field therefore has its own storage key, and a fragment that reuses one
 * loses the field rather than corrupting another capability's value.
 */
class ContextSchemaServiceTest {

    @Test
    void everyFieldOfTheBundledSchemaHasItsOwnStorageKey() throws Exception {
        Map<String, String> owners = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        int fields = 0;
        for (ContextCapabilitySchema cap : new ContextSchemaService().loadSchema()) {
            for (ContextFieldDef f : cap.fields) {
                fields++;
                String owner = owners.putIfAbsent(f.storageKey(), cap.capability + "." + f.name);
                if (owner != null) clashes.add(f.storageKey() + ": " + owner + " and " + cap.capability + "." + f.name);
            }
        }
        assertEquals(List.of(), clashes);
        assertEquals(fields, countFieldsInFragments(), "no bundled field may be dropped as a duplicate");
    }

    @Test
    void theThreeRealmsAreStoredUnderTheirOwnKeys() {
        assertEquals("realm", field("kerberos", "realm").storageKey(), "the Kerberos realm keeps its historical key");
        assertEquals("oidcRealm", field("oidc", "realm").storageKey());
        assertEquals("polarisRealm", field("polaris", "realm").storageKey());
    }

    @Test
    void thePastedFilesAreTheFieldsKeptApartFromTheContextRow() {
        assertEquals(List.of("krb5Conf", "coreSiteXml", "hdfsSiteXml", "hiveSiteXml", "caCert").stream().sorted().toList(),
                new ContextSchemaService().largeTextFields().keySet().stream().sorted().toList());
        assertEquals("hdfs-site.xml", new ContextSchemaService().largeTextFields().get("hdfsSiteXml"));
    }

    @Test
    void aFieldReusingAnotherFieldsStorageKeyIsDropped() {
        ContextCapabilitySchema kerberos = capability("kerberos", def("realm", null), def("kdcHost", null));
        ContextCapabilitySchema sso = capability("sso", def("realm", null), def("issuer", null));
        ContextCapabilitySchema catalog = capability("catalog", def("realm", "catalogRealm"), def("uri", "issuer"));
        List<ContextCapabilitySchema> schema = new ArrayList<>(List.of(kerberos, sso, catalog));

        ContextSchemaService.rejectDuplicateStorageKeys(schema);

        assertEquals(List.of("realm", "kdcHost"), names(kerberos), "the first field in schema order keeps the key");
        assertEquals(List.of("issuer"), names(sso));
        assertEquals(List.of("realm"), names(catalog), "a configKey counts as the key it stores under");
    }

    private static ContextFieldDef field(String capability, String name) {
        for (ContextCapabilitySchema cap : new ContextSchemaService().loadSchema()) {
            if (!cap.capability.equals(capability)) continue;
            for (ContextFieldDef f : cap.fields) {
                if (f.name.equals(name)) return f;
            }
        }
        throw new AssertionError("no field " + capability + "." + name + " in the bundled schema");
    }

    /** Fields in the bundled fragments as written, before the duplicate check. */
    private static int countFieldsInFragments() throws Exception {
        File dir = new File(ContextSchemaServiceTest.class.getClassLoader()
                .getResource("KDPS/contexts/capabilities").toURI());
        int n = 0;
        for (File fragment : dir.listFiles((d, name) -> name.endsWith(".json"))) {
            try (Reader in = Files.newBufferedReader(fragment.toPath(), StandardCharsets.UTF_8)) {
                n += new Gson().fromJson(in, ContextCapabilitySchema.class).fields.size();
            }
        }
        return n;
    }

    private static ContextFieldDef def(String name, String configKey) {
        ContextFieldDef f = new ContextFieldDef();
        f.name = name;
        f.configKey = configKey;
        return f;
    }

    private static ContextCapabilitySchema capability(String name, ContextFieldDef... fields) {
        ContextCapabilitySchema cap = new ContextCapabilitySchema();
        cap.capability = name;
        cap.fields = new ArrayList<>(List.of(fields));
        return cap;
    }

    private static List<String> names(ContextCapabilitySchema cap) {
        List<String> out = new ArrayList<>();
        cap.fields.forEach(f -> out.add(f.name));
        return out;
    }
}
