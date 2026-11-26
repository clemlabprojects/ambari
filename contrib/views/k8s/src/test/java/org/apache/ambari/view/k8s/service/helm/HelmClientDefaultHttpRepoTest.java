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
package org.apache.ambari.view.k8s.service.helm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An HTTP chart repository with a login is read with that login. */
class HelmClientDefaultHttpRepoTest {

    private static final String INDEX = "apiVersion: v1\nentries: {}\ngenerated: \"2026-01-01T00:00:00Z\"\n";

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void aRepositoryThatAsksForALogin() throws Exception {
        String expected = "Basic " + Base64.getEncoder().encodeToString("reader:s3cret".getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String given = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(String.valueOf(given));
            if (!expected.equals(given)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"charts\"");
                exchange.sendResponseHeaders(401, -1);
            } else {
                byte[] body = INDEX.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/charts");
    }

    @Test
    void anHttpRepositoryIsReadWithItsLogin() throws Exception {
        Path config = dir.resolve("repositories.yaml");
        new HelmClientDefault().ensureHttpRepo(config, "private", url(), "reader", "s3cret");
        assertTrue(Files.readString(config).contains("name: private"));
        assertTrue(authorizations.stream().anyMatch(a -> a.startsWith("Basic ")), "no login was sent: " + authorizations);
    }

    @Test
    void withoutItsLoginTheRepositoryRefusesTheIndex() {
        Path config = dir.resolve("repositories.yaml");
        assertThrows(RuntimeException.class,
                () -> new HelmClientDefault().ensureHttpRepo(config, "private", url(), null, null));
    }
}
