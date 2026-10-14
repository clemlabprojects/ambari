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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.Daemon;
import org.eclipse.jgit.transport.DaemonClient;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Git servers for tests of what a GitOps deploy does when the server refuses a push, drops a connection or stops
 * answering: a bare repository, a Git daemon serving it whose pushes can be refused by a server-side hook or dropped,
 * and a server that accepts connections and never answers.
 */
final class GitTestServers {

    private GitTestServers() {
    }

    /** A bare repository whose {@code main} branch holds one commit (a README). */
    static Path seededBareRepo(Path dir) throws Exception {
        Path bare = dir.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        // No background gc after a push: it would still hold gc.log.lock when the temp directory is removed.
        try (Git bareGit = Git.open(bare.toFile())) {
            org.eclipse.jgit.lib.StoredConfig config = bareGit.getRepository().getConfig();
            config.setInt("gc", null, "auto", 0);
            config.setBoolean("receive", null, "autogc", false);
            config.save();
        }
        Path seed = Files.createDirectories(dir.resolve("seed-" + System.nanoTime()));
        try (Git g = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            Files.writeString(seed.resolve("README.md"), "seed");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setAuthor("seed", "seed@example.com").call();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
        return bare;
    }

    /** The commit a branch of a bare repository is on, or null. */
    static String head(Path bare, String branch) throws Exception {
        try (Git g = Git.open(bare.toFile())) {
            org.eclipse.jgit.lib.ObjectId id = g.getRepository().resolve("refs/heads/" + branch);
            return id == null ? null : id.getName();
        }
    }

    /** Commits a file to a branch of a bare repository from a clone of its own: someone else's change. */
    static String pushFromElsewhere(Path bare, String branch, String file, String content) throws Exception {
        Path clone = Files.createTempDirectory(bare.getParent(), "elsewhere-");
        try (Git g = Git.cloneRepository().setURI(bare.toUri().toString()).setBranch(branch)
                .setDirectory(clone.toFile()).call()) {
            Path target = clone.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            g.add().addFilepattern(".").call();
            String sha = g.commit().setMessage("change from elsewhere").setAuthor("other", "other@example.com").call()
                    .getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + branch)).call();
            return sha;
        }
    }

    /**
     * Merges a branch of a bare repository into another as one new commit, as a pull request merged by "squash" or
     * "rebase" is, then deletes the merged branch, as the Git server does when set to delete a branch after its merge.
     *
     * @return the commit the target branch is on after the merge
     */
    static String squashMergeAndDelete(Path bare, String branch, String into) throws Exception {
        Path clone = Files.createTempDirectory(bare.getParent(), "merger-");
        try (Git g = Git.cloneRepository().setURI(bare.toUri().toString()).setBranch(into)
                .setDirectory(clone.toFile()).call()) {
            // A merge starts JGit's garbage collection, which would still run in the background when the test's
            // directory is removed.
            org.eclipse.jgit.lib.StoredConfig config = g.getRepository().getConfig();
            config.setBoolean("gc", null, "autoDetach", false);
            config.save();
            g.merge().include(g.getRepository().resolve("refs/remotes/origin/" + branch)).setSquash(true).call();
            String sha = g.commit().setMessage("Merge pull request").setAuthor("reviewer", "reviewer@example.com")
                    .call().getName();
            g.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + into)).call();
            deleteBranch(bare, branch);
            return sha;
        }
    }

    /** Deletes a branch of a bare repository, as a pull request closed and its branch deleted on the server. */
    static void deleteBranch(Path bare, String branch) throws Exception {
        try (Git g = Git.open(bare.toFile())) {
            g.branchDelete().setBranchNames("refs/heads/" + branch).setForce(true).call();
        }
    }

    /** Creates a branch of a bare repository on the commit another one is on. */
    static void createBranch(Path bare, String branch, String from) throws Exception {
        try (Git g = Git.open(bare.toFile())) {
            g.branchCreate().setName(branch).setStartPoint("refs/heads/" + from).call();
        }
    }

    /**
     * A Git daemon ({@code git://}) serving a bare repository on the loopback interface. Its pushes can be refused by
     * a server-side hook, as a protected branch or a pre-receive hook refuses them, dropped before they are read, as a
     * network failure drops them, or lose a race against another push inside the server.
     */
    static final class GitDaemon implements AutoCloseable {
        private final Daemon daemon;
        private final AtomicInteger pushes = new AtomicInteger();
        private final AtomicInteger pushesToDrop = new AtomicInteger();
        private final AtomicReference<Callable<String>> raceWinner = new AtomicReference<>();
        private volatile String refusal;
        private volatile String refusalMessage;

        GitDaemon(Path bare) throws IOException {
            daemon = new Daemon(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            daemon.getService("git-receive-pack").setEnabled(true);
            daemon.setRepositoryResolver((DaemonClient client, String name) -> {
                try {
                    return new FileRepositoryBuilder().setGitDir(bare.toFile()).setBare().build();
                } catch (IOException e) {
                    throw new RepositoryNotFoundException(name, e);
                }
            });
            daemon.setReceivePackFactory((DaemonClient client, Repository db) -> {
                pushes.incrementAndGet();
                ReceivePack receivePack = new ReceivePack(db);
                if (pushesToDrop.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    // The pack is received, then the connection drops before the server answers: the branch is not
                    // updated and the client sees the connection go.
                    receivePack.setPreReceiveHook((rp, commands) -> {
                        for (ReceiveCommand command : commands) {
                            command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "connection lost");
                        }
                        try {
                            client.getOutputStream().close();
                        } catch (IOException ignored) {
                            // already gone
                        }
                    });
                    return receivePack;
                }
                Callable<String> winner = raceWinner.getAndSet(null);
                if (winner != null) {
                    // Another push updates the branch while this one is received, then this one fails to update it,
                    // worded as C git words it: the reason "failed to update ref", the details only in its messages.
                    receivePack.setPreReceiveHook((rp, commands) -> {
                        for (ReceiveCommand command : commands) {
                            String landed;
                            try {
                                landed = winner.call();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            rp.sendError("cannot lock ref '" + command.getRefName() + "': is at " + landed
                                    + " but expected " + command.getOldId().getName());
                            command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "failed to update ref");
                        }
                    });
                    return receivePack;
                }
                String refused = refusal;
                if (refused != null) {
                    String said = refusalMessage;
                    receivePack.setPreReceiveHook((rp, commands) -> {
                        if (said != null) {
                            rp.sendMessage(said);
                        }
                        for (ReceiveCommand command : commands) {
                            command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, refused);
                        }
                    });
                }
                return receivePack;
            });
            daemon.start();
        }

        /** The URL of the repository. */
        String url() {
            return "git://127.0.0.1:" + daemon.getAddress().getPort() + "/remote.git";
        }

        /**
         * Refuses every push from now on, as a server hook does.
         *
         * @param reason  the status the server gives the update
         * @param message what the hook prints
         */
        void refusePushes(String reason, String message) {
            this.refusalMessage = message;
            this.refusal = reason;
        }

        /** Accepts pushes again. */
        void acceptPushes() {
            this.refusal = null;
        }

        /**
         * Makes the next push lose a race inside the server: while it is received, {@code winner} pushes to the branch
         * first, and the server then fails to update the branch.
         *
         * @param winner pushes the other change and returns the commit it landed
         */
        void loseNextPushToRace(Callable<String> winner) {
            raceWinner.set(winner);
        }

        /** Drops the next {@code count} push connections once the pushed commits are received, before answering. */
        void dropNextPushes(int count) {
            pushesToDrop.set(count);
        }

        /** How many push connections the daemon took. */
        int pushes() {
            return pushes.get();
        }

        @Override
        public void close() {
            daemon.stop();
        }
    }

    /**
     * A server that accepts connections and never answers: a Git server or proxy that stalled, or a connection the
     * network dropped without closing it.
     */
    static final class Blackhole implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> held = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();

        Blackhole() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        held.add(server.accept());
                        connections.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "git-blackhole");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /** An HTTP URL of a repository on this server. */
        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/platform/gitops.git";
        }

        /** How many connections the server took. */
        int connections() {
            return connections.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : held) {
                socket.close();
            }
        }
    }
}
