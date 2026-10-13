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

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host keys a Git client accepts from its Git server: only those configured, or, with trust on first use and none
 * configured, the first one presented and then that one only. A refusal names the key presented, its fingerprint and
 * the line to configure.
 */
class GitHostKeysTest {

    private static final String HOST = "[gitea.example.org]:2222";

    private static byte[] newKey(int type) throws Exception {
        KeyPair pair = KeyPair.genKeyPair(new JSch(), type, type == KeyPair.RSA ? 2048 : 256);
        byte[] blob = pair.getPublicKeyBlob();
        pair.dispose();
        return blob;
    }

    private static String line(String host, byte[] blob) throws Exception {
        HostKey key = new HostKey(host, blob);
        return host + " " + key.getType() + " " + key.getKey();
    }

    @Test
    void onlyAConfiguredKeyIsAccepted() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        byte[] impostor = newKey(KeyPair.ED25519);
        GitHostKeys keys = new GitHostKeys(line(HOST, server), "gitea.example.org", 2222, false);

        assertEquals(HostKeyRepository.OK, keys.check(HOST, server));
        assertNull(keys.rejection("gitea.example.org:2222"));

        assertEquals(HostKeyRepository.CHANGED, keys.check(HOST, impostor));
        String refused = keys.rejection("gitea.example.org:2222");
        assertTrue(refused.contains("does not match the configured host key"), refused);
        assertTrue(refused.contains(fingerprint(impostor)), "the key presented is named: " + refused);
        assertTrue(refused.endsWith(line(HOST, impostor)), refused);
        assertNull(keys.captured(), "nothing is learnt from a session");
    }

    @Test
    void withoutAConfiguredKeyTheServerIsRefusedAndItsKeyShown() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        GitHostKeys keys = new GitHostKeys(null, "gitea.example.org", 2222, false);

        assertEquals(HostKeyRepository.NOT_INCLUDED, keys.check(HOST, server));
        assertFalse(keys.anyPinned());
        String refused = keys.rejection("gitea.example.org:2222");
        assertTrue(refused.startsWith("No SSH host key is configured for the Git server gitea.example.org:2222"),
                refused);
        assertTrue(refused.contains("ssh-ed25519 key " + fingerprint(server)), refused);
        assertTrue(refused.endsWith(line(HOST, server)), "the line to paste once checked: " + refused);
        assertNull(keys.captured());
    }

    @Test
    void aKeyOfTheServerForAnotherHostIsNotAccepted() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        GitHostKeys keys = new GitHostKeys(line("git.example.org", server), "gitea.example.org", 2222, false);

        assertNotEquals(HostKeyRepository.OK, keys.check(HOST, server));
        assertTrue(keys.rejection("gitea.example.org:2222").contains("is not one of the configured host keys"));
    }

    @Test
    void aPublicKeyWithoutAHostIsForTheRepositorysServer() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        HostKey key = new HostKey(HOST, server);
        // As /etc/ssh/ssh_host_ed25519_key.pub holds it, after the ssh-keyscan comment.
        String pasted = "# gitea.example.org:2222 SSH-2.0-OpenSSH_9.7\r\n" + key.getType() + " " + key.getKey()
                + " root@gitea\r\n";

        GitHostKeys keys = new GitHostKeys(pasted, "gitea.example.org", 2222, false);

        assertEquals(HostKeyRepository.OK, keys.check(HOST, server));
        // Bound to the server of the repository it is given for, not to any host.
        assertEquals(HostKeyRepository.NOT_INCLUDED, new GitHostKeys(pasted, "other.example.org", 22, false)
                .check(HOST, server));
        assertEquals(key.getType() + " " + key.getKey() + " root@gitea", GitHostKeys.compact(pasted));
    }

    @Test
    void aHostNamedLikeAKeyTypeIsReadAsTheHost() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        String pinned = line("ssh-gateway.example.org", server);

        GitHostKeys.check(pinned);
        GitHostKeys keys = new GitHostKeys(pinned, "ssh-gateway.example.org", 22, false);

        assertEquals(HostKeyRepository.OK, keys.check("ssh-gateway.example.org", server));
        // The line names its own host: it is not taken for a key of the repository's server.
        assertEquals(HostKeyRepository.NOT_INCLUDED, new GitHostKeys(pinned, "gitea.example.org", 22, false)
                .check("gitea.example.org", server));
    }

    @Test
    void trustOnFirstUseAcceptsTheFirstKeyOnlyAndKeepsIt() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        GitHostKeys keys = new GitHostKeys("", "gitea.example.org", 2222, true);

        assertEquals(HostKeyRepository.OK, keys.check(HOST, server));
        assertEquals(line(HOST, server), keys.captured());
        assertEquals(HostKeyRepository.OK, keys.check(HOST, server), "the same key again");
        assertEquals(HostKeyRepository.CHANGED, keys.check(HOST, newKey(KeyPair.ED25519)),
                "another key afterwards is refused");
        assertTrue(keys.rejection("gitea.example.org:2222").contains("does not match"));
    }

    @Test
    void trustOnFirstUseIsIgnoredOnceAKeyIsConfigured() throws Exception {
        byte[] server = newKey(KeyPair.ED25519);
        GitHostKeys keys = new GitHostKeys(line(HOST, server), "gitea.example.org", 2222, true);

        assertEquals(HostKeyRepository.CHANGED, keys.check(HOST, newKey(KeyPair.ED25519)));
        assertNull(keys.captured());
    }

    @Test
    void theServerIsAskedToSignWithTheTypeOfTheConfiguredKey() throws Exception {
        String offered = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256";
        assertEquals(offered, new GitHostKeys(null, "h", 22, false).preferredAlgorithms(offered));

        String ecdsa = line("h", newKey(KeyPair.ECDSA));
        assertEquals("ecdsa-sha2-nistp256,ssh-ed25519,rsa-sha2-512,rsa-sha2-256",
                new GitHostKeys(ecdsa, "h", 22, false).preferredAlgorithms(offered));

        // An RSA host key is checked through its SHA-2 signatures, which current servers make.
        String rsa = line("h", newKey(KeyPair.RSA));
        assertEquals("rsa-sha2-512,rsa-sha2-256,ssh-ed25519,ecdsa-sha2-nistp256",
                new GitHostKeys(rsa, "h", 22, false).preferredAlgorithms(offered));
    }

    @Test
    void whatIsNotAHostKeyIsRefusedBeforeAnythingIsSent() throws Exception {
        IllegalArgumentException fingerprint = assertThrows(IllegalArgumentException.class,
                () -> GitHostKeys.check("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"));
        assertTrue(fingerprint.getMessage().startsWith("Line 1 of the Git server host key is not a known_hosts line"));
        assertThrows(IllegalArgumentException.class, () -> GitHostKeys.check("git.example.org ssh-ed25519 %%%"));
        assertThrows(IllegalArgumentException.class, () -> new GitClient(java.nio.file.Path.of("ws"),
                "ssh://git@git.example.org/r.git", "main", null).withHostKeys("-----BEGIN OPENSSH PRIVATE KEY-----"));

        String many = (line("git.example.org", newKey(KeyPair.ED25519)) + "\n").repeat(25);
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () -> GitHostKeys.check(many));
        assertTrue(tooLong.getMessage().contains("too long"), tooLong.getMessage());

        GitHostKeys.check(null);
        GitHostKeys.check("  \n# only a comment\n");
        assertNull(GitHostKeys.compact("  \n# only a comment\n"));
    }

    @Test
    void markedLinesAreRefusedSoARevokedKeyIsNeverTrusted() throws Exception {
        byte[] stolen = newKey(KeyPair.ED25519);
        String revoked = "@revoked " + line("gitea.example.org", stolen);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new GitHostKeys(revoked, "gitea.example.org", 22, false));
        assertTrue(refused.getMessage().startsWith("Line 1 of the Git server host key starts with the marker @revoked"),
                refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> GitHostKeys.check(revoked));

        String server = line("gitea.example.org", newKey(KeyPair.ED25519));
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> GitHostKeys.check(server + "\n@cert-authority *.example.org " + line("", stolen).trim()));
        assertTrue(mixed.getMessage().startsWith("Line 2 "), mixed.getMessage());
        assertTrue(mixed.getMessage().contains("@cert-authority"), mixed.getMessage());
    }

    /** The fingerprint ssh-keygen -l prints for a public key. */
    private static String fingerprint(byte[] blob) throws Exception {
        return "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
    }
}
