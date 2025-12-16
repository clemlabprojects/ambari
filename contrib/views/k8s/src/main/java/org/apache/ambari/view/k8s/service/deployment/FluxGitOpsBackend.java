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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flux GitOps backend: renders HelmRelease/HelmRepository YAML, commits to Git and returns the commit SHA.
 * Status/destroy are still minimal and should be enhanced to read HelmRelease CRs and delete manifests.
 */
public class FluxGitOpsBackend implements DeploymentBackend {
    private static final Logger LOG = LoggerFactory.getLogger(FluxGitOpsBackend.class);

    // Retry configuration constants
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 500L;
    private static final long HTTP_RETRY_BASE_DELAY_MS = 250L;

    private final Path fluxRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final KubernetesService kubernetesService;
    private final ViewContext viewContext;
    private final GitCredentialService gitCredentialService;
    private final ReleaseMetadataService releaseMetadataService;
    private final HttpClient httpClient;
    private final Duration httpTimeout = Duration.ofSeconds(10);

    /**
     * @param fluxRoot root directory where manifests will be written (simulates Git workspace).
     */
    public FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext) {
        this(fluxRoot, kubernetesService, viewContext, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    FluxGitOpsBackend(Path fluxRoot, KubernetesService kubernetesService, ViewContext viewContext, HttpClient httpClient) {
        this.fluxRoot = fluxRoot;
        this.kubernetesService = kubernetesService;
        this.viewContext = viewContext;
        this.gitCredentialService = new GitCredentialService(viewContext);
        this.releaseMetadataService = new ReleaseMetadataService(viewContext);
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                : httpClient;
    }

    @Override
    public String apply(HelmDeployRequest request, DeploymentContext context) throws Exception {
        Objects.requireNonNull(request.getNamespace(), "namespace is required");
        Objects.requireNonNull(request.getReleaseName(), "releaseName is required");

        final String namespace = request.getNamespace();
        final String release = request.getReleaseName();
        final var existingMeta = releaseMetadataService.find(namespace, release);

        HelmDeployRequest.GitOptions git = mergeGitOptions(request, existingMeta);
        if (git == null || git.getRepoUrl() == null || git.getRepoUrl().isBlank()) {
            throw new IllegalArgumentException("Flux GitOps requires git.repoUrl");
        }

        String repoUrl = git.getRepoUrl().trim();
        boolean isHttps = repoUrl.startsWith("https://");
        boolean isSsh = repoUrl.startsWith("git@") || repoUrl.startsWith("ssh://");

        logFluxInfo(namespace, release, "apply", "Starting Flux GitOps deploy (mode=%s, repo=%s)", git.getCommitMode(), repoUrl);

        String baseBranch = firstNonBlank(git.getBaseBranch(), "main");
        String repoName = firstNonBlank(request.getRepoId(), existingMeta != null ? existingMeta.getRepoId() : null, "default-repo");
        request.setRepoId(repoName);
        String chart = request.getChart();
        String version = firstNonBlank(request.getVersion(), "latest");

        String pathOverride = git.getPathPrefix();
        String storedPath = existingMeta != null ? existingMeta.getGitPath() : null;
        Path targetDir = resolveTargetPath(pathOverride, storedPath, namespace, release);

        // Resolve credentials: prefer provided, else resolve alias. Always persist for future operations.
        String token = git.getAuthToken();
        String sshKey = git.getSshKey();
        String alias = firstNonBlank(git.getCredentialAlias(), existingMeta != null ? existingMeta.getGitCredentialAlias() : null);
        if ((token == null || token.isBlank()) && (sshKey == null || sshKey.isBlank()) && alias != null) {
            String resolved = gitCredentialService.resolveSecret(alias);
            if (resolved != null && resolved.contains("BEGIN")) {
                sshKey = resolved;
            } else {
                token = resolved;
            }
        }
        if ((token != null && !token.isBlank()) || (sshKey != null && !sshKey.isBlank())) {
            String stored = gitCredentialService.storeSecret(sshKey != null && !sshKey.isBlank() ? sshKey : token, "gitops");
            if (stored != null) {
                alias = stored;
            }
        }

        if ("PR_MODE".equalsIgnoreCase(git.getCommitMode()) && isHttps && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("PR mode requires an HTTPS token");
        }
        if (isHttps && (token == null || token.isBlank()) && (sshKey == null || sshKey.isBlank())) {
            throw new IllegalArgumentException("GitOps over HTTPS requires authToken or sshKey");
        }
        if (isSsh && (sshKey == null || sshKey.isBlank()) && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("GitOps over SSH requires sshKey or HTTPS token");
        }

        Path repoDir = fluxRoot.resolve("gitops-workdir");
        GitClient gitClient = new GitClient(repoDir, repoUrl, baseBranch, token, sshKey)
                .withAuthor(viewContext.getUsername(), viewContext.getUsername() + "@ambari");
        withGitRetry(() -> { gitClient.sync(); return null; }, "sync " + repoUrl);

        String branchCandidate = firstNonBlank(git.getBranch(), existingMeta != null ? existingMeta.getGitBranch() : null, GitClient.generateBranch("flux"));
        final String branch = branchCandidate;
        git.setBranch(branch);
        withGitRetry(() -> { gitClient.checkoutBranch(branch); return null; }, "checkout " + branch);

        Files.createDirectories(repoDir.resolve(targetDir));

        // Compose YAML (JSON-in-YAML is allowed; JSON is a subset of YAML)
        String valuesJson = request.getValues() == null ? "{}" : gson.toJson(request.getValues());

        String repoYaml = """
                apiVersion: source.toolkit.fluxcd.io/v1beta2
                kind: HelmRepository
                metadata:
                  name: %s
                  namespace: flux-system
                spec:
                  interval: 5m
                  url: %s
                """.formatted(repoName, repoUrl);

        String hrYaml = """
                apiVersion: helm.toolkit.fluxcd.io/v2beta2
                kind: HelmRelease
                metadata:
                  name: %s
                  namespace: %s
                spec:
                  interval: 5m
                  chart:
                    spec:
                      chart: %s
                      version: %s
                      sourceRef:
                        kind: HelmRepository
                        name: %s
                        namespace: flux-system
                  values: %s
                """.formatted(release, namespace, chart, version, repoName, valuesJson);

        gitClient.writeFile(targetDir.resolve("helmrepository.yaml"), repoYaml);
        gitClient.writeFile(targetDir.resolve("helmrelease.yaml"), hrYaml);

        boolean prMode = "PR_MODE".equalsIgnoreCase(git.getCommitMode());
        String commitMsg = prMode
                ? "Flux GitOps (PR) apply " + namespace + "/" + release
                : "Flux GitOps apply " + namespace + "/" + release;

        if (!gitClient.hasChanges()) {
            LOG.info("Flux GitOps found no changes for {}/{}; skipping commit", namespace, release);
            return "NO_CHANGES";
        }

        boolean canPush = (token != null && !token.isBlank()) || (sshKey != null && !sshKey.isBlank());
        String commitSha;
        if (prMode && !canPush) {
            // PR intent without credentials: keep a local commit as a stub and surface to the operator.
            commitSha = withGitRetry(() -> gitClient.commitWithoutPush(commitMsg), "commit(no-push)");
            if (commitSha == null) {
                return "NO_CHANGES";
            }
            commitSha = "PR-STUB:" + commitSha;
            LOG.warn("Flux GitOps PR mode requested but no credentials provided; created local commit stub {}", commitSha);
        } else {
            commitSha = withGitRetry(() -> gitClient.commitAndPush(commitMsg), "commit/push");
            if (commitSha == null) {
                return "NO_CHANGES";
            }
        }
        String prUrl = null;
        String prNumber = null;
        String prState = null;
        if (prMode) {
            // Attempt to open a PR/MR on supported providers.
            try {
                String prToken = token;
                PullRequestInfo info = (prToken != null && !prToken.isBlank()) ? createPullRequest(git.getRepoUrl(), baseBranch, branch, commitMsg, prToken) : null;
                if (info != null) {
                    prUrl = info.url;
                    prNumber = info.id;
                    prState = firstNonBlank(info.state, "open");
                    LOG.info("Flux GitOps PR created for {}/{}: {}", namespace, release, prUrl);
                } else if (!canPush) {
                    LOG.warn("Flux GitOps PR mode without credentials: PR creation skipped; commit is local stub.");
                    prState = "pending";
                } else {
                    LOG.warn("Flux GitOps PR creation skipped/unsupported for repo {}", git.getRepoUrl());
                    prState = "pending";
                }
            } catch (Exception ex) {
                LOG.warn("Flux GitOps PR creation failed for repo {} branch {}: {}", git.getRepoUrl(), branch, ex.toString());
                prState = "pending";
            }
        }

        LOG.info("Flux GitOps wrote manifests for {}/{} into {} branch {} commit {} (prMode={})",
                namespace, release, targetDir, branch, commitSha, prMode);

        // Persist git metadata alongside release for future destroy/status
        try {
            releaseMetadataService.recordInstallOrUpgrade(
                    namespace,
                    release,
                    request.getServiceKey(),
                    chart,
                    repoName,
                    version,
                    request.getValues(),
                    commitSha, // deploymentId
                    java.util.Collections.emptyList(),
                    null, // global config fingerprint unknown here
                    request.getSecurityProfile(),
                    null, // security profile hash not computed here
                    "FLUX_GITOPS",
                    commitSha,
                    branch,
                    targetDir.toString(),
                    repoUrl,
                    alias,
                    git.getCommitMode(),
                    prUrl,
                    prNumber,
                    prState
            );
        } catch (Exception ex) {
            LOG.warn("Failed to persist Flux metadata for {}/{}: {}", namespace, release, ex.toString());
        }

        return commitSha;
    }

    private HelmDeployRequest.GitOptions mergeGitOptions(HelmDeployRequest request, K8sReleaseEntity existingMeta) {
        HelmDeployRequest.GitOptions git = request.getGit();
        if (git == null) {
            git = new HelmDeployRequest.GitOptions();
            request.setGit(git);
        }
        if ((git.getRepoUrl() == null || git.getRepoUrl().isBlank()) && existingMeta != null) {
            git.setRepoUrl(existingMeta.getGitRepoUrl());
        }
        if ((git.getBaseBranch() == null || git.getBaseBranch().isBlank()) && existingMeta != null) {
            git.setBaseBranch(existingMeta.getGitBranch());
        }
        if ((git.getBranch() == null || git.getBranch().isBlank()) && existingMeta != null) {
            git.setBranch(existingMeta.getGitBranch());
        }
        if ((git.getCredentialAlias() == null || git.getCredentialAlias().isBlank()) && existingMeta != null) {
            git.setCredentialAlias(existingMeta.getGitCredentialAlias());
        }
        if ((git.getCommitMode() == null || git.getCommitMode().isBlank()) && existingMeta != null) {
            git.setCommitMode(existingMeta.getGitCommitMode());
        }
        return git;
    }

    private Path resolveTargetPath(String pathOverride, String storedPath, String namespace, String release) {
        if (pathOverride != null && !pathOverride.isBlank()) {
            return targetPath(pathOverride, namespace, release);
        }
        if (storedPath != null && !storedPath.isBlank()) {
            return validateRelative(Path.of(storedPath));
        }
        return targetPath("clusters/default", namespace, release);
    }

    @Override
    public HelmReleaseDTO status(String namespace, String releaseName) throws Exception {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        dto.name = releaseName;
        dto.namespace = namespace;
        dto.status = "UNKNOWN";
        dto.reconcileMessage = null;
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta != null) {
            dto.gitPrUrl = meta.getGitPrUrl();
            dto.gitPrNumber = meta.getGitPrNumber();
            dto.gitPrState = meta.getGitPrState();
        }
        boolean hasPr = meta != null && meta.getGitPrNumber() != null && !meta.getGitPrNumber().isBlank();

        // If commit was PR-stubbed, surface that before hitting the cluster.
        try {
            if (meta != null && meta.getGitCommitSha() != null && meta.getGitCommitSha().startsWith("PR-STUB:")) {
                dto.status = "PENDING_PR";
                dto.message = "Flux PR mode (local commit " + meta.getGitCommitSha().substring("PR-STUB:".length()) + ") not pushed";
                dto.lastHandledReconcileAt = null;
                // still continue to check cluster; if resource exists, it will override
            }
            if (meta != null && meta.getGitPrUrl() != null) {
                dto.message = (dto.message == null ? "" : dto.message + " | ") + "PR: " + meta.getGitPrUrl();
                dto.lastAttemptedRevision = meta.getGitPrNumber();
                dto.gitPrUrl = meta.getGitPrUrl();
                dto.gitPrNumber = meta.getGitPrNumber();
            }
            refreshPrState(meta, dto);
        } catch (Exception e) {
            // Expected: PR state refresh may fail if Git provider API is unavailable or rate-limited
            // This is non-critical, so we continue with the rest of the status check
            LOG.debug("Failed to refresh PR state for {}/{}: {}", dto.namespace, dto.name, e.getMessage());
        }

        if (kubernetesService == null || kubernetesService.getClient() == null) {
            dto.message = "Flux status unavailable (no k8s client)";
            return dto;
        }

        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();

            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get();

            if (hr == null) {
                dto.status = "NOT_FOUND";
                dto.message = dto.message == null
                        ? "HelmRelease not found; waiting for Flux to reconcile"
                        : dto.message + " | HelmRelease not found";
                dto.reconcileMessage = dto.message;
                return dto;
            }
            if (hr.getMetadata() != null && hr.getMetadata().getGeneration() != null) {
                dto.desiredGeneration = String.valueOf(hr.getMetadata().getGeneration());
            }
            String sourceName = null;
            String sourceNamespace = "flux-system";
            Object specObj = hr.get("spec");
            if (specObj instanceof java.util.Map) {
                java.util.Map<?, ?> spec = (java.util.Map<?, ?>) specObj;
                Object chartSpecObj = spec.get("chart");
                if (chartSpecObj instanceof java.util.Map) {
                    Object specMap = ((java.util.Map<?, ?>) chartSpecObj).get("spec");
                    if (specMap instanceof java.util.Map) {
                        Object sourceRefObj = ((java.util.Map<?, ?>) specMap).get("sourceRef");
                        if (sourceRefObj instanceof java.util.Map) {
                            java.util.Map<?, ?> src = (java.util.Map<?, ?>) sourceRefObj;
                            sourceName = Optional.ofNullable(src.get("name")).map(Object::toString).orElse(null);
                            sourceNamespace = Optional.ofNullable(src.get("namespace")).map(Object::toString).orElse(sourceNamespace);
                        }
                    }
                }
            }
            dto.status = "UNKNOWN";
            Object statusObj = hr.get("status");
            if (statusObj instanceof java.util.Map) {
                java.util.Map<?, ?> status = (java.util.Map<?, ?>) statusObj;
                dto.lastAppliedRevision = Optional.ofNullable(status.get("lastAppliedRevision")).map(Object::toString).orElse(null);
                dto.lastAttemptedRevision = Optional.ofNullable(status.get("lastAttemptedRevision")).map(Object::toString).orElse(null);
                dto.lastHandledReconcileAt = Optional.ofNullable(status.get("lastHandledReconcileAt")).map(Object::toString).orElse(null);
                dto.observedGeneration = Optional.ofNullable(status.get("observedGeneration")).map(Object::toString).orElse(null);
                dto.reconcileState = Optional.ofNullable(status.get("phase")).map(Object::toString).orElse(null);
                Object condObj = status.get("conditions");
                if (condObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> conds = (java.util.List<java.util.Map<String, Object>>) condObj;
                    evaluateConditions(dto, conds);
                    dto.reconcileMessage = dto.message;
                }
                Object hrObserved = status.get("observedGeneration");
                if (hrObserved != null) {
                    dto.message = appendMessage(dto.message, "ObservedGeneration=" + hrObserved);
                }
            }
            try {
                long desired = dto.desiredGeneration != null ? Long.parseLong(dto.desiredGeneration) : -1L;
                long observed = dto.observedGeneration != null ? Long.parseLong(dto.observedGeneration) : -1L;
                evaluateGenerationStaleness(dto, desired, observed);
            } catch (NumberFormatException e) {
                // Expected: Generation values may be non-numeric or malformed in some edge cases
                // This is non-critical for status display, so we continue without staleness evaluation
                LOG.debug("Could not parse generation values for {}/{}: desired={}, observed={}", 
                    dto.namespace, dto.name, dto.desiredGeneration, dto.observedGeneration);
            }
            if (dto.status == null || dto.status.isBlank()) {
                dto.status = "UNKNOWN";
            }
            // Enrich with source (HelmRepository) health if possible
            try {
                String repoToCheck = sourceName;
                if (repoToCheck == null && meta != null) {
                    repoToCheck = meta.getRepoId();
                }
                if (repoToCheck != null) {
                    CustomResourceDefinitionContext repoCtx = new CustomResourceDefinitionContext.Builder()
                            .withGroup("source.toolkit.fluxcd.io")
                            .withVersion("v1beta2")
                            .withScope("Namespaced")
                            .withPlural("helmrepositories")
                            .build();
                    GenericKubernetesResource repo = kubernetesService.getClient()
                            .genericKubernetesResources(repoCtx)
                            .inNamespace(sourceNamespace)
                            .withName(repoToCheck)
                            .get();
                    if (repo != null && repo.get("status") instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> repoStatus = (java.util.Map<String, Object>) repo.get("status");
                        Object condObj = repoStatus.get("conditions");
                        if (condObj instanceof java.util.List) {
                            @SuppressWarnings("unchecked")
                            java.util.List<java.util.Map<String, Object>> conds = (java.util.List<java.util.Map<String, Object>>) condObj;
                            evaluateSourceConditions(dto, conds);
                        }
                        dto.sourceName = repoToCheck;
                        dto.sourceNamespace = sourceNamespace;
                    }
                }
            } catch (Exception inner) {
                LOG.debug("Flux source status lookup failed for {}/{}: {}", namespace, releaseName, inner.toString());
            }
            return dto;
        } catch (Exception ex) {
            LOG.warn("Failed to read Flux status for {}/{}: {}", namespace, releaseName, ex.toString());
            dto.message = "Flux status error: " + ex.getMessage();
            return dto;
        }
    }

    /**
     * Lightweight existence check to avoid a second status call for PR state handling.
     */
    private boolean hrNotAvailable(String namespace, String releaseName) {
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();
            GenericKubernetesResource hr = kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .get();
            return hr == null;
        } catch (Exception e) {
            LOG.debug("hrNotAvailable check failed for {}/{}: {}", namespace, releaseName, e.toString());
            return false;
        }
    }

    /**
     * Simple holder for PR/MR info.
     */
    static class PullRequestInfo {
        final String url;
        final String id;
        final String state;
        final String message;
        PullRequestInfo(String url, String id) { this(url, id, null, null); }
        PullRequestInfo(String url, String id, String state, String message) {
            this.url = url;
            this.id = id;
            this.state = state;
            this.message = message;
        }
    }

    static final class PullRequestParser {
        private static final Gson PARSER = new GsonBuilder().create();

        static PullRequestInfo parseGithub(String body, String defaultId) {
            if (body == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = PARSER.fromJson(body, Map.class);
            if (parsed == null) {
                return null;
            }
            Object url = parsed.get("html_url");
            Object state = parsed.get("state");
            Object merged = parsed.get("merged");
            Object draft = parsed.get("draft");
            String stateStr = state != null ? state.toString() : null;
            if (Boolean.TRUE.equals(merged)) {
                stateStr = "merged";
            } else if (Boolean.TRUE.equals(draft) && stateStr != null && "open".equalsIgnoreCase(stateStr)) {
                stateStr = "draft";
            }
            String message = Optional.ofNullable(parsed.get("mergeable_state")).map(Object::toString).orElse(null);
            String id = formatId(parsed.get("number"), defaultId);
            return new PullRequestInfo(url != null ? url.toString() : null, id, stateStr, message);
        }

        static PullRequestInfo parseGitlab(String body, String defaultId) {
            if (body == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = PARSER.fromJson(body, Map.class);
            if (parsed == null) {
                return null;
            }
            Object url = parsed.get("web_url");
            Object state = parsed.get("state");
            String stateStr = state != null ? state.toString() : null;
            if (parsed.containsKey("merged_at") && parsed.get("merged_at") != null) {
                stateStr = "merged";
            }
            String message = Optional.ofNullable(parsed.get("detailed_merge_status")).map(Object::toString).orElse(null);
            String id = formatId(parsed.get("iid"), defaultId);
            return new PullRequestInfo(url != null ? url.toString() : null, id, stateStr, message);
        }

        private static String formatId(Object value, String fallback) {
            if (value instanceof Number) {
                return String.valueOf(((Number) value).longValue());
            }
            return value != null ? value.toString() : fallback;
        }
    }

    /**
     * Create a PR/MR on GitHub or GitLab if supported.
     *
     * @param repoUrl    remote URL ending with .git or HTTPS form.
     * @param baseBranch target branch (base).
     * @param headBranch source branch (head).
     * @param title      PR/MR title.
     * @param token      OAuth/token with repo write perms (required).
     * @return PullRequestInfo or null if unsupported.
     */
    private PullRequestInfo createPullRequest(String repoUrl, String baseBranch, String headBranch, String title, String token) throws Exception {
        if (token == null || token.isBlank() || repoUrl == null) {
            LOG.warn("Skipping PR creation because token or repoUrl is missing");
            return null;
        }
        String trimmed = normalizeRepoUrl(repoUrl);
        java.net.URI uri = java.net.URI.create(trimmed);
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        String path = uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (host.contains("github")) {
            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            String owner = parts[0];
            String repo = parts[1];
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls";
            String body = """
                    {
                      "title": "%s",
                      "head": "%s",
                      "base": "%s"
                    }
                    """.formatted(title, headBranch, baseBranch);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "create-pr-github");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                PullRequestInfo info = PullRequestParser.parseGithub(resp.body(), headBranch);
                return info != null ? info : new PullRequestInfo(extractJsonField(resp.body(), "html_url"), extractJsonField(resp.body(), "number"), "open", null);
            }
            logFluxWarn("createPR", repoUrl, "CREATE", "GitHub PR creation failed (status %d)", resp.statusCode());
        } else {
            String project = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
            String apiUrl = uri.getScheme() + "://" + host + "/api/v4/projects/" + project + "/merge_requests";
            String body = "title=" + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8)
                    + "&source_branch=" + java.net.URLEncoder.encode(headBranch, java.nio.charset.StandardCharsets.UTF_8)
                    + "&target_branch=" + java.net.URLEncoder.encode(baseBranch, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "create-pr-gitlab");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                PullRequestInfo info = PullRequestParser.parseGitlab(resp.body(), headBranch);
                return info != null ? info : new PullRequestInfo(extractJsonField(resp.body(), "web_url"), extractJsonField(resp.body(), "iid"), "opened", null);
            }
            logFluxWarn("createPR", repoUrl, "CREATE", "GitLab MR creation failed (status %d)", resp.statusCode());
        }
        return null;
    }

    /**
     * Fetch current PR/MR state from GitHub or GitLab.
     *
     * @param repoUrl   git URL
     * @param prNumber  PR number/IID
     * @param token     auth token (required)
     * @return state string (open/closed/merged etc) or null if unsupported/failure
     */
    private PullRequestInfo fetchPullRequestState(String repoUrl, String prNumber, String token) throws Exception {
        if (repoUrl == null || prNumber == null || token == null) {
            return null;
        }
        String trimmed = normalizeRepoUrl(repoUrl);
        java.net.URI uri = java.net.URI.create(trimmed);
        String host = uri.getHost();
        String path = uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (host == null || path.isBlank()) {
            return null;
        }
        if (host.contains("github")) {
            String[] parts = path.split("/");
            if (parts.length < 2) return null;
            String owner = parts[0];
            String repo = parts[1];
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "fetch-pr-github");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return PullRequestParser.parseGithub(resp.body(), prNumber);
            }
            LOG.debug("GitHub PR state lookup failed: status {} body {}", resp.statusCode(), resp.body());
        } else {
            String project = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
            String apiUrl = uri.getScheme() + "://" + host + "/api/v4/projects/" + project + "/merge_requests/" + prNumber;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .timeout(httpTimeout)
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHttpWithRetry(req, "fetch-pr-gitlab");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return PullRequestParser.parseGitlab(resp.body(), prNumber);
            }
            LOG.debug("GitLab MR state lookup failed: status {} body {}", resp.statusCode(), resp.body());
        }
        return null;
    }

    private String resolveToken(String credentialAlias) {
        if (credentialAlias == null || credentialAlias.isBlank()) {
            return null;
        }
        try {
            String resolved = gitCredentialService.resolveSecret(credentialAlias);
            if (resolved != null && !resolved.contains("BEGIN")) {
                return resolved;
            }
        } catch (Exception ex) {
            LOG.debug("Failed to resolve token for alias {}: {}", credentialAlias, ex.toString());
        }
        return null;
    }

    private void refreshPrState(K8sReleaseEntity meta, HelmReleaseDTO dto) {
        if (meta == null || meta.getGitPrNumber() == null || meta.getGitPrNumber().isBlank()) {
            return;
        }
        String token = resolveToken(meta.getGitCredentialAlias());
        if (token == null) {
            return;
        }
        try {
            PullRequestInfo info = fetchPullRequestState(meta.getGitRepoUrl(), meta.getGitPrNumber(), token);
            if (info == null) {
                return;
            }
            dto.gitPrState = firstNonBlank(info.state, dto.gitPrState);
            dto.gitPrUrl = firstNonBlank(info.url, dto.gitPrUrl);
            releaseMetadataService.updatePrInfo(meta.getNamespace(), meta.getReleaseName(), dto.gitPrUrl, info.id, dto.gitPrState);
            String prMessage = "PR state: " + dto.gitPrState;
            if (info.message != null && !info.message.isBlank()) {
                prMessage += " (" + info.message + ")";
            }
            dto.message = appendMessage(dto.message, prMessage);
            if (dto.gitPrState != null) {
                boolean hrMissing = hrNotAvailable(meta.getNamespace(), meta.getReleaseName());
                if ("merged".equalsIgnoreCase(dto.gitPrState) && hrMissing) {
                    dto.status = "PROGRESSING";
                    dto.message = appendMessage(dto.message, "PR merged; waiting for Flux reconciliation");
                } else if ("closed".equalsIgnoreCase(dto.gitPrState) && hrMissing) {
                    dto.status = "FAILED";
                    dto.message = appendMessage(dto.message, "PR closed without merge");
                }
            }
        } catch (Exception ex) {
            logFluxWarn(meta != null ? meta.getNamespace() : null, meta != null ? meta.getReleaseName() : null,
                    "pr-poll", "PR polling failed: %s", ex.toString());
        }
    }

    /**
     * Very small JSON field extractor to avoid extra dependencies.
     */
    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int startQuote = json.indexOf("\"", colon);
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (startQuote < 0 || endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    @Override
    public void destroy(String namespace, String releaseName) throws Exception {
        var meta = releaseMetadataService.find(namespace, releaseName);
        if (meta == null || !"FLUX_GITOPS".equalsIgnoreCase(meta.getDeploymentMode())) {
            LOG.info("Flux destroy skipped for {}/{} because deploymentMode is not FLUX_GITOPS or metadata missing", namespace, releaseName);
            return;
        }
        logFluxInfo(namespace, releaseName, "destroy", "Destroy requested for repo=%s branch=%s path=%s", meta.getGitRepoUrl(), meta.getGitBranch(), meta.getGitPath());
        List<String> errors = new ArrayList<>();
        boolean gitDeleted = false;
        String repoUrl = meta.getGitRepoUrl();
        String branch = meta.getGitBranch();
        String path = meta.getGitPath();
        String token = null;
        String sshKey = null;
        try {
            String resolved = gitCredentialService.resolveSecret(meta.getGitCredentialAlias());
            if (resolved != null && resolved.contains("BEGIN")) {
                sshKey = resolved;
            } else {
                token = resolved;
            }
        } catch (Exception ex) {
            errors.add("Credential resolution failed: " + ex.getMessage());
        }
        try {
            destroy(repoUrl, branch, path, token, sshKey);
            gitDeleted = true;
        } catch (Exception ex) {
            errors.add("Git cleanup failed: " + ex.getMessage());
            LOG.warn("Flux destroy git cleanup failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }
        // Best-effort cluster cleanup of HR/HelmRepository
        try {
            deleteHelmRelease(meta.getNamespace(), meta.getReleaseName());
            if (meta.getRepoId() != null) {
                deleteHelmRepository(meta.getRepoId());
            }
        } catch (Exception ex) {
            errors.add("Cluster cleanup failed: " + ex.getMessage());
            LOG.warn("Flux destroy: cluster cleanup failed for {}/{}: {}", namespace, releaseName, ex.toString());
        }
        if (errors.isEmpty() || gitDeleted) {
            try {
                releaseMetadataService.delete(namespace, releaseName);
            } catch (Exception ex) {
                errors.add("Failed to delete metadata: " + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("Flux destroy encountered issues: " + String.join(" | ", errors));
        }
    }

    /**
     * Delete rendered manifests from Git and push the change.
     *
     * @param repoUrl   Git repository URL.
     * @param branch    branch to use (will be created if absent).
     * @param repoPath  path to delete (relative to repo root).
     * @param authToken HTTPS token (optional).
     * @param sshKey    SSH private key (optional).
     */
    public void destroy(String repoUrl, String branch, String repoPath, String authToken, String sshKey) throws Exception {
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warn("Flux destroy skipped because repoUrl is missing");
            return;
        }
        String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        Path repoDir = fluxRoot.resolve("gitops-workdir-destroy");
        GitClient gitClient = new GitClient(repoDir, repoUrl, effectiveBranch, authToken, sshKey);
        withGitRetry(() -> { gitClient.sync(); return null; }, "destroy-sync");
        withGitRetry(() -> { gitClient.checkoutBranch(effectiveBranch); return null; }, "destroy-checkout");

        Path deletePath = validateRelative((repoPath == null || repoPath.isBlank())
                ? Path.of("clusters/default")
                : Path.of(repoPath));
        logFluxInfo("-", deletePath.toString(), "destroy", "Removing path %s", deletePath);
        gitClient.deletePath(deletePath);
        if (gitClient.hasChanges()) {
            String sha = withGitRetry(() -> gitClient.commitAndPush("Flux GitOps delete " + deletePath), "destroy-commit");
            LOG.info("Flux GitOps deleted {} on branch {} commit {}", deletePath, effectiveBranch, sha);
        } else {
            LOG.info("Flux GitOps delete skipped commit because no changes detected at {}", deletePath);
        }
    }

    /**
     * Compute the target directory path for the given prefix/namespace/release.
     *
     * @param pathPrefix user-provided prefix (can be relative).
     * @param namespace  namespace of the release.
     * @param release    release name.
     * @return resolved path under the configured flux root.
     */
    public Path targetPath(String pathPrefix, String namespace, String release) {
        return validateRelative(Path.of(pathPrefix).resolve(namespace).resolve(release));
    }

    static List<java.util.Map<String, String>> mapConditions(java.util.List<java.util.Map<String, Object>> conds) {
        List<java.util.Map<String, String>> list = new ArrayList<>();
        for (java.util.Map<String, Object> c : conds) {
            java.util.Map<String, String> mapped = new LinkedHashMap<>();
            mapped.put("type", Objects.toString(c.get("type"), ""));
            mapped.put("status", Objects.toString(c.get("status"), ""));
            mapped.put("reason", Objects.toString(c.get("reason"), ""));
            mapped.put("message", Objects.toString(c.get("message"), ""));
            mapped.put("lastTransitionTime", Objects.toString(c.get("lastTransitionTime"), ""));
            list.add(mapped);
        }
        return list;
    }

    static Path validateRelative(Path path) {
        Path normalized = path.normalize();
        if (normalized.isAbsolute()) {
            throw new IllegalArgumentException("Flux GitOps path must be relative to repo: " + path);
        }
        for (Path part : normalized) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("Flux GitOps path cannot traverse outside repo: " + path);
            }
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String appendMessage(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        return (existing == null || existing.isBlank()) ? addition : existing + " | " + addition;
    }

    /**
     * Derive a status/message from Flux conditions. This captures Ready/Progressing/Stalled
     * and produces a compact summary for operators.
     *
     * @param dto    target DTO to update
     * @param conds  list of condition maps from HelmRelease.status.conditions
     */
    static void evaluateConditions(HelmReleaseDTO dto, java.util.List<java.util.Map<String, Object>> conds) {
        dto.conditions = mapConditions(conds);
        // Ready drives primary state
        java.util.Map<String, Object> ready = conds.stream()
                .filter(c -> "Ready".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (ready != null) {
            String st = Objects.toString(ready.get("status"), "Unknown");
            if ("True".equalsIgnoreCase(st)) {
                dto.status = "SUCCEEDED";
            } else if ("False".equalsIgnoreCase(st)) {
                dto.status = "FAILED";
            } else {
                dto.status = "PROGRESSING";
            }
            String ltt = Objects.toString(ready.get("lastTransitionTime"), null);
            if (ltt != null) {
                dto.lastTransitionTime = ltt;
            }
            String msg = Objects.toString(ready.get("message"), null);
            String reason = Objects.toString(ready.get("reason"), null);
            dto.message = appendMessage(dto.message, (reason != null ? reason + ": " : "") + (msg != null ? msg : ""));
        }

        // Progressing gives detail while reconciling
        java.util.Map<String, Object> progressing = conds.stream()
                .filter(c -> "Progressing".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (progressing != null) {
            if (dto.status == null || "UNKNOWN".equals(dto.status)) {
                dto.status = "PROGRESSING";
            }
            String progMsg = Objects.toString(progressing.get("message"), null);
            if (progMsg != null && (dto.message == null || dto.message.isBlank())) {
                dto.message = appendMessage(dto.message, progMsg);
            }
        }

        // Stalled (common Flux condition) marks failure.
        java.util.Map<String, Object> stalled = conds.stream()
                .filter(c -> "Stalled".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (stalled != null) {
            String st = Objects.toString(stalled.get("status"), "");
            if ("True".equalsIgnoreCase(st)) {
                dto.status = "FAILED";
            }
            String stallMsg = Objects.toString(stalled.get("message"), null);
            String stallReason = Objects.toString(stalled.get("reason"), null);
            if (stallMsg != null) {
                dto.message = appendMessage(dto.message, (stallReason != null ? stallReason + ": " : "") + stallMsg);
            }
        }

        // Capture a concise condition summary for operators.
        StringBuilder summary = new StringBuilder();
        for (java.util.Map<String, Object> c : conds) {
            String type = Objects.toString(c.get("type"), "");
            String st = Objects.toString(c.get("status"), "");
            String reason = Objects.toString(c.get("reason"), "");
            String msg = Objects.toString(c.get("message"), "");
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(type).append("=").append(st);
            if (!reason.isBlank()) {
                summary.append(" (").append(reason).append(")");
            }
            if (!msg.isBlank()) {
                summary.append(" ").append(msg);
            }
        }
        if (summary.length() > 0) {
            dto.message = appendMessage(dto.message, summary.toString());
        }
        dto.conditionSummary = summary.length() > 0 ? summary.toString() : null;

        if (dto.status == null || dto.status.isBlank()) {
            dto.status = "UNKNOWN";
        }
    }

    static void evaluateGenerationStaleness(HelmReleaseDTO dto, long desired, long observed) {
        if (desired >= 0 && observed >= 0 && observed < desired) {
            dto.staleGeneration = true;
            if (dto.status == null || dto.status.isBlank() || "UNKNOWN".equalsIgnoreCase(dto.status)) {
                dto.status = "PROGRESSING";
            }
            dto.message = appendMessage(dto.message, "Waiting for generation " + desired + " (observed " + observed + ")");
        }
    }

    /**
     * Evaluate HelmRepository conditions to expose source health.
     */
    static void evaluateSourceConditions(HelmReleaseDTO dto, java.util.List<java.util.Map<String, Object>> conds) {
        dto.sourceConditions = mapConditions(conds);
        java.util.Map<String, Object> ready = conds.stream()
                .filter(c -> "Ready".equalsIgnoreCase(Objects.toString(c.get("type"), "")))
                .findFirst().orElse(null);
        if (ready != null) {
            dto.sourceStatus = Objects.toString(ready.get("status"), "Unknown");
            String msg = Objects.toString(ready.get("message"), "");
            String reason = Objects.toString(ready.get("reason"), "");
            dto.sourceMessage = (reason.isBlank() ? "" : reason + ": ") + msg;
        }
        if (dto.sourceStatus == null || dto.sourceStatus.isBlank()) {
            dto.sourceStatus = "Unknown";
        }
        if (dto.reconcileMessage == null && dto.sourceMessage != null) {
            dto.reconcileMessage = dto.sourceMessage;
        }
        StringBuilder summary = new StringBuilder();
        for (java.util.Map<String, Object> c : conds) {
            String type = Objects.toString(c.get("type"), "");
            String st = Objects.toString(c.get("status"), "");
            String reason = Objects.toString(c.get("reason"), "");
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(type).append("=").append(st);
            if (!reason.isBlank()) {
                summary.append(" (").append(reason).append(")");
            }
        }
        dto.sourceConditionSummary = summary.length() > 0 ? summary.toString() : null;
    }

    /**
     * Delete a HelmRelease from the cluster (best effort).
     */
    private void deleteHelmRelease(String namespace, String releaseName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return;
        }
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("helm.toolkit.fluxcd.io")
                    .withVersion("v2beta2")
                    .withScope("Namespaced")
                    .withPlural("helmreleases")
                    .build();
            kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .withName(releaseName)
                    .delete();
            LOG.info("Deleted HelmRelease {}/{} from cluster", namespace, releaseName);
        } catch (Exception ex) {
            LOG.debug("Failed to delete HelmRelease {}/{}: {}", namespace, releaseName, ex.toString());
        }
    }

    /**
     * Delete a HelmRepository from the flux-system namespace (best effort).
     */
    private void deleteHelmRepository(String repoName) {
        if (kubernetesService == null || kubernetesService.getClient() == null) {
            return;
        }
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup("source.toolkit.fluxcd.io")
                    .withVersion("v1beta2")
                    .withScope("Namespaced")
                    .withPlural("helmrepositories")
                    .build();
            kubernetesService.getClient()
                    .genericKubernetesResources(ctx)
                    .inNamespace("flux-system")
                    .withName(repoName)
                    .delete();
            LOG.info("Deleted HelmRepository {} from cluster", repoName);
        } catch (Exception ex) {
            LOG.debug("Failed to delete HelmRepository {}: {}", repoName, ex.toString());
        }
    }

    /**
     * Execute a git operation with a tiny retry window to absorb transient network hiccups.
     *
     * @param op    git callable to run
     * @param label human-readable label for logging
     */
    private <T> T withGitRetry(Callable<T> op, String label) throws Exception {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return op.call();
            } catch (IOException io) {
                lastIo = io;
                LOG.warn("Git operation {} failed (attempt {}/{}): {}", label, attempt, MAX_RETRY_ATTEMPTS, io.getMessage());
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw io;
                }
                // Exponential backoff: base delay * attempt number
                long backoffDelayMs = RETRY_BASE_DELAY_MS * attempt;
                Thread.sleep(backoffDelayMs);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        return op.call();
    }

    private HttpResponse<String> sendHttpWithRetry(HttpRequest request, String label) throws InterruptedException, IOException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < MAX_RETRY_ATTEMPTS) {
                    lastIo = new IOException("HTTP " + response.statusCode());
                    logFluxWarn(null, label, "http", "Request returned %d, retrying", response.statusCode());
                    Thread.sleep(HTTP_RETRY_BASE_DELAY_MS * attempt);
                    continue;
                }
                return response;
            } catch (SocketTimeoutException toe) {
                lastIo = toe;
                logFluxWarn(null, label, "http-timeout", "Timeout on attempt %d: %s", attempt, toe.getMessage());
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw toe;
                }
                Thread.sleep(HTTP_RETRY_BASE_DELAY_MS * attempt);
            } catch (IOException io) {
                lastIo = io;
                logFluxWarn(null, label, "http", "HTTP request failed on attempt %d: %s", attempt, io.getMessage());
                if (attempt >= 3) {
                    throw io;
                }
                Thread.sleep(250L * attempt);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        throw new IOException("Failed to execute HTTP request for " + label);
    }

    private void logFluxInfo(String namespace, String release, String phase, String message, Object... args) {
        String context = buildContext(namespace, release);
        String formatted = (args == null || args.length == 0) ? message : String.format(message, args);
        LOG.info("[FluxGitOps:%s][%s] %s", context, phase, formatted);
    }

    private void logFluxWarn(String namespace, String release, String phase, String message, Object... args) {
        String context = buildContext(namespace, release);
        String formatted = (args == null || args.length == 0) ? message : String.format(message, args);
        LOG.warn("[FluxGitOps:%s][%s] %s", context, phase, formatted);
    }

    private String buildContext(String namespace, String release) {
        String ns = (namespace == null || namespace.isBlank()) ? "-" : namespace;
        String rel = (release == null || release.isBlank()) ? "-" : release;
        return ns + "/" + rel;
    }

    private String normalizeRepoUrl(String repoUrl) {
        if (repoUrl == null) return null;
        String trimmed = repoUrl.replace(".git", "");
        if (trimmed.startsWith("git@")) {
            trimmed = "https://" + trimmed.substring(4).replace(":", "/");
        }
        return trimmed;
    }
}
