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

package org.apache.ambari.view.k8s.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.URLStreamProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Ambari server on a local port, answering the REST calls a deploy makes while it is planned: the cluster list, the
 * desired configurations of the cluster, its components, the operator's authorizations (all of them), plus whatever a
 * test adds ({@link #answer}).
 * The view reaches it the way it reaches Ambari, through the deploy's base URI and the view's
 * {@link URLStreamProvider} ({@link #streamProvider}), which, like Ambari's, hands an HTTP error back as its body.
 *
 * <p>A call it does not know is answered 404 and listed in {@link #unanswered}, so a test can tell a call the
 * code under test newly makes from a value the cluster lacks.</p>
 */
public final class FakeAmbariServer implements AutoCloseable {

    /** Every authorization a cluster operator may hold, all granted to the operator. */
    private static final List<String> AUTHORIZATIONS = List.of("CLUSTER.VIEW_STATUS_INFO", "CLUSTER.VIEW_CONFIGS",
            "CLUSTER.TOGGLE_KERBEROS", "SERVICE.MODIFY_CONFIGS", "SERVICE.RUN_CUSTOM_COMMAND", "AMBARI.MANAGE_VIEWS",
            "VIEW.USE");

    private final MockWebServer server = new MockWebServer();
    private final String cluster;
    private final Map<String, Map<String, String>> configs = new ConcurrentHashMap<>();
    private final Map<String, Function<RecordedRequest, MockResponse>> answers = new ConcurrentHashMap<>();
    private final Map<String, List<String>> componentHosts = new ConcurrentHashMap<>();
    /** Every request received, as {@code METHOD path?query}, oldest first. */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    /** The requests answered 404 because nothing here knows them. */
    public final List<String> unanswered = new CopyOnWriteArrayList<>();

    /**
     * Starts the server.
     *
     * @param cluster the name of the one cluster it manages
     */
    public FakeAmbariServer(String cluster) throws IOException {
        // MockWebServer logs every request it answers.
        Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);
        this.cluster = cluster;
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return FakeAmbariServer.this.dispatch(request);
            }
        });
        server.start();
    }

    /** The Ambari API base, {@code http://127.0.0.1:<port>/api/v1}, as a deploy's base URI carries it. */
    public String baseUri() {
        String url = server.url("/api/v1").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Sets the desired version of a config type; returns this server. */
    public FakeAmbariServer config(String type, Map<String, String> properties) {
        configs.put(type, new LinkedHashMap<>(properties));
        return this;
    }

    /** Removes a config type, as on a cluster without the service that owns it. */
    public FakeAmbariServer withoutConfig(String type) {
        configs.remove(type);
        return this;
    }

    /**
     * Installs a component of the cluster on the given hosts; a component not installed is answered 404, as Ambari
     * answers for a service the cluster lacks.
     *
     * @param serviceAndComponent {@code SERVICE/COMPONENT}, for instance {@code RANGER/RANGER_ADMIN}
     */
    public FakeAmbariServer component(String serviceAndComponent, String... hosts) {
        componentHosts.put(serviceAndComponent, List.of(hosts));
        return this;
    }

    /**
     * Answers the requests whose {@code METHOD path} starts with {@code methodAndPathPrefix} (for instance
     * {@code "POST /api/v1/clusters/c1/requests"}), before any built-in answer.
     */
    public FakeAmbariServer answer(String methodAndPathPrefix, Function<RecordedRequest, MockResponse> answer) {
        answers.put(methodAndPathPrefix, answer);
        return this;
    }

    /** A URL stream provider that reaches this server, and any other, over HTTP. */
    public URLStreamProvider streamProvider() {
        return new HttpStreamProvider();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    private MockResponse dispatch(RecordedRequest request) {
        String path = request.getPath();
        String described = request.getMethod() + " " + path;
        requests.add(described);
        for (Map.Entry<String, Function<RecordedRequest, MockResponse>> custom : answers.entrySet()) {
            if (described.startsWith(custom.getKey())) {
                return custom.getValue().apply(request);
            }
        }
        URI uri = URI.create(path);
        Map<String, String> query = query(uri.getRawQuery());
        String clusterPath = "/api/v1/clusters/" + cluster;
        if ("GET".equals(request.getMethod())) {
            if ("/api/v1/clusters".equals(uri.getPath())) {
                return json("{\"items\":[{\"Clusters\":{\"cluster_name\":\"" + cluster + "\"}}]}");
            }
            if (clusterPath.equals(uri.getPath()) && query.getOrDefault("fields", "").startsWith("Clusters/desired_configs/")) {
                String type = query.get("fields").substring("Clusters/desired_configs/".length());
                JsonObject desired = new JsonObject();
                if (configs.containsKey(type)) {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("tag", "version1");
                    desired.add(type, tag);
                }
                JsonObject clusters = new JsonObject();
                clusters.add("desired_configs", desired);
                JsonObject root = new JsonObject();
                root.add("Clusters", clusters);
                return json(root.toString());
            }
            if ((clusterPath + "/configurations").equals(uri.getPath()) && configs.containsKey(query.get("type"))) {
                JsonObject properties = new JsonObject();
                configs.get(query.get("type")).forEach(properties::addProperty);
                JsonObject item = new JsonObject();
                item.addProperty("type", query.get("type"));
                item.addProperty("tag", "version1");
                item.add("properties", properties);
                JsonArray items = new JsonArray();
                items.add(item);
                JsonObject root = new JsonObject();
                root.add("items", items);
                return json(root.toString());
            }
            Matcher component = Pattern.compile(Pattern.quote(clusterPath) + "/services/([^/]+)/components/([^/]+)")
                    .matcher(uri.getPath());
            if (component.matches()) {
                List<String> hosts = componentHosts.get(component.group(1) + "/" + component.group(2));
                if (hosts == null) {
                    return new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                            .setBody("{\"status\":404,\"message\":\"The requested resource doesn't exist\"}");
                }
                JsonArray items = new JsonArray();
                for (String host : hosts) {
                    JsonObject roles = new JsonObject();
                    roles.addProperty("host_name", host);
                    JsonObject item = new JsonObject();
                    item.add("HostRoles", roles);
                    items.add(item);
                }
                JsonObject root = new JsonObject();
                root.add("host_components", items);
                return json(root.toString());
            }
            if (uri.getPath().matches("/api/v1/users/[^/]+/authorizations")) {
                JsonArray items = new JsonArray();
                for (String id : AUTHORIZATIONS) {
                    JsonObject info = new JsonObject();
                    info.addProperty("authorization_id", id);
                    info.addProperty("resource_type", id.substring(0, id.indexOf('.')));
                    info.addProperty("cluster_name", id.startsWith("CLUSTER.") || id.startsWith("SERVICE.") ? cluster : null);
                    JsonObject item = new JsonObject();
                    item.add("AuthorizationInfo", info);
                    items.add(item);
                }
                JsonObject root = new JsonObject();
                root.add("items", items);
                return json(root.toString());
            }
        }
        unanswered.add(described);
        return new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("{\"status\":404,\"message\":\"Not served by the fake Ambari: " + described + "\"}");
    }

    private static Map<String, String> query(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    /**
     * Reads URLs over HTTP as Ambari's URL stream provider does for a view: the headers given are sent, and the body
     * of an HTTP error is handed back rather than thrown.
     */
    public static final class HttpStreamProvider implements URLStreamProvider {

        @Override
        public InputStream readFrom(String url, String method, String body, Map<String, String> headers)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method == null ? "GET" : method);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(15_000);
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && value != null) connection.setRequestProperty(name, value);
                });
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream answer = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] data = answer == null ? new byte[0] : answer.readAllBytes();
            if (answer != null) answer.close();
            return new ByteArrayInputStream(data);
        }

        @Override
        public InputStream readFrom(String url, String method, InputStream body, Map<String, String> headers)
                throws IOException {
            return readFrom(url, method, body == null ? null
                    : new String(body.readAllBytes(), StandardCharsets.UTF_8), headers);
        }

        @Override
        public InputStream readAs(String url, String method, String body, Map<String, String> headers, String user)
                throws IOException {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAs(String url, String method, InputStream body, Map<String, String> headers,
                                  String user) throws IOException {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, String body, Map<String, String> headers)
                throws IOException {
            return readFrom(url, method, body, headers);
        }

        @Override
        public InputStream readAsCurrent(String url, String method, InputStream body, Map<String, String> headers)
                throws IOException {
            return readFrom(url, method, body, headers);
        }
    }

}
