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
package org.apache.ambari.view.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view runs inside the Ambari server JVM, next to the server itself and the other views. A switch that turns off
 * TLS host-name checks, or replaces the proxy or the proxy credentials, for that whole JVM must not come back: TLS
 * and proxy settings are given to each client the view builds. The words are spelled in pieces here so this test
 * does not find itself.
 */
class NoJvmWideNetworkSwitchTest {

    private static final List<String> FORBIDDEN = List.of(
            "disable" + "HostnameVerification",
            "ProxySelector" + ".setDefault(",
            "Authenticator" + ".setDefault(",
            "setDefault" + "HostnameVerifier(",
            "setDefault" + "SSLSocketFactory(",
            "System.setProperty(\"jdk.http" + ".auth");

    /** Built or installed front-end files, not source. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("node_modules", "node", "dist", "target");

    @Test
    void noSourceOfTheViewFlipsANetworkSettingForTheWholeServer() throws IOException {
        Path main = Paths.get(System.getProperty("basedir", ".")).resolve("src/main");
        assertTrue(Files.isDirectory(main.resolve("java")), "run from the view module: " + main.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        Files.walkFileTree(main, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException binary) {
                    return FileVisitResult.CONTINUE;
                }
                for (String word : FORBIDDEN) {
                    if (text.contains(word)) {
                        offenders.add(main.relativize(file) + ": " + word);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(List.of(), offenders, "JVM-wide network switches in the view sources");
    }
}
