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
        String sha = client.commitAndPush("Add trino values");
        assertNotNull(sha, "commit should produce a sha");
        assertEquals(40, sha.length(), "sha should be a full git object id");

        // A second commit with no changes returns null (nothing to commit).
        assertEquals(null, client.commitAndPush("no-op"), "no changes → no commit");

        // Verify the push landed by re-cloning the remote fresh.
        Path verify = tmp.resolve("verify");
        try (Git g = Git.cloneRepository().setURI(url).setBranch(BRANCH).setDirectory(verify.toFile()).call()) {
            Path pushed = verify.resolve("apps/trino/values.yaml");
            assertTrue(Files.exists(pushed), "pushed file must exist in the remote");
            assertEquals("replicas: 3\n", Files.readString(pushed, StandardCharsets.UTF_8));
        }
    }

    @Test
    void deletePathIsStagedAndPushed(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        Path ws = tmp.resolve("workspace");
        GitClient client = new GitClient(ws, url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        client.writeFile(Path.of("gone.txt"), "temporary");
        client.commitAndPush("add gone.txt");

        client.deletePath(Path.of("gone.txt"));
        String sha = client.commitAndPush("remove gone.txt");
        assertNotNull(sha, "deletion should be committed");

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
        String sha = client.commitAndPush("add trino");
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
    void aRefusedPushFailsAndTheNextCallPushesTheCommitAlreadyMade(@TempDir Path tmp) throws Exception {
        String url = seededBareRepo(tmp);
        GitClient client = new GitClient(tmp.resolve("ws"), url, BRANCH, null).withAuthor("KDPS Test", "kdps@example.com");
        client.sync();
        String seed = client.headCommit();
        client.writeFile(Path.of("apps/trino/helmrelease.yaml"), "kind: HelmRelease\n");
        // The remote cannot update its branch (another update holds the lock): it answers with a refusal, not an error.
        Path lock = tmp.resolve("remote.git/refs/heads/" + BRANCH + ".lock");
        Files.writeString(lock, "");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> client.commitAndPush("add trino"));
        String unpushed = client.headCommit();
        org.junit.jupiter.api.Assertions.assertNotEquals(seed, unpushed);
        assertEquals(seed, remoteHead(tmp), "nothing landed on the remote");

        Files.delete(lock);
        assertEquals(unpushed, client.commitAndPush("add trino"), "the retry pushes the commit, not \"no changes\"");
        assertEquals(unpushed, remoteHead(tmp));
        org.junit.jupiter.api.Assertions.assertNull(client.commitAndPush("no-op"), "then nothing is left to push");
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
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> client.commitAndPush("add trino"));

        client.resetTo(before);
        Files.delete(lock);

        assertEquals(before, client.headCommit());
        assertTrue(Files.notExists(tmp.resolve("ws/apps/trino/helmrelease.yaml")));
        org.junit.jupiter.api.Assertions.assertNull(client.commitAndPush("add trino"),
                "the dropped commit is not pushed by a later call");
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
        client.commitWithoutPush("local only");
        org.junit.jupiter.api.Assertions.assertFalse(client.existsOnBaseBranch(Path.of("deps/keda-helmrelease.yaml")),
                "committed but not pushed");
        client.writeFile(Path.of("deps/other.yaml"), "x: 1\n");
        assertNotNull(client.commitAndPush("publish"), "pushes both commits");
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
        assertNotNull(client.commitAndPush("on the working branch"));
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
