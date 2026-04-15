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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal git wrapper that shells out to the system git binary.
 * This keeps dependencies small while still providing deterministic commits.
 * If git is not available, this class will throw with a descriptive message.
 */
public class GitClient {
    private static final Logger LOG = LoggerFactory.getLogger(GitClient.class);

    private final Path workspace;
    private final String repoUrl;
    private final String baseBranch;
    private final String authToken;
    private final String sshKey;
    private final String effectiveRepoUrl;
    private String authorName;
    private String authorEmail;
    private Path sshKeyPath;

    public GitClient(Path workspace, String repoUrl, String baseBranch, String authToken) {
        this(workspace, repoUrl, baseBranch, authToken, null);
    }

    public GitClient(Path workspace, String repoUrl, String baseBranch, String authToken, String sshKey) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.repoUrl = repoUrl;
        this.baseBranch = baseBranch;
        this.authToken = authToken;
        this.sshKey = sshKey;
        this.effectiveRepoUrl = buildEffectiveRepoUrl(repoUrl, authToken);
    }

    private String buildEffectiveRepoUrl(String url, String token) {
        if (url == null) {
            return null;
        }
        if (token == null || token.isBlank() || !url.startsWith("https://")) {
            return url;
        }
        // simple token injection for HTTPS remotes: https://token@host/path
        String withoutScheme = url.substring("https://".length());
        return "https://" + token + "@" + withoutScheme;
    }

    /**
     * Apply an explicit author/committer identity for commits.
     */
    public GitClient withAuthor(String name, String email) {
        this.authorName = (name == null || name.isBlank()) ? null : name;
        this.authorEmail = (email == null || email.isBlank()) ? null : email;
        return this;
    }

    /**
     * Clone (or pull) the repository into the workspace.
     */
    public void sync() throws IOException, InterruptedException {
        if (Files.exists(workspace.resolve(".git"))) {
            if (effectiveRepoUrl != null && !effectiveRepoUrl.equals(repoUrl)) {
                // ensure remote uses the auth-bearing URL if changed
                runInRepo("git", "remote", "set-url", "origin", effectiveRepoUrl);
            }
            run("git", "fetch", "--all");
            try {
                run("git", "show-ref", "--verify", "refs/heads/" + baseBranch);
            } catch (IOException missingBranch) {
                // create local branch tracking origin/<baseBranch>
                run("git", "checkout", "-b", baseBranch, "origin/" + baseBranch);
            }
            run("git", "checkout", baseBranch);
            run("git", "pull");
            return;
        }
        Files.createDirectories(workspace);
        try {
            run("git", "clone", "--branch", baseBranch, effectiveRepoUrl, workspace.toString());
        } catch (IOException cloneFailed) {
            // fallback: clone default branch then create baseBranch locally
            LOG.warn("Clone with explicit branch {} failed ({}). Falling back to default branch clone.", baseBranch, cloneFailed.getMessage());
            run("git", "clone", effectiveRepoUrl, workspace.toString());
            runInRepo("git", "checkout", "-B", baseBranch);
        }
    }

    /**
     * Create or switch to a branch name.
     */
    public void checkoutBranch(String branch) throws IOException, InterruptedException {
        runInRepo("git", "checkout", "-B", branch);
    }

    /**
     * Write a file relative to the repo root.
     */
    public void writeFile(Path relativePath, String content) throws IOException {
        Path abs = resolveSafe(relativePath);
        Files.createDirectories(abs.getParent());
        Files.writeString(abs, content, StandardCharsets.UTF_8);
    }

    /**
     * Stage, commit, and push. Returns the commit SHA.
     */
    public String commitAndPush(String message) throws IOException, InterruptedException {
        String sha = commitInternal(message);
        if (sha == null) {
            return null;
        }
        runInRepo("git", "push", "origin", "HEAD");
        LOG.info("Pushed commit {} to {}", sha, repoUrl);
        return sha;
    }

    /**
     * Stage and commit without pushing (used for PR stub flows).
     *
     * @param message commit message to use.
     * @return the commit SHA.
     */
    public String commitWithoutPush(String message) throws IOException, InterruptedException {
        String sha = commitInternal(message);
        if (sha == null) {
            return null;
        }
        LOG.info("Created local commit {} (not pushed) for {}", sha, repoUrl);
        return sha;
    }

    private String run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureEnv(pb);
        return execute(pb, cmd);
    }

    private String runInRepo(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace.toFile());
        configureEnv(pb);
        return execute(pb, cmd);
    }

    private void configureEnv(ProcessBuilder pb) throws IOException {
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (authorName != null) {
            pb.environment().put("GIT_AUTHOR_NAME", authorName);
            pb.environment().put("GIT_COMMITTER_NAME", authorName);
        }
        if (authorEmail != null) {
            pb.environment().put("GIT_AUTHOR_EMAIL", authorEmail);
            pb.environment().put("GIT_COMMITTER_EMAIL", authorEmail);
        }

        if (sshKey != null && !sshKey.isBlank()) {
            if (sshKeyPath == null) {
                sshKeyPath = workspace.resolve(".gitops-ssh-key");
                Files.createDirectories(workspace);
                // Write with mode 0600 in one atomic operation so the key is never
                // world-readable, even momentarily. Falls back to best-effort chmod
                // on non-POSIX filesystems (e.g. Windows, some Docker volumes).
                try {
                    Set<PosixFilePermission> ownerRW = PosixFilePermissions.fromString("rw-------");
                    Files.writeString(
                        sshKeyPath, sshKey, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                    );
                    Files.setPosixFilePermissions(sshKeyPath, ownerRW);
                } catch (UnsupportedOperationException nonPosix) {
                    // Non-POSIX filesystem: write the file and restrict as best we can
                    Files.writeString(sshKeyPath, sshKey, StandardCharsets.UTF_8);
                    sshKeyPath.toFile().setReadable(false, false);
                    sshKeyPath.toFile().setReadable(true, true);
                    sshKeyPath.toFile().setWritable(false, false);
                    sshKeyPath.toFile().setWritable(true, true);
                    sshKeyPath.toFile().setExecutable(false, false);
                    LOG.warn("POSIX permissions unavailable; SSH key restricted via File API (best effort): {}", sshKeyPath);
                }
            }
            pb.environment().put("GIT_SSH_COMMAND", "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=accept-new");
        } else if (authToken != null && !authToken.isBlank() && repoUrl != null && repoUrl.startsWith("https://")) {
            pb.environment().put("GIT_ASKPASS", "echo");
            pb.environment().put("GIT_TOKEN", authToken);
        }
    }

    private String execute(ProcessBuilder pb, String... cmd) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git command failed (" + code + "): " + String.join(" ", cmd) + " output=" + out);
        }
        return out;
    }

    private String commitInternal(String message) throws IOException, InterruptedException {
        runInRepo("git", "add", ".");
        if (!hasChanges()) {
            LOG.info("No git changes detected, skipping commit for {}", repoUrl);
            return null;
        }
        runInRepo("git", "commit", "-m", message);
        return runInRepo("git", "rev-parse", "HEAD").trim();
    }

    /**
     * Generate a unique branch name when PR mode is requested but not yet implemented.
     */
    public static String generateBranch(String prefix) {
        return (prefix == null || prefix.isBlank() ? "gitops" : prefix) + "-" + UUID.randomUUID();
    }

    /**
     * Remove a path (file or directory) relative to repo root.
     */
    public void deletePath(Path relative) throws IOException {
        Path abs = resolveSafe(relative);
        if (Files.notExists(abs)) {
            return;
        }
        if (Files.isDirectory(abs)) {
            try (var walk = Files.walk(abs)) {
                walk.sorted((a, b) -> b.compareTo(a)) // delete children first
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) { LOG.warn("Failed to delete {}", p); }
                    });
            }
        } else {
            Files.deleteIfExists(abs);
        }
    }

    /**
     * Returns true if there are staged or unstaged changes.
     */
    public boolean hasChanges() throws IOException, InterruptedException {
        String output = runInRepo("git", "status", "--porcelain");
        return output != null && !output.trim().isEmpty();
    }

    /**
     * Ensure callers cannot escape the repository root via ".." or absolute paths.
     */
    private Path resolveSafe(Path relative) throws IOException {
        if (relative == null) {
            throw new IOException("Path is required");
        }
        Path normalized = workspace.resolve(relative).normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IOException("Refusing to operate outside repo: " + relative);
        }
        return normalized;
    }
}
