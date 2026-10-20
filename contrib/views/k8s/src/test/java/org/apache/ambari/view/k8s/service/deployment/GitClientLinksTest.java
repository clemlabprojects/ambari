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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** A symbolic link in a Git workspace never takes a write or a delete outside the repository. */
class GitClientLinksTest {

    @Test
    void noWriteNorDeleteGoesThroughALink(@TempDir Path workspace, @TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("keep.txt"), "host file\n");
        Files.createSymbolicLink(workspace.resolve("environments"), outside);
        Files.createSymbolicLink(workspace.resolve("values.yaml"), outside.resolve("keep.txt"));
        GitClient git = new GitClient(workspace, "file:///unused.git", "main", null);

        assertThrows(IOException.class, () -> git.writeFile(Path.of("environments/prod/values.yaml"), "x"));
        assertThrows(IOException.class, () -> git.writeFile(Path.of("values.yaml"), "x"));
        assertThrows(IOException.class, () -> git.write(new GitClient.Change(
                Map.of(Path.of("environments/a.yaml"), new byte[] {1}), Set.of())));
        assertThrows(IOException.class, () -> git.deletePath(Path.of("environments/keep.txt")));

        try (Stream<Path> files = Files.list(outside)) {
            assertEquals(1, files.count());
        }
        assertEquals("host file\n", Files.readString(outside.resolve("keep.txt")));
    }

    @Test
    void aPlainPathIsStillWritten(@TempDir Path workspace) throws IOException {
        GitClient git = new GitClient(workspace, "file:///unused.git", "main", null);
        git.writeFile(Path.of("environments/prod/values.yaml"), "a: 1\n");
        assertEquals("a: 1\n", Files.readString(workspace.resolve("environments/prod/values.yaml")));
    }
}
