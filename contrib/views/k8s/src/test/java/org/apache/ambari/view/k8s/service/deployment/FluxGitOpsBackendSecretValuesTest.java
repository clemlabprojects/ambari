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

package org.apache.ambari.view.k8s.service.deployment;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GitOps deploys against a local bare repository: what is committed, what goes to the values Secret, and the
 * fail-closed guard.
 */
public class FluxGitOpsBackendSecretValuesTest {

    private static final String BIND_PASSWORD = "Bind-Pass-42";
    private static final String POLARIS_SECRET = "Polaris-Secret-9";
    private static final String RELEASE_DIR = "clusters/default/analytics/trino";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ViewContext ctx;
    private DataStore ds;
    private KubernetesService k8s;
    private HelmRepoEntity chartRepo;
    private Path bareRepo;
    private String repoUrl;
    private Path fluxRoot;
    private String seedCommit;
    /** The values Secrets as written, by namespace/name. */
    private final Map<String, String> secretPayloads = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> secretLabels = new ConcurrentHashMap<>();
    /** The last commit of the remote when each Secret was written. */
    private final Map<String, String> remoteHeadAtSecretWrite = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        bareRepo = tmp.getRoot().toPath().resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareRepo.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bare = Git.open(bareRepo.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bare.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
        repoUrl = bareRepo.toUri().toString();
        Path seed = tmp.newFolder("seed").toPath();
        try (Git g = Git.cloneRepository().setURI(repoUrl).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            seedCommit = g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call().getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        fluxRoot = tmp.newFolder("flux").toPath();

        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.newFolder("work").toString()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getInstanceData(anyString())).thenReturn(null);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);

        chartRepo = new HelmRepoEntity();
        chartRepo.setId("charts");
        chartRepo.setType("HTTP");
        chartRepo.setUrl("https://charts.example.com");
        chartRepo.setAuthMode("anonymous");
        when(ds.find(eq(HelmRepoEntity.class), eq("charts"))).thenAnswer(inv -> chartRepo);
        useLdapProfile(BIND_PASSWORD);

