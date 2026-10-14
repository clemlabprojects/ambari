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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The pull requests of a Git server, kept in memory, for tests of GitOps deploys and uninstalls against a local
 * repository: what KDPS opened, and what the test merged or closed since, as a reviewer would on the server.
 */
public class FakePullRequests extends GitPullRequests {

    /** A pull request KDPS opened. */
    public record Opened(String number, String base, String head, String title, String token) {}

    public final List<Opened> opened = new CopyOnWriteArrayList<>();
    private final Map<String, String> states = new ConcurrentHashMap<>();

    public FakePullRequests() {
        super(null);
    }

    public static String url(String number) {
        return "https://git.example.org/platform/gitops/pulls/" + number;
    }

    @Override
    public Api api(String repoUrl, String token) {
        return new Api(Provider.GITEA, "https://git.example.org/api/v1", "platform/gitops");
    }

    @Override
    public PullRequestInfo open(String repoUrl, String base, String head, String title, String token) {
        String number = String.valueOf(opened.size() + 1);
        opened.add(new Opened(number, base, head, title, token));
        states.put(number, "open");
        return new PullRequestInfo(url(number), number, "open", null);
    }

    @Override
    public PullRequestInfo get(String repoUrl, String number, String token) throws IOException {
        String state = states.get(number);
        if (state == null) {
            throw new IOException("Gitea did not return pull request " + number + " (HTTP 404)");
        }
        return new PullRequestInfo(url(number), number, state, null);
    }

    /** Closes a pull request as KDPS asks the server to; one already merged or closed stays as it is. */
    @Override
    public PullRequestInfo close(String repoUrl, String number, String token) throws IOException {
        String state = states.get(number);
        if (state == null) {
            throw new IOException("Gitea did not close pull request " + number + " (HTTP 404)");
        }
        if (GitPullRequests.isOpen(state)) {
            states.put(number, "closed");
            closedByKdps.add(number);
        }
        return new PullRequestInfo(url(number), number, states.get(number), null);
    }

    /** The pull requests KDPS closed, by number. */
    public final List<String> closedByKdps = new CopyOnWriteArrayList<>();

    /** The state of a pull request, as the server holds it. */
    public String state(String number) {
        return states.get(number);
    }

    /** The last pull request opened. */
    public Opened last() {
        return opened.get(opened.size() - 1);
    }

    public void merge(String number) {
        states.put(number, "merged");
    }

    public void close(String number) {
        states.put(number, "closed");
    }
}
