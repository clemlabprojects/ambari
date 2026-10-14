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
import org.apache.ambari.view.k8s.model.TrinoCatalogDTO;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.ProvisioningLedger;
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

    /**
     * The DataStore leaves a column alone when it is given null: an Upgrade/Config that deselects every reusable
     * catalog must still detach them, so the catalog can be deleted and is not selected again at the next upgrade.
     */
    @Test
    void deselectingEveryReusableCatalogDetachesThemAndLetsThemBeDeleted() {
        recordGitOpsInstall();
        TrinoCatalogDTO pg = new TrinoCatalogDTO();
        pg.name = "pg_finance";
        pg.propertiesText = "connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/finance\n";
        String id = new TrinoCatalogService(view.context).save(pg).id;
        metadata().recordCatalogRefs(NS, "trino",
                TrinoCatalogService.toRefsJson(Map.of("pg_finance", Map.of("id", id, "hash", "abc"))));
        IllegalStateException attached = assertThrows(IllegalStateException.class,
                () -> new TrinoCatalogService(view.context).delete(id));
        assertTrue(attached.getMessage().contains("analytics/trino"), attached.getMessage());

        // What the deploy records once the wizard selects none: the refs of an empty selection, as a string.
        metadata().recordCatalogRefs(NS, "trino", TrinoCatalogService.toRefsJson(Map.of()));
        assertEquals("{}", metadata().find(NS, "trino").getCatalogRefsJson());
        metadata().recordCatalogRefs(NS, "trino", "");
        assertEquals("{}", metadata().find(NS, "trino").getCatalogRefsJson());

        assertDoesNotThrow(() -> new TrinoCatalogService(view.context).delete(id));
        assertNull(new TrinoCatalogService(view.context).findEntity(id));
    }

    @Test
    void aCatalogForgottenOnTheReleaseIsDetachedAndTheOthersStay() {
        recordGitOpsInstall();
        metadata().recordCatalogRefs(NS, "trino", TrinoCatalogService.toRefsJson(new java.util.LinkedHashMap<>(Map.of(
                "pg_finance", Map.of("id", "cat-1", "hash", "abc"), "events", Map.of("id", "cat-2", "hash", "def")))));

        metadata().forgetCatalogRef(NS, "trino", "pg_finance");
        assertEquals(java.util.Set.of("events"),
                TrinoCatalogService.parseRefs(metadata().find(NS, "trino").getCatalogRefsJson()).keySet());

        metadata().forgetCatalogRef(NS, "trino", "events");
        assertEquals("{}", metadata().find(NS, "trino").getCatalogRefsJson());
        assertDoesNotThrow(() -> metadata().forgetCatalogRef(NS, "trino", "unknown"));
        assertDoesNotThrow(() -> metadata().forgetCatalogRef(NS, "gone", "events"));
    }

    @Test
    void aDeletedReleaseIsGone() {
        recordGitOpsInstall();

        metadata().delete(NS, "trino");

        assertNull(metadata().find(NS, "trino"));
        assertTrue(metadata().findAll().isEmpty());
    }
    @Test
    void theObjectsKdpsCreatedAreRecordedAndAClusterBindingIsNeverForgotten() {
        recordGitOpsInstall();

        metadata().recordKdpsObjects(NS, "trino", List.of("Secret/trino-keytab",
                "ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"));
        assertEquals(List.of("Secret/trino-keytab", "ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"),
                metadata().find(NS, "trino").getKdpsObjects());

        // A later deploy by an account that may not list cluster-scoped objects finds none: the binding is still
        // there, and still recorded.
        metadata().recordKdpsObjects(NS, "trino", List.of("Secret/trino-keytab", "Secret/trino-truststore"));
        K8sReleaseEntity e = metadata().find(NS, "trino");
        assertEquals(List.of("Secret/trino-keytab", "Secret/trino-truststore",
                "ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"), e.getKdpsObjects());
        assertEquals("kdps/trino", e.getGitBranch(), "the rest of the metadata is left as it is");

        metadata().recordKdpsObjects(NS, "trino", null);
        assertEquals(3, metadata().find(NS, "trino").getKdpsObjects().size(), "null changes nothing");
    }
    private static final ProvisioningLedger.OidcClient CLIENT = new ProvisioningLedger.OidcClient("trino-analytics", "odp", null);

    @Test
    void whatADeployRegisteredOutsideKubernetesIsReadBackAndSurvivesAnUpgrade() {
        recordGitOpsInstall();
        metadata().recordProvisioning(NS, "trino", ledger -> ledger.addOidcClient(CLIENT));
        metadata().recordProvisioning(NS, "trino", ledger -> ledger.addVaultPath("secret/data/oidc/trino"));

        metadata().recordInstallOrUpgrade(NS, "trino", null, null, null, null, "1.44.0", null, "op-upgrade", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        ProvisioningLedger read = metadata().provisioningOf(NS, "trino");
        assertEquals(List.of(CLIENT), read.oidcClients());
        assertEquals(List.of("secret/data/oidc/trino"), read.vaultPaths());
        assertEquals("kdps/trino", metadata().find(NS, "trino").getGitBranch(), "the Git metadata is untouched");
    }

    @Test
    void whatWasRegisteredBeforeTheReleaseWasRecordedComesWithItsRecord() {
        // A GitOps release is recorded by its commit, after the steps that register it in Keycloak.
        metadata().recordProvisioning(NS, "trino", ledger -> ledger.addOidcClient(CLIENT));
        assertNull(metadata().find(NS, "trino"));
        assertEquals(List.of(CLIENT), metadata().provisioningOf(NS, "trino").oidcClients());

        recordGitOpsInstall();

        assertEquals(List.of(CLIENT), metadata().find(NS, "trino").provisioningLedger().oidcClients());
        metadata().delete(NS, "trino");
        assertTrue(metadata().provisioningOf(NS, "trino").isEmpty(), "nothing is left once the release is deleted");
    }

    @Test
    void theOtherReleasesLedgersLeaveOutTheReleaseItself() {
        recordGitOpsInstall();
        metadata().recordInstallOrUpgrade(NS, "om", "OPENMETADATA", null, "om", null, "1.0", null, null, null,
                null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null, null, null);
        metadata().recordProvisioning(NS, "trino", ledger -> ledger.addOidcClient(CLIENT));
        metadata().recordProvisioning(NS, "om", ledger -> ledger.addPrincipal(
                new ProvisioningLedger.Principal("oma-ing-analytics@EXAMPLE.COM", "om-keytab")));

        Map<String, ProvisioningLedger> others = metadata().otherReleasesProvisioning(NS, "trino");

        assertEquals(List.of(NS + "/om"), List.copyOf(others.keySet()));
    }

    @Test
    void aReleaseDeployedBeforeLedgersIsListedAsUnrecorded() throws Exception {
        recordGitOpsInstall();
        K8sReleaseEntity earlier = new K8sReleaseEntity();
        earlier.setId(K8sReleaseEntity.idOf(NS, "om-old"));
        earlier.setNamespace(NS);
        earlier.setReleaseName("om-old");
        view.dataStore.store(earlier);
        metadata().recordInstallOrUpgrade(NS, "superset", "SUPERSET", null, "superset", null, "1.0", null, null, null,
                null, null, null, "DIRECT_HELM", null, null, null, null, null, null, null, null, null);

        Map<String, ProvisioningLedger> others = metadata().otherReleasesProvisioning(NS, "trino");

        assertEquals(List.of(NS + "/om-old"), List.copyOf(others.keySet()),
                "a release recorded since ledgers exist that registered nothing is not listed");
        assertTrue(others.get(NS + "/om-old").isUnrecorded());
    }
}
