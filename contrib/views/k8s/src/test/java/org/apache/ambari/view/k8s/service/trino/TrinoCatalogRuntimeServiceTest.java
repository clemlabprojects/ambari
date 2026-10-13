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
package org.apache.ambari.view.k8s.service.trino;

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.deployment.ReleaseHeldInGitException;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure helpers of the live catalog editor: SQL building, SHOW CREATE CATALOG parsing, provenance merge. */
class TrinoCatalogRuntimeServiceTest {

    @Test
    void buildsCreateCatalogWithQuotedProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("connector.name", "postgresql");
        p.put("connection-url", "jdbc:postgresql://db:5432/x?a=1");
        p.put("connection-user", "o'neil");
        String sql = TrinoCatalogRuntimeService.buildCreateCatalog("pg", "postgresql", p);
        assertEquals("CREATE CATALOG pg USING postgresql WITH (\"connection-url\" = 'jdbc:postgresql://db:5432/x?a=1', \"connection-user\" = 'o''neil')", sql);
        assertEquals("CREATE CATALOG m USING memory", TrinoCatalogRuntimeService.buildCreateCatalog("m", "memory", Map.of("connector.name", "memory")));
    }

    @Test
    void parsesPropertiesTextKeepingValuesWithEquals() {
        Map<String, String> m = TrinoCatalogRuntimeService.parseProperties("# c\nconnector.name=postgresql\n\nconnection-url = jdbc:x?y=z\nbad line\n");
        assertEquals(2, m.size());
        assertEquals("jdbc:x?y=z", m.get("connection-url"));
    }

    @Test
    void showCreateCatalogRoundTripsToProperties() {
        String ddl = "CREATE CATALOG pg USING postgresql\nWITH (\n   \"connection-url\" = 'jdbc:postgresql://db:5432/x',\n   \"connection-user\" = 'o''neil'\n)";
        String text = TrinoCatalogRuntimeService.propertiesFromShowCreate(ddl);
        assertEquals("connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/x\nconnection-user=o'neil", text);
        assertEquals("postgresql", TrinoCatalogRuntimeService.connectorOf(text));
        assertNull(TrinoCatalogRuntimeService.connectorOf("x=y"));
    }

    @Test
    void mergeClassifiesProvenance() {
        Map<String, String> persisted = new LinkedHashMap<>();
        persisted.put("hive", "connector.name=hive\nhive.metastore.uri=thrift://m:9083");
        persisted.put("pg", "connector.name=postgresql");
        persisted.put("staged", "connector.name=memory");           // persisted but not live yet (restart pending)
        Map<String, Map<String, String>> refs = Map.of("pg", Map.of("id", "abc", "hash", "h"));
        Map<String, String> rendered = new LinkedHashMap<>(persisted);
        rendered.put("tpch", "connector.name=tpch");                 // chart default: rendered, not in values
        List<TrinoCatalogRuntimeService.CatalogView> out = TrinoCatalogRuntimeService.merge(
                List.of("system", "hive", "pg", "probe_x", "tpch"), rendered, persisted, refs);
        Map<String, TrinoCatalogRuntimeService.CatalogView> by = new LinkedHashMap<>();
        for (TrinoCatalogRuntimeService.CatalogView v : out) by.put(v.name, v);
        assertEquals("builtin", by.get("system").source);
        assertEquals("managed", by.get("hive").source);
        assertEquals("hive", by.get("hive").connector);
        assertEquals("reusable", by.get("pg").source);
        assertEquals("abc", by.get("pg").reusableId);
        assertEquals("unmanaged", by.get("probe_x").source);
        assertTrue(by.get("probe_x").live && !by.get("probe_x").persisted);
        assertEquals("inline", by.get("staged").source);
        assertTrue(by.get("staged").persisted && !by.get("staged").live);
        assertEquals("default", by.get("tpch").source);
        assertTrue(by.get("tpch").persisted && by.get("tpch").live);
        assertEquals("tpch", by.get("tpch").connector);
    }

    @Test
    void readOnlyRowsKeepTheirProvenanceButNotTheirCredentials() {
        Map<String, String> persisted = new LinkedHashMap<>();
        persisted.put("pg", "connector.name=postgresql\nconnection-user=app\nconnection-password=s3cret");
        List<TrinoCatalogRuntimeService.CatalogView> rows = TrinoCatalogRuntimeService.withoutSecrets(
                TrinoCatalogRuntimeService.merge(List.of("system", "pg", "probe_x"), persisted, persisted, Map.of()));

        TrinoCatalogRuntimeService.CatalogView pg = rows.get(1);
        assertEquals("pg", pg.name);
        assertEquals("inline", pg.source);
        assertEquals("postgresql", pg.connector);
        assertEquals("connector.name=postgresql\nconnection-user=app\nconnection-password=********", pg.properties);
        assertNull(rows.get(2).properties, "a catalog only Trino knows has no properties to mask");
    }

    @Test
    void storeFileBecomesPropertiesTextWithConnectorFirst() {
        String raw = "#Sat Oct 03 09:07:21 UTC 2026\nmemory.max-data-per-node=16MB\nconnector.name=memory\nconnection-url=jdbc\\:postgresql\\://db\\:5432/x\n";
        assertEquals("connector.name=memory\nmemory.max-data-per-node=16MB\nconnection-url=jdbc:postgresql://db:5432/x",
                TrinoCatalogRuntimeService.propertiesFromStoreFile(raw));
    }

    @Test
    void shellQuotingSurvivesSingleQuotes() {
        assertEquals("'a'\\''b'", TrinoStatementClient.sq("a'b"));
    }

    /**
     * Saving a catalog upgrades the release with its values. Those of a release deployed by an earlier definition may
     * hold secrets it wrote into the server configuration, a ConfigMap: the upgrade must not render them again.
     */
    @Test
    void aSavedCatalogUpgradesTheReleaseWithoutTheSecretsAnEarlierDefinitionWroteIntoItsConfiguration() {
        TrinoCatalogRuntimeService.Target t = target("trino", "DIRECT_HELM");
        t.metadata.setServiceKey("TRINO");
        t.values = new LinkedHashMap<>();
        t.values.put("internalSharedSecret", "Typed-Shared-Secret-1");
        t.values.put("additionalConfigProperties", new ArrayList<>(List.of(
                "internal-communication.shared-secret=Typed-Shared-Secret-1", "http-server.process-forwarded=true")));
        Map<String, Object> coordinator = new LinkedHashMap<>();
        coordinator.put("additionalConfigProperties", new ArrayList<>(List.of(
                "http-server.https.keystore.key=Typed-Keystore-Pass-2", "query.max-history=500")));
        t.values.put("coordinator", coordinator);
        t.values.put("catalogs", new LinkedHashMap<>(Map.of("tpch", "connector.name=tpch")));

        Map<String, Object> values = TrinoCatalogRuntimeService.valuesWithCatalog(null, t, "pg",
                "connector.name=postgresql\nconnection-password=${ENV:PG_PASSWORD}");

        assertEquals(Map.of("tpch", "connector.name=tpch",
                "pg", "connector.name=postgresql\nconnection-password=${ENV:PG_PASSWORD}"), values.get("catalogs"));
        assertFalse(values.containsKey("internalSharedSecret"));
        assertEquals(List.of("http-server.process-forwarded=true"), values.get("additionalConfigProperties"));
        assertEquals(List.of("query.max-history=500"),
                ((Map<?, ?>) values.get("coordinator")).get("additionalConfigProperties"));
    }

    private static TrinoCatalogRuntimeService.Target target(String release, String deploymentMode) {
        TrinoCatalogRuntimeService.Target t = new TrinoCatalogRuntimeService.Target();
        t.namespace = "trino";
        t.release = release;
        t.metadata = new K8sReleaseEntity();
        t.metadata.setDeploymentMode(deploymentMode);
        return t;
    }

    /** A GitOps Trino release as KDPS records it, deployed through a pull request into production. */
    private static TrinoCatalogRuntimeService.Target gitOpsTarget() {
        TrinoCatalogRuntimeService.Target t = target("trino-flux", "FLUX_GITOPS");
        t.metadata.setNamespace("trino");
        t.metadata.setReleaseName("trino-flux");
        t.metadata.setChartRef("clemlab-trino");
        t.metadata.setRepoId("charts");
        t.metadata.setVersion("1.43.15");
        t.metadata.setServiceKey("TRINO");
        t.metadata.setSecurityProfile("ldap");
        t.metadata.setPlatformContextId("ctx-prod");
        t.metadata.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        t.metadata.setGitBaseBranch("production");
        t.metadata.setGitCommitMode("PR_MODE");
        t.metadata.setGitSpecDigest(COMMITTED_DIGEST);
        t.values = new LinkedHashMap<>();
        t.values.put("catalogs", new LinkedHashMap<>(Map.of("tpch", "connector.name=tpch")));
        return t;
    }

    /** The spec digest of the HelmRelease the last change of the GitOps release committed. */
    private static final String COMMITTED_DIGEST = "5e1d0c";

    /** The cluster, where Flux has applied the last commit of the GitOps release and reports it Ready. */
    private static KubernetesService fluxApplied() {
        KubernetesService kubernetes = mock(KubernetesService.class);
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenReturn(TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "True", "UpgradeSucceeded"));
        return kubernetes;
    }

    @Test
    void aCatalogSavedToAFluxManagedReleaseIsCommittedToGitNotUpgradedWithHelm() {
        List<HelmDeployRequest> submitted = new ArrayList<>();
        TrinoCatalogRuntimeService service = new TrinoCatalogRuntimeService(null, fluxApplied(), request -> {
            submitted.add(request);
            return "op-1";
        });

        Map<String, Object> out = service.persistCatalog(gitOpsTarget(), "pg", "connector.name=postgresql");

        assertEquals(Map.of("committedToGit", true, "commandId", "op-1"), out);
        assertEquals(1, submitted.size());
        HelmDeployRequest request = submitted.get(0);
        assertEquals("FLUX_GITOPS", request.getDeploymentMode());
        assertEquals("trino", request.getNamespace());
        assertEquals("trino-flux", request.getReleaseName());
        assertEquals("clemlab-trino", request.getChart());
        assertEquals("charts", request.getRepoId());
        assertEquals("TRINO", request.getServiceKey());
        assertEquals("ldap", request.getSecurityProfile());
        assertEquals(Map.of("platformContextId", "ctx-prod"), request.getFormValues());
        assertNull(request.getVersion(), "the deploy keeps the chart version the release runs");
        assertEquals("https://git.example.org/platform/gitops.git", request.getGit().getRepoUrl());
        assertEquals("production", request.getGit().getBaseBranch(), "where Flux reads the release");
        assertEquals("PR_MODE", request.getGit().getCommitMode(), "through a pull request, as it was deployed");
        assertEquals(Map.of("tpch", "connector.name=tpch", "pg", "connector.name=postgresql"),
                request.getValues().get("catalogs"));
    }

    @Test
    void aCatalogForgottenOnAFluxManagedReleaseIsRemovedThroughGit() {
        List<HelmDeployRequest> submitted = new ArrayList<>();
        TrinoCatalogRuntimeService service = new TrinoCatalogRuntimeService(null, fluxApplied(), request -> {
            submitted.add(request);
            return "op-2";
        });

        Map<String, Object> out = service.persistCatalog(gitOpsTarget(), "tpch", null);

        assertEquals("op-2", out.get("commandId"));
        assertEquals(Map.of(), submitted.get(0).getValues().get("catalogs"));
    }

    /** Commits the values of a GitOps release as the Releases page would, and records the deploys still running. */
    private static class RecordingRedeploy implements TrinoCatalogRuntimeService.GitOpsRedeploy {
        final List<HelmDeployRequest> submitted = new ArrayList<>();
        String running;

        @Override
        public String submit(HelmDeployRequest request) {
            submitted.add(request);
            running = "op-" + submitted.size();
            return running;
        }

        @Override
        public String runningDeploy(String namespace, String release) {
            return running;
        }
    }

    /** The release once a first catalog saved to it opened its pull request, which is not merged yet. */
    private static TrinoCatalogRuntimeService.Target gitOpsTargetWithOpenPullRequest() {
        TrinoCatalogRuntimeService.Target t = gitOpsTarget();
        t.metadata.setGitPrUrl("https://git.example.org/platform/gitops/pulls/12");
        t.metadata.setGitPrNumber("12");
        t.metadata.setGitPrState("open");
        t.dynamicCatalogs = true;
        return t;
    }

    @Test
    void aSecondCatalogIsRefusedBeforeAnythingLiveWhileTheFirstOneWaitsForItsPullRequest() {
        KubernetesService kubernetes = fluxApplied();
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        TrinoCatalogRuntimeService.Target first = gitOpsTarget();
        first.dynamicCatalogs = true;
        service.persistCatalog(first, "a", "connector.name=tpch");
        assertEquals(1, redeploy.submitted.size());

        // Helm still reports the values without catalog 'a': building on them would take 'a' out of the pull request.
        doReturn(gitOpsTargetWithOpenPullRequest()).when(service).resolve("trino", "trino-flux");
        ReleaseHeldInGitException refused = assertThrows(ReleaseHeldInGitException.class,
                () -> service.create("trino", "trino-flux", "b", "connector.name=tpch", true, "alice"));

        assertTrue(refused.getMessage().contains("pull request https://git.example.org/platform/gitops/pulls/12 is"
                + " not merged yet"), refused.getMessage());
        assertEquals(1, redeploy.submitted.size(), "nothing more is committed");
        verify(kubernetes, never()).getClient();
    }

    @Test
    void aCatalogIsRefusedBeforeAnythingLiveWhileADeployOfTheReleaseIsStillRunning() {
        KubernetesService kubernetes = fluxApplied();
        RecordingRedeploy redeploy = new RecordingRedeploy();
        redeploy.running = "op-9";
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        TrinoCatalogRuntimeService.Target t = gitOpsTarget();
        t.metadata.setGitCommitMode("DIRECT_COMMIT");
        t.dynamicCatalogs = true;
        doReturn(t).when(service).resolve("trino", "trino-flux");

        ReleaseHeldInGitException refused = assertThrows(ReleaseHeldInGitException.class,
                () -> service.create("trino", "trino-flux", "b", "connector.name=tpch", true, "alice"));

        assertTrue(refused.getMessage().contains("operation op-9"), refused.getMessage());
        assertTrue(redeploy.submitted.isEmpty());
        verify(kubernetes, never()).getClient();
        // A catalog not saved to the release changes nothing in Git.
        redeploy.running = null;
        assertDoesNotThrow(() -> service.refuseWhileGitChangePending(t));
    }

    @Test
    void aCatalogAPendingPullRequestAddsIsNotForgottenBeforeItIsMerged() {
        KubernetesService kubernetes = mock(KubernetesService.class);
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        doReturn(gitOpsTargetWithOpenPullRequest()).when(service).resolve("trino", "trino-flux");

        // 'a' is in the pull request only, not in the values Helm reports.
        ReleaseHeldInGitException refused = assertThrows(ReleaseHeldInGitException.class,
                () -> service.drop("trino", "trino-flux", "a", true, "alice"));

        assertTrue(refused.getMessage().contains("That change may save catalog 'a', which would then come back once"
                + " it is applied"), refused.getMessage());
        assertTrue(redeploy.submitted.isEmpty());
        verify(kubernetes, never()).getClient();
    }

    @Test
    void aCatalogNotSavedToAFluxManagedReleaseIsDroppedLiveOnceGitHoldsNoPendingChange() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService service = editorOn(directCommitNotApplied(), fluxApplied(), trino, redeploy);

        Map<String, Object> out = service.drop("trino", "trino-flux", "scratch", true, "alice");

        assertEquals(Map.of("name", "scratch", "live", false), out);
        verify(trino).execute("DROP CATALOG scratch", "alice");
        assertTrue(redeploy.submitted.isEmpty(), "nothing to remove from Git");
    }

    @Test
    void twoCatalogsSavedToAFluxManagedReleaseAtOnceAreNotBothCommitted() throws Exception {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        CountDownLatch submitting = new CountDownLatch(1);
        CountDownLatch stored = new CountDownLatch(1);
        // The first deploy is stored, and seen by later checks, only once its submission returns.
        RecordingRedeploy redeploy = new RecordingRedeploy() {
            @Override
            public String submit(HelmDeployRequest request) {
                submitting.countDown();
                try {
                    assertTrue(stored.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.submit(request);
            }
        };
        TrinoCatalogRuntimeService service = editorOn(directCommitNotApplied(), fluxApplied(), trino, redeploy);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> first = pool.submit(
                    () -> service.create("trino", "trino-flux", "a", "connector.name=tpch", true, "alice"));
            assertTrue(submitting.await(30, TimeUnit.SECONDS));

            // Both would pass the check for a running deploy: the second is refused before anything live.
            assertThrows(org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend.GitOpsBusyException.class,
                    () -> service.create("trino", "trino-flux", "b", "connector.name=tpch", true, "bob"));
            assertThrows(org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend.GitOpsBusyException.class,
                    () -> service.drop("trino", "trino-flux", "tpch", true, "bob"));
            assertThrows(org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend.GitOpsBusyException.class,
                    () -> service.adopt("trino", "trino-flux", "probe_x", "bob"));
            // A catalog not saved to the release commits nothing.
            assertDoesNotThrow(() -> service.create("trino", "trino-flux", "scratch", "connector.name=memory", false,
                    "bob"));

            stored.countDown();
            assertEquals("op-1", first.get(30, TimeUnit.SECONDS).get("commandId"));
        } finally {
            stored.countDown();
            pool.shutdownNow();
        }
        verify(trino, never()).execute(org.mockito.ArgumentMatchers.startsWith("CREATE CATALOG b "), anyString());
        verify(trino, never()).execute(org.mockito.ArgumentMatchers.startsWith("DROP CATALOG"), anyString());
        assertEquals(1, redeploy.submitted.size(), "only the first is committed");

        // Once the first is submitted, its deploy is what refuses the next one until Flux has applied it.
        ReleaseHeldInGitException refused = assertThrows(ReleaseHeldInGitException.class,
                () -> service.create("trino", "trino-flux", "b", "connector.name=tpch", true, "bob"));
        assertTrue(refused.getMessage().contains("operation op-1"), refused.getMessage());
    }

    @Test
    void aCatalogIsRefusedBeforeAnythingLiveWhileTheRemovalOfTheReleaseWaitsForItsPullRequest() {
        KubernetesService kubernetes = mock(KubernetesService.class);
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        TrinoCatalogRuntimeService.Target t = gitOpsTargetWithOpenPullRequest();
        t.metadata.setGitPendingDelete(true);
        doReturn(t).when(service).resolve("trino", "trino-flux");

        // A deploy of the release would be refused at its commit, after the catalog was created live.
        ReleaseHeldInGitException refused = assertThrows(ReleaseHeldInGitException.class,
                () -> service.create("trino", "trino-flux", "b", "connector.name=tpch", true, "alice"));
        assertTrue(refused.getMessage().contains("its removal is proposed in pull request"), refused.getMessage());
        assertThrows(ReleaseHeldInGitException.class, () -> service.drop("trino", "trino-flux", "tpch", true, "alice"));
        assertThrows(ReleaseHeldInGitException.class, () -> service.adopt("trino", "trino-flux", "probe_x", "alice"));

        assertTrue(redeploy.submitted.isEmpty());
        verify(kubernetes, never()).getClient();
    }

    @Test
    void aCatalogWhoseCommitCouldNotBeSubmittedAndThatCouldNotBeDroppedAgainIsSaidToBeLiveOnly() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        TrinoStatementClient.Result denied = new TrinoStatementClient.Result();
        denied.error = "Access Denied: Cannot drop catalog pg";
        when(trino.execute(anyString(), anyString())).thenAnswer(inv -> ((String) inv.getArgument(0))
                .startsWith("DROP") ? denied : new TrinoStatementClient.Result());
        TrinoCatalogRuntimeService.Target t = gitOpsTarget();
        t.metadata.setGitCommitMode("DIRECT_COMMIT");
        t.dynamicCatalogs = true;
        TrinoCatalogRuntimeService service = TrinoCatalogEditors.on(t, trino, request -> {
            throw new IllegalArgumentException("The security profile 'ldap' does not exist");
        });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create("trino", "trino-flux", "pg", "connector.name=postgresql", true, "alice"));

        assertTrue(e.getMessage().startsWith("Catalog 'pg' was created in Trino, but it could not be committed to Git"
                + " (The security profile 'ldap' does not exist), and dropping it again failed (Access Denied: Cannot"
                + " drop catalog pg). It is live only"), e.getMessage());
        verify(trino).execute("DROP CATALOG pg", "alice");
    }

    @Test
    void aCatalogOfAReleaseDeployedWithHelmIsNotDroppedAgainWhenItsUpgradeFails() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        TrinoCatalogRuntimeService.Target t = target("trino-direct", "DIRECT_HELM");
        t.dynamicCatalogs = true;
        TrinoCatalogRuntimeService service = TrinoCatalogEditors.on(t, trino, null);

        // No chart recorded: the upgrade is refused after the catalog was created, as it was before.
        assertThrows(IllegalStateException.class,
                () -> service.create("trino", "trino-direct", "pg", "connector.name=postgresql", true, "alice"));
        verify(trino, never()).execute("DROP CATALOG pg", "alice");
    }

    @Test
    void onlyAPullRequestNotMergedOrACommitNotPushedIsAChangePendingInGit() {
        K8sReleaseEntity meta = gitOpsTargetWithOpenPullRequest().metadata;
        assertTrue(TrinoCatalogRuntimeService.gitChangePending(meta).contains("not merged yet"));

        meta.setGitPrState("merged");
        assertNull(TrinoCatalogRuntimeService.gitChangePending(meta));
        meta.setGitPrState("closed");
        assertNull(TrinoCatalogRuntimeService.gitChangePending(meta), "the release runs what the base branch holds");

        meta.setGitCommitSha("PR-STUB:9b1e2d4");
        assertTrue(TrinoCatalogRuntimeService.gitChangePending(meta).contains("not pushed"));

        meta.setGitCommitSha("3f0c9a5");
        meta.setGitPrState("open");
        meta.setGitCommitMode("DIRECT_COMMIT");
        assertNull(TrinoCatalogRuntimeService.gitChangePending(meta), "a direct commit opens no pull request");

        // Its removal proposed in a pull request still open: Flux still runs it, but a deploy of it is refused.
        meta.setGitPendingDelete(true);
        meta.setGitPrUrl("https://git.example.org/platform/gitops/pulls/14");
        meta.setGitPrNumber("14");
        meta.setGitPrState("open");
        assertEquals("its removal is proposed in pull request https://git.example.org/platform/gitops/pulls/14, not"
                + " merged yet", TrinoCatalogRuntimeService.gitChangePending(meta));
        meta.setGitPrState("closed");
        assertNull(TrinoCatalogRuntimeService.gitChangePending(meta), "the removal was withdrawn: it can be deployed");

        assertNull(TrinoCatalogRuntimeService.gitChangePending(target("trino-direct", "DIRECT_HELM").metadata));
        assertNull(TrinoCatalogRuntimeService.gitChangePending(null));
    }

    @Test
    void aCatalogIsNotSavedToAFluxManagedReleaseWithoutAWayToCommitIt() {
        TrinoCatalogRuntimeService service = new TrinoCatalogRuntimeService(null, null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.persistCatalog(gitOpsTarget(), "pg", "connector.name=postgresql"));
        assertTrue(e.getMessage().contains("Upgrade/Config, which commits it to Git"), e.getMessage());
    }

    @Test
    void aFluxManagedReleaseMayHaveItsCatalogsSaved() {
        assertDoesNotThrow(() -> TrinoCatalogRuntimeService.requirePersistable(target("trino-flux", "FLUX_GITOPS")),
                "its catalogs are committed to Git");
        assertTrue(TrinoCatalogRuntimeService.heldInGit(target("trino-flux", "FLUX_GITOPS")));
        assertFalse(TrinoCatalogRuntimeService.heldInGit(target("trino-direct", "DIRECT_HELM")));
        assertFalse(TrinoCatalogRuntimeService.heldInGit(target("trino-legacy", null)),
                "releases recorded before deployment modes existed were deployed directly");
        assertDoesNotThrow(() -> TrinoCatalogRuntimeService.requirePersistable(target("trino-direct", "DIRECT_HELM")));
    }

    @Test
    void catalogsAreNotSavedWhileAHelmOperationOnTheReleaseRuns() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch helmReturns = new CountDownLatch(1);
        try {
            Future<?> upgrade = HelmOperationRegistry.submit("trino", "trino-busy", pool, () -> {
                running.countDown();
                return helmReturns.await(30, TimeUnit.SECONDS);
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertThrows(HelmOperationRegistry.ReleaseBusyException.class,
                    () -> TrinoCatalogRuntimeService.requirePersistable(target("trino-busy", "DIRECT_HELM")));
            helmReturns.countDown();
            upgrade.get(5, TimeUnit.SECONDS);
        } finally {
            helmReturns.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * A release whose last change was committed straight to the branch Flux reads, by a deploy that ended before Flux
     * applied it: its upgrade failed and Flux rolled it back, or the wait for Flux timed out.
     */
    private static TrinoCatalogRuntimeService.Target directCommitNotApplied() {
        TrinoCatalogRuntimeService.Target t = gitOpsTarget();
        t.metadata.setGitCommitMode("DIRECT_COMMIT");
        t.dynamicCatalogs = true;
        return t;
    }

    /** The live catalog editor of {@code t}, on a cluster where the HelmRelease of the release reads as given. */
    private static TrinoCatalogRuntimeService editorOn(TrinoCatalogRuntimeService.Target t, KubernetesService kubernetes,
                                                      TrinoStatementClient trino, RecordingRedeploy redeploy) {
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        doReturn(t).when(service).resolve(t.namespace, t.release);
        doReturn(trino).when(service).clientFor(org.mockito.ArgumentMatchers.any());
        return service;
    }

    @Test
    void aCommitFluxHasNotAppliedRefusesEveryCatalogSavedToTheReleaseBeforeAnythingLive() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        RecordingRedeploy redeploy = new RecordingRedeploy();
        KubernetesService kubernetes = mock(KubernetesService.class);
        TrinoCatalogRuntimeService service = editorOn(directCommitNotApplied(), kubernetes, trino, redeploy);

        // Flux applied the commit, but the upgrade failed and was rolled back: Helm reports the values from before.
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenReturn(TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "False", "UpgradeFailed"));
        assertRefusedBeforeAnythingLive(service, "UpgradeFailed");

        // Flux has not applied the commit at all: the HelmRelease on the cluster is the previous version.
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenReturn(TrinoCatalogEditors.appliedHelmRelease("0ld0", "True", "UpgradeSucceeded"));
        assertRefusedBeforeAnythingLive(service, "still the previous version");

        // Nothing tells what Flux applied.
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenThrow(new IllegalStateException("helmreleases is forbidden"));
        assertRefusedBeforeAnythingLive(service, "helmreleases is forbidden");

        verify(trino, never()).execute(anyString(), anyString());
        assertTrue(redeploy.submitted.isEmpty(), "nothing is committed");
        // A catalog not saved to the release changes nothing in Git.
        assertDoesNotThrow(() -> service.create("trino", "trino-flux", "scratch", "connector.name=memory", false,
                "alice"));
        verify(trino).execute("CREATE CATALOG scratch USING memory", "alice");
    }

    @Test
    void aPullRequestClosedWithoutBeingMergedNoLongerHoldsTheCatalogsOfTheRelease() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService.Target t = gitOpsTargetWithOpenPullRequest();
        t.metadata.setGitPrState("closed");
        // The cluster runs what the base branch holds, not the version the closed pull request proposed.
        KubernetesService kubernetes = mock(KubernetesService.class);
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenReturn(TrinoCatalogEditors.appliedHelmRelease("0ld0", "True", "UpgradeSucceeded"));
        TrinoCatalogRuntimeService service = editorOn(t, kubernetes, trino, redeploy);

        assertEquals(Map.of("name", "scratch", "live", false),
                service.drop("trino", "trino-flux", "scratch", true, "alice"), "a catalog not saved to the release");
        verify(trino).execute("DROP CATALOG scratch", "alice");
        assertEquals("op-1", service.create("trino", "trino-flux", "pg", "connector.name=postgresql", true, "alice")
                .get("commandId"));
        assertEquals(1, redeploy.submitted.size());

        // While it is open, the pull request is still a change Flux has not applied.
        assertEquals(COMMITTED_DIGEST, TrinoCatalogRuntimeService.digestStillToApply(
                gitOpsTargetWithOpenPullRequest().metadata));
        K8sReleaseEntity merged = gitOpsTargetWithOpenPullRequest().metadata;
        merged.setGitPrState("merged");
        assertEquals(COMMITTED_DIGEST, TrinoCatalogRuntimeService.digestStillToApply(merged));
        assertNull(TrinoCatalogRuntimeService.digestStillToApply(t.metadata));
        assertNull(TrinoCatalogRuntimeService.digestStillToApply(null));
    }

    @Test
    void aCommitRevertedInGitIsRefusedWithHowToRecordWhatGitHoldsNow() {
        TrinoStatementClient trino = mock(TrinoStatementClient.class);
        when(trino.execute(anyString(), anyString())).thenReturn(new TrinoStatementClient.Result());
        RecordingRedeploy redeploy = new RecordingRedeploy();
        TrinoCatalogRuntimeService.Target t = directCommitNotApplied();
        // The commit KDPS made was reverted in Git: Flux runs the previous version again, and will not apply it.
        KubernetesService kubernetes = mock(KubernetesService.class);
        when(kubernetes.getFluxHelmRelease("trino", "trino-flux"))
                .thenReturn(TrinoCatalogEditors.appliedHelmRelease("0ld0", "True", "UpgradeSucceeded"));
        TrinoCatalogRuntimeService service = editorOn(t, kubernetes, trino, redeploy);

        assertRefusedBeforeAnythingLive(service, "redeploy the release with Upgrade/Config");
        ReleaseHeldInGitException dropped = assertThrows(ReleaseHeldInGitException.class,
                () -> service.drop("trino", "trino-flux", "scratch", true, "alice"));
        assertTrue(dropped.getMessage().contains("Upgrade/Config"), dropped.getMessage());
        verify(trino, never()).execute(anyString(), anyString());

        // Upgrade/Config records the version Git holds now, which Flux runs: the catalogs are saved again.
        t.metadata.setGitSpecDigest("0ld0");
        assertEquals("op-1", service.create("trino", "trino-flux", "pg", "connector.name=postgresql", true, "alice")
                .get("commandId"));

        // A HelmRelease that is not Ready says what Flux reports, with no mention of a revert.
        assertFalse(TrinoCatalogRuntimeService.fluxNotApplied(TrinoCatalogEditors.appliedHelmRelease(
                COMMITTED_DIGEST, "False", "UpgradeFailed"), COMMITTED_DIGEST).contains("Upgrade/Config"));
        assertFalse(TrinoCatalogRuntimeService.fluxNotApplied(null, COMMITTED_DIGEST).contains("Upgrade/Config"));
    }

    private static void assertRefusedBeforeAnythingLive(TrinoCatalogRuntimeService service, String cause) {
        ReleaseHeldInGitException created = assertThrows(ReleaseHeldInGitException.class,
                () -> service.create("trino", "trino-flux", "pg", "connector.name=postgresql", true, "alice"));
        assertTrue(created.getMessage().contains(cause), created.getMessage());
        assertThrows(ReleaseHeldInGitException.class, () -> service.drop("trino", "trino-flux", "tpch", true, "alice"));
        assertThrows(ReleaseHeldInGitException.class, () -> service.adopt("trino", "trino-flux", "probe_x", "alice"));
    }

    @Test
    void theCatalogsOfAReleaseAreCommittedOnlyOnceFluxRunsWhatGitHolds() {
        assertNull(TrinoCatalogRuntimeService.fluxNotApplied(
                TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "True", "UpgradeSucceeded"), COMMITTED_DIGEST));
        assertNull(TrinoCatalogRuntimeService.fluxNotApplied(
                TrinoCatalogEditors.appliedHelmRelease("any", "True", "UpgradeSucceeded"), null),
                "no digest recorded (deployed by an earlier version): the version on the cluster");

        assertTrue(TrinoCatalogRuntimeService.fluxNotApplied(null, COMMITTED_DIGEST).contains("not on the cluster"));
        assertTrue(TrinoCatalogRuntimeService.fluxNotApplied(
                TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "False", "DependencyNotReady"),
                COMMITTED_DIGEST).contains("DependencyNotReady"));

        // Ready for an earlier generation only: Flux has not reconciled the latest change of the HelmRelease.
        io.fabric8.kubernetes.api.model.GenericKubernetesResource stale =
                TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "True", "UpgradeSucceeded");
        stale.getMetadata().setGeneration(3L);
        assertNotNullReason(TrinoCatalogRuntimeService.fluxNotApplied(stale, COMMITTED_DIGEST));

        // The chart revision Flux last attempted is not the one the release runs.
        io.fabric8.kubernetes.api.model.GenericKubernetesResource attempted =
                TrinoCatalogEditors.appliedHelmRelease(COMMITTED_DIGEST, "True", "UpgradeSucceeded");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) attempted.get("status");
        status.put("lastAttemptedRevision", "1.43.16");
        status.put("lastAppliedRevision", "1.43.15");
        assertEquals("Flux last attempted chart revision 1.43.16 but the release runs 1.43.15",
                TrinoCatalogRuntimeService.fluxNotApplied(attempted, COMMITTED_DIGEST));
    }

    private static void assertNotNullReason(String reason) {
        assertTrue(reason != null && !reason.isBlank(), String.valueOf(reason));
    }
}
