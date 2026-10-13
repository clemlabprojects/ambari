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

import com.sun.net.httpserver.HttpServer;
import org.apache.ambari.view.k8s.service.ProxySupport;
import org.apache.ambari.view.k8s.service.deployment.GitPullRequests.Api;
import org.apache.ambari.view.k8s.service.deployment.GitPullRequests.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the pull requests of a repository are, for each kind of Git server KDPS opens them on, and what it sends there:
 * github.com's API at api.github.com, any other GitHub server's at /api/v3, GitLab's and Gitea's on the server itself,
 * with the port and the context path the repository URL names. Other Git servers are refused with a clear error.
 */
class GitPullRequestsTest {

    private static Api only(String repoUrl) {
        List<Api> candidates = GitPullRequests.candidates(repoUrl);
        assertEquals(1, candidates.size(), candidates.toString());
        return candidates.get(0);
    }

    @Test
    void githubComGoesToApiGithubCom() {
        Api expected = new Api(Provider.GITHUB, "https://api.github.com", "acme/gitops");
        assertEquals(expected, only("https://github.com/acme/gitops.git"));
        assertEquals(expected, only("git@github.com:acme/gitops.git"));
        assertEquals(expected, only("https://github.com/acme/gitops/"));
        assertEquals("https://api.github.com/repos/acme/gitops/pulls", expected.pullRequestsUrl());
    }

    @Test
    void anotherGithubServerGoesToItsOwnApiWithItsPort() {
        assertEquals(new Api(Provider.GITHUB, "https://github.acme.corp:8443/api/v3", "platform/gitops"),
                only("https://github.acme.corp:8443/platform/gitops.git"));
        // Over SSH, the port is the SSH server's: the API is on the default HTTPS port.
        assertEquals(new Api(Provider.GITHUB, "https://github.acme.corp/api/v3", "platform/gitops"),
                only("ssh://git@github.acme.corp:2222/platform/gitops.git"));
    }

    @Test
    void gitlabKeepsThePortAndTheContextPath() {
        assertEquals(new Api(Provider.GITLAB, "https://gitlab.acme.corp:8443/api/v4", "platform/gitops"),
                only("https://gitlab.acme.corp:8443/platform/gitops.git"));
        // A project in a subgroup, or on a GitLab served below a path: each split is a candidate.
        assertEquals(List.of(
                new Api(Provider.GITLAB, "https://gitlab.acme.corp:8443/api/v4", "gitlab/platform/gitops"),
                new Api(Provider.GITLAB, "https://gitlab.acme.corp:8443/gitlab/api/v4", "platform/gitops")),
                GitPullRequests.candidates("https://gitlab.acme.corp:8443/gitlab/platform/gitops.git"));
        assertEquals("https://gitlab.acme.corp:8443/gitlab/api/v4/projects/platform%2Fgitops/merge_requests",
                new Api(Provider.GITLAB, "https://gitlab.acme.corp:8443/gitlab/api/v4", "platform/gitops")
                        .pullRequestsUrl());
    }

    @Test
    void onlyTheGitSuffixIsStrippedNeverPartOfTheHost() {
        assertEquals(new Api(Provider.GITLAB, "https://scm.gitlab.bank.local/api/v4", "platform/gitops"),
                only("https://scm.gitlab.bank.local/platform/gitops.git"));
        assertEquals(new Api(Provider.GITLAB, "https://code.gitlab.example/api/v4", "platform/gitops.github"),
                only("https://code.gitlab.example/platform/gitops.github.git"));
    }

    @Test
    void giteaGoesToApiV1OfItsServer() {
        Api crc = new Api(Provider.GITEA, "http://gitea-gitea.apps-crc.testing/api/v1", "kdps/gitops-test");
        assertEquals(crc, only("http://gitea-gitea.apps-crc.testing/kdps/gitops-test.git"));
        assertEquals("http://gitea-gitea.apps-crc.testing/api/v1/repos/kdps/gitops-test/pulls", crc.pullRequestsUrl());
        assertEquals(new Api(Provider.GITEA, "https://forgejo.acme.corp:3000/git/api/v1", "platform/gitops"),
                only("https://forgejo.acme.corp:3000/git/platform/gitops.git"));
    }

