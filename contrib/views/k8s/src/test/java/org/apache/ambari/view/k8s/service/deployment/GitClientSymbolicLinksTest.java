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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The symbolic links a Git sync replaces: those of the working tree, never Git's own folder. */
class GitClientSymbolicLinksTest {

  @TempDir
  Path workspace;

  @Test
  void theLinksOfTheWorkingTreeAreFoundWithoutEnteringGitsOwnFolder() throws Exception {
    Files.createDirectories(workspace.resolve("chart/templates"));
    Files.writeString(workspace.resolve("chart/values.yaml"), "a: 1\n");
    Files.createSymbolicLink(workspace.resolve("chart/link.yaml"), Path.of("values.yaml"));
    Files.createSymbolicLink(workspace.resolve("templates"), Path.of("chart/templates"));
    Path git = Files.createDirectories(workspace.resolve(".git/hooks"));
    Files.createSymbolicLink(git.resolve("pre-commit"), Path.of("/bin/true"));
    // JGit's files come and go while it tidies the repository: a walk entering .git could fail on any of them,
    // as it fails on a folder it may not read.
    Files.setPosixFilePermissions(git, PosixFilePermissions.fromString("---------"));
    try {
      assertEquals(List.of("chart/link.yaml", "templates"),
          GitClient.symbolicLinksIn(workspace).stream().sorted().toList());
    } finally {
      Files.setPosixFilePermissions(git, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void aTreeWithoutLinksHasNone() throws Exception {
    Files.writeString(workspace.resolve("README.md"), "# gitops\n");
    assertEquals(List.of(), GitClient.symbolicLinksIn(workspace));
  }
}
