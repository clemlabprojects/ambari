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

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.k8s.service.deployment.GitHostKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The SSH host keys of a release's Git server are kept in its Git metadata, which must still fit its column. */
class K8sReleaseEntitySshKnownHostsTest {

    private static final String LINE = "[gitea.example.org]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6U";

    @Test
    void theHostKeysRoundTripWithTheRestOfTheGitMetadata() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setGitRepoUrl("ssh://git@gitea.example.org:2222/platform/gitops.git");
        e.setGitSshKnownHosts(LINE + "\n" + LINE.replace("ed25519", "rsa"));

        K8sReleaseEntity reloaded = new K8sReleaseEntity();
        reloaded.setGitMetaJson(e.getGitMetaJson());
        assertEquals(LINE + "\n" + LINE.replace("ed25519", "rsa"), reloaded.getGitSshKnownHosts());
        assertEquals("ssh://git@gitea.example.org:2222/platform/gitops.git", reloaded.getGitRepoUrl());

        reloaded.setGitSshKnownHosts(null);
        assertNull(reloaded.getGitSshKnownHosts());
    }

    @Test
    void hostKeysThatWouldNotFitTheColumnAreNotRecordedNorThePreviousOnesKept() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setGitSshKnownHosts(LINE);
        e.setGitPrUrl("https://gitea.example.org/platform/gitops/pulls/" + "9".repeat(1500));

        e.setGitSshKnownHosts(LINE + "\n" + "x".repeat(1600));

        assertNull(e.getGitSshKnownHosts(), "not the previous value either, which may be keys the server no longer"
                + " presents: left to the copy recorded with the release's Git credential");
        assertTrue(e.getGitMetaJson().length() <= InstanceSettings.MAX_VALUE_LENGTH);
    }

    @Test
    void hostKeysGiveWayToAPendingRemovalThatWouldNotFitWithThem() {
        // The keys of a Git server during a rotation of its keys: its old and new RSA 4096 keys (by name and address)
        // and Ed25519 keys, and its ECDSA key. Close to the most a deploy accepts.
        String host = "[git.example.org]:2222 ";
        String rsa = "[git.example.org]:2222,[10.20.30.40]:2222 ssh-rsa AAAAB3NzaC1yc2E" + "A".repeat(701);
        String ed25519 = host + "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + "A".repeat(48);
        String knownHosts = String.join("\n", rsa, rsa.replace("yc2EA", "yc2EB"), ed25519,
                ed25519.replace("AAAAIA", "AAAAIB"), host + "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY"
                        + "A".repeat(149));
        assertTrue(knownHosts.length() > 1900 && knownHosts.length() <= GitHostKeys.MAX_LENGTH,
                "accepted by a deploy: " + knownHosts.length());

        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setGitRepoUrl("ssh://git@git.example.org:2222/data-platform/production/gitops-releases.git");
        e.setGitPath("clusters/production/analytics/superset");
        e.setGitBaseBranch("main");
        e.setGitBranch("main");
        e.setGitCommitMode("PULL_REQUEST");
        e.setGitCredentialAlias("kdps.gitops.credential.kdps.analytics.superset");
        e.setGitCommitSha("0123456789abcdef0123456789abcdef01234567");
        e.setTruststoreRefs(List.of("corporate-root-ca", "git-server-ca"));
        e.setGitSshKnownHosts(knownHosts);
        assertEquals(knownHosts, e.getGitSshKnownHosts());

        // An uninstall in pull request mode records the removal waiting for its merge (as recordPendingRemoval does).
        List<String> removed = List.of("analytics/superset", "analytics/superset-postgresql",
                "analytics/superset-redis", "analytics/superset-worker", "analytics/superset-celerybeat",
                "analytics/superset-flower", "analytics/superset-init-db", "analytics/superset-ingress",
                "analytics/superset-oauth-proxy", "analytics/superset-statsd-exporter",
                "analytics/superset-trino-catalog-sync", "analytics/superset-ldap-group-sync",
                "analytics/superset-thumbnail-cache");
        e.setGitBranch("kdps/remove-analytics-superset-20261013-142311");
        e.setGitPrUrl("https://git.example.org/data-platform/production/gitops-releases/-/merge_requests/1287");
        e.setGitPrNumber("1287");
        e.setGitPrState("OPEN");
        e.setGitPendingDelete(true);
        e.setGitRemovedHelmReleases(removed);

        assertTrue(e.getGitMetaJson().length() <= InstanceSettings.MAX_VALUE_LENGTH,
                "the release can still be stored: " + e.getGitMetaJson().length());
        assertNull(e.getGitSshKnownHosts(), "left to the copy recorded with the release's Git credential");
        K8sReleaseEntity reloaded = new K8sReleaseEntity();
        reloaded.setGitMetaJson(e.getGitMetaJson());
        assertTrue(reloaded.isGitPendingDelete());
        assertEquals(removed, reloaded.getGitRemovedHelmReleases());
        assertEquals("https://git.example.org/data-platform/production/gitops-releases/-/merge_requests/1287",
                reloaded.getGitPrUrl());
        assertEquals("ssh://git@git.example.org:2222/data-platform/production/gitops-releases.git",
                reloaded.getGitRepoUrl());
        assertEquals(List.of("corporate-root-ca", "git-server-ca"), reloaded.getTruststoreRefs());

        // Recorded again by a later deploy once the removal no longer takes the room.
        e.setGitRemovedHelmReleases(null);
        e.setGitPendingDelete(false);
        e.setGitSshKnownHosts(knownHosts);
        assertEquals(knownHosts, e.getGitSshKnownHosts());
        assertTrue(e.getGitMetaJson().length() <= InstanceSettings.MAX_VALUE_LENGTH);
    }

    @Test
    void hostKeysThatStillFitAreKeptWhenOtherMetadataChanges() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setGitSshKnownHosts(LINE);
        e.setGitPrUrl("https://gitea.example.org/platform/gitops/pulls/42");
        e.setGitRemovedHelmReleases(List.of("analytics/superset"));
        assertEquals(LINE, e.getGitSshKnownHosts());
    }
}