        k8s = mock(KubernetesService.class);
        when(k8s.readOpaqueSecretKeyAsString(anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(secretPayloads.get(inv.getArgument(0) + "/" + inv.getArgument(1))));
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "/" + inv.getArgument(1);
            secretPayloads.put(key, new String((byte[]) inv.getArgument(3), StandardCharsets.UTF_8));
            secretLabels.put(key, inv.getArgument(4));
            remoteHeadAtSecretWrite.put(key, remoteHead());
            return null;
        }).when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class), anyMap(), any(), any());
        doAnswer(inv -> {
            secretPayloads.remove(inv.getArgument(0) + "/" + inv.getArgument(1));
            return null;
        }).when(k8s).deleteSecret(anyString(), anyString());
    }

    private void useLdapProfile(String bindPassword) throws Exception {
        SecurityProfileEntity profile = new SecurityProfileEntity();
        profile.setId("corp-ldap");
        profile.setViewInstance("kdps");
        profile.setDefault(true);
        profile.setProfileJson("{\"mode\":\"ldap\",\"ldap\":{\"url\":\"ldap://dir.example.com\","
                + "\"bindDn\":\"cn=kdps,dc=example,dc=com\",\"bindPassword\":\"" + bindPassword + "\"}}");
        when(ds.findAll(eq(SecurityProfileEntity.class), any())).thenReturn(List.of(profile));
    }

    private HelmDeployRequest trinoRequest() {
        return trinoRequest(BIND_PASSWORD);
    }

    /** @param bindPassword the directory password, which a dependency also receives */
    private HelmDeployRequest trinoRequest(String bindPassword) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setNamespace("analytics");
        request.setReleaseName("trino");
        request.setChart("trino");
        request.setVersion("1.0.0");
        request.setRepoId("charts");
        request.setServiceKey("TRINO");
        request.setDeploymentMode("FLUX_GITOPS");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("additionalConfigProperties", new ArrayList<>(List.of("query.max-memory=4GB")));
        values.put("polarisCredential", new LinkedHashMap<>(Map.of("clientSecret", POLARIS_SECRET)));
        values.put("server", new LinkedHashMap<>(Map.of("workers", 2)));
        request.setValues(values);
        // The Polaris client secret is kept out of the values; a binding copied it into polarisCredential.
        request.setFormValues(new LinkedHashMap<>(Map.of("icebergCatalog",
                new LinkedHashMap<>(Map.of("clientSecret", POLARIS_SECRET)))));

        Map<String, Object> depValues = new LinkedHashMap<>();
        depValues.put("upstream", new LinkedHashMap<>(Map.of("bindPassword", bindPassword)));
        depValues.put("replicas", 1);
        LinkedHashMap<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("ldap-proxy", new LinkedHashMap<>(Map.of(
                "chart", "ldap-proxy", "releaseName", "ldap-proxy", "chartVersion", "0.1.0", "values", depValues)));
        request.setDependencies(dependencies);

        HelmDeployRequest.GitOptions git = new HelmDeployRequest.GitOptions();
        git.setRepoUrl(repoUrl);
        git.setBaseBranch("main");
        git.setBranch("main");
        git.setCommitMode("DIRECT_COMMIT");
        request.setGit(git);
        return request;
    }

    private FluxGitOpsBackend backend() {
        return new FluxGitOpsBackend(fluxRoot, k8s, ctx);
    }

    private String remoteHead() throws Exception {
        try (Git g = Git.open(bareRepo.toFile())) {
            return g.getRepository().resolve("refs/heads/main").getName();
        }
    }

    /** A file as published on the remote main branch, or null. */
    private String published(String path) throws Exception {
        return published("main", path);
    }

    /** A file as published on a remote branch, or null. */
    private String published(String branch, String path) throws Exception {
        Path clone = tmp.getRoot().toPath().resolve("verify-" + System.nanoTime());
        try (Git ignored = Git.cloneRepository().setURI(repoUrl).setBranch(branch).setDirectory(clone.toFile()).call()) {
            Path file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    /** Release metadata kept between deploys, as the view's data store keeps it. */
    private final Map<String, K8sReleaseEntity> releases = new ConcurrentHashMap<>();

    private void keepReleaseMetadata() throws Exception {
        doAnswer(inv -> {
            K8sReleaseEntity entity = inv.getArgument(0);
            releases.put(entity.getId(), entity);
            return null;
        }).when(ds).store(any(K8sReleaseEntity.class));
        when(ds.find(eq(K8sReleaseEntity.class), anyString())).thenAnswer(inv -> releases.get((String) inv.getArgument(1)));
    }

    /** The values Secret a HelmRelease manifest reads. */
    private static String valuesSecretOf(String manifest) {
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("valuesFrom:\n  - kind: Secret\n    name: (\\S+)\n")
                .matcher(manifest);
        assertTrue(manifest, name.find());
        return name.group(1);
    }

    /** A direct commit to a working branch other than the base branch, which Flux reconciles. */
    private HelmDeployRequest workingBranchRequest(String bindPassword) {
        HelmDeployRequest request = trinoRequest(bindPassword);
        request.getGit().setBranch("flux-work");
        return request;
    }

    @Test
    public void theCommittedHelmReleaseReadsItsSecretsFromASecretAndHoldsNone() throws Exception {
        String sha = backend().apply(trinoRequest(), null);

        assertEquals(40, sha.length());
        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertNotNull(helmRelease);
        assertTrue(helmRelease, helmRelease.contains("  valuesFrom:\n  - kind: Secret\n    name: trino-kdps-values\n"
                + "    valuesKey: values.yaml\n  values:\n"));
        for (String secret : List.of(BIND_PASSWORD, POLARIS_SECRET)) {
            assertFalse("no password or secret in Git", helmRelease.contains(secret));
        }
        assertTrue("the other settings stay reviewable in Git", helmRelease.contains("ldap://dir.example.com"));
        assertTrue(helmRelease, helmRelease.contains("workers: 2"));

        String payload = secretPayloads.get("analytics/trino-kdps-values");
        assertNotNull("the values Secret is written in the release namespace", payload);
        for (String secret : List.of(BIND_PASSWORD, POLARIS_SECRET)) {
            assertTrue(payload.contains(secret));
        }
        assertTrue("a list holding no secret stays reviewable in Git", helmRelease.contains("query.max-memory=4GB"));
        assertEquals(Map.of("managed-by", "ambari-k8s-view", "app.kubernetes.io/managed-by", "kdps",
                "ambari.clemlab.com/release", "trino", "reconcile.fluxcd.io/watch", "Enabled"),
                secretLabels.get("analytics/trino-kdps-values"));
        assertEquals("the Secret exists before the commit Flux applies", seedCommit,
                remoteHeadAtSecretWrite.get("analytics/trino-kdps-values"));

        String dependency = published(RELEASE_DIR + "/dependencies/ldap-proxy-helmrelease.yaml");
        assertNotNull(dependency);
        assertTrue(dependency, dependency.contains("name: ldap-proxy-kdps-values"));
        assertFalse(dependency.contains(BIND_PASSWORD));
        assertTrue(dependency.contains("replicas: 1"));
        assertTrue(secretPayloads.get("analytics/ldap-proxy-kdps-values").contains(BIND_PASSWORD));
    }

    @Test
    public void aSecurityProfileTheDeployNamesIsNeverReplacedByTheDefaultOne() throws Exception {
        HelmDeployRequest request = trinoRequest();
        request.setSecurityProfile("retired-ldap");

        try {
            backend().apply(request, null);
            fail("the default profile 'corp-ldap' must not stand in for the profile the deploy names");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().startsWith(
                    "The security profile 'retired-ldap' that this deploy asks for does not exist"));
        }

        assertEquals("nothing is committed", seedCommit, remoteHead());
        assertTrue("no values Secret is written", secretPayloads.isEmpty());
        verify(k8s, never()).createNamespace(anyString());
    }

    @Test
    public void aChangedPasswordUpdatesTheSecretWithoutACommit() throws Exception {
        String first = backend().apply(trinoRequest(), null);
        useLdapProfile("Rotated-Pass-77");

        String second = backend().apply(trinoRequest("Rotated-Pass-77"), null);

        assertEquals("a success, not NO_CHANGES: the deployed commit is reported", first, second);
        assertEquals("nothing new in Git", first, remoteHead());
        assertTrue(secretPayloads.get("analytics/trino-kdps-values").contains("Rotated-Pass-77"));
        assertFalse(secretPayloads.get("analytics/trino-kdps-values").contains(BIND_PASSWORD));
        verify(k8s).requestFluxReconcile("analytics", "trino");
        assertFalse(published(RELEASE_DIR + "/helmrelease.yaml").contains("Rotated-Pass-77"));

        assertEquals("nothing changed at all", "NO_CHANGES", backend().apply(trinoRequest("Rotated-Pass-77"), null));
        verify(k8s, times(1)).requestFluxReconcile("analytics", "trino");
    }

    /**
     * What the Releases page sends to resync or refresh the security of a release: its current values as Helm holds
     * them (Flux merged the values Secret back in), no form, no stack configuration overrides, no dependencies.
     */
    private HelmDeployRequest resyncRequest(int workers) {
        HelmDeployRequest request = trinoRequest();
        request.getValues().put("server", new LinkedHashMap<>(Map.of("workers", workers)));
        request.setFormValues(null);
        request.setStackConfigOverrides(null);
        request.setDependencies(null);
        return request;
    }

    @Test
    public void aResyncWithoutTheFormCommitsNoSecretThatTheLastDeployStored() throws Exception {
        backend().apply(trinoRequest(), null);

        String sha = backend().apply(resyncRequest(3), null);

        assertEquals(sha, remoteHead());
        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertTrue("the non-secret change is committed", helmRelease.contains("workers: 3"));
        for (String secret : List.of(BIND_PASSWORD, POLARIS_SECRET)) {
            assertFalse("once secret, stays secret: " + helmRelease, helmRelease.contains(secret));
        }
        assertTrue(helmRelease.contains("name: trino-kdps-values"));
        assertTrue("the excluded field's copy is still in the Secret",
                secretPayloads.get("analytics/trino-kdps-values").contains(POLARIS_SECRET));
    }

    @Test
    public void aStackConfigPasswordInTheResubmittedValuesIsNotCommitted() throws Exception {
        // A release whose values carry a stack configuration password under its "<config>/<property>" key, with no
        // values Secret yet and no override naming it.
        HelmDeployRequest request = resyncRequest(2);
        request.setServiceKey("SUPERSET");
        request.setReleaseName("superset");
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("superset-env/SUPERSET_ADMIN_PASSWORD", "Admin-Pass-55");
        live.put("superset-env/ROW_LIMIT", 5000);
        live.put("supersetNode", new LinkedHashMap<>(Map.of("replicas", 1)));
        request.setValues(live);

        backend().apply(request, null);

        String helmRelease = published("clusters/default/analytics/superset/helmrelease.yaml");
        assertFalse(helmRelease, helmRelease.contains("Admin-Pass-55"));
        assertTrue("a property that is not a password stays in Git", helmRelease.contains("superset-env/ROW_LIMIT"));
        assertTrue(secretPayloads.get("analytics/superset-kdps-values").contains("Admin-Pass-55"));
    }

    @Test
    public void aPullRequestNeverChangesTheSecretTheRunningReleaseReads() throws Exception {
        String deployed = backend().apply(trinoRequest(), null);
        String liveValues = secretPayloads.get("analytics/trino-kdps-values");
        String liveDependencyValues = secretPayloads.get("analytics/ldap-proxy-kdps-values");
        useLdapProfile("Rotated-Pass-77");
        HelmDeployRequest proposed = trinoRequest("Rotated-Pass-77");
        proposed.getGit().setCommitMode("PR_MODE");
        proposed.getGit().setBranch("kdps-rotate");

        String sha = backend().apply(proposed, null);

        assertTrue(sha, sha.startsWith("PR-STUB:"));
        assertEquals("the running release reads the same values", liveValues, secretPayloads.get("analytics/trino-kdps-values"));
        assertEquals(liveDependencyValues, secretPayloads.get("analytics/ldap-proxy-kdps-values"));
        verify(k8s, never()).requestFluxReconcile(anyString(), anyString());
        String proposedRelease = Files.readString(fluxRoot.resolve("gitops-analytics-trino").resolve(RELEASE_DIR)
                .resolve("helmrelease.yaml"));
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("name: (trino-kdps-values-[0-9a-f]{8})")
                .matcher(proposedRelease);
        assertTrue("the proposed HelmRelease reads a Secret of its own: " + proposedRelease, name.find());
        assertTrue(secretPayloads.get("analytics/" + name.group(1)).contains("Rotated-Pass-77"));
        assertFalse(proposedRelease.contains("Rotated-Pass-77"));
        String proposedDependency = Files.readString(fluxRoot.resolve("gitops-analytics-trino").resolve(RELEASE_DIR)
                .resolve("dependencies/ldap-proxy-helmrelease.yaml"));
        assertTrue(proposedDependency, proposedDependency.matches("(?s).*name: ldap-proxy-kdps-values-[0-9a-f]{8}\n.*"));
        assertEquals("the base branch is untouched", deployed, remoteHead());

        int secretsBefore = secretPayloads.size();
        assertEquals("proposing the same change again changes nothing", "NO_CHANGES", backend().apply(proposed, null));
        assertEquals(secretsBefore, secretPayloads.size());
    }

    @Test
    public void aRefusedPushPutsTheSecretsBackAndLeavesNoCommitBehind() throws Exception {
        String deployed = backend().apply(trinoRequest(), null);
        String liveValues = secretPayloads.get("analytics/trino-kdps-values");
        useLdapProfile("Rotated-Pass-77");
        HelmDeployRequest changed = trinoRequest("Rotated-Pass-77");
        changed.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 5)));
        // The remote cannot update its branch (another update holds the lock): every push attempt is refused.
        Path lock = bareRepo.resolve("refs/heads/main.lock");
        Files.writeString(lock, "");

        try {
            backend().apply(changed, null);
            fail("the deploy must fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("refused"));
        }

        assertEquals("nothing landed in Git", deployed, remoteHead());
        assertEquals("the running release reads its previous values again", liveValues,
                secretPayloads.get("analytics/trino-kdps-values"));
        Path workspace = fluxRoot.resolve("gitops-analytics-trino");
        assertEquals("no unpublished commit is left for the next deploy to take as published", deployed,
                new GitClient(workspace, repoUrl, "main", null).headCommit());

        Files.delete(lock);
        String retried = backend().apply(changed, null);
        assertEquals(retried, remoteHead());
        assertNotEquals(deployed, retried);
        assertTrue(published(RELEASE_DIR + "/helmrelease.yaml").contains("workers: 5"));
        assertTrue(secretPayloads.get("analytics/trino-kdps-values").contains("Rotated-Pass-77"));
    }

    @Test
    public void aRefusedFirstDeployDeletesTheSecretsItCreated() throws Exception {
        Files.writeString(bareRepo.resolve("refs/heads/main.lock"), "");

        try {
            backend().apply(trinoRequest(), null);
            fail("the deploy must fail");
        } catch (java.io.IOException expected) {
            // refused push
        }

        assertEquals(seedCommit, remoteHead());
        assertNull(secretPayloads.get("analytics/trino-kdps-values"));
        assertNull(secretPayloads.get("analytics/ldap-proxy-kdps-values"));
    }

    @Test
    public void aSecretLeakingIntoAnyWrittenFileStopsTheDeployBeforeTheCommit() throws Exception {
        // The operator typed the same password into the chart repository URL: the split cannot move it.
        chartRepo.setUrl("https://kdps:" + BIND_PASSWORD + "@charts.example.com");

        try {
            backend().apply(trinoRequest(), null);
            fail("the deploy must stop");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(RELEASE_DIR + "/helmrepository.yaml"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("nothing was committed to Git"));
            assertFalse("the error never shows the value", expected.getMessage().contains(BIND_PASSWORD));
        }

        assertEquals("nothing was committed", seedCommit, remoteHead());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class),
                anyMap(), any(), any());
        Path workspace = fluxRoot.resolve("gitops-analytics-trino");
        assertFalse("the workspace is reset", new GitClient(workspace, repoUrl, "main", null).hasChanges());
    }

    @Test
    public void aSecretThatCannotBeWrittenStopsTheDeployBeforeTheCommit() throws Exception {
        doThrow(new SecurityException("Namespace 'analytics' is not in the allowed list"))
                .when(k8s).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class),
                        anyMap(), any(), any());

        try {
            backend().apply(trinoRequest(), null);
            fail("the deploy must stop");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nothing was committed to Git"));
        }
        assertEquals("never falls back to Git", seedCommit, remoteHead());
    }

    @Test
    public void uninstallDeletesTheValuesSecretsThisViewCreated() throws Exception {
        backend().apply(trinoRequest(), null);
        when(k8s.getSecret("analytics", "trino-kdps-values")).thenReturn(secret("trino-kdps-values", true));
        when(k8s.getSecret("analytics", "ldap-proxy-kdps-values")).thenReturn(secret("ldap-proxy-kdps-values", false));

        backend().destroy(repoUrl, "main", RELEASE_DIR, null, null, "analytics", "trino");

        assertNull("removed from Git", published(RELEASE_DIR + "/helmrelease.yaml"));
        verify(k8s).deleteSecret("analytics", "trino-kdps-values");
        verify(k8s, never()).deleteSecret("analytics", "ldap-proxy-kdps-values");
    }

    @Test
    public void uninstallAlsoDeletesTheSecretsOfProposedChanges() throws Exception {
        backend().apply(trinoRequest(), null);
        Map<String, String> trinoLabels = Map.of("managed-by", "ambari-k8s-view", "ambari.clemlab.com/release", "trino");
        when(k8s.listSecretNames("analytics", trinoLabels))
                .thenReturn(List.of("trino-kdps-values", "trino-kdps-values-1a2b3c4d", "trino-truststore"));
        when(k8s.getSecret(eq("analytics"), anyString())).thenAnswer(inv -> secret(inv.getArgument(1), true));

        backend().destroy(repoUrl, "main", RELEASE_DIR, null, null, "analytics", "trino");

        verify(k8s).deleteSecret("analytics", "trino-kdps-values");
        verify(k8s).deleteSecret("analytics", "trino-kdps-values-1a2b3c4d");
        verify(k8s).deleteSecret("analytics", "ldap-proxy-kdps-values");
        verify(k8s, never()).deleteSecret("analytics", "trino-truststore");
    }

    @Test
    public void uninstallByReleaseDeletesItsValuesSecretEvenWhenGitNoLongerNamesIt() throws Exception {
        when(k8s.getSecret("analytics", "trino-kdps-values")).thenReturn(secret("trino-kdps-values", true));

        backend().destroy(repoUrl, "main", RELEASE_DIR, null, null, "analytics", "trino");

        verify(k8s).deleteSecret("analytics", "trino-kdps-values");
    }

    @Test
    public void theTlsBlockOfTheFormIsSearchedForPasswords() {
        HelmDeployRequest request = trinoRequest();
        request.setTls(new LinkedHashMap<>(Map.of("https", new LinkedHashMap<>(Map.of(
                "enabled", true, "password", "Keystore-Pass-31", "secretName", "trino-https-tls")))));
        GitOpsSecretValues secrets = new GitOpsSecretValues();

        secrets.addPasswordFields(new org.apache.ambari.view.k8s.service.StackDefinitionService()
                .getServiceDefinition("TRINO").form, FluxGitOpsBackend.formFields(request));

        assertTrue("the wizard sends it with the TLS block, not with the form values",
                secrets.secrets().contains("Keystore-Pass-31"));
        assertTrue(secrets.secrets().contains(POLARIS_SECRET));
    }

    @Test
    public void aKeystorePasswordSentWithTheTlsBlockIsNotCommitted() throws Exception {
        HelmDeployRequest request = trinoRequest();
        // What the wizard sends with HTTPS on: the TLS block in its own field, a binding's copy of its password.
        request.setTls(new LinkedHashMap<>(Map.of("https", new LinkedHashMap<>(Map.of(
                "enabled", true, "password", "Keystore-Pass-31", "secretName", "trino-https-tls")))));
        request.getValues().put("coordinator", new LinkedHashMap<>(Map.of("additionalConfigProperties",
                new ArrayList<>(List.of("http-server.https.keystore.key=Keystore-Pass-31")))));
        request.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 2, "config",
                new LinkedHashMap<>(Map.of("https", new LinkedHashMap<>(Map.of("enabled", true)))))));

        backend().apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        assertFalse(helmRelease, helmRelease.contains("Keystore-Pass-31"));
        assertTrue(secretPayloads.get("analytics/trino-kdps-values").contains("http-server.https.keystore.key=Keystore-Pass-31"));
    }

    @Test
    public void aKeystorePasswordSentWithTheTlsBlockIsKnownToTheGuard() throws Exception {
        HelmDeployRequest request = trinoRequest();
        request.setTls(new LinkedHashMap<>(Map.of("https", new LinkedHashMap<>(Map.of(
                "enabled", true, "password", "Keystore-Pass-31", "secretName", "trino-https-tls")))));
        // The same password typed into the chart repository URL: only its being known as a secret stops the commit.
        chartRepo.setUrl("https://kdps:Keystore-Pass-31@charts.example.com");

        try {
            backend().apply(request, null);
            fail("the deploy must stop");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(RELEASE_DIR + "/helmrepository.yaml"));
        }
        assertEquals(seedCommit, remoteHead());
    }

    @Test
    public void aCopyOfAnExcludedPasswordIsNotCommittedWithoutTheFormNorAStoredSecret() throws Exception {
        // A release deployed before its values went to a Secret, redeployed from its values: no form, no values Secret.
        HelmDeployRequest request = resyncRequest(2);
        request.getValues().put("s3Credential", new LinkedHashMap<>(Map.of("accessKey", "ozone-access-key",
                "secretKey", "S3-Secret-Key-8")));

        backend().apply(request, null);

        String helmRelease = published(RELEASE_DIR + "/helmrelease.yaml");
        for (String secret : List.of(POLARIS_SECRET, "S3-Secret-Key-8")) {
            assertFalse("known from the bindings of the definition: " + helmRelease, helmRelease.contains(secret));
        }
        assertTrue("the access key is not a password", helmRelease.contains("ozone-access-key"));
        String payload = secretPayloads.get("analytics/trino-kdps-values");
        assertTrue(payload.contains(POLARIS_SECRET));
        assertTrue(payload.contains("S3-Secret-Key-8"));
    }

    @Test
    public void aDependencyWhoseSecretsCannotBeReadStillDeploysWhenItReadsNone() throws Exception {
        HelmDeployRequest request = trinoRequest();
        request.getDependencies().put("metrics-proxy", new LinkedHashMap<>(Map.of("chart", "metrics-proxy",
                "releaseName", "metrics-proxy", "namespace", "monitoring", "chartVersion", "0.2.0",
                "values", new LinkedHashMap<>(Map.of("replicas", 1)))));
        // The view's account may not read Secrets in the dependency's namespace.
        when(k8s.readOpaqueSecretKeyAsString(eq("monitoring"), anyString(), anyString()))
                .thenThrow(new RuntimeException("Failed to read Secret monitoring/metrics-proxy-kdps-values: Forbidden"));

        String sha = backend().apply(request, null);

        assertEquals(sha, remoteHead());
        String dependency = published(RELEASE_DIR + "/dependencies/metrics-proxy-helmrelease.yaml");
        assertNotNull(dependency);
        assertFalse(dependency, dependency.contains("valuesFrom"));
        assertTrue(published(RELEASE_DIR + "/helmrelease.yaml").contains("name: trino-kdps-values"));
    }

    @Test
    public void aValuesSecretTheManifestReadsButThatCannotBeReadStopsTheDeploy() throws Exception {
        String deployed = backend().apply(trinoRequest(), null);
        when(k8s.readOpaqueSecretKeyAsString(eq("analytics"), eq("ldap-proxy-kdps-values"), anyString()))
                .thenThrow(new RuntimeException("Failed to read Secret analytics/ldap-proxy-kdps-values: Forbidden"));
        HelmDeployRequest changed = trinoRequest();
        changed.getValues().put("server", new LinkedHashMap<>(Map.of("workers", 4)));

        try {
            backend().apply(changed, null);
            fail("the deploy must stop");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("analytics/ldap-proxy-kdps-values"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("nothing was committed to Git"));
        }
        assertEquals(deployed, remoteHead());
    }

    @Test
    public void aDirectCommitToAWorkingBranchNeverChangesTheSecretTheRunningReleaseReads() throws Exception {
        String deployed = backend().apply(trinoRequest(), null);
        String liveValues = secretPayloads.get("analytics/trino-kdps-values");
        String liveDependencyValues = secretPayloads.get("analytics/ldap-proxy-kdps-values");
        useLdapProfile("Rotated-Pass-77");

        backend().apply(workingBranchRequest("Rotated-Pass-77"), null);

        assertEquals("the running release reads the same values until the branch is merged", liveValues,
                secretPayloads.get("analytics/trino-kdps-values"));
        assertEquals(liveDependencyValues, secretPayloads.get("analytics/ldap-proxy-kdps-values"));
        verify(k8s, never()).requestFluxReconcile(anyString(), anyString());
        assertEquals("the base branch is untouched", deployed, remoteHead());
        String committed = published("flux-work", RELEASE_DIR + "/helmrelease.yaml");
        String name = valuesSecretOf(committed);
        assertTrue(name, name.matches("trino-kdps-values-[0-9a-f]{8}"));
        assertTrue(secretPayloads.get("analytics/" + name).contains("Rotated-Pass-77"));
        assertFalse(committed.contains("Rotated-Pass-77"));
    }

    @Test
    public void aRedeployThatNamesNoBaseBranchUsesTheRecordedOne() throws Exception {
        keepReleaseMetadata();
        backend().apply(workingBranchRequest(BIND_PASSWORD), null);
        assertEquals("main", releases.get("analytics:trino").getGitBaseBranch());
        useLdapProfile("Rotated-Pass-77");
        // A security refresh from the Releases page: the release's working branch, no base branch.
        HelmDeployRequest refresh = workingBranchRequest("Rotated-Pass-77");
        refresh.getGit().setBaseBranch(null);

        backend().apply(refresh, null);

        assertNull("the Secret named after the release, which a merge of the branch would make Flux read, is never "
                + "written ahead of it", secretPayloads.get("analytics/trino-kdps-values"));
        verify(k8s, never()).requestFluxReconcile(anyString(), anyString());
        String name = valuesSecretOf(published("flux-work", RELEASE_DIR + "/helmrelease.yaml"));
        assertTrue(secretPayloads.get("analytics/" + name).contains("Rotated-Pass-77"));
    }

    @Test
    public void uninstallKeepsTheValuesSecretsWhileTheReconciledBranchStillHoldsTheRelease() throws Exception {
        backend().apply(trinoRequest(), null);
        when(k8s.getSecret(eq("analytics"), anyString())).thenAnswer(inv -> secret(inv.getArgument(1), true));
        // A release proposed in a pull request and merged: its branch is still there, and the removal goes to it.
        try (Git g = Git.open(bareRepo.toFile())) {
            g.branchCreate().setName("kdps-change").setStartPoint("refs/heads/main").call();
        }

        backend().destroy(repoUrl, "kdps-change", "main", RELEASE_DIR, null, null, "analytics", "trino");

        assertNotNull("Flux still applies the release from the base branch", published(RELEASE_DIR + "/helmrelease.yaml"));
        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    public void uninstallKeepsTheValuesSecretsWhenTheReconciledBranchIsNotKnown() throws Exception {
        backend().apply(trinoRequest(), null);
        when(k8s.getSecret(eq("analytics"), anyString())).thenAnswer(inv -> secret(inv.getArgument(1), true));

        backend().destroy(repoUrl, "main", null, RELEASE_DIR, null, null, "analytics", "trino");

        verify(k8s, never()).deleteSecret(anyString(), anyString());
    }

    @Test
    public void uninstallOfAWorkingBranchNeverMergedDeletesItsValuesSecrets() throws Exception {
        backend().apply(workingBranchRequest(BIND_PASSWORD), null);
        String name = valuesSecretOf(published("flux-work", RELEASE_DIR + "/helmrelease.yaml"));
        when(k8s.getSecret(eq("analytics"), anyString())).thenAnswer(inv -> secret(inv.getArgument(1), true));

        backend().destroy(repoUrl, "flux-work", "main", RELEASE_DIR, null, null, "analytics", "trino");

        assertNull(published("flux-work", RELEASE_DIR + "/helmrelease.yaml"));
        verify(k8s).deleteSecret("analytics", name);
    }

    @Test
    public void theReconciledBranchOfAReleaseComesFromItsMetadata() {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setGitBranch("kdps-change");
        meta.setGitCommitMode("PR_MODE");
        assertNull("the base of a pull request is not known", FluxGitOpsBackend.reconciledBranch(meta));
        meta.setGitBaseBranch("production");
        assertEquals("production", FluxGitOpsBackend.reconciledBranch(meta));

        K8sReleaseEntity direct = new K8sReleaseEntity();
        direct.setGitBranch("main");
        direct.setGitCommitMode("DIRECT_COMMIT");
        assertEquals("a direct commit goes where Flux reads", "main", FluxGitOpsBackend.reconciledBranch(direct));
        assertNull(FluxGitOpsBackend.reconciledBranch(null));
    }

    private static Secret secret(String name, boolean managed) {
        SecretBuilder builder = new SecretBuilder().withNewMetadata().withName(name).withNamespace("analytics")
                .endMetadata();
        if (managed) {
            builder.editMetadata().addToLabels("managed-by", "ambari-k8s-view").endMetadata();
        }
        return builder.build();
    }
}
