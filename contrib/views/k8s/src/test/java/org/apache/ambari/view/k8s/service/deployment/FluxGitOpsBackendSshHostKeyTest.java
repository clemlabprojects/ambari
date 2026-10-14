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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A GitOps release deployed over SSH to an OpenSSH 9 Git server with an Ed25519 deploy key: the first deploy without
 * the server's host key is refused with the key it presented; once that key is given, the deploy, its redeploys and
 * its uninstall work, and a server presenting another key is refused. Trust on first use, chosen explicitly, records
 * the key presented and requires it from then on.
 */
@Testcontainers(disabledWithoutDocker = true)
class FluxGitOpsBackendSshHostKeyTest {

    private static final String NS = "analytics";

    @Container
    static final OpenSshGitServer SERVER = new OpenSshGitServer();

    @TempDir
    Path tmp;

    private InMemoryDataStore dataStore;
    private AmbariAliasResolver store;
    private GitCredentialService credentials;
    private ViewContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createDirectories(tmp.resolve("work")).toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getInstanceData()).thenReturn(Map.of());
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);
        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.com");
        charts.setAuthMode("anonymous");
        dataStore.store(charts);

        FileBasedCredentialStore jceks = new FileBasedCredentialStore(tmp.resolve("credentials.jceks").toFile());
        jceks.setMasterKeyService(new MasterKeyService() {
            @Override
            public char[] getMasterSecret() {
                return "test-master-key".toCharArray();
            }

            @Override
            public boolean isMasterKeyInitialized() {
                return true;
            }
        });
        store = new AmbariAliasResolver(ctx, jceks);
        credentials = new GitCredentialService(ctx, store);
    }

    private FluxGitOpsBackend backend() throws Exception {
        KubernetesService k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        FluxGitOpsBackend backend = new FluxGitOpsBackend(tmp.resolve("flux"), k8s, ctx);
        Field field = FluxGitOpsBackend.class.getDeclaredField("gitCredentialService");
        field.setAccessible(true);
        field.set(backend, credentials);
        return backend;
    }

    /** A deploy of a release over SSH, with the deploy key and host key given ({@code null} for none). */
    private HelmDeployRequest deploy(String release, int replicas, String sshKey, String knownHosts) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace(NS);
        request.setReleaseName(release);
        request.setChart("superset");
        request.setVersion("0.12.43");
        request.setRepoId("charts");
        request.setDeploymentMode("FLUX_GITOPS");
        request.setValues(new LinkedHashMap<>(Map.of("replicaCount", replicas)));
        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(SERVER.repoUrl());
        git.setBaseBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        git.setSshKey(sshKey);
        git.setSshKnownHosts(knownHosts);
        request.setGit(git);
        return request;
    }

    private K8sReleaseEntity recorded(String release) {
        return dataStore.find(K8sReleaseEntity.class, K8sReleaseEntity.idOf(NS, release));
    }

    private static String helmRelease(String release) {
        return "clusters/default/" + NS + "/" + release + "/" + FluxGitOpsBackend.HELM_RELEASE_FILE;
    }

    @Test
    void aFirstDeployWithoutTheHostKeyIsRefusedThenThePastedKeyDeploysAndUninstalls() throws Exception {
        String release = "superset-pinned";
        String deployKey = SERVER.read(OpenSshGitServer.ED25519_KEY);
        String alias = GitCredentialService.releaseAlias("kdps", NS, release);

        Exception refused = assertThrows(Exception.class,
                () -> backend().apply(deploy(release, 1, deployKey, null), null));
        String message = refused.getMessage();
        assertTrue(message.contains("No SSH host key is configured for the Git server"), message);
        assertTrue(message.contains(SERVER.hostKeyFingerprint()), "the fingerprint to check: " + message);
        assertFalse(SERVER.holds("main", helmRelease(release)), "nothing was pushed");
        assertNull(recorded(release));
        assertEquals(Set.of(), store.listAliases(), "a deploy key that never reached Git is not kept");

        // The operator checks the fingerprint and pastes the line the message gives.
        String line = message.substring(message.lastIndexOf(": ") + 2).trim();
        assertEquals(SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY), line);
        backend().apply(deploy(release, 1, deployKey, line), null);
        assertTrue(SERVER.holds("main", helmRelease(release)));
        assertEquals(line, recorded(release).getGitSshKnownHosts(), "recorded for the release");
        assertEquals(line, credentials.knownHosts(alias), "and with its Git credential");

        // A redeploy gives neither: the stored deploy key and the recorded host key are used.
        String before = SERVER.head("main");
        backend().apply(deploy(release, 2, null, null), null);
        assertNotEquals(before, SERVER.head("main"));

        backend().removeFromGit(recorded(release));
        assertFalse(SERVER.holds("main", helmRelease(release)), "the uninstall removed the release from Git");
        assertEquals(Set.of(), store.listAliases());
        assertNull(credentials.knownHosts(alias));
    }

    @Test
    void anotherServerAnsweringForTheGitServerIsRefusedAtTheUninstall() throws Exception {
        String release = "superset-changed";
        String deployKey = SERVER.read(OpenSshGitServer.ED25519_KEY);
        backend().apply(deploy(release, 1, deployKey, SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY)), null);
        String head = SERVER.head("main");

        // As if the Git server's host key had been regenerated, or another server answered in its place.
        K8sReleaseEntity meta = recorded(release);
        meta.setGitSshKnownHosts(SERVER.knownHostsLine(OpenSshGitServer.IMPOSTOR_HOST_KEY));
        dataStore.store(meta);

        Exception refused = assertThrows(Exception.class, () -> backend().removeFromGit(recorded(release)));
        assertTrue(refused.getMessage().contains("does not match the configured host key"), refused.getMessage());
        assertEquals(head, SERVER.head("main"));
        assertTrue(SERVER.holds("main", helmRelease(release)), "nothing removed");
        assertEquals(Set.of(GitCredentialService.releaseAlias("kdps", NS, release)), store.listAliases(),
                "the credential is kept for a retry");
    }

    @Test
    void trustOnFirstUseRecordsTheKeyPresentedAndRequiresIt() throws Exception {
        String release = "superset-tofu";
        HelmDeployRequest first = deploy(release, 1, SERVER.read(OpenSshGitServer.ED25519_KEY), null);
        first.getGit().setTrustHostKeyOnFirstUse(true);

        backend().apply(first, null);

        assertTrue(SERVER.holds("main", helmRelease(release)));
        assertEquals(SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY), recorded(release).getGitSshKnownHosts());
        backend().removeFromGit(recorded(release));
        assertFalse(SERVER.holds("main", helmRelease(release)));
    }

    @Test
    void theHostKeyRecordedForAnotherGitServerIsNotRequiredOfTheOneTheReleaseMovesTo() throws Exception {
        String release = "superset-moved";
        String deployKey = SERVER.read(OpenSshGitServer.ED25519_KEY);
        String alias = GitCredentialService.releaseAlias("kdps", NS, release);
        backend().apply(deploy(release, 1, deployKey, SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY)), null);

        // As if the release had been deployed to another Git server, whose key was pasted without its host, as the
        // content of its ssh_host_*_key.pub.
        String impostor = SERVER.knownHostsLine(OpenSshGitServer.IMPOSTOR_HOST_KEY);
        String otherServersKey = impostor.substring(impostor.indexOf(' ') + 1);
        K8sReleaseEntity meta = recorded(release);
        meta.setGitRepoUrl("ssh://git@old-git.example.org:2222/platform/gitops.git");
        meta.setGitSshKnownHosts(otherServersKey);
        dataStore.store(meta);
        credentials.storeKnownHosts(alias, otherServersKey);

        // Moved to this server with no host key given: refused as for a new release, not as a changed key.
        Exception refused = assertThrows(Exception.class,
                () -> backend().apply(deploy(release, 2, deployKey, null), null));
        assertTrue(refused.getMessage().contains("No SSH host key is configured for the Git server"),
                refused.getMessage());

        // Trust on first use, chosen for the move, applies: the key this server presents is recorded.
        String head = SERVER.head("main");
        HelmDeployRequest moved = deploy(release, 2, deployKey, null);
        moved.getGit().setTrustHostKeyOnFirstUse(true);
        backend().apply(moved, null);
        assertNotEquals(head, SERVER.head("main"));
        assertEquals(SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY), recorded(release).getGitSshKnownHosts());
        assertEquals(SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY), credentials.knownHosts(alias));
    }

    @Test
    void newHostKeysThatDoNotFitTheReleaseMetadataReplaceTheOldOnesForItsLaterOperations() throws Exception {
        String release = "superset-rotated";
        String deployKey = SERVER.read(OpenSshGitServer.ED25519_KEY);
        String alias = GitCredentialService.releaseAlias("kdps", NS, release);
        backend().apply(deploy(release, 1, deployKey, SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY)), null);

        // As if the server's host key had been rotated since: the release recorded the key it presented before. Its
        // Git metadata is close to full (here with the truststores it names), with room for that key but not for the
        // server's keys after the rotation, which add a longer RSA key.
        String oldKey = SERVER.knownHostsLine(OpenSshGitServer.IMPOSTOR_HOST_KEY);
        K8sReleaseEntity meta = recorded(release);
        meta.setGitSshKnownHosts(oldKey);
        meta.setTruststoreRefs(List.of("corporate-root-ca"));
        int room = InstanceSettings.MAX_VALUE_LENGTH - meta.getGitMetaJson().length();
        meta.setTruststoreRefs(List.of("corporate-root-ca" + "-".repeat(room - 150)));
        assertEquals(oldKey, meta.getGitSshKnownHosts());
        dataStore.store(meta);
        credentials.storeKnownHosts(alias, oldKey);

        String newKeys = SERVER.knownHostsLine(OpenSshGitServer.HOST_KEY) + "\n"
                + SERVER.knownHostsLine(OpenSshGitServer.RSA_KEY + ".pub");
        assertTrue(newKeys.length() > oldKey.length() + 150, "longer: " + newKeys.length());
        backend().apply(deploy(release, 2, null, newKeys), null);
        assertNull(recorded(release).getGitSshKnownHosts(), "the old key is not left to take precedence");
        assertEquals(newKeys, credentials.knownHosts(alias));

        // The redeploys and the uninstall that give no host key require the server's current keys.
        String head = SERVER.head("main");
        backend().apply(deploy(release, 3, null, null), null);
        assertNotEquals(head, SERVER.head("main"));
        backend().removeFromGit(recorded(release));
        assertFalse(SERVER.holds("main", helmRelease(release)), "the uninstall removed the release from Git");
    }

    @Test
    void aHostKeyThatIsNotOneIsRefusedBeforeAnythingIsSent() throws Exception {
        String release = "superset-invalid";
        HelmDeployRequest request = deploy(release, 1, SERVER.read(OpenSshGitServer.ED25519_KEY),
                "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> backend().checkCommittable(request, null));
        assertTrue(refused.getMessage().contains("is not a known_hosts line"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> backend().apply(request, null));
        assertFalse(SERVER.holds("main", helmRelease(release)));
    }
}
