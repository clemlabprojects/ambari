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

import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.ViewInstanceDefinition;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Git tokens and SSH keys of GitOps releases in a credential store like Ambari's (credentials.jceks): one entry
 * per release, bound to the Git host it was entered for, and deleted with the release.
 */
class GitCredentialServiceTest {

    @TempDir
    Path tmp;

    PerUserViewContext view;
    AmbariAliasResolver store;
    GitCredentialService credentials;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("kdps", tmp.resolve("work"));
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
        store = new AmbariAliasResolver(view.context, jceks);
        credentials = new GitCredentialService(view.context, store);
    }

    /** Ambari runs the given instances of the KDPS view, and possibly others. */
    private void ambariRuns(String... kdpsInstances) {
        ViewDefinition kdps = mock(ViewDefinition.class);
        when(kdps.getViewName()).thenReturn("K8S-VIEW");
        when(view.context.getViewDefinition()).thenReturn(kdps);
        List<ViewInstanceDefinition> instances = new ArrayList<>();
        for (String name : kdpsInstances) {
            ViewInstanceDefinition instance = mock(ViewInstanceDefinition.class);
            when(instance.getInstanceName()).thenReturn(name);
            when(instance.getViewDefinition()).thenReturn(kdps);
            instances.add(instance);
        }
        ViewDefinition files = mock(ViewDefinition.class);
        when(files.getViewName()).thenReturn("FILES");
        ViewInstanceDefinition other = mock(ViewInstanceDefinition.class);
        when(other.getInstanceName()).thenReturn("kdps");
        when(other.getViewDefinition()).thenReturn(files);
        instances.add(other);
        when(view.context.getViewInstanceDefinitions()).thenReturn(instances);
    }

    @Test
    void aRepositoryUrlCarryingAPasswordIsRefusedWithoutRepeatingIt() {
        for (String url : new String[] {"https://user:s3cret@git.example.org/org/repo.git",
                "https://oauth2-token@git.example.org/org/repo.git", "ssh://git:s3cret@git.example.org/org/repo.git"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> GitCredentialService.checkRepoUrl(url), url);
            assertFalse(refused.getMessage().contains("s3cret") || refused.getMessage().contains("oauth2-token"),
                    refused.getMessage());
        }
        for (String url : new String[] {"https://git.example.org/org/repo.git", "git@git.example.org:org/repo.git",
                "ssh://git@git.example.org:2222/org/repo.git"}) {
            assertDoesNotThrow(() -> GitCredentialService.checkRepoUrl(url), url);
        }
    }

    @Test
    void aReleaseKeepsOneEntryThatEachNewSecretReplaces() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        assertEquals("kdps.gitops.kdps.analytics.trino", alias);

        credentials.storeSecret(alias, "glpat-First", "https://git.example.org/platform/gitops.git");
        credentials.storeSecret(alias, "glpat-Rotated", "https://git.example.org/platform/gitops.git");

        assertEquals(Set.of(alias), store.listAliases(), "one entry per release");
        assertEquals("glpat-Rotated", credentials.resolveSecret(alias));
        assertEquals("git.example.org", credentials.boundHost(alias));
    }

    @Test
    void theHostOfAStoredSecretIsTheOneOfTheRepositoryItWasEnteredFor() {
        String alias = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        credentials.storeSecret(alias, "-----BEGIN OPENSSH PRIVATE KEY-----", "git@git.example.org:platform/gitops.git");
        assertEquals("git.example.org", credentials.boundHost(alias));

        credentials.storeSecret(alias, "glpat-Moved", "https://Gitea.Internal:3000/platform/gitops.git");
        assertEquals("gitea.internal", credentials.boundHost(alias), "the new secret, bound to the new host");
    }

    @Test
    void theInstanceIsPartOfTheAliasSinceAmbarisCredentialStoreIsShared() {
        assertNotEquals(GitCredentialService.releaseAlias("kdps", "analytics", "trino"),
                GitCredentialService.releaseAlias("kdps-prod", "analytics", "trino"));
        assertEquals("kdps.gitops-pending.kdps.analytics.trino",
                GitCredentialService.pendingAlias("KDPS", "analytics", "trino"));
        assertEquals("kdps.gitops-pending-token.kdps.analytics.trino",
                GitCredentialService.pendingTokenAlias("KDPS", "analytics", "trino"));
        assertNotEquals(GitCredentialService.pendingAlias("kdps", "analytics", "trino"),
                GitCredentialService.releaseAlias("kdps", "analytics", "trino.pending"), "release names may hold dots");
        assertTrue(GitCredentialService.isViewAlias(GitCredentialService.pendingAlias("kdps", "analytics", "trino")));
        assertTrue(GitCredentialService.isViewAlias(GitCredentialService.pendingTokenAlias("kdps", "analytics", "trino")));
    }

    @Test
    void aNewSecretBecomesTheReleasesOnlyWhenPromotedWithTheHostItWasEnteredFor() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        String pending = GitCredentialService.pendingAlias("kdps", "analytics", "trino");
        credentials.storeSecret(alias, "glpat-Working", "https://git.example.org/platform/gitops.git");

        credentials.storeSecret(pending, "glpat-Moved", "https://gitea.internal/platform/gitops.git");
        assertEquals("glpat-Working", credentials.resolveSecret(alias), "kept until the deploy reached Git");
        assertEquals("git.example.org", credentials.boundHost(alias));

        assertEquals(alias, credentials.promote(pending, alias));
        assertEquals("glpat-Moved", credentials.resolveSecret(alias));
        assertEquals("gitea.internal", credentials.boundHost(alias));
        assertEquals(Set.of(alias), store.listAliases(), "the pending copy is gone");

        assertNull(credentials.promote(pending, alias), "nothing pending");
        assertEquals("glpat-Moved", credentials.resolveSecret(alias));
    }

    @Test
    void hostsAreReadFromEveryKindOfGitUrl() {
        assertEquals("git.example.org", GitCredentialService.hostOf("https://git.example.org/platform/gitops.git"));
        assertEquals("git.example.org", GitCredentialService.hostOf("https://ci:token@Git.Example.org:8443/x.git"));
        assertEquals("git.example.org", GitCredentialService.hostOf("ssh://git@git.example.org:22/platform/gitops.git"));
        assertEquals("git.example.org", GitCredentialService.hostOf("git@git.example.org:platform/gitops.git"));
        assertEquals("", GitCredentialService.hostOf("file:///srv/git/gitops.git"));
        assertEquals("", GitCredentialService.hostOf("/srv/git/gitops.git"));
        assertNull(GitCredentialService.hostOf(null));
        assertNull(GitCredentialService.hostOf("https://bad host/x.git"));
        assertNull(GitCredentialService.hostOf("https:///x.git"));
    }

    @Test
    void aUrlThatJGitAndThePullRequestCallsWouldSendToDifferentHostsHasNoHost() {
        // JGit pushes to attacker.example (its user part holds "git.example.org#"); java.net.URI reads git.example.org.
        for (String url : List.of("https://git.example.org#@attacker.example/x.git",
                "https://git.example.org?@attacker.example/x.git",
                "https://attacker.example#@git.example.org/x.git",
                "https://git.example.org\\@attacker.example/x.git",
                "https://git.example.org:@attacker.example/x.git",
                "https://a@git.example.org@attacker.example/x.git",
                "git@git.example.org#@attacker.example:x.git",
                "https://git.example.org/x.git?ref=main")) {
            assertNull(GitCredentialService.hostOf(url), url);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> GitCredentialService.checkRepoUrl(url));
            assertFalse(refused.getMessage().contains("attacker"), "the URL is not repeated: " + refused.getMessage());
        }
        GitCredentialService.checkRepoUrl("https://git.example.org/platform/gitops.git");
        GitCredentialService.checkRepoUrl("git@git.example.org:platform/gitops.git");
        GitCredentialService.checkRepoUrl("file:///srv/git/gitops.git");
    }

    @Test
    void aSecretIsNeverStoredForAUrlThatDoesNotNameOneHost() throws Exception {
        String alias = GitCredentialService.pendingAlias("kdps", "analytics", "trino");

        assertNull(credentials.storeSecret(alias, "glpat-Token", "https://git.example.org#@attacker.example/x.git"));

        assertEquals(Set.of(), store.listAliases());
        assertNull(credentials.boundHost(alias));
    }

    @Test
    void theGitServerHostKeysFollowTheSecretTheyWereRecordedWith() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        String pending = GitCredentialService.pendingAlias("kdps", "analytics", "trino");
        String line = "[gitea.internal]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9ok";
        assertNull(credentials.knownHosts(alias));

        credentials.storeSecret(pending, "-----BEGIN OPENSSH PRIVATE KEY-----", "ssh://git@gitea.internal:2222/p/g.git");
        assertTrue(credentials.storeKnownHosts(pending, line));
        assertFalse(credentials.storeKnownHosts(pending, " "), "nothing to record");
        assertEquals(line, credentials.knownHosts(pending));

        assertEquals(alias, credentials.promote(pending, alias));
        assertEquals(line, credentials.knownHosts(alias), "promoted with the key");
        assertNull(credentials.knownHosts(pending));

        assertTrue(credentials.removeSecret(alias));
        assertNull(credentials.knownHosts(alias), "forgotten with the key");
    }

    @Test
    void removingASecretDeletesItsEntryAndItsHostButNeverAnEntryKdpsDidNotCreate() throws Exception {
        String alias = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        credentials.storeSecret(alias, "glpat-Token", "https://git.example.org/platform/gitops.git");
        store.addAliasToCredentialStore("ambari.db.password", "Db-Pass-1");

        assertTrue(credentials.removeSecret(alias));
        assertFalse(credentials.removeSecret("ambari.db.password"));

        assertEquals(Set.of("ambari.db.password"), store.listAliases());
        assertNull(credentials.boundHost(alias));
        assertTrue(credentials.removeSecret(alias), "removing it again is harmless");
    }

    @Test
    void theCopiesEarlierVersionsStoredAndNoReleaseUsesAreDeletedOnce() throws Exception {
        ambariRuns("kdps");
        store.addAliasToCredentialStore("gitops-1b2c", "glpat-Of-An-Uninstalled-Release");
        store.addAliasToCredentialStore("gitops-3d4e", "glpat-Before-Rotation");
        store.addAliasToCredentialStore("gitops-5f6a", "glpat-Of-A-Running-Release");
        store.addAliasToCredentialStore("ambari.db.password", "Db-Pass-1");
        String current = GitCredentialService.releaseAlias("kdps", "analytics", "trino");
        credentials.storeSecret(current, "glpat-Current", "https://git.example.org/platform/gitops.git");

        assertEquals(2, credentials.removeUnreferencedLegacySecrets(List.of("gitops-5f6a", current)));

        assertEquals(Set.of("gitops-5f6a", "ambari.db.password", current), store.listAliases());
        store.addAliasToCredentialStore("gitops-7a8b", "glpat-Later");
        assertEquals(-1, credentials.removeUnreferencedLegacySecrets(List.of()), "only once");
        assertTrue(store.listAliases().contains("gitops-7a8b"));
    }

    @Test
    void theCopiesAreKeptWhileAnotherKdpsInstanceMayUseThem() throws Exception {
        store.addAliasToCredentialStore("gitops-1b2c", "glpat-Of-Another-Instance");

        ambariRuns("kdps", "kdps-prod");
        assertEquals(-1, credentials.removeUnreferencedLegacySecrets(List.of()));
        ambariRuns();
        assertEquals(-1, credentials.removeUnreferencedLegacySecrets(List.of()), "this instance is not known to Ambari");

        assertEquals(Set.of("gitops-1b2c"), store.listAliases());
        ambariRuns("kdps");
        assertEquals(1, credentials.removeUnreferencedLegacySecrets(List.of()));
    }
}
