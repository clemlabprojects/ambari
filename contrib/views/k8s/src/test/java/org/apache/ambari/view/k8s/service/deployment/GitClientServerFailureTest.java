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
import com.jcraft.jsch.Session;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitClient} against Git servers that refuse a push, see their branch move on, or stop answering: a refusal
 * is an error carrying the server's message, never a commit reported as pushed; nothing a failed push left is taken
 * as published; a silent server fails the operation within the timeout.
 */
class GitClientServerFailureTest {

    @TempDir
    Path tmp;

    private GitClient client(String url) {
        return new GitClient(tmp.resolve("ws"), url, "main", null).withAuthor("KDPS Test", "kdps@example.com");
    }

    @Test
    void aPushAServerHookRefusesFailsWithTheServersMessage() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        String seed = GitTestServers.head(bare, "main");
        try (GitTestServers.GitDaemon server = new GitTestServers.GitDaemon(bare)) {
            server.refusePushes("pre-receive hook declined", "Gitea: Not allowed to push to protected branch main");
            GitClient client = client(server.url());
            client.sync();
            client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
            client.commit("add trino");

            GitPushRejectedException refused = assertThrows(GitPushRejectedException.class, client::push);

            assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON, refused.getStatus());
            assertTrue(refused.getMessage().contains("pre-receive hook declined"), refused.getMessage());
            assertTrue(refused.getMessage().contains("Not allowed to push to protected branch main"),
                    "the hook's output reaches the operator: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("branch main of " + server.url()), refused.getMessage());
            assertFalse(GitClient.isRetryable(refused), "the same push gets the same answer");
            assertEquals(seed, GitTestServers.head(bare, "main"), "nothing landed on the server");
            assertEquals(1, client.unpublishedCommits());
            client.fetch();
            assertFalse(client.branchMovedOnServer(), "refused although the branch is as it was taken");
        }
    }

    @Test
    void aBranchThatMovedOnTheServerIsRefusedThenTheChangeIsMadeAgainOnTopOfIt() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        GitClient client = client(bare.toUri().toString());
        client.sync();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        client.deletePath(Path.of("README.md"));
        client.commit("add trino");
        String other = GitTestServers.pushFromElsewhere(bare, "main", "apps/superset/helmrelease.yaml",
                "kind: HelmRelease\n");

        GitPushRejectedException refused = assertThrows(GitPushRejectedException.class, client::push);
        assertEquals(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, refused.getStatus());
        assertTrue(refused.mayBeBranchMoved());
        assertEquals(other, GitTestServers.head(bare, "main"));

        GitClient.Change change = client.unpublishedChange();
        assertEquals(java.util.Set.of(Path.of("apps/trino/helmrelease.yaml")), change.written().keySet());
        assertEquals(java.util.Set.of(Path.of("README.md")), change.deleted());
        assertFalse(client.branchMovedOnServer(), "not known before the server's branch is fetched");
        client.fetch();
        assertTrue(client.branchMovedOnServer());
        client.resetToRemote();
        assertEquals(other, client.headCommit(), "back on the server's branch");
        client.write(change);
        String again = client.commit("add trino");
        assertEquals(again, client.push());

        assertEquals(again, GitTestServers.head(bare, "main"));
        try (Git g = Git.open(bare.toFile()); RevWalk walk = new RevWalk(g.getRepository())) {
            RevCommit pushed = walk.parseCommit(g.getRepository().resolve(again));
            assertEquals(other, pushed.getParent(0).getName(), "made on top of the other change, which stays");
        }
        Path verify = tmp.resolve("verify");
        try (Git ignored = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(verify.toFile()).call()) {
            assertTrue(Files.exists(verify.resolve("apps/trino/helmrelease.yaml")));
            assertTrue(Files.exists(verify.resolve("apps/superset/helmrelease.yaml")));
            assertTrue(Files.notExists(verify.resolve("README.md")));
        }
    }

    @Test
    void aBranchTheServerCannotCreateIsRefusedWithoutHavingMoved() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        // kdps/trino cannot be created next to a branch kdps; JGit words it as a failure to lock the branch.
        GitTestServers.createBranch(bare, "kdps", "main");
        GitClient client = client(bare.toUri().toString());
        client.sync();
        client.checkoutBranch("kdps/trino");
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        client.commit("add trino");

        GitPushRejectedException refused = assertThrows(GitPushRejectedException.class, client::push);
        assertTrue(refused.mayBeBranchMoved(), "worded as a lost race: " + refused.getMessage());
        client.fetch();

        assertFalse(client.branchMovedOnServer(), "the server does not have the branch");
        assertNull(GitTestServers.head(bare, "kdps/trino"));
        assertEquals(1, client.unpublishedCommits());
    }

    @Test
    void syncDropsWhatAFailedPushLeftInTheWorkspace() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        String seed = GitTestServers.head(bare, "main");
        GitClient client = client(bare.toUri().toString());
        client.sync();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        client.commit("add trino, never pushed");
        client.writeFile(Path.of("apps/trino/stray.yaml"), "left behind\n");
        assertEquals(1, client.unpublishedCommits());

        client.sync();

        assertEquals(seed, client.headCommit(), "on the server's branch, not on a commit the server never got");
        assertEquals(0, client.unpublishedCommits());
        assertFalse(client.hasChanges());
        assertTrue(Files.notExists(tmp.resolve("ws/apps/trino/helmrelease.yaml")));
        assertTrue(Files.notExists(tmp.resolve("ws/apps/trino/stray.yaml")));
    }

    @Test
    void aWorkingBranchFollowsTheServerAndOneTheServerDoesNotHaveKeepsItsCommits() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        GitClient client = client(bare.toUri().toString());
        client.sync();
        client.checkoutBranch("flux-work");
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        String local = client.commit("on a branch the server does not have");

        client.sync();
        client.checkoutBranch("flux-work");
        assertEquals(local, client.headCommit(), "kept: nothing on the server replaces it");
        assertEquals(1, client.unpublishedCommits(), "and it is known to be unpublished");

        client.push();
        String moved = GitTestServers.pushFromElsewhere(bare, "flux-work", "apps/trino/notes.txt", "reviewed\n");
        client.writeFile(Path.of("apps/trino/stray.yaml"), "left behind\n");
        client.sync();
        client.checkoutBranch("flux-work");
        assertEquals(moved, client.headCommit(), "a branch the server has is taken as the server holds it");
        assertTrue(Files.notExists(tmp.resolve("ws/apps/trino/stray.yaml")));
    }

    @Test
    void aGitServerThatStopsAnsweringFailsTheCloneWithinTheTimeout() throws Exception {
        try (GitTestServers.Blackhole server = new GitTestServers.Blackhole()) {
            GitClient client = new GitClient(tmp.resolve("ws"), server.url(), "main", "t0k").withTimeout(1);

            long start = System.nanoTime();
            IOException failed = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(IOException.class, client::sync));

            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(15)) < 0,
                    "gave up after the timeout");
            assertTrue(timedOut(failed), "failed on the timeout: " + failed);
            assertFalse(GitClient.isRetryable(failed), "a timeout is not tried again: " + failed);
            assertEquals(1, server.connections(), "one clone, not a second attempt on another branch");
            assertFalse(Files.exists(tmp.resolve("ws/.git")), "no partial clone is left for the next sync");
        }
    }

    @Test
    void aPushToAGitServerThatStopsAnsweringFailsWithinTheTimeout() throws Exception {
        Path bare = GitTestServers.seededBareRepo(tmp);
        client(bare.toUri().toString()).sync();
        try (GitTestServers.Blackhole server = new GitTestServers.Blackhole()) {
            // The same workspace, its server now silent.
            try (Git g = Git.open(tmp.resolve("ws").toFile())) {
                StoredConfig config = g.getRepository().getConfig();
                config.setString("remote", "origin", "url", server.url());
                config.save();
            }
            GitClient client = new GitClient(tmp.resolve("ws"), server.url(), "main", "t0k")
                    .withAuthor("KDPS Test", "kdps@example.com").withTimeout(1);
            client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
            client.commit("add trino");

            IOException failed = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(IOException.class, client::push));

            assertTrue(timedOut(failed), "failed on the timeout: " + failed);
            assertFalse(GitClient.isRetryable(failed), failed.toString());
            assertFalse(failed instanceof GitPushRejectedException);
        }
    }

    /** Whether a failure was the server not answering in time. */
    static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.InterruptedIOException
                    || String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void sshSessionsToTheGitServerSendKeepalives() throws Exception {
        Session session = new JSch().getSession("git", "git.example.com", 22);

        GitClient.configureSession(session);

        assertEquals(30_000, session.getServerAliveInterval());
        assertEquals(4, session.getServerAliveCountMax());
        assertEquals("no", session.getConfig("StrictHostKeyChecking"));
    }

    @Test
    void refusalsBadCredentialsAndTimeoutsAreNotTriedAgain() {
        assertFalse(GitClient.isRetryable(new IOException("git push failed", new SocketTimeoutException("Read timed out"))));
        assertFalse(GitClient.isRetryable(new IOException("git fetch failed",
                new org.eclipse.jgit.errors.TransportException("https://git.example.com/a.git: Read timed out after 120000 ms"))));
        assertFalse(GitClient.isRetryable(new IOException("git push failed",
                new org.eclipse.jgit.errors.TransportException("https://git.example.com/a.git: not authorized"))));
        assertFalse(GitClient.isRetryable(new IOException("git push failed", new org.eclipse.jgit.errors.TransportException(
                "https://git.example.com/a.git: git-receive-pack not permitted on 'https://git.example.com/a.git/'"))));
        assertFalse(GitClient.isRetryable(new IOException("git sync failed", new IOException("Auth fail"))));
        assertFalse(GitClient.isRetryable(new GitPushRejectedException("https://git.example.com/a.git", "main",
                RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "protected branch", null)));

        assertTrue(GitClient.isRetryable(new IOException("git push failed",
                new org.eclipse.jgit.errors.TransportException("https://git.example.com/a.git: remote hung up unexpectedly"))));
        assertTrue(GitClient.isRetryable(new IOException("git sync failed", new java.net.ConnectException("Connection refused"))));
    }

    @Test
    void aRefusalNamesWhatTheServerSaid() {
        GitPushRejectedException refused = new GitPushRejectedException("https://git.example.com/platform/gitops.git",
                "main", RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "pre-receive hook declined",
                "\nGitea: Not allowed to push to protected branch main\n\n");

        assertEquals("Git refused the push to branch main of https://git.example.com/platform/gitops.git: the server"
                + " rejected the change (pre-receive hook declined). The Git server said: Gitea: Not allowed to push"
                + " to protected branch main.", refused.getMessage());
        // Whatever the server's words, a refusal of the update may be a lost race; the server's branch tells.
        assertTrue(new GitPushRejectedException("u", "main", RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
                "failed to lock", null).mayBeBranchMoved());
        assertTrue(new GitPushRejectedException("u", "main", RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
                "failed to update ref", "error: cannot lock ref 'refs/heads/main': is at 1234 but expected 5678")
                .mayBeBranchMoved());
        assertTrue(new GitPushRejectedException("u", "main", RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, null, null)
                .mayBeBranchMoved());
        assertFalse(new GitPushRejectedException("u", "main", RemoteRefUpdate.Status.REJECTED_NODELETE, null, null)
                .mayBeBranchMoved());
    }

    @Test
    void theTimeoutIsTwoMinutesUnlessSetOtherwise() {
        GitClient client = new GitClient(tmp.resolve("ws"), "https://git.example.com/a.git", "main", "t0k");
        assertEquals(120, client.timeoutSeconds());
        assertEquals(30, client.withTimeout(30).timeoutSeconds());
        assertEquals(120, client.withTimeout(0).timeoutSeconds());
    }
}