    @Test
    void aServerWhoseNameDoesNotTellIsAskedAsGitlabThenGiteaThenGithub() {
        assertEquals(List.of(
                new Api(Provider.GITLAB, "https://git.acme.corp/api/v4", "scm/platform/gitops"),
                new Api(Provider.GITLAB, "https://git.acme.corp/scm/api/v4", "platform/gitops"),
                new Api(Provider.GITEA, "https://git.acme.corp/scm/api/v1", "platform/gitops"),
                new Api(Provider.GITHUB, "https://git.acme.corp/scm/api/v3", "platform/gitops")),
                GitPullRequests.candidates("https://git.acme.corp/scm/platform/gitops.git"));
    }

    @Test
    void otherGitServersAreRefusedWithAClearError() {
        for (Map.Entry<String, String> server : Map.of(
                "https://bitbucket.org/acme/gitops.git", "bitbucket.org is Bitbucket",
                "https://bitbucket.acme.corp/scm/platform/gitops.git", "bitbucket.acme.corp is Bitbucket",
                "https://dev.azure.com/acme/platform/_git/gitops", "dev.azure.com is Azure DevOps",
                "https://git-codecommit.eu-west-1.amazonaws.com/v1/repos/gitops", "is AWS CodeCommit").entrySet()) {
            GitPullRequests.UnsupportedGitServerException refused = assertThrows(
                    GitPullRequests.UnsupportedGitServerException.class, () -> GitPullRequests.candidates(server.getKey()));
            assertTrue(refused.getMessage().startsWith("KDPS opens pull requests on GitHub, GitLab and Gitea only, and "),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains(server.getValue()), refused.getMessage());
        }
        assertThrows(GitPullRequests.UnsupportedGitServerException.class,
                () -> GitPullRequests.candidates("file:///srv/git/gitops.git"));
        assertThrows(GitPullRequests.UnsupportedGitServerException.class,
                () -> GitPullRequests.candidates("https://git.acme.corp/gitops.git"), "no owner");
        assertThrows(GitPullRequests.UnsupportedGitServerException.class,
                () -> GitPullRequests.candidates("https://attacker.example#@git.acme.corp/x/y.git"),
                "the token never goes to another host than the one it is bound to");
    }

    @Test
    void openOrOpenedOrDraftMayStillBeMerged() {
        for (String open : List.of("open", "opened", "draft", "OPEN")) assertTrue(GitPullRequests.isOpen(open), open);
        for (String done : List.of("merged", "closed", "locked")) assertFalse(GitPullRequests.isOpen(done), done);
        assertFalse(GitPullRequests.isOpen(null));
        assertTrue(GitPullRequests.isMerged("merged"));
        assertTrue(GitPullRequests.isClosed("closed"));
        for (String notClosed : List.of("merged", "open", "opened")) assertFalse(GitPullRequests.isClosed(notClosed));
        assertFalse(GitPullRequests.isClosed(null));
    }

    // ---------------------------------------------------------------- what is sent

    /** A request as the Git server received it. */
    private record Received(String method, String uri, Map<String, List<String>> headers, String body) {}

    private final List<Received> received = new ArrayList<>();

