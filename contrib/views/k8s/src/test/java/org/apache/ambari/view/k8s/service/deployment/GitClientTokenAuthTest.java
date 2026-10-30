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

package org.apache.ambari.view.k8s.service.deployment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The access token of an HTTPS Git repository goes as the password of the user oauth2: GitLab (and Azure DevOps)
 * answer "not authorized" to a token given as the user name, while GitHub and Gitea take both forms.
 */
class GitClientTokenAuthTest {

    @TempDir
    Path tmp;

    @Test
    void theTokenIsSentAsThePasswordOfTheUserOauth2() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Like GitLab: asks for Basic credentials, then refuses them, which ends the clone after one try.
        server.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization);
                exchange.sendResponseHeaders(403, -1);
            } else {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"GitLab\"");
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/team/deploy-trino.git";
            GitClient client = new GitClient(tmp.resolve("ws"), url, "main", "glpat-s3cret");

            assertThrows(Exception.class, client::sync);

            assertFalse(authorizations.isEmpty(), "the server was given credentials");
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("oauth2:glpat-s3cret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authorizations.get(0));
        } finally {
            server.stop(0);
        }
    }
}
