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
}