    /** A Git server answering each request with what {@code answer} gives for it: status and body. */
    @SuppressWarnings("unchecked")
    private GitPullRequests server(Function<Received, Object[]> answer) throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            Received got = new Received(request.method(), request.uri().toString(), request.headers().map(),
                    body(request));
            received.add(got);
            Object[] reply = answer.apply(got);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn((Integer) reply[0]);
            when(response.body()).thenReturn((String) reply[1]);
            return response;
        });
        return new GitPullRequests(http);
    }

    private static String body(HttpRequest request) {
        if (request.bodyPublisher().isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aGithubPullRequestIsOpenedThroughApiGithubCom() throws Exception {
        GitPullRequests github = server(request -> new Object[]{201,
                "{\"number\":12,\"html_url\":\"https://github.com/acme/gitops/pull/12\",\"state\":\"open\"}"});

        GitPullRequests.PullRequestInfo opened = github.open("https://github.com/acme/gitops.git", "main",
                "flux-1", "Flux GitOps (PR) apply analytics/trino", "ghp_t0k");

        assertEquals("12", opened.id());
        assertEquals("https://github.com/acme/gitops/pull/12", opened.url());
        assertEquals("open", opened.state());
        Received request = received.get(0);
        assertEquals("POST", request.method());
        assertEquals("https://api.github.com/repos/acme/gitops/pulls", request.uri());
        assertEquals(List.of("Bearer ghp_t0k"), request.headers().get("Authorization"));
        assertEquals("{\"title\":\"Flux GitOps (PR) apply analytics/trino\",\"head\":\"flux-1\",\"base\":\"main\"}",
                request.body());
    }

    @Test
    void aGitlabMergeRequestIsOpenedOnTheServersPortAndContextPath() throws Exception {
        GitPullRequests gitlab = server(request -> request.method().equals("GET")
                ? new Object[]{request.uri().contains("/gitlab/api/v4/projects/platform%2Fgitops") ? 200 : 404, "{}"}
                : new Object[]{201, "{\"iid\":3,\"web_url\":\"https://gitlab.acme.corp:8443/gitlab/platform/gitops/-/"
                        + "merge_requests/3\",\"state\":\"opened\"}"});

        GitPullRequests.PullRequestInfo opened = gitlab.open("https://gitlab.acme.corp:8443/gitlab/platform/gitops.git",
                "main", "flux-1", "Flux GitOps (PR) apply analytics/trino", "glpat-t0k");

        assertEquals("3", opened.id());
        assertEquals("opened", opened.state());
        // The project is first looked for without a context path, then below /gitlab, where it is.
        assertEquals(List.of(
                "GET https://gitlab.acme.corp:8443/api/v4/projects/gitlab%2Fplatform%2Fgitops",
                "GET https://gitlab.acme.corp:8443/gitlab/api/v4/projects/platform%2Fgitops",
                "POST https://gitlab.acme.corp:8443/gitlab/api/v4/projects/platform%2Fgitops/merge_requests"),
                received.stream().map(r -> r.method() + " " + r.uri()).toList());
        Received post = received.get(2);
        assertEquals(List.of("glpat-t0k"), post.headers().get("PRIVATE-TOKEN"));
        assertEquals("title=Flux+GitOps+%28PR%29+apply+analytics%2Ftrino&source_branch=flux-1&target_branch=main",
                post.body());

        // Found once: the next call goes straight to it.
        received.clear();
        gitlab.get("https://gitlab.acme.corp:8443/gitlab/platform/gitops.git", "3", "glpat-t0k");
        assertEquals(List.of("GET https://gitlab.acme.corp:8443/gitlab/api/v4/projects/platform%2Fgitops/merge_requests/3"),
                received.stream().map(r -> r.method() + " " + r.uri()).toList());
    }

    @Test
    void aGiteaPullRequestIsOpenedAndReadThroughApiV1() throws Exception {
        GitPullRequests gitea = server(request -> request.method().equals("POST")
                ? new Object[]{201, "{\"number\":5,\"html_url\":\"http://gitea-gitea.apps-crc.testing/kdps/gitops-test/"
                        + "pulls/5\",\"state\":\"open\",\"merged\":false}"}
                : new Object[]{200, "{\"number\":5,\"html_url\":\"http://gitea-gitea.apps-crc.testing/kdps/gitops-test/"
                        + "pulls/5\",\"state\":\"closed\",\"merged\":true}"});
        String repoUrl = "http://gitea-gitea.apps-crc.testing/kdps/gitops-test.git";

        GitPullRequests.PullRequestInfo opened = gitea.open(repoUrl, "main", "flux-1", "Flux GitOps (PR) apply x/y",
                "gitea-t0k");
        GitPullRequests.PullRequestInfo read = gitea.get(repoUrl, "5", "gitea-t0k");

        assertEquals("5", opened.id());
        assertEquals("open", opened.state());
        assertEquals("merged", read.state());
        assertEquals("http://gitea-gitea.apps-crc.testing/api/v1/repos/kdps/gitops-test/pulls", received.get(0).uri());
        assertEquals(List.of("token gitea-t0k"), received.get(0).headers().get("Authorization"));
        assertEquals("{\"title\":\"Flux GitOps (PR) apply x/y\",\"head\":\"flux-1\",\"base\":\"main\"}",
                received.get(0).body());
        assertEquals("http://gitea-gitea.apps-crc.testing/api/v1/repos/kdps/gitops-test/pulls/5", received.get(1).uri());
    }

    @Test
    void aServerWhoseNameDoesNotTellIsFoundByAskingIt() throws Exception {
        GitPullRequests unknown = server(request -> new Object[]{
                request.uri().equals("https://git.acme.corp/api/v1/repos/platform/gitops") ? 200 : 404, "{}"});

        Api found = unknown.api("https://git.acme.corp/platform/gitops.git", "t0k");

        assertEquals(new Api(Provider.GITEA, "https://git.acme.corp/api/v1", "platform/gitops"), found);
        assertEquals(List.of("https://git.acme.corp/api/v4/projects/platform%2Fgitops",
                "https://git.acme.corp/api/v1/repos/platform/gitops"), received.stream().map(Received::uri).toList());
    }

    @Test
    void aServerThatIsNoneOfThemIsRefusedWithWhatEachApiAnswered() throws Exception {
        GitPullRequests none = server(request -> new Object[]{404, "Not Found"});

        GitPullRequests.UnsupportedGitServerException refused = assertThrows(
                GitPullRequests.UnsupportedGitServerException.class,
                () -> none.open("https://git.acme.corp/platform/gitops.git", "main", "flux-1", "t", "t0k"));

        assertEquals("KDPS found no GitHub, GitLab or Gitea API that knows the repository "
                + "https://git.acme.corp/platform/gitops (GitLab https://git.acme.corp/api/v4: HTTP 404; Gitea "
                + "https://git.acme.corp/api/v1: HTTP 404; GitHub https://git.acme.corp/api/v3: HTTP 404). It opens pull"
                + " requests on those Git servers only: check the repository URL and that the access token may read the"
                + " repository, or use Direct commit.", refused.getMessage());
        assertTrue(received.stream().noneMatch(r -> r.method().equals("POST")), "nothing opened");
    }

    /**
     * Through the client the view builds (no proxy credentials), a Git server that refuses the access token answers
     * HTTP 401 for each API asked, once each, rather than reading as a server that cannot be reached.
     */
    @Test
    void anAccessTokenTheServerRefusesIsReportedAsHttp401ForEachApiAsked() throws Exception {
        HttpServer git = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> asked = new CopyOnWriteArrayList<>();
        git.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().contains("/api/v4/")) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"gitlab\"");
            } else if (exchange.getRequestURI().getPath().contains("/api/v1/")) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"gitea\"");
            }
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        git.start();
        try {
            String root = "http://127.0.0.1:" + git.getAddress().getPort();
            GitPullRequests pullRequests = GitPullRequests.through(ProxySupport.clients(HttpClient::newBuilder,
                    () -> ProxySupport.from(null, null, null, null, false)));

            GitPullRequests.UnsupportedGitServerException refused = assertThrows(
                    GitPullRequests.UnsupportedGitServerException.class,
                    () -> pullRequests.open(root + "/platform/gitops.git", "main", "flux-1", "t", "expired"));

            assertTrue(refused.getMessage().contains("(GitLab " + root + "/api/v4: HTTP 401; Gitea " + root
                    + "/api/v1: HTTP 401; GitHub " + root + "/api/v3: HTTP 401)"), refused.getMessage());
            assertEquals(3, asked.size(), "one request per API, none retried: " + asked);
        } finally {
            git.stop(0);
        }
    }

    @Test
    void aPullRequestTheServerRefusesFailsWithItsReason() throws Exception {
        GitPullRequests github = server(request -> new Object[]{422, "{\"message\":\"Validation Failed\",\"errors\":"
                + "[{\"resource\":\"PullRequest\",\"code\":\"custom\",\"message\":\"A pull request already exists for "
                + "acme:flux-1.\"}]}"});

        IOException refused = assertThrows(IOException.class, () -> github.open("https://github.com/acme/gitops.git",
                "main", "flux-1", "t", "ghp_t0k"));

        assertEquals("GitHub did not open the pull request of branch flux-1 into main of acme/gitops (HTTP 422): "
                + "Validation Failed; A pull request already exists for acme:flux-1.", refused.getMessage());
    }

    @Test
    void aPullRequestIsClosedThroughTheApiOfEachProvider() throws Exception {
        GitPullRequests github = server(request -> new Object[]{200, "{\"number\":12,\"html_url\":"
                + "\"https://github.com/acme/gitops/pull/12\",\"state\":\"closed\",\"merged\":false}"});
        GitPullRequests.PullRequestInfo closed = github.close("https://github.com/acme/gitops.git", "12", "ghp_t0k");
        assertEquals("closed", closed.state());
        assertEquals("PATCH https://api.github.com/repos/acme/gitops/pulls/12",
                received.get(0).method() + " " + received.get(0).uri());
        assertEquals(List.of("Bearer ghp_t0k"), received.get(0).headers().get("Authorization"));
        assertEquals("{\"state\":\"closed\"}", received.get(0).body());

        received.clear();
        GitPullRequests gitlab = server(request -> new Object[]{200, "{\"iid\":3,\"state\":\"closed\"}"});
        assertEquals("closed", gitlab.close("https://gitlab.acme.corp:8443/platform/gitops.git", "3", "glpat-t0k")
                .state());
        assertEquals("PUT https://gitlab.acme.corp:8443/api/v4/projects/platform%2Fgitops/merge_requests/3",
                received.get(0).method() + " " + received.get(0).uri());
        assertEquals(List.of("glpat-t0k"), received.get(0).headers().get("PRIVATE-TOKEN"));
        assertEquals("state_event=close", received.get(0).body());

        received.clear();
        GitPullRequests gitea = server(request -> new Object[]{201, "{\"number\":5,\"state\":\"closed\"}"});
        gitea.close("http://gitea-gitea.apps-crc.testing/kdps/gitops-test.git", "5", "gitea-t0k");
        assertEquals("PATCH http://gitea-gitea.apps-crc.testing/api/v1/repos/kdps/gitops-test/pulls/5",
                received.get(0).method() + " " + received.get(0).uri());
        assertEquals(List.of("token gitea-t0k"), received.get(0).headers().get("Authorization"));
        assertEquals("{\"state\":\"closed\"}", received.get(0).body());
    }

    @Test
    void aPullRequestTheServerDoesNotCloseFailsWithItsReason() throws Exception {
        GitPullRequests github = server(request -> new Object[]{403,
                "{\"message\":\"Must have admin rights to Repository.\"}"});

        IOException refused = assertThrows(IOException.class,
                () -> github.close("https://github.com/acme/gitops.git", "12", "ghp_t0k"));

        assertEquals("GitHub did not close pull request 12 of acme/gitops (HTTP 403): Must have admin rights to"
                + " Repository.", refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> github.close("https://github.com/acme/gitops.git", "12/merge", "ghp_t0k"));
    }

    @Test
    void parsersReadTheStateOfEachProvider() {
        assertEquals("merged", GitPullRequests.PullRequestParser.parseGithub(
                "{\"number\":1,\"state\":\"closed\",\"merged\":true}", "1").state());
        assertEquals("draft", GitPullRequests.PullRequestParser.parseGithub(
                "{\"number\":1,\"state\":\"open\",\"draft\":true}", "1").state());
        assertEquals("merged", GitPullRequests.PullRequestParser.parseGitlab(
                "{\"iid\":2,\"state\":\"merged\",\"merged_at\":\"2026-10-12T10:00:00Z\"}", "2").state());
        assertEquals("2", GitPullRequests.PullRequestParser.parseGitlab("{\"iid\":2.0}", "x").id());
    }
}
