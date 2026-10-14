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

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.charset.StandardCharsets;

/**
 * A Git server over SSH as current Git hosting runs it: OpenSSH 9 (Alpine 3.20) with its default algorithms, which
 * refuse RSA signatures made with SHA-1, serving a bare repository ({@link #REPO}, branch main holding a README) to
 * user {@code git} through git-shell. Its deploy keys are made by ssh-keygen with its defaults (OpenSSH format):
 * Ed25519 ({@link #ED25519_KEY}) and RSA ({@link #RSA_KEY}).
 */
final class OpenSshGitServer extends GenericContainer<OpenSshGitServer> {

    static final String REPO = "/home/git/repo.git";
    static final String ED25519_KEY = "/keys/deploy_ed25519";
    static final String RSA_KEY = "/keys/deploy_rsa";
    /** An Ed25519 key of another server: what a server answering in the Git server's place presents. */
    static final String IMPOSTOR_HOST_KEY = "/keys/impostor_host_ed25519.pub";
    static final String HOST_KEY = "/etc/ssh/ssh_host_ed25519_key.pub";

    OpenSshGitServer() {
        super(new ImageFromDockerfile("kdps-test-openssh-git", true).withDockerfileFromBuilder(image -> image
                .from("alpine:3.20")
                .run("apk add --no-cache openssh-server git"
                        + " && ssh-keygen -A"
                        // Only the Ed25519 host key: the key a client is asked to pin.
                        + " && rm -f /etc/ssh/ssh_host_rsa_key* /etc/ssh/ssh_host_ecdsa_key*"
                        + " && adduser -D -s /usr/bin/git-shell git"
                        // An account without a password is locked, and sshd turns its keys down.
                        + " && sed -i 's/^git:!/git:*/' /etc/shadow"
                        + " && mkdir -p /keys /home/git/.ssh"
                        + " && ssh-keygen -q -t ed25519 -N '' -C deploy-ed25519 -f " + ED25519_KEY
                        + " && ssh-keygen -q -t rsa -b 3072 -N '' -C deploy-rsa -f " + RSA_KEY
                        + " && ssh-keygen -q -t ed25519 -N '' -C impostor -f /keys/impostor_host_ed25519"
                        + " && cat " + ED25519_KEY + ".pub " + RSA_KEY + ".pub > /home/git/.ssh/authorized_keys"
                        + " && git init -q --bare -b main " + REPO
                        + " && git clone -q " + REPO + " /tmp/seed && cd /tmp/seed && echo seed > README.md"
                        + " && git add README.md && git -c user.name=seed -c user.email=seed@example.com commit -qm init"
                        + " && git push -q origin HEAD:main && cd / && rm -rf /tmp/seed"
                        + " && chown -R git:git /home/git && chmod 700 /home/git/.ssh"
                        + " && chmod 600 /home/git/.ssh/authorized_keys")
                .expose(22)
                .cmd("/usr/sbin/sshd", "-D", "-e")
                .build()));
        withExposedPorts(22);
        waitingFor(Wait.forListeningPort());
    }

    /** The repository's URL, {@code ssh://git@host:port/home/git/repo.git}. */
    String repoUrl() {
        return "ssh://git@" + getHost() + ":" + getMappedPort(22) + REPO;
    }

    /** How the server's host key is named in known_hosts: {@code [host]:port}. */
    String hostPattern() {
        return "[" + getHost() + "]:" + getMappedPort(22);
    }

    /** A file of the server, as text. */
    String read(String path) {
        return copyFileFromContainer(path, in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /** A public key file as a known_hosts line for this server: {@code [host]:port type key}. */
    String knownHostsLine(String publicKeyPath) {
        String[] fields = read(publicKeyPath).trim().split("\\s+");
        return hostPattern() + " " + fields[0] + " " + fields[1];
    }

    /** The fingerprint of the server's host key as OpenSSH itself prints it, e.g. {@code SHA256:...}. */
    String hostKeyFingerprint() throws Exception {
        Container.ExecResult result = execInContainer("ssh-keygen", "-l", "-f", HOST_KEY);
        return result.getStdout().trim().split("\\s+")[1];
    }

    /** The commit a branch of the repository is on, as the server holds it. */
    String head(String branch) throws Exception {
        return execInContainer("git", "--git-dir", REPO, "rev-parse", "refs/heads/" + branch).getStdout().trim();
    }

    /** Whether a file is on a branch of the repository, as the server holds it. */
    boolean holds(String branch, String file) throws Exception {
        return execInContainer("git", "--git-dir", REPO, "cat-file", "-e", branch + ":" + file).getExitCode() == 0;
    }
}
