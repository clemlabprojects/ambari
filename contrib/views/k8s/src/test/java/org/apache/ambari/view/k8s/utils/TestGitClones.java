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
package org.apache.ambari.view.k8s.utils;

import java.nio.file.Path;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;

/** Clones the tests read what a deploy published with. */
public final class TestGitClones {

    private TestGitClones() {
    }

    /**
     * A clone of {@code branch} in {@code dir}, with JGit's automatic gc turned off before anything is fetched: a
     * background gc started by the fetch would still hold {@code gc.log.lock} when JUnit removes the temp directory.
     */
    public static Git clone(String url, String branch, Path dir) throws Exception {
        // Started on another branch: a checkout of the branch HEAD already names would leave the working tree empty.
        Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("unborn-" + branch).call();
        StoredConfig config = git.getRepository().getConfig();
        config.setInt("gc", null, "auto", 0);
        config.setInt("gc", null, "autoPackLimit", 0);
        config.setBoolean("gc", null, "autoDetach", false);
        config.save();
        // As a clone has it: the remote "origin" with every branch, and the branch tracking its own.
        git.remoteAdd().setName("origin").setUri(new URIish(url)).call();
        git.fetch().setRemote("origin").call();
        git.checkout().setName(branch).setCreateBranch(true).setStartPoint("origin/" + branch)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call();
        return git;
    }
}
