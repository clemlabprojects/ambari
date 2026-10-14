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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.apache.ambari.view.k8s.service.GitCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The pull requests of a Git repository (merge requests on GitLab), through the API of its Git server: GitHub
 * (github.com and GitHub Enterprise), GitLab and Gitea. The API is found from the repository URL: github.com's at
 * api.github.com, any other GitHub server's at {@code /api/v3}, GitLab's at {@code /api/v4} and Gitea's at
 * {@code /api/v1}, each with the port and the context path the server is reached at. A server whose name does not tell
 * which one it is gets asked: the first API that knows the repository is the one. Any other Git server (Bitbucket,
 * Azure DevOps) is refused with an error that says so, rather than leaving a change waiting for a pull request nobody
 * opens.
 */
public class GitPullRequests {
    private static final Logger LOG = LoggerFactory.getLogger(GitPullRequests.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 250L;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Gson GSON = new GsonBuilder().create();

    /** The kinds of Git server KDPS opens pull requests on. */
    public enum Provider {
        GITHUB("GitHub"), GITLAB("GitLab"), GITEA("Gitea");

        private final String label;

        Provider(String label) {
            this.label = label;
        }

        /** The name of the product, for messages. */
        public String label() {
            return label;
        }
    }

    /**
     * Where the pull requests of a repository are, in the API of its Git server.
     *
     * @param provider   the kind of Git server
     * @param baseUrl    the root of its API, such as {@code https://api.github.com} or
     *                   {@code https://git.example.org:8443/gitlab/api/v4}
     * @param repository the repository as the API names it: {@code owner/name}, or the project path on GitLab
     */
    public record Api(Provider provider, String baseUrl, String repository) {
        /** The repository in the API. */
        String repositoryUrl() {
            return provider == Provider.GITLAB
                    ? baseUrl + "/projects/" + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                    : baseUrl + "/repos/" + repository;
        }

        /** Its pull requests (GitLab: merge requests) in the API. */
        String pullRequestsUrl() {
            return repositoryUrl() + (provider == Provider.GITLAB ? "/merge_requests" : "/pulls");
        }
    }

    /** A pull request (GitLab: a merge request) as the Git server describes it. */
    public static final class PullRequestInfo {
        final String url;
        final String id;
        final String state;
        final String message;

        PullRequestInfo(String url, String id) {
            this(url, id, null, null);
        }

        PullRequestInfo(String url, String id, String state, String message) {
            this.url = url;
            this.id = id;
            this.state = state;
            this.message = message;
        }

        /** Its web page. */
        public String url() {
            return url;
        }

        /** Its number (GitLab: its iid). */
        public String id() {
            return id;
        }

        /** open, draft, closed or merged; GitLab says opened, locked, closed or merged. */
        public String state() {
            return state;
        }
    }

    /**
     * A Git server, or a repository URL, on which KDPS cannot open pull requests. Nothing was pushed for the change
     * the pull request was for.
     */
    public static final class UnsupportedGitServerException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UnsupportedGitServerException(String message) {
            super(message);
        }
    }

    /** The client of the moment: which one may change with the outbound proxy settings. */
    private final Supplier<HttpClient> httpClient;
    /** The API found for a repository whose server had to be asked, by repository. */
    private final Map<String, Api> found = new ConcurrentHashMap<>();

    public GitPullRequests(HttpClient httpClient) {
        this.httpClient = () -> httpClient;
    }

    private GitPullRequests(Supplier<HttpClient> httpClient) {
        this.httpClient = httpClient;
    }

    /** Pull requests sent through the client {@code httpClient} gives at each request. */
    public static GitPullRequests through(Supplier<HttpClient> httpClient) {
        return new GitPullRequests(httpClient);
    }

    /** Whether a pull request in that state may still be merged: its branch may take more changes. */
    public static boolean isOpen(String state) {
        return state != null && List.of("open", "opened", "draft").contains(state.toLowerCase(Locale.ROOT));
    }

    /** Whether a pull request in that state was merged. */
    public static boolean isMerged(String state) {
        return "merged".equalsIgnoreCase(state);
    }

    /** Whether a pull request in that state was closed without being merged. */
    public static boolean isClosed(String state) {
        return "closed".equalsIgnoreCase(state);
    }

