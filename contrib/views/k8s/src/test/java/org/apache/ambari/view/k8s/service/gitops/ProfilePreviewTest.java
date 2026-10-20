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

package org.apache.ambari.view.k8s.service.gitops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The repository preview's file list, and which files an operator may edit before a deploy. */
class ProfilePreviewTest {

    private static final List<ProfileRenderer.RenderedFile> RENDERED = List.of(
            new ProfileRenderer.RenderedFile("environments/prod/values.yaml", "a: 1\n", GitOpsProfile.MODE_ALWAYS),
            new ProfileRenderer.RenderedFile("environments/prod/README.md", "# trino\n", GitOpsProfile.MODE_CREATE_IF_MISSING));

    @Test
    void onlyAFileCreatedOnceAndNotInTheRepositoryYetCanBeEdited() {
        List<ProfileRenderer.RenderedFile> edited = ProfilePreview.withEdits(RENDERED,
                Map.of("environments/prod/README.md", "# trino\nOwned by the data team.\n"), path -> false, List.of());
        assertEquals("# trino\nOwned by the data team.\n", edited.get(1).content());
        assertEquals("a: 1\n", edited.get(0).content());

        assertThrows(IllegalArgumentException.class, () -> ProfilePreview.withEdits(RENDERED,
                Map.of("environments/prod/values.yaml", "a: 2\n"), path -> false, List.of()), "written by every deploy");
        assertThrows(IllegalArgumentException.class, () -> ProfilePreview.withEdits(RENDERED,
                Map.of("environments/prod/README.md", "x\n"), path -> true, List.of()), "already in the repository");
        assertThrows(IllegalArgumentException.class, () -> ProfilePreview.withEdits(RENDERED,
                Map.of("docs/runbook.md", "x\n"), path -> false, List.of()), "no rule writes it");
        assertThrows(IllegalStateException.class, () -> ProfilePreview.withEdits(RENDERED,
                Map.of("environments/prod/README.md", "token: s3cret-value\n"), path -> false, List.of("s3cret-value")));
        assertThrows(IllegalArgumentException.class, () -> ProfilePreview.withEdits(RENDERED,
                Map.of("environments/prod/README.md", "x".repeat(ProfilePreview.MAX_EDITS_BYTES + 1)), path -> false, List.of()));
    }

    @Test
    void theRepositoryIsListedWithWhereEachFileComesFrom(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("chart/charts"));
        Files.writeString(repo.resolve("chart/Chart.yaml"), "# trino\n");
        Files.writeString(repo.resolve("chart/old.yaml"), "gone\n");
        Files.write(repo.resolve("chart/charts/dep.tgz"), new byte[] {31, -117, 8, 0, 0});
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("docs/runbook.md"), "# Runbook\n");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        GitOpsProfile profile = new GitOpsProfile();
        profile.protect.add("docs/**");

        List<ProfilePreview.PreviewFile> files = ProfilePreview.files(profile, "chart",
                Map.of("Chart.yaml", "# trino\n".getBytes(), "charts/dep.tgz", new byte[] {31, -117, 8, 0, 0}),
                RENDERED, repo);

        assertEquals(List.of("chart/Chart.yaml", "chart/charts/dep.tgz", "chart/old.yaml", "docs/runbook.md",
                "environments/prod/README.md", "environments/prod/values.yaml"),
                files.stream().map(ProfilePreview.PreviewFile::path).toList());
        assertEquals(ProfilePreview.CHANGE_UNCHANGED, files.get(0).change());
        assertTrue(files.get(1).binary());
        assertNull(files.get(1).content());
        assertEquals(ProfilePreview.CHANGE_REMOVED, files.get(2).change());
        assertTrue(files.get(3).protectedPath());
        assertEquals(ProfilePreview.ORIGIN_REPOSITORY, files.get(3).origin());
        assertTrue(files.get(4).editable());
        assertFalse(files.get(5).editable());
    }
}
