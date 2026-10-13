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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The record of a release, written by one request and read back by another through the view's DataStore (which, like
 * Ambari's, hands out copies and only overwrites the properties a store sets): what a deploy recorded, its Git
 * metadata and its truststores come back as written, and a later deploy changes only what it sets.
 */
class ReleaseMetadataServiceRoundTripTest {

    private static final String NS = "analytics";

    @TempDir
    Path workDir;

    private PerUserViewContext view;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("release-metadata", workDir).as("admin");
    }

    /** A service as a new request builds it: nothing is kept in memory between requests. */
    private ReleaseMetadataService metadata() {
        return new ReleaseMetadataService(view.context);
    }

    private void recordGitOpsInstall() {
        metadata().recordInstallOrUpgrade(NS, "trino", "TRINO", "default", "trinodb/trino", "charts", "1.43.0",
                null, "op-install", List.of(Map.of("id", "trino-ui", "url", "https://trino.example.org")),
                "global-1", "corp-oidc", "hash-1", "FLUX_GITOPS", "0a1b2c3", "kdps/trino", "clusters/dev",
                "https://git.example.org/platform.git", "git.token.trino", "PR_MODE",
                "https://git.example.org/platform/pulls/12", "12", "OPEN");
    }

    @Test
    void anInstallIsReadBackAsItWasRecorded() throws Exception {
        recordGitOpsInstall();

        K8sReleaseEntity e = metadata().find(NS, "trino");

        assertNotNull(e);
        assertEquals(K8sReleaseEntity.idOf(NS, "trino"), e.getId());
        assertEquals("TRINO", e.getServiceKey());
        assertEquals("default", e.getPlatformContextId());
        assertEquals("trinodb/trino", e.getChartRef());
        assertEquals("charts", e.getRepoId());
        assertEquals("1.43.0", e.getVersion());
        assertEquals("op-install", e.getDeploymentId());
        assertEquals("global-1", e.getGlobalConfigVersion());
        assertEquals("corp-oidc", e.getSecurityProfile());
        assertEquals("hash-1", e.getSecurityProfileHash());
        assertEquals("FLUX_GITOPS", e.getDeploymentMode());
        assertEquals("0a1b2c3", e.getGitCommitSha());
        assertEquals("kdps/trino", e.getGitBranch());
        assertEquals("clusters/dev", e.getGitPath());
        assertEquals("https://git.example.org/platform.git", e.getGitRepoUrl());
        assertEquals("git.token.trino", e.getGitCredentialAlias());
        assertEquals("PR_MODE", e.getGitCommitMode());
        assertEquals("https://git.example.org/platform/pulls/12", e.getGitPrUrl());
        assertEquals("12", e.getGitPrNumber());
        assertEquals("OPEN", e.getGitPrState());
        List<Map<String, Object>> endpoints = new ObjectMapper().readValue(e.getEndpointsJson(),
                new TypeReference<List<Map<String, Object>>>() {});
        assertEquals("https://trino.example.org", endpoints.get(0).get("url"));
    }

    @Test
    void anUpgradeChangesWhatItSetsAndKeepsTheRest() {
        recordGitOpsInstall();

        metadata().recordInstallOrUpgrade(NS, "trino", null, null, null, null, "1.44.0", null, "op-upgrade", null,
                null, null, null, null, "4d5e6f7", null, null, null, null, null, null, null, null);

        K8sReleaseEntity e = metadata().find(NS, "trino");
        assertEquals("1.44.0", e.getVersion());
        assertEquals("op-upgrade", e.getDeploymentId());
        assertEquals("4d5e6f7", e.getGitCommitSha());
        assertEquals("TRINO", e.getServiceKey());
        assertEquals("trinodb/trino", e.getChartRef());
        assertEquals("charts", e.getRepoId());
        assertEquals("corp-oidc", e.getSecurityProfile());
        assertEquals("FLUX_GITOPS", e.getDeploymentMode(), "a GitOps release stays one");
        assertEquals("git.token.trino", e.getGitCredentialAlias());
        assertEquals("https://git.example.org/platform.git", e.getGitRepoUrl());
    }

    @Test
    void aDirectCommitForgetsThePullRequestOfAnEarlierChange() {
        recordGitOpsInstall();

        metadata().recordGitChange(NS, "trino", "main", "main", "DIRECT_COMMIT", null, null, null, "digest-2");

        K8sReleaseEntity e = metadata().find(NS, "trino");
        assertEquals("main", e.getGitBranch());
        assertEquals("main", e.getGitBaseBranch());
        assertEquals("DIRECT_COMMIT", e.getGitCommitMode());
        assertNull(e.getGitPrUrl());
        assertNull(e.getGitPrNumber());
        assertNull(e.getGitPrState());
        assertEquals("digest-2", e.getGitSpecDigest());
        assertEquals("git.token.trino", e.getGitCredentialAlias(), "the credential is not part of a change");
    }

    @Test
    void theTruststoresOfAReleaseAreKeptAcrossRequests() {
        recordGitOpsInstall();
        assertNull(metadata().truststoreRefs(NS, "trino"), "no choice recorded yet");

        metadata().recordTruststoreRefs(NS, "trino", List.of("analytics/corp-ca", "kdps/ambari-ca"));
        assertEquals(List.of("analytics/corp-ca", "kdps/ambari-ca"), metadata().truststoreRefs(NS, "trino"));

        metadata().recordTruststoreRefs(NS, "trino", null);
        assertEquals(List.of("analytics/corp-ca", "kdps/ambari-ca"), metadata().truststoreRefs(NS, "trino"),
                "a deploy that names none keeps them");

        metadata().recordTruststoreRefs(NS, "trino", List.of());
        assertEquals(List.of(), metadata().truststoreRefs(NS, "trino"), "the default truststores only: a choice too");
        assertEquals("0a1b2c3", metadata().find(NS, "trino").getGitCommitSha(), "kept beside the Git metadata");
    }

    @Test
    void aDeletedReleaseIsGone() {
        recordGitOpsInstall();

        metadata().delete(NS, "trino");

        assertNull(metadata().find(NS, "trino"));
        assertTrue(metadata().findAll().isEmpty());
    }
}