    /**
     * The APIs the repository's pull requests may be in, the most likely first; one when the name of its server tells
     * which kind of server it is and where its API is. Never reaches the server.
     *
     * @throws UnsupportedGitServerException when the repository is not on a Git server KDPS opens pull requests on:
     *                                       Bitbucket, Azure DevOps, AWS CodeCommit, a local repository, or a URL
     *                                       that names no repository
     */
    public static List<Api> candidates(String repoUrl) {
        URI uri = repositoryUri(repoUrl);
        if (uri == null) {
            throw new UnsupportedGitServerException("KDPS opens pull requests on GitHub, GitLab and Gitea servers"
                    + " only, through their API, and the repository URL does not name one. Use a repository on such a"
                    + " server, or Direct commit.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String unsupported = unsupportedServer(host);
        if (unsupported != null) {
            throw new UnsupportedGitServerException("KDPS opens pull requests on GitHub, GitLab and Gitea only, and "
                    + host + " is " + unsupported + ". Use Direct commit for this repository, or a repository on one"
                    + " of those servers.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : uri.getPath().split("/")) {
            if (!segment.isEmpty()) segments.add(segment);
        }
        if (segments.size() < 2) {
            throw new UnsupportedGitServerException("The repository URL does not name a repository on " + host
                    + " (<owner>/<repository>), so KDPS cannot open a pull request in it.");
        }
        String root = webRoot(uri);
        int n = segments.size();
        // GitHub and Gitea name a repository by its owner and its name: what comes before is the path the server is
        // reached at.
        String context = n == 2 ? "" : "/" + String.join("/", segments.subList(0, n - 2));
        String ownerAndName = segments.get(n - 2) + "/" + segments.get(n - 1);
        if ("github.com".equals(host) || "www.github.com".equals(host)) {
            return List.of(new Api(Provider.GITHUB, "https://api.github.com", ownerAndName));
        }
        Api github = new Api(Provider.GITHUB, root + context + "/api/v3", ownerAndName);
        Api gitea = new Api(Provider.GITEA, root + context + "/api/v1", ownerAndName);
        if (host.contains("github")) {
            return List.of(github);
        }
        if (host.contains("gitea") || host.contains("forgejo") || host.contains("codeberg")) {
            return List.of(gitea);
        }
        // A GitLab project may sit in nested groups, which the URL does not tell apart from the path GitLab is served
        // at: each split is a candidate, the one without a context path first.
        List<Api> gitlab = new ArrayList<>();
        for (int c = 0; c <= n - 2; c++) {
            String gitlabContext = c == 0 ? "" : "/" + String.join("/", segments.subList(0, c));
            gitlab.add(new Api(Provider.GITLAB, root + gitlabContext + "/api/v4",
                    String.join("/", segments.subList(c, n))));
        }
        if (host.contains("gitlab")) {
            return gitlab;
        }
        List<Api> any = new ArrayList<>(gitlab);
        any.add(gitea);
        any.add(github);
        return any;
    }

    /** What a Git server KDPS cannot open pull requests on is, by its host; {@code null} for any other host. */
    private static String unsupportedServer(String host) {
        if (host.contains("bitbucket")) return "Bitbucket";
        if ("dev.azure.com".equals(host) || host.endsWith(".visualstudio.com")) return "Azure DevOps";
        if (host.startsWith("git-codecommit.")) return "AWS CodeCommit";
        return null;
    }

    /**
     * The scheme, host and port the web pages and the API of the server are at: those of the repository URL over
     * HTTP(S); HTTPS on the default port for a repository reached over SSH, whose port is the SSH server's.
     */
    private static String webRoot(URI uri) {
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean web = "http".equals(scheme) || "https".equals(scheme);
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        return (web ? scheme : "https") + "://" + host + (web && uri.getPort() != -1 ? ":" + uri.getPort() : "");
    }

    /**
     * The API the repository's pull requests are in: the only candidate ({@link #candidates}), else the first that
     * knows the repository, asked with the access token, which only ever goes to the host of the repository URL.
     *
     * @throws UnsupportedGitServerException when no candidate knows the repository, with what each one answered
     * @throws IOException                   when the server cannot be reached
     */
    public Api api(String repoUrl, String token) throws IOException, InterruptedException {
        List<Api> candidates = candidates(repoUrl);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String key = String.valueOf(repositoryUri(repoUrl));
        Api known = found.get(key);
        if (known != null) {
            return known;
        }
        List<String> answers = new ArrayList<>();
        for (Api candidate : candidates) {
            HttpResponse<String> response = send(request(candidate, candidate.repositoryUrl(), token).GET().build(),
                    "find-api");
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                found.put(key, candidate);
                LOG.info("Pull requests of {} go through the {} API at {}", key, candidate.provider().label(),
                        candidate.baseUrl());
                return candidate;
            }
            answers.add(candidate.provider().label() + " " + candidate.baseUrl() + ": HTTP " + response.statusCode());
        }
        throw new UnsupportedGitServerException("KDPS found no GitHub, GitLab or Gitea API that knows the repository "
                + key + " (" + String.join("; ", answers) + "). It opens pull requests on those Git servers only: "
                + "check the repository URL and that the access token may read the repository, or use Direct commit.");
    }

    /**
     * Opens a pull request (GitLab: a merge request) of a branch into another.
     *
     * @param base  the branch it is merged into
     * @param head  the branch it proposes
     * @param title its title
     * @param token the access token, which may open pull requests in the repository
     * @return the pull request
     * @throws UnsupportedGitServerException when the repository is not on a Git server KDPS opens pull requests on
     * @throws IOException                   when the server did not open it, with the server's reason
     */
    public PullRequestInfo open(String repoUrl, String base, String head, String title, String token)
            throws IOException, InterruptedException {
        Api api = api(repoUrl, token);
        HttpRequest.Builder request = request(api, api.pullRequestsUrl(), token);
        if (api.provider() == Provider.GITLAB) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("title=" + encode(title)
                            + "&source_branch=" + encode(head) + "&target_branch=" + encode(base)));
        } else {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("title", title);
            body.put("head", head);
            body.put("base", base);
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        }
        HttpResponse<String> response = send(request.build(), "open-pull-request");
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            PullRequestInfo info = parse(api, response.body(), null);
            if (info != null && info.id != null) {
                return info;
            }
        }
        throw new IOException(api.provider().label() + " did not open the pull request of branch " + head + " into "
                + base + " of " + api.repository() + " (HTTP " + response.statusCode() + ")"
                + reason(response.body()));
    }

    /**
     * Reads a pull request (GitLab: a merge request) as the Git server holds it now.
     *
     * @param number its number (GitLab: its iid)
     * @throws UnsupportedGitServerException when the repository is not on a Git server KDPS opens pull requests on
     * @throws IOException                   when the server did not return it
     */
    public PullRequestInfo get(String repoUrl, String number, String token) throws IOException, InterruptedException {
        checkNumber(number);
        Api api = api(repoUrl, token);
        HttpResponse<String> response = send(request(api, api.pullRequestsUrl() + "/" + number, token).GET().build(),
                "read-pull-request");
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            PullRequestInfo info = parse(api, response.body(), number);
            if (info != null) {
                return info;
            }
        }
        throw new IOException(api.provider().label() + " did not return pull request " + number + " of "
                + api.repository() + " (HTTP " + response.statusCode() + ")" + reason(response.body()));
    }

    /**
     * Closes a pull request (GitLab: a merge request) without merging it.
     *
     * @param number its number (GitLab: its iid)
     * @return the pull request as the server holds it once closed
     * @throws UnsupportedGitServerException when the repository is not on a Git server KDPS opens pull requests on
     * @throws IOException                   when the server did not close it, with the server's reason
     */
    public PullRequestInfo close(String repoUrl, String number, String token) throws IOException, InterruptedException {
        checkNumber(number);
        Api api = api(repoUrl, token);
        HttpRequest.Builder request = request(api, api.pullRequestsUrl() + "/" + number, token);
        if (api.provider() == Provider.GITLAB) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .PUT(HttpRequest.BodyPublishers.ofString("state_event=close"));
        } else {
            request.header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(Map.of("state", "closed"))));
        }
        HttpResponse<String> response = send(request.build(), "close-pull-request");
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            PullRequestInfo info = parse(api, response.body(), number);
            if (info != null) {
                return info;
            }
        }
        throw new IOException(api.provider().label() + " did not close pull request " + number + " of "
                + api.repository() + " (HTTP " + response.statusCode() + ")" + reason(response.body()));
    }

    /** Refuses anything but a pull request number, which goes into the URL of the API. */
    private static void checkNumber(String number) {
        if (number == null || !number.matches("\\d+")) {
            throw new IllegalArgumentException("Not a pull request number: " + DeployRequestValidator.shown(
                    String.valueOf(number)));
        }
    }

    private static PullRequestInfo parse(Api api, String body, String number) {
        try {
            return api.provider() == Provider.GITLAB ? PullRequestParser.parseGitlab(body, number)
                    : PullRequestParser.parseGithub(body, number);
        } catch (JsonSyntaxException | ClassCastException e) {
            LOG.warn("{} answered with something other than a pull request: {}", api.provider().label(), e.toString());
            return null;
        }
    }

    /** A request to the API, with the access token as the provider expects it. */
    private static HttpRequest.Builder request(Api api, String url, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT);
        switch (api.provider()) {
            case GITHUB -> request.header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json");
            case GITLAB -> request.header("PRIVATE-TOKEN", token);
            case GITEA -> request.header("Authorization", "token " + token).header("Accept", "application/json");
        }
        return request;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** What the server said of a refused request, as ": <message>", or nothing. */
    static String reason(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        try {
            Object parsed = GSON.fromJson(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                collectMessages(map.get("message"), parts);
                collectMessages(map.get("errors"), parts);
                collectMessages(map.get("error"), parts);
            }
        } catch (JsonSyntaxException notJson) {
            // Shown as it came, shortened below.
        }
        String text = parts.isEmpty() ? body.strip() : String.join("; ", parts);
        return ": " + (text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }

    private static void collectMessages(Object node, List<String> into) {
        if (node instanceof String text && !text.isBlank()) {
            into.add(text);
        } else if (node instanceof List<?> list) {
            list.forEach(item -> collectMessages(item, into));
        } else if (node instanceof Map<?, ?> map) {
            collectMessages(map.get("message"), into);
        }
    }

    /** Sends a request, again after a moment when the server failed (5xx) or could not be reached. */
    private HttpResponse<String> send(HttpRequest request, String label) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                    LOG.warn("Git server API {} returned {} for {}, trying again", request.uri().getHost(),
                            response.statusCode(), label);
                    Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
                    continue;
                }
                return response;
            } catch (IOException io) {
                last = io;
                LOG.warn("Git server API {} could not be reached for {} (attempt {}/{}): {}", request.uri().getHost(),
                        label, attempt, MAX_ATTEMPTS, io.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
                }
            }
        }
        throw new IOException("The API of the Git server at " + request.uri().getHost() + " could not be reached: "
                + (last == null ? "no answer" : last.getMessage()), last);
    }

    /**
     * The repository as the pull request calls read it (the web URL, without the {@code .git} suffix), whose host gets
     * the access token; {@code null} when that is not the host the release's Git credential is bound to
     * ({@link GitCredentialService#hostOf}), or for a local repository.
     */
    static URI repositoryUri(String repoUrl) {
        String host = GitCredentialService.hostOf(repoUrl);
        if (host == null || host.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(normalizeRepoUrl(repoUrl));
            return host.equalsIgnoreCase(uri.getHost()) && uri.getPath() != null ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeRepoUrl(String repoUrl) {
        String trimmed = repoUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Only the suffix: a host may hold ".git" too (code.gitlab.example), and must not change.
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        if (trimmed.startsWith("git@")) {
            trimmed = "https://" + trimmed.substring(4).replaceFirst(":", "/");
        }
        return trimmed;
    }

    /** Reads the pull requests the Git servers return. */
    static final class PullRequestParser {
        private PullRequestParser() {
        }

        /** A GitHub or Gitea pull request: both name its page html_url and say merged next to its state. */
        static PullRequestInfo parseGithub(String body, String defaultId) {
            Map<?, ?> parsed = object(body);
            if (parsed == null) {
                return null;
            }
            Object state = parsed.get("state");
            String stateStr = state != null ? state.toString() : null;
            if (Boolean.TRUE.equals(parsed.get("merged"))) {
                stateStr = "merged";
            } else if (Boolean.TRUE.equals(parsed.get("draft")) && "open".equalsIgnoreCase(stateStr)) {
                stateStr = "draft";
            }
            String message = Optional.ofNullable(parsed.get("mergeable_state")).map(Object::toString).orElse(null);
            Object url = parsed.get("html_url");
            return new PullRequestInfo(url != null ? url.toString() : null, formatId(parsed.get("number"), defaultId),
                    stateStr, message);
        }

        /** A GitLab merge request. */
        static PullRequestInfo parseGitlab(String body, String defaultId) {
            Map<?, ?> parsed = object(body);
            if (parsed == null) {
                return null;
            }
            Object state = parsed.get("state");
            String stateStr = state != null ? state.toString() : null;
            if (parsed.get("merged_at") != null) {
                stateStr = "merged";
            }
            String message = Optional.ofNullable(parsed.get("detailed_merge_status")).map(Object::toString).orElse(null);
            Object url = parsed.get("web_url");
            return new PullRequestInfo(url != null ? url.toString() : null, formatId(parsed.get("iid"), defaultId),
                    stateStr, message);
        }

        private static Map<?, ?> object(String body) {
            if (body == null) {
                return null;
            }
            return GSON.fromJson(body, Object.class) instanceof Map<?, ?> map ? map : null;
        }

        private static String formatId(Object value, String fallback) {
            if (value instanceof Number number) {
                return String.valueOf(number.longValue());
            }
            return value != null ? value.toString() : fallback;
        }
    }
}
