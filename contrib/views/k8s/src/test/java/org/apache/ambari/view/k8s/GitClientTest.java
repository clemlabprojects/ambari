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
package org.apache.ambari.view.k8s;

import org.apache.ambari.view.k8s.service.deployment.GitClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the JGit-based {@link GitClient} (AMBARI-533): exercises the real
 * clone → write → commit → push → re-clone round trip against a local bare repository, so the
 * pure-Java git plumbing (no OS {@code git} binary) is verified end-to-end without a network server.
 */
class GitClientTest {

    private static final String BRANCH = "main";

    /** Create a bare repo and seed it with an initial commit on {@code main}. */
    private String seededBareRepo(Path tmp) throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch(BRANCH).setDirectory(bare.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bareGit = Git.open(bare.toFile())) {
            org.eclipse.jgit.lib.StoredConfig bareConfig = bareGit.getRepository().getConfig();
            bareConfig.setInt("gc", null, "auto", 0);
            bareConfig.setBoolean("receive", null, "autogc", false);
            bareConfig.save();
        }
        String url = bare.toUri().toString();
        Path seed = tmp.resolve("seed");
        try (Git g = Git.cloneRepository().setURI(url).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + BRANCH)).call();
        }
        return url;
    }

    @Test
    void cloneWriteCommitPushRoundTrip(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);

        Path ws = tmp.resolve("workspace");
        GitClient client = new GitClient(ws, url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        assertTrue(Files.exists(ws.resolve("README.md")), "sync should have cloned the seeded repo");

        client.writeFile(Path.of("apps/trino/values.yaml"), "replicas: 3\n");
        assertTrue(client.hasChanges(), "new file should be an uncommitted change");
        String sha = client.commit("Add trino values");
        assertNotNull(sha, "commit should produce a sha");
        assertEquals(40, sha.length(), "sha should be a full git object id");
        assertEquals(1, client.unpublishedCommits());
        assertEquals(sha, client.push(), "the commit is what the server now holds");
        assertEquals(0, client.unpublishedCommits());

        // A second commit with no changes returns null (nothing to commit).
        assertEquals(null, client.commit("no-op"), "no changes → no commit");

        // Verify the push landed by re-cloning the remote fresh.
        Path verify = tmp.resolve("verify");
        try (Git g = Git.cloneRepository().setURI(url).setBranch(BRANCH).setDirectory(verify.toFile()).call()) {
            Path pushed = verify.resolve("apps/trino/values.yaml");
            assertTrue(Files.exists(pushed), "pushed file must exist in the remote");
            assertEquals("replicas: 3\n", Files.readString(pushed, StandardCharsets.UTF_8));
        }
    }

    @Test
    void theFirstPushToAnEmptyRepositoryCreatesTheBranch(@TempDir Path tmp) throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch(BRANCH).setDirectory(bare.toFile()).call().close();
        try (Git bareGit = Git.open(bare.toFile())) {
            bareGit.getRepository().getConfig().setInt("gc", null, "auto", 0);
            bareGit.getRepository().getConfig().setBoolean("receive", null, "autogc", false);
            bareGit.getRepository().getConfig().save();
        }
        GitClient client = new GitClient(tmp.resolve("ws"), bare.toUri().toString(), BRANCH, null)
                .withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        // No background gc after the next fetch: it would still hold gc.log.lock when the temp directory is removed.
        try (Git ws = Git.open(tmp.resolve("ws").toFile())) {
            ws.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false);
            ws.getRepository().getConfig().save();
        }

        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        String sha = client.commit("first commit");
        assertEquals(1, client.unpublishedCommits());
        assertEquals(sha, client.push());

        assertEquals(sha, remoteHead(tmp));
        client.sync();
        assertEquals(sha, client.headCommit(), "the next sync finds the branch on the server");
    }

    @Test
    void deletePathIsStagedAndPushed(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        Path ws = tmp.resolve("workspace");
        GitClient client = new GitClient(ws, url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.writeFile(Path.of("gone.txt"), "temporary");
        client.commit("add gone.txt");
        client.push();

        client.deletePath(Path.of("gone.txt"));
        String sha = client.commit("remove gone.txt");
        assertNotNull(sha, "deletion should be committed");
        client.push();

        Path verify = tmp.resolve("verify");
        try (Git g = Git.cloneRepository().setURI(url).setBranch(BRANCH).setDirectory(verify.toFile()).call()) {
            assertTrue(Files.notExists(verify.resolve("gone.txt")), "deleted file must be gone in the remote");
        }
    }

    @Test
    void neitherTheWholeWorkingTreeNorGitsOwnDataCanBeDeletedOrWritten(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        Path ws = tmp.resolve("workspace");
        GitClient client = new GitClient(ws, url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();

        for (String root : new String[] {"", ".", "apps/.."}) {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> client.deletePath(Path.of(root)), "refused: '" + root + "'");
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> client.deletePath(Path.of(".git")));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> client.writeFile(Path.of("apps/../.git/hooks/post-checkout"), "#!/bin/sh\n"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> client.deletePath(Path.of("../other")));

        assertTrue(Files.exists(ws.resolve("README.md")), "the working tree is untouched");
        assertTrue(Files.exists(ws.resolve(".git/HEAD")), "so is the repository");
        assertTrue(Files.notExists(ws.resolve(".git/hooks/post-checkout")));
    }

  private static Object credentialsFor(String url, String token) throws Exception {
    org.apache.ambari.view.k8s.service.deployment.GitClient c = new org.apache.ambari.view.k8s.service.deployment.GitClient(
        java.nio.file.Files.createTempDirectory("git-cred"), url, "main", token, null);
    java.lang.reflect.Method m = c.getClass().getDeclaredMethod("credentials");
    m.setAccessible(true);
    return m.invoke(c);
  }

  @org.junit.jupiter.api.Test
  void aTokenIsSentForHttpsAndPlainHttpGitServers() throws Exception {
    org.junit.jupiter.api.Assertions.assertNotNull(credentialsFor("https://git.example.com/a/b.git", "t0k"));
    org.junit.jupiter.api.Assertions.assertNotNull(credentialsFor("http://gitea.gitea.svc:3000/a/b.git", "t0k"),
        "internal Git servers reached over plain HTTP need the token too");
    org.junit.jupiter.api.Assertions.assertNull(credentialsFor("ssh://git@git.example.com/a/b.git", "t0k"));
    org.junit.jupiter.api.Assertions.assertNull(credentialsFor("https://git.example.com/a/b.git", ""));
  }

    @Test
    void changedFilesAreTheNewAndModifiedOnes(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        String sha = client.commit("add trino");
        client.push();
        assertEquals(sha, client.headCommit());
        assertTrue(client.changedFiles().isEmpty());

        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\nspec: {}\n");
        client.writeFile(Path.of("apps/trino/dependencies/keda-helmrelease.yaml"), "kind: HelmRelease\n");
        client.deletePath(Path.of("README.md"));

        assertEquals(java.util.Set.of(Path.of("apps/trino/helmrelease.yaml"),
                Path.of("apps/trino/dependencies/keda-helmrelease.yaml")), client.changedFiles(),
                "a deletion writes nothing");
        assertEquals(sha, client.headCommit(), "uncommitted files do not move HEAD");
    }

    @Test
    void aRefusedPushFailsAndPushingAgainPushesTheCommitAlreadyMade(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        String seed = client.headCommit();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        // The remote cannot update its branch (another update holds the lock): it answers with a refusal, not an error.
        Path lock = tmp.resolve("remote.git/refs/heads/" + BRANCH + ".lock");
        Files.writeString(lock, "");

        String unpushed = client.commit("add trino");
        org.junit.jupiter.api.Assertions.assertThrows(org.apache.ambari.view.k8s.service.deployment.GitPushRejectedException.class,
                client::push);
        assertEquals(unpushed, client.headCommit());
        org.junit.jupiter.api.Assertions.assertNotEquals(seed, unpushed);
        assertEquals(seed, remoteHead(tmp), "nothing landed on the remote");
        assertEquals(1, client.unpublishedCommits(), "the commit is known to be unpublished");

        Files.delete(lock);
        assertEquals(unpushed, client.push(), "pushing again pushes the commit");
        assertEquals(unpushed, remoteHead(tmp));
        org.junit.jupiter.api.Assertions.assertNull(client.commit("no-op"));
        assertEquals(0, client.unpublishedCommits(), "then nothing is left to push");
    }

    @Test
    void resetToDropsACommitThatWasNotPublished(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        String before = client.headCommit();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        Path lock = tmp.resolve("remote.git/refs/heads/" + BRANCH + ".lock");
        Files.writeString(lock, "");
        client.commit("add trino");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, client::push);

        client.resetTo(before);
        Files.delete(lock);

        assertEquals(before, client.headCommit());
        assertTrue(Files.notExists(tmp.resolve("ws/apps/trino/helmrelease.yaml")));
        org.junit.jupiter.api.Assertions.assertNull(client.commit("add trino"));
        assertEquals(0, client.unpublishedCommits(), "the dropped commit is not pushed by a later call");
    }

    /**
     * Commits files to {@code main} from a clone of its own, as a pull request merged by "squash" adds its change, then
     * deletes {@code branch} on the server as it does after the merge.
     */
    private static void squashMergeAndDelete(Path tmp, String url, String branch, java.util.Map<String, String> files)
            throws Exception {
        Path merger = tmp.resolve("merger-" + System.nanoTime());
        try (Git g = Git.cloneRepository().setURI(url).setBranch(BRANCH).setDirectory(merger.toFile()).call()) {
            for (java.util.Map.Entry<String, String> file : files.entrySet()) {
                Files.createDirectories(merger.resolve(file.getKey()).getParent());
                Files.writeString(merger.resolve(file.getKey()), file.getValue());
            }
            g.add().addFilepattern(".").call();
            g.commit().setMessage("Merge pull request").setAuthor("reviewer", "reviewer@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + BRANCH)).call();
        }
        try (Git bare = Git.open(tmp.resolve("remote.git").toFile())) {
            bare.branchDelete().setBranchNames("refs/heads/" + branch).setForce(true).call();
        }
    }

    @Test
    void aWorkingBranchMergedAsANewCommitAndDeletedIsStartedAgainFromTheBranchItWasMergedInto(@TempDir Path tmp)
            throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.checkoutBranch("flux-work");
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        client.commit("propose trino");
        client.push();
        org.junit.jupiter.api.Assertions.assertFalse(client.restartIfMergedInto(BRANCH), "the server still has it");

        squashMergeAndDelete(tmp, url, "flux-work", java.util.Map.of("apps/trino/helmrelease.yaml", "kind: HelmRelease\n"));
        client.sync();
        client.checkoutBranch("flux-work");
        assertEquals(1, client.unpublishedCommits(), "its commit is on none of the server's branches");

        assertTrue(client.restartIfMergedInto(BRANCH));
        assertEquals(remoteHead(tmp), client.headCommit(), "started again from main, which holds its change");
        assertEquals(0, client.unpublishedCommits(), "nothing is left to push");
        org.junit.jupiter.api.Assertions.assertNull(client.commit("no-op"));
    }

    @Test
    void aWorkingBranchWhoseChangeTheBaseBranchLacksIsKept(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.checkoutBranch("flux-work");
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        client.deletePath(Path.of("README.md"));
        String proposed = client.commit("propose trino, drop the readme");

        // Never pushed: main lacks its change.
        org.junit.jupiter.api.Assertions.assertFalse(client.restartIfMergedInto(BRANCH));
        org.junit.jupiter.api.Assertions.assertFalse(client.restartIfMergedInto(null));
        org.junit.jupiter.api.Assertions.assertFalse(client.restartIfMergedInto("never-pushed"));
        assertEquals(proposed, client.headCommit());

        // Main got the new file, but still has the file the branch deletes: only part of the change is merged.
        client.push();
        squashMergeAndDelete(tmp, url, "flux-work", java.util.Map.of("apps/trino/helmrelease.yaml", "kind: HelmRelease\n"));
        client.sync();
        client.checkoutBranch("flux-work");

        org.junit.jupiter.api.Assertions.assertFalse(client.restartIfMergedInto(BRANCH));
        assertEquals(proposed, client.headCommit(), "kept, to be pushed");
        assertEquals(1, client.unpublishedCommits());
    }

    private static String remoteHead(Path tmp) throws Exception {
        try (Git g = Git.open(tmp.resolve("remote.git").toFile())) {
            return g.getRepository().resolve("refs/heads/" + BRANCH).getName();
        }
    }

    @Test
    void onlyWhatTheBaseBranchPublishesCountsAsExisting(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        assertTrue(client.existsOnBaseBranch(Path.of("README.md")));

        client.writeFile(Path.of("deps/keda-helmrelease.yaml"), "kind: HelmRelease\n");
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnBaseBranch(Path.of("deps/keda-helmrelease.yaml")),
                "written but not published");
        client.commit("local only");
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnBaseBranch(Path.of("deps/keda-helmrelease.yaml")),
                "committed but not pushed");
        client.writeFile(Path.of("deps/other.yaml"), "x: 1\n");
        assertNotNull(client.commit("publish"));
        assertEquals(2, client.unpublishedCommits());
        client.push();
        client.sync();
        assertTrue(client.existsOnBaseBranch(Path.of("deps/keda-helmrelease.yaml")));
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnBaseBranch(Path.of("../outside")));
    }

    @Test
    void aFilePublishedOnTheWorkingBranchIsSeenThere(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.checkoutBranch("flux-123");
        client.writeFile(Path.of("deps/keda-helmrelease.yaml"), "kind: HelmRelease\n");
        assertNotNull(client.commit("on the working branch"));
        client.push();
        client.sync();

        assertTrue(client.existsOnRemoteBranch(Path.of("deps/keda-helmrelease.yaml"), "flux-123"));
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnBaseBranch(Path.of("deps/keda-helmrelease.yaml")));
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnRemoteBranch(Path.of("README.md"), "never-pushed"));
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnRemoteBranch(Path.of("README.md"), null));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, client.publishedOn(Path.of("README.md"), BRANCH));

        GitClient noRepo = new GitClient(tmp.resolve("not-a-repo"), url, BRANCH, null);
        org.junit.jupiter.api.Assertions.assertNull(noRepo.publishedOn(Path.of("README.md"), BRANCH),
                "unreadable: unknown, so the caller deletes nothing");
    }
}
