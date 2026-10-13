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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitClient} against an OpenSSH 9 Git server, with deploy keys as ssh-keygen writes them by default: it pushes
 * with an Ed25519 or an RSA key in OpenSSH format, and only to a server presenting the host key configured. A server
 * presenting another key, or one whose key KDPS was given nothing to check against, is refused with the key it
 * presented, which the operator can check and configure.
 */
@Testcontainers(disabledWithoutDocker = true)
class GitClientOpenSshServerTest {

    @Container
    static final OpenSshGitServer SERVER = new OpenSshGitServer();

    @TempDir
    Path tmp;

    private GitClient client(String keyPath, String knownHosts) {
        return new GitClient(tmp.resolve("ws-" + System.nanoTime()), SERVER.repoUrl(), "main", null,
                SERVER.read(keyPath))
                .withAuthor("KDPS Test", "kdps@example.com")
                .withTimeout(30)
                .withHostKeys(knownHosts);
    }

    private String pinned() {
        return SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY);
    }

    @Test
    void pushesWithAnEd25519DeployKeyToTheServerPresentingThePinnedHostKey() throws Exception {
        GitClient client = client(OpenSshGitServer.ED25519_KEY, pinned());

        client.sync();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        String sha = client.commit("add trino");
        client.push();

        assertEquals(sha, SERVER.head("main"));
        assertTrue(SERVER.holds("main", "apps/trino/helmrelease.yaml"));
    }

    @Test
    void theServersOwnPublicKeyFileCanBePastedAsItsHostKey() throws Exception {
        // As cat /etc/ssh/ssh_host_ed25519_key.pub prints it: no host, a comment.
        GitClient client = client(OpenSshGitServer.ED25519_KEY, SERVER.read(OpenSshGitServer.HOST_KEY));

        client.sync();

        assertNotNull(client.headCommit());
    }

    @Test
    void aServerPresentingAnotherHostKeyIsRefused() throws Exception {
        GitClient client = client(OpenSshGitServer.ED25519_KEY,
                SERVER.knownHostsLine(OpenSshGitServer.IMPOSTOR_HOST_KEY));

        IOException refused = assertThrows(IOException.class, client::sync);

        String message = refused.getMessage();
        assertTrue(message.contains("does not match the configured host key"), message);
        assertTrue(message.contains(SERVER.hostKeyFingerprint()), "the key presented is shown: " + message);
        assertFalse(GitClient.isRetryable(refused), "the same server presents the same key again");
    }

    @Test
    void withoutAHostKeyTheServerIsRefusedAndItsFingerprintShownToBePasted() throws Exception {
        GitClient client = client(OpenSshGitServer.ED25519_KEY, null);

        IOException refused = assertThrows(IOException.class, client::sync);

        String message = refused.getMessage();
        assertTrue(message.contains("No SSH host key is configured for the Git server"), message);
        // The fingerprint OpenSSH prints for its own key, to check with the Git server's administrator.
        assertTrue(message.contains("ssh-ed25519 key " + SERVER.hostKeyFingerprint()), message);
        assertFalse(GitClient.isRetryable(refused));
        assertNull(client.capturedHostKey());

        // The line the message gives, once checked, is the host key to configure.
        String line = message.substring(message.lastIndexOf(": ") + 2).trim();
        assertEquals(pinned(), line);
        GitClient configured = client(OpenSshGitServer.ED25519_KEY, line);
        configured.sync();
        assertNotNull(configured.headCommit());
    }

    @Test
    void anRsaDeployKeyInOpenSshFormatIsAccepted() throws Exception {
        assertTrue(SERVER.read(OpenSshGitServer.RSA_KEY).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
        GitClient client = client(OpenSshGitServer.RSA_KEY, pinned());

        client.sync();
        client.writeFile(Path.of("apps/rsa.txt"), "pushed with an RSA key\n");
        String sha = client.commit("rsa");
        client.push();

        assertEquals(sha, SERVER.head("main"));
    }

    @Test
    void trustOnFirstUseKeepsTheKeyPresentedForTheCallerToRecord() throws Exception {
        GitClient client = client(OpenSshGitServer.ED25519_KEY, null).withHostKeyTrustOnFirstUse(true);

        client.sync();
        client.fetch();

        assertEquals(pinned(), client.capturedHostKey());
        GitClient later = client(OpenSshGitServer.ED25519_KEY, client.capturedHostKey());
        later.sync();
        assertNotNull(later.headCommit());
    }

    @Test
    void aWrongDeployKeyIsTurnedDownWithoutRetrying() throws Exception {
        // The server's own host key is no deploy key of it: a key the server does not know.
        GitClient client = new GitClient(tmp.resolve("ws-wrong"), SERVER.repoUrl(), "main", null,
                SERVER.read("/etc/ssh/ssh_host_ed25519_key")).withTimeout(30).withHostKeys(pinned());

        IOException refused = assertThrows(IOException.class, client::sync);

        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("auth"), refused.getMessage());
        assertFalse(GitClient.isRetryable(refused));
    }
}
