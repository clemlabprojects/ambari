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

import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Services installed on the Ambari server as add-ons, beside those built into the view. */
class StackDefinitionServiceAddonTest {

    @TempDir
    Path root;

    @BeforeEach
    void ownerOnlyRoot() throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Path addon(String key, String serviceJson) throws Exception {
        Path dir = root.resolve(key);
        Files.createDirectories(dir.resolve("configurations/templates"));
        for (Path p : List.of(dir, dir.resolve("configurations"), dir.resolve("configurations/templates"))) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        write(dir.resolve("service.json"), serviceJson);
        return dir;
    }

    private static void write(Path file, String content) throws Exception {
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    }

    private StackDefinitionService service(String viewVersion) {
        return new StackDefinitionService(root, viewVersion);
    }

    @Test
    void anAddonIsOfferedAfterTheBuiltInServicesWithItsConfigurationsAndIcon() throws Exception {
        Path dir = addon("MINIO_GW", "{\"label\":\"Gateway\",\"chart\":\"gateway\",\"version\":\"0.1.0\"}");
        write(dir.resolve("configurations/gateway-env.json"),
                "{\"name\":\"gateway-env\",\"properties\":[{\"name\":\"conf\",\"valueSourceFile\":\"gateway.conf\"}]}");
        write(dir.resolve("configurations/templates/gateway.conf"), "listen 8080\n");
        write(dir.resolve("icon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

        StackDefinitionService svc = service("1.0.0.10");
        List<String> keys = svc.discoverServiceKeys();
        assertEquals("MINIO_GW", keys.get(keys.size() - 1), "add-ons come after the built-in services: " + keys);
        assertTrue(keys.contains("TRINO"));
        assertTrue(svc.hasServiceDefinition("minio_gw"));

        StackServiceDef def = svc.getServiceDefinition("MINIO_GW");
        assertEquals("MINIO_GW", def.name);
        assertEquals("gateway", def.chart);
        assertEquals(Map.of("source", "addon", "icon", true), def.addon);

        List<StackConfig> configs = svc.getServiceConfigurations("MINIO_GW");
        assertEquals(1, configs.size());
        assertEquals("listen 8080\n", configs.get(0).properties.get(0).value);
        assertEquals(dir.resolve("icon.svg"), svc.addonIcon("MINIO_GW"));
        assertNull(svc.getServiceDefinition("TRINO").addon, "a built-in service is not marked as an add-on");
    }

    @Test
    void anAddonCannotReplaceABuiltInService() throws Exception {
        addon("TRINO", "{\"chart\":\"evil\"}");
        StackDefinitionService svc = service("1.0.0.10");
        assertEquals("trino", svc.getServiceDefinition("TRINO").chart);
        assertTrue(svc.scanAddons().refused().get("TRINO").contains("built into KDPS"));
        assertEquals(1, svc.discoverServiceKeys().stream().filter("TRINO"::equals).count());
    }

    @Test
    void foldersKdpsCannotTrustAreLeftOutWithTheReason() throws Exception {
        addon("lowercase", "{}");
        Path writable = addon("WRITABLE", "{}");
        Files.setPosixFilePermissions(writable.resolve("service.json"), PosixFilePermissions.fromString("rw-rw-rw-"));
        Path linked = addon("LINKED", "{}");
        Files.delete(linked.resolve("service.json"));
        Path outside = Files.createTempFile("outside", ".json");
        Files.writeString(outside, "{}");
        Files.createSymbolicLink(linked.resolve("service.json"), outside);
        Files.createDirectories(root.resolve("EMPTY"));
        Files.setPosixFilePermissions(root.resolve("EMPTY"), PosixFilePermissions.fromString("rwxr-xr-x"));

        StackDefinitionService.AddonScan scan = service("1.0.0.10").scanAddons();
        assertTrue(scan.accepted().isEmpty(), "accepted: " + scan.accepted());
        assertTrue(scan.refused().get("lowercase").contains("upper case"));
        assertTrue(scan.refused().get("WRITABLE").contains("written by other accounts"));
        assertTrue(scan.refused().get("LINKED").contains("no service.json"));
        assertTrue(scan.refused().get("EMPTY").contains("no service.json"));
        assertFalse(service("1.0.0.10").hasServiceDefinition("WRITABLE"));
        assertThrows(RuntimeException.class, () -> service("1.0.0.10").getServiceDefinition("WRITABLE"));
    }

    @Test
    void anAddonForANewerKdpsIsNotOffered() throws Exception {
        addon("NEXT", "{\"requiresKdps\":\"1.0.0.11\"}");
        addon("NOW", "{\"requiresKdps\":\"1.0.0.10\"}");
        StackDefinitionService.AddonScan scan = service("1.0.0.10").scanAddons();
        assertEquals(List.of("NOW"), List.copyOf(scan.accepted().keySet()));
        assertEquals("it needs KDPS view 1.0.0.11 or later, and this one is 1.0.0.10", scan.refused().get("NEXT"));
    }

    @Test
    void aTemplateOutsideTheAddonFolderIsNotRead() throws Exception {
        Path dir = addon("ESCAPE", "{}");
        write(root.resolve("secret.txt"), "not yours");
        write(dir.resolve("configurations/escape-env.json"),
                "{\"name\":\"escape-env\",\"properties\":[{\"name\":\"x\",\"valueSourceFile\":\"../../../secret.txt\"}]}");
        assertTrue(service(null).getServiceConfigurations("ESCAPE").isEmpty(),
                "a configuration naming a file outside the add-on is skipped");
    }

    @Test
    void installingOrRemovingAnAddonNeedsNoRestart() throws Exception {
        StackDefinitionService svc = service("1.0.0.10");
        assertFalse(svc.discoverServiceKeys().contains("LATER"));
        Path dir = addon("LATER", "{\"version\":\"1.0.0\"}");
        assertTrue(svc.discoverServiceKeys().contains("LATER"));
        assertEquals("1.0.0", svc.getServiceDefinition("LATER").version);
        write(dir.resolve("service.json"), "{\"version\":\"1.0.1\"}");
        assertEquals("1.0.1", svc.getServiceDefinition("LATER").version, "an upgraded add-on is read again");
        Files.delete(dir.resolve("service.json"));
        assertFalse(svc.discoverServiceKeys().contains("LATER"));
    }

    @Test
    void withoutAnAddonDirectoryOnlyTheBuiltInServicesAreOffered() {
        StackDefinitionService svc = new StackDefinitionService(root.resolve("absent"), "1.0.0.10");
        assertEquals(svc.discoverBuiltInServiceKeys(), svc.discoverServiceKeys());
        Map<String, Object> status = svc.addonStatus();
        assertEquals(List.of(), status.get("accepted"));
        assertEquals(List.of(), status.get("refused"));
    }
}
