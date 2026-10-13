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
import org.apache.ambari.view.k8s.service.ProxySupport;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pure-Java git wrapper built on JGit — no OS {@code git} binary required (more portable/robust) and
 * a single {@link TransportConfigCallback} hook where SSH and the outbound proxy are attached.
 *
 * <p>Auth: HTTPS remotes use the token as the username (matching the old {@code https://token@host}),
 * SSH remotes use the provided private key in memory (no key file on disk). SSH host-key checking is
 * relaxed ("no") for unattended GitOps — the classic JSch provider has no OpenSSH "accept-new".
 *
 * <p>Every clone, fetch and push gives up once the server has sent nothing for {@link #withTimeout the timeout}:
 * JGit's own default waits forever, so a Git server or proxy that stops answering would hold the caller (and the
 * release's workspace lock) until Ambari is restarted.
 */
public class GitClient {
    private static final Logger LOG = LoggerFactory.getLogger(GitClient.class);

    /** Seconds a clone, fetch or push may go without any data from the server, unless set otherwise. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** SSH keepalive: a probe after 30 s of silence, the connection dropped after 4 unanswered ones. */
    static final int SERVER_ALIVE_INTERVAL_MS = 30_000;
    static final int SERVER_ALIVE_COUNT_MAX = 4;

    /**
     * What the message of a failure says when trying again cannot help: the server turned down the credentials, or
     * stopped answering (JGit's "Read timed out after", the JDK's "Read timed out", JSch's "timeout").
     */
    private static final List<String> NOT_RETRYABLE_MESSAGES = List.of(
            "timed out", "time out", "timeout",
            "not authorized", "authentication", "auth fail", "auth cancel", "not permitted", "forbidden");

    private final Path workspace;
    private final String repoUrl;
    private final String baseBranch;
    private final String authToken;
    private final String sshKey;
    private String authorName;
    private String authorEmail;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private final TransportConfigCallback transportConfig;
    private ProxySupport proxy;

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

    /**
     * Sets how long a clone, fetch or push may go without any data from the server before it fails. A transfer that
     * is slow but still moving is not cut off.
     *
     * @param seconds the timeout; {@link #DEFAULT_TIMEOUT_SECONDS} when not positive
     */
    public GitClient withTimeout(int seconds) {
        this.timeoutSeconds = seconds > 0 ? seconds : DEFAULT_TIMEOUT_SECONDS;
        return this;
    }

    /**
     * Sends the HTTP(S) transfers of this client through the view's outbound proxy, for the hosts it proxies. Only
     * this client's connections are affected; SSH remotes are not proxied.
     *
     * @param proxy the proxy settings; null or disabled connects directly
     */
    public GitClient withProxy(ProxySupport proxy) {
        this.proxy = proxy;
        return this;
    }

    /** The timeout of clones, fetches and pushes, in seconds. */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Whether trying a failed Git operation again may help. Not when the server refused the push, turned down the
     * credentials or stopped answering: a refusal and bad credentials get the same answer again, and a server that
     * did not answer within the timeout already had all that time, which each new attempt would add again while the
     * release's workspace stays locked.
     */
    public static boolean isRetryable(Throwable failure) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            // SocketTimeoutException and JGit's own read and write timeouts are InterruptedIOExceptions.
            if (cause instanceof GitPushRejectedException || cause instanceof InterruptedIOException) {
                return false;
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (NOT_RETRYABLE_MESSAGES.stream().anyMatch(message::contains)) {
                return false;
            }
        }
        return true;
    }

    // ---- auth / transport ------------------------------------------------------------------------

    private CredentialsProvider credentials() {
        if (authToken == null || authToken.isBlank() || repoUrl == null) {
            return null;
        }
        if (repoUrl.startsWith("https://") || repoUrl.startsWith("http://")) {
            if (repoUrl.startsWith("http://") && !plaintextWarningLogged) {
                plaintextWarningLogged = true;
                LOG.warn("Git repository {} is reached over plain HTTP: the access token travels unencrypted", redact(repoUrl));
            }
            // Token as username, empty password (works for GitHub/GitLab/Gitea tokens and mirrors token@host).
            return new UsernamePasswordCredentialsProvider(authToken, "");
        }
        return null;
    }

    private boolean plaintextWarningLogged;

    /** The URL without any user information, for logs. */
    private static String redact(String url) {
        return url == null ? null : url.replaceFirst("://[^@/]+@", "://");
    }

    /** Transport callback: install the in-memory SSH key on SSH transports. */
    private TransportConfigCallback buildTransportConfig() {
        if (sshKey == null || sshKey.isBlank()) {
            return null;
        }
        final JschConfigSessionFactory sshFactory = new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, Session session) {
                configureSession(session);
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

    /**
     * Settings of an SSH session to the Git server. Classic JSch has no "accept-new", so strict host-key checking is
     * off for unattended automation. Keepalives notice a connection the network dropped without closing it (a
     * firewall that forgot it, say) even while no data is expected, which the timeout of the transfer alone does not.
     */
    static void configureSession(Session session) {
        session.setConfig("StrictHostKeyChecking", "no");
        try {
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
        } catch (JSchException e) {
            LOG.warn("Could not enable SSH keepalives to the Git server: {}", e.getMessage());
        }
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
    }

    /** Apply credentials, the timeout and transport (SSH/proxy) config to any JGit remote command. */
    private <C extends TransportCommand<C, ?>> C configure(C command) {
        command.setCredentialsProvider(credentials());
        command.setTimeout(timeoutSeconds);
        final ProxySupport outbound = proxy != null && proxy.isEnabled() ? proxy : null;
        if (transportConfig != null || outbound != null) {
            command.setTransportConfigCallback(transport -> {
                if (transportConfig != null) {
                    transportConfig.configure(transport);
                }
                if (outbound != null && transport instanceof TransportHttp http) {
                    http.setHttpConnectionFactory(outbound.gitConnectionFactory());
                }
            });
        }
        return command;
    }

    // ---- repository operations -------------------------------------------------------------------

    /**
     * Clones the repository into the workspace, or fetches into the clone already there, then puts the workspace on
     * the base branch exactly as the server holds it. Commits made here and never pushed, uncommitted changes and new
     * files are all dropped: a failed operation must not leave anything the next one would take as published.
     */
    public void sync() throws IOException, InterruptedException {
        try {
            if (Files.exists(workspace.resolve(".git"))) {
                try (Git git = Git.open(workspace.toFile())) {
                    StoredConfig cfg = git.getRepository().getConfig();
                    cfg.setString("remote", "origin", "url", repoUrl);
                    cfg.save();
                }
                fetch();
            } else {
                Files.createDirectories(workspace);
                try {
                    // Every branch is fetched; the base branch is set up below from the server's, also when the server
                    // does not have it yet.
                    configure(Git.cloneRepository()
                            .setURI(repoUrl)
                            .setDirectory(workspace.toFile()))
                            .call().close();
                } catch (GitAPIException | RuntimeException cloneFailed) {
                    // A partial clone would be taken for a usable one by the next sync.
                    deleteDirContents(workspace);
                    throw cloneFailed;
                }
            }
            try (Git git = Git.open(workspace.toFile())) {
                switchTo(git, baseBranch);
            }
        } catch (GitAPIException e) {
            throw new IOException("git sync failed for " + redact(repoUrl) + ": " + e.getMessage(), e);
        }
    }

    /** Fetches every branch of the server, forgetting those it no longer has. */
    public void fetch() throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            configure(git.fetch().setRemote("origin").setRemoveDeletedRefs(true)).call();
        } catch (GitAPIException e) {
            throw new IOException("git fetch from " + redact(repoUrl) + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Switches the workspace to a branch as the server holds it ({@code origin/<branch>} as last fetched). A branch
     * the server does not have is kept as it is here, or created from the current commit.
     */
    public void checkoutBranch(String branch) throws IOException, InterruptedException {
        try (Git git = Git.open(workspace.toFile())) {
            switchTo(git, branch);
        } catch (GitAPIException e) {
            throw new IOException("git checkout " + branch + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Puts the workspace on the branch a pull request proposes a change from: as the server holds it when the server
     * has the branch, otherwise started from {@code origin/<base>} as last fetched, whatever a local branch of that name
     * held, as {@code git checkout -B <branch> origin/<base>} does. A branch left behind by an earlier change, merged
     * or not, never leaks its commits into the new one. Uncommitted changes and new files are dropped.
     *
     * @param branch the branch the change is pushed to
     * @param base   the branch the pull request is merged into
     * @throws IOException when the server has neither branch: a pull request needs the branch it is merged into
     */
    public void checkoutWorkBranch(String branch, String base) throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            Repository repo = git.getRepository();
            if (repo.exactRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                switchTo(git, branch);
                return;
            }
            Ref start = repo.exactRef(Constants.R_REMOTES + "origin/" + base);
            if (start == null || start.getObjectId() == null) {
                throw new IOException(notOnServer(base, "a pull request needs the branch it is merged into"));
            }
            if (!branch.equals(repo.getBranch())) {
                if (repo.exactRef(Constants.R_HEADS + branch) != null) {
                    git.branchDelete().setBranchNames(branch).setForce(true).call();
                }
                git.checkout().setName(branch).setCreateBranch(true).setStartPoint(start.getName())
                        .setUpstreamMode(SetupUpstreamMode.NOTRACK).setForced(true).call();
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(start.getName()).call();
            git.clean().setCleanDirectories(true).call();
        } catch (GitAPIException e) {
            throw new IOException("git checkout -B " + branch + " origin/" + base + " failed: " + e.getMessage(), e);
        }
    }

    /** Whether the server has the branch, as last fetched ({@code origin/<branch>}). */
    public boolean isOnServer(String branch) throws IOException {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        try (Git git = Git.open(workspace.toFile())) {
            Ref published = git.getRepository().exactRef(Constants.R_REMOTES + "origin/" + branch);
            return published != null && published.getObjectId() != null;
        }
    }

    /**
     * Refuses a branch the server does not have, as last fetched, unless the repository has no branch at all yet: its
     * first commit creates the branch. Anywhere else, a push would create a new branch, from whatever the workspace
     * started from, that nothing reads.
     *
     * @param needs why the branch must be there, as the message tells it
     * @throws IOException naming the branch and the repository
     */
    public void requireOnServer(String branch, String needs) throws IOException {
        if (isOnServer(branch)) {
            return;
        }
        try (Git git = Git.open(workspace.toFile())) {
            boolean empty = git.getRepository().getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/")
                    .stream().noneMatch(ref -> !ref.isSymbolic() && ref.getObjectId() != null);
            if (empty) {
                return;
            }
        }
        throw new IOException(notOnServer(branch, needs));
    }

    /** That the server does not have a branch something needs, and what the operator can do. */
    private String notOnServer(String branch, String needs) {
        return "Branch " + branch + " is not on " + redact(repoUrl) + ": " + needs + ". Create it, or choose another"
                + " base branch.";
    }

    /**
     * Puts the current branch back to what the server holds as last fetched ({@code origin/<branch>}), dropping the
     * commits made here, uncommitted changes and new files. On a branch the server does not have, only the
     * uncommitted changes and new files are dropped.
     */
    public void resetToRemote() throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            String fullBranch = git.getRepository().getFullBranch();
            if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS)) {
                switchTo(git, Repository.shortenRefName(fullBranch));
            } else {
                git.reset().setMode(ResetCommand.ResetType.HARD).call();
                git.clean().setCleanDirectories(true).call();
            }
        } catch (GitAPIException e) {
            throw new IOException("git reset of " + workspace + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Puts the workspace on {@code branch}: reset to {@code origin/<branch>} when the server has it, otherwise the
     * local branch as it is (created from the current commit when absent). Uncommitted changes and new files are
     * dropped either way.
     */
    private static void switchTo(Git git, String branch) throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        Ref published = repo.exactRef(Constants.R_REMOTES + "origin/" + branch);
        boolean local = repo.exactRef(Constants.R_HEADS + branch) != null;
        if (!local && published == null && repo.resolve(Constants.HEAD) == null) {
            // An empty repository: the first commit creates the branch.
            RefUpdate head = repo.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(Constants.R_HEADS + branch);
            return;
        }
        if (!local || !branch.equals(repo.getBranch())) {
            CheckoutCommand checkout = git.checkout().setName(branch).setForced(true);
            if (!local) {
                checkout.setCreateBranch(true)
                        .setStartPoint(published != null ? published.getName() : Constants.HEAD);
                if (published != null) {
                    checkout.setUpstreamMode(SetupUpstreamMode.TRACK);
                }
            }
            checkout.call();
        }
        git.reset().setMode(ResetCommand.ResetType.HARD)
                .setRef(published != null ? published.getName() : Constants.HEAD).call();
        git.clean().setCleanDirectories(true).call();
    }

    /** Write a file relative to the repo root. */
    public void writeFile(Path relativePath, String content) throws IOException {
        Path abs = resolveSafe(relativePath);
        Files.createDirectories(abs.getParent());
        Files.writeString(abs, content, StandardCharsets.UTF_8);
    }

    /**
     * Stages every change of the working tree (new, modified and deleted files) and commits it. Nothing is pushed.
     *
     * @return the commit, or {@code null} when nothing changed
     */
    public String commit(String message) throws IOException, InterruptedException {
        try (Git git = Git.open(workspace.toFile())) {
            git.add().addFilepattern(".").call();              // new + modified files
            git.add().addFilepattern(".").setUpdate(true).call(); // stage deletions of tracked files
            if (git.status().call().isClean()) {
                LOG.info("No git changes detected, skipping commit for {}", redact(repoUrl));
                return null;
            }
            var commit = git.commit().setMessage(message);
            if (authorName != null && authorEmail != null) {
                commit.setAuthor(authorName, authorEmail).setCommitter(authorName, authorEmail);
            }
            RevCommit c = commit.call();
            LOG.info("Created local commit {} for {}", c.getName(), redact(repoUrl));
            return c.getName();
        } catch (GitAPIException e) {
            throw new IOException("git commit failed for " + redact(repoUrl) + ": " + e.getMessage(), e);
        }
    }

    /**
     * Pushes the current branch to the branch of the same name on the server.
     *
     * @return the commit the server now holds
     * @throws GitPushRejectedException when the server refused the update (the branch moved on, a protected branch, a
     *                                  server hook): nothing changed on the server
     * @throws IOException              when the server could not be reached or the transfer failed or timed out
     */
    public String push() throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            String branch = git.getRepository().getBranch();
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            Iterable<PushResult> results = configure(git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec("HEAD:" + Constants.R_HEADS + branch))).call();
            // JGit throws only when the transfer fails: a refused update is reported in the result.
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() != RemoteRefUpdate.Status.OK
                            && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new GitPushRejectedException(redact(repoUrl), branch, update.getStatus(),
                                update.getMessage(), result.getMessages());
                    }
                }
            }
            String sha = head == null ? null : head.getName();
            LOG.info("Pushed commit {} to {} ({})", sha, redact(repoUrl), branch);
            return sha;
        } catch (GitAPIException e) {
            throw new IOException("git push to " + redact(repoUrl) + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * How many commits of the current branch the server does not hold: those not on {@code origin/<branch>} as last
     * fetched or pushed, or, for a branch the server does not have, on none of its branches. Counted here rather than
     * read from JGit's BranchTrackingStatus, which knows only branches with an upstream configured: a working branch
     * created here has none.
     */
    public int unpublishedCommits() throws IOException {
        try (Git git = Git.open(workspace.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return unpublished(git.getRepository(), walk).size();
        }
    }

    /**
     * Whether the server's copy of the current branch, as last fetched ({@code origin/<branch>}), holds commits the
     * current branch does not: another change landed there since the branch was taken from the server. False when the
     * server does not have the branch, or when the current branch holds all of it: pushing it would then be a
     * fast-forward, so a push refused was refused for another reason.
     */
    public boolean branchMovedOnServer() throws IOException {
        try (Git git = Git.open(workspace.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            Ref published = repo.exactRef(Constants.R_REMOTES + "origin/" + repo.getBranch());
            if (published == null || published.getObjectId() == null) {
                return false;
            }
            ObjectId head = repo.resolve(Constants.HEAD);
            return head == null
                    || !walk.isMergedInto(walk.parseCommit(published.getObjectId()), walk.parseCommit(head));
        }
    }

    /**
     * What the {@link #unpublishedCommits unpublished commits} of the current branch changed, each file as they left
     * it: the change to make again ({@link #write}) on top of a branch that moved on the server.
     */
    public Change unpublishedChange() throws IOException {
        try (Git git = Git.open(workspace.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            Map<Path, byte[]> written = new LinkedHashMap<>();
            Set<Path> deleted = new LinkedHashSet<>();
            for (DiffEntry entry : changeOf(repo, walk, unpublished(repo, walk))) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    deleted.add(Path.of(entry.getOldPath()));
                } else {
                    written.put(Path.of(entry.getNewPath()), repo.open(entry.getNewId().toObjectId()).getBytes());
                }
            }
            return new Change(written, deleted);
        }
    }

    /**
     * Starts the current branch again from {@code origin/<base>} as last fetched when the server no longer has the
     * branch and {@code base} already holds everything its {@link #unpublishedCommits unpublished commits} change: a
     * pull request or working branch merged as new commits (a "squash" or "rebase" merge), then deleted on the server
     * as several Git servers do after a merge. Those commits are on none of the server's branches, yet pushing them
     * again would only recreate the branch, and its pull request, for a change already merged, and a new change made
     * on top of them would carry that merged change once more. A branch whose change {@code base} lacks (never pushed,
     * or deleted without its merge) is kept as it is, to be pushed.
     *
     * @param base the branch the working branch is merged into; nothing is done when null or not on the server
     * @return whether the branch was started again from {@code origin/<base>}
     */
    public boolean restartIfMergedInto(String base) throws IOException {
        if (base == null || base.isBlank()) {
            return false;
        }
        try (Git git = Git.open(workspace.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            Ref merged = repo.exactRef(Constants.R_REMOTES + "origin/" + base);
            if (merged == null || merged.getObjectId() == null
                    || repo.exactRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                return false;
            }
            List<RevCommit> commits = unpublished(repo, walk);
            if (commits.isEmpty()
                    || !holds(repo, walk.parseCommit(merged.getObjectId()).getTree(), changeOf(repo, walk, commits))) {
                return false;
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(merged.getName()).call();
            git.clean().setCleanDirectories(true).call();
            LOG.info("Branch {} of {} is no longer on the server and {} already holds its {} commit(s): started it"
                    + " again from {}", branch, redact(repoUrl), base, commits.size(), base);
            return true;
        } catch (GitAPIException e) {
            throw new IOException("git reset of " + workspace + " to origin/" + base + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * What commits change together, newest first and each the parent of the one before: the oldest one's parent
     * against the newest one, file by file.
     */
    private static List<DiffEntry> changeOf(Repository repo, RevWalk walk, List<RevCommit> commits) throws IOException {
        if (commits.isEmpty()) {
            return List.of();
        }
        RevCommit newest = commits.get(0);
        RevCommit oldest = commits.get(commits.size() - 1);
        try (TreeWalk diff = new TreeWalk(repo)) {
            diff.setRecursive(true);
            if (oldest.getParentCount() == 0) {
                diff.addTree(new EmptyTreeIterator());
            } else {
                diff.addTree(walk.parseCommit(oldest.getParent(0)).getTree());
            }
            diff.addTree(newest.getTree());
            diff.setFilter(TreeFilter.ANY_DIFF);
            return DiffEntry.scan(diff);
        }
    }

    /** Whether a tree holds each file a change writes, with the content it writes, and none of those it deletes. */
    private static boolean holds(Repository repo, RevTree tree, List<DiffEntry> change) throws IOException {
        for (DiffEntry entry : change) {
            boolean deletes = entry.getChangeType() == DiffEntry.ChangeType.DELETE;
            try (TreeWalk found = TreeWalk.forPath(repo, deletes ? entry.getOldPath() : entry.getNewPath(), tree)) {
                boolean held = deletes ? found == null
                        : found != null && found.getObjectId(0).equals(entry.getNewId().toObjectId());
                if (!held) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Writes the files of a change into the working tree and deletes those it deleted. Nothing is committed. */
    public void write(Change change) throws IOException {
        for (Map.Entry<Path, byte[]> file : change.written().entrySet()) {
            Path abs = resolveSafe(file.getKey());
            Files.createDirectories(abs.getParent());
            Files.write(abs, file.getValue());
        }
        for (Path gone : change.deleted()) {
            deletePath(gone);
        }
    }

    /**
     * Files a change writes, with their content, and files it deletes, relative to the repository root.
     *
     * @param written the files written, by path
     * @param deleted the files deleted
     */
    public record Change(Map<Path, byte[]> written, Set<Path> deleted) {
        /** Whether the change neither writes nor deletes anything. */
        public boolean isEmpty() {
            return written.isEmpty() && deleted.isEmpty();
        }
    }

    /**
     * The commits of HEAD the server does not hold, newest first, each before its parents (see
     * {@link #unpublishedCommits}).
     */
    private static List<RevCommit> unpublished(Repository repo, RevWalk walk) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return List.of();
        }
        // In the order of the history, not of the commit dates, which a clock set wrong can make lie.
        walk.sort(RevSort.TOPO);
        walk.markStart(walk.parseCommit(head));
        Ref tracking = repo.exactRef(Constants.R_REMOTES + "origin/" + repo.getBranch());
        List<Ref> published = tracking != null ? List.of(tracking)
                : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/");
        for (Ref ref : published) {
            if (ref.getObjectId() != null) {
                walk.markUninteresting(walk.parseCommit(ref.getObjectId()));
            }
        }
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit : walk) {
            commits.add(commit);
        }
        return commits;
    }

    /**
     * Moves the current branch back to a commit, dropping the commits and the changes made since: a commit whose
     * push failed must not be left in the workspace, where the next deploy would take it as published.
     *
     * @param commit the commit to go back to; nothing is done when it is null
     */
    public void resetTo(String commit) throws IOException {
        if (commit == null) {
            return;
        }
        try (Git git = Git.open(workspace.toFile())) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit).call();
        } catch (GitAPIException e) {
            throw new IOException("git reset to " + commit + " failed: " + e.getMessage(), e);
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

    /**
     * The files the next commit would add or change (new, modified or staged; not deletions), relative to the repo
     * root.
     */
    public Set<Path> changedFiles() throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            Status status = git.status().call();
            Set<String> names = new TreeSet<>();
            names.addAll(status.getUntracked());
            names.addAll(status.getModified());
            names.addAll(status.getAdded());
            names.addAll(status.getChanged());
            Set<Path> files = new LinkedHashSet<>();
            names.forEach(name -> files.add(Path.of(name)));
            return files;
        } catch (GitAPIException e) {
            throw new IOException("git status failed: " + e.getMessage(), e);
        }
    }

    /** The commit the workspace is on, or {@code null} when the repository has none yet. */
    public String headCommit() throws IOException {
        try (Git git = Git.open(workspace.toFile())) {
            org.eclipse.jgit.lib.ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.getName();
        }
    }

    /**
     * Whether a file exists on the remote base branch as last fetched ({@code origin/<baseBranch>}), whatever the local
     * branch or uncommitted files hold: what is actually published.
     *
     * @param relative path relative to the repo root
     */
    public boolean existsOnBaseBranch(Path relative) {
        return existsOnRemoteBranch(relative, baseBranch);
    }

    /**
     * Whether a file exists on {@code origin/<branch>} as last fetched.
     *
     * @param relative path relative to the repo root
     * @param branch   remote branch name; false when it was never pushed
     */
    public boolean existsOnRemoteBranch(Path relative, String branch) {
        return Boolean.TRUE.equals(publishedOn(relative, branch));
    }

    /**
     * Whether a file exists on {@code origin/<branch>}: TRUE or FALSE (also FALSE when the branch was never pushed),
     * {@code null} when the repository could not be read.
     */
    public Boolean publishedOn(Path relative, String branch) {
        if (branch == null || branch.isBlank()) {
            return Boolean.FALSE;
        }
        String path;
        try {
            path = workspace.relativize(resolveSafe(relative)).toString().replace('\\', '/');
        } catch (IOException outside) {
            return Boolean.FALSE;
        }
        try (Git git = Git.open(workspace.toFile())) {
            org.eclipse.jgit.lib.ObjectId tree = git.getRepository().resolve("refs/remotes/origin/" + branch + "^{tree}");
            if (tree == null) {
                return Boolean.FALSE;
            }
            try (org.eclipse.jgit.treewalk.TreeWalk walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(git.getRepository(), path, tree)) {
                return walk != null;
            }
        } catch (Exception e) {
            LOG.warn("Could not look up {} on origin/{}: {}", path, branch, e.toString());
            return null;
        }
    }

    /**
     * The content of a file as published on a branch of the remote, as of the last {@link #sync}.
     *
     * @return the content, or {@code null} when the branch or the file is not there, or cannot be read
     */
    public String publishedContent(Path relative, String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }
        String path;
        try {
            path = workspace.relativize(resolveSafe(relative)).toString().replace('\\', '/');
        } catch (IOException outside) {
            return null;
        }
        try (Git git = Git.open(workspace.toFile())) {
            org.eclipse.jgit.lib.ObjectId tree = git.getRepository().resolve("refs/remotes/origin/" + branch + "^{tree}");
            if (tree == null) {
                return null;
            }
            try (org.eclipse.jgit.treewalk.TreeWalk walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(git.getRepository(), path, tree)) {
                return walk == null ? null : new String(git.getRepository().open(walk.getObjectId(0)).getBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOG.warn("Could not read {} on origin/{}: {}", path, branch, e.toString());
            return null;
        }
    }

    /**
     * Remove a path (file or directory) relative to repo root.
     *
     * @throws IOException when the path is outside the repository, in its {@code .git} directory, or the whole
     *                     working tree
     */
    public void deletePath(Path relative) throws IOException {
        Path abs = resolveSafe(relative);
        if (abs.equals(workspace)) {
            throw new IOException("Refusing to delete the whole repository: '" + relative + "'");
        }
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

    /**
     * A new, unique branch name for a pull request: {@code <prefix>-<uuid>}. Only a pull request gets one; a direct
     * commit goes to the branch Flux reconciles.
     */
    public static String generateBranch(String prefix) {
        return (prefix == null || prefix.isBlank() ? "gitops" : prefix) + "-" + UUID.randomUUID();
    }

    /**
     * Ensure callers cannot escape the repository root via ".." or absolute paths, nor write to or delete Git's own
     * data in its {@code .git} directory.
     */
    private Path resolveSafe(Path relative) throws IOException {
        if (relative == null) {
            throw new IOException("Path is required");
        }
        Path normalized = workspace.resolve(relative).normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IOException("Refusing to operate outside repo: " + relative);
        }
        if (normalized.startsWith(workspace.resolve(".git"))) {
            throw new IOException("Refusing to operate on the repository's .git directory: " + relative);
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
