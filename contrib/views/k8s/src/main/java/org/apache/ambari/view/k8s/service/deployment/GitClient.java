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

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Pure-Java git wrapper built on JGit — no OS {@code git} binary required (more portable/robust) and
 * a single {@link TransportConfigCallback} hook where SSH (and, later, an outbound proxy) is attached.
 * The public API is unchanged from the previous binary-based implementation.
 *
 * <p>Auth: HTTPS remotes use the token as the username (matching the old {@code https://token@host}),
 * SSH remotes use the provided private key in memory (no key file on disk). SSH host-key checking is
 * relaxed ("no") for unattended GitOps — the classic JSch provider has no OpenSSH "accept-new".
 */
public class GitClient {
    private static final Logger LOG = LoggerFactory.getLogger(GitClient.class);

    private final Path workspace;
    private final String repoUrl;
    private final String baseBranch;
    private final String authToken;
    private final String sshKey;
    private String authorName;
    private String authorEmail;
    private final TransportConfigCallback transportConfig;

    public GitClient(Path workspace, String repoUrl, String baseBranch, String authToken) {
        this(workspace, repoUrl, baseBranch, authToken, null);
    }

    public GitClient(Path workspace, String repoUrl, String baseBranch, String authToken, String sshKey) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.repoUrl = repoUrl;
        this.baseBranch = baseBranch;
        this.authToken = authToken;
        this.sshKey = sshKey;
        this.transportConfig = buildTransportConfig();
    }

    /** Apply an explicit author/committer identity for commits. */
    public GitClient withAuthor(String name, String email) {
        this.authorName = (name == null || name.isBlank()) ? null : name;
        this.authorEmail = (email == null || email.isBlank()) ? null : email;
        return this;
    }

    // ---- auth / transport ------------------------------------------------------------------------

    private CredentialsProvider credentials() {
        if (authToken != null && !authToken.isBlank() && repoUrl != null && repoUrl.startsWith("https")) {
            // Token as username, empty password (works for GitHub/GitLab PAT and mirrors token@host).
            return new UsernamePasswordCredentialsProvider(authToken, "");
        }
        return null;
    }

    /**
     * Transport callback: install the in-memory SSH key on SSH transports. This is also the single
     * place an outbound proxy will be wired later (JGit honors a per-transport proxy here).
     */
    private TransportConfigCallback buildTransportConfig() {
        if (sshKey == null || sshKey.isBlank()) {
            return null;
        }
        final JschConfigSessionFactory sshFactory = new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, Session session) {
                // Classic JSch has no "accept-new"; disable strict checking for unattended automation.
                session.setConfig("StrictHostKeyChecking", "no");
            }
            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch jsch = super.createDefaultJSch(fs);
                jsch.addIdentity("kdps-gitops",
                        sshKey.getBytes(StandardCharsets.UTF_8), null, null);
                return jsch;
            }
        };
        return transport -> {
            if (transport instanceof SshTransport) {
                ((SshTransport) transport).setSshSessionFactory(sshFactory);
            }
        };
    }

    /** Apply credentials + transport (SSH/proxy) config to any JGit remote command. */
    private <C extends TransportCommand<C, ?>> C configure(C command) {
        command.setCredentialsProvider(credentials());
        if (transportConfig != null) {
            command.setTransportConfigCallback(transportConfig);
        }
        return command;
    }

    // ---- repository operations -------------------------------------------------------------------

    /** Clone (or fetch+checkout) the repository into the workspace. */
    public void sync() throws IOException, InterruptedException {
        try {
            if (Files.exists(workspace.resolve(".git"))) {
                try (Git git = Git.open(workspace.toFile())) {
                    StoredConfig cfg = git.getRepository().getConfig();
                    cfg.setString("remote", "origin", "url", repoUrl);
                    cfg.save();
                    configure(git.fetch().setRemote("origin")).call();
                    boolean exists = git.getRepository().findRef("refs/heads/" + baseBranch) != null;
                    CheckoutCommand checkout = git.checkout().setName(baseBranch);
                    if (!exists) {
                        checkout.setCreateBranch(true)
                                .setUpstreamMode(SetupUpstreamMode.TRACK)
                                .setStartPoint("origin/" + baseBranch);
                    }
                    checkout.call();
                    configure(git.pull().setRemote("origin")).call();
                }
                return;
            }
            Files.createDirectories(workspace);
            try {
                configure(Git.cloneRepository()
                        .setURI(repoUrl)
                        .setBranch(baseBranch)
                        .setDirectory(workspace.toFile()))
                        .call().close();
            } catch (GitAPIException branchClone) {
                LOG.warn("Clone of branch {} failed ({}). Falling back to default branch + local branch.",
                        baseBranch, branchClone.getMessage());
                deleteDirContents(workspace);
                try (Git git = configure(Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(workspace.toFile())).call()) {
                    boolean exists = git.getRepository().findRef("refs/heads/" + baseBranch) != null;
                    git.checkout().setName(baseBranch).setCreateBranch(!exists).setForceRefUpdate(true).call();
                }
            }
        } catch (GitAPIException e) {
            throw new IOException("git sync failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    /** Create or reset a branch to the current HEAD (equivalent to {@code git checkout -B}). */
    public void checkoutBranch(String branch) throws IOException, InterruptedException {
        try (Git git = Git.open(workspace.toFile())) {
            boolean exists = git.getRepository().findRef("refs/heads/" + branch) != null;
            git.checkout().setName(branch).setCreateBranch(!exists).setForceRefUpdate(true).call();
        } catch (GitAPIException e) {
            throw new IOException("git checkout " + branch + " failed: " + e.getMessage(), e);
        }
    }

    /** Write a file relative to the repo root. */
    public void writeFile(Path relativePath, String content) throws IOException {
        Path abs = resolveSafe(relativePath);
        Files.createDirectories(abs.getParent());
        Files.writeString(abs, content, StandardCharsets.UTF_8);
    }

    /** Stage, commit, and push. Returns the commit SHA (or null if nothing changed). */
    public String commitAndPush(String message) throws IOException, InterruptedException {
        String sha = commitInternal(message);
        if (sha == null) {
            return null;
        }
        try (Git git = Git.open(workspace.toFile())) {
            String branch = git.getRepository().getBranch();
            configure(git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/" + branch))).call();
            LOG.info("Pushed commit {} to {} ({})", sha, repoUrl, branch);
        } catch (GitAPIException e) {
            throw new IOException("git push failed for " + repoUrl + ": " + e.getMessage(), e);
        }
        return sha;
    }

    /** Stage and commit without pushing (used for PR stub flows). */
    public String commitWithoutPush(String message) throws IOException, InterruptedException {
        String sha = commitInternal(message);
        if (sha != null) {
            LOG.info("Created local commit {} (not pushed) for {}", sha, repoUrl);
        }
        return sha;
    }

    private String commitInternal(String message) throws IOException, InterruptedException {
        try (Git git = Git.open(workspace.toFile())) {
            git.add().addFilepattern(".").call();              // new + modified files
            git.add().addFilepattern(".").setUpdate(true).call(); // stage deletions of tracked files
            if (git.status().call().isClean()) {
                LOG.info("No git changes detected, skipping commit for {}", repoUrl);
                return null;
            }
            var commit = git.commit().setMessage(message);
            if (authorName != null && authorEmail != null) {
                commit.setAuthor(authorName, authorEmail).setCommitter(authorName, authorEmail);
            }
            RevCommit c = commit.call();
            return c.getName();
        } catch (GitAPIException e) {
            throw new IOException("git commit failed for " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    /** Returns true if there are staged or unstaged changes. */
    public boolean hasChanges() throws IOException, InterruptedException {
        try (Git git = Git.open(workspace.toFile())) {
            return !git.status().call().isClean();
        } catch (GitAPIException e) {
            throw new IOException("git status failed: " + e.getMessage(), e);
        }
    }

    /** Remove a path (file or directory) relative to repo root. */
    public void deletePath(Path relative) throws IOException {
        Path abs = resolveSafe(relative);
        if (Files.notExists(abs)) {
            return;
        }
        if (Files.isDirectory(abs)) {
            try (var walk = Files.walk(abs)) {
                walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) { LOG.warn("Failed to delete {}", p); }
                    });
            }
        } else {
            Files.deleteIfExists(abs);
        }
    }

    /** Generate a unique branch name for PR-style flows. */
    public static String generateBranch(String prefix) {
        return (prefix == null || prefix.isBlank() ? "gitops" : prefix) + "-" + UUID.randomUUID();
    }

    /** Ensure callers cannot escape the repository root via ".." or absolute paths. */
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

    /** Delete the contents of a directory (used to retry a failed partial clone). */
    private void deleteDirContents(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .filter(p -> !p.equals(dir))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { LOG.warn("Failed to delete {}", p); }
                });
        }
    }
}
