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

package org.apache.ambari.view.k8s.service.om;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Just enough of the Ranger Admin public REST API, in memory, to check what the view's Ranger
 * helpers leave behind: policies by id and by name, the per-service listing (paged with
 * {@code startIndex}/{@code pageSize} like Ranger), and Ranger's refusal of a second policy over
 * the same resources (error 3010, naming the policy that owns them), plus service repositories by
 * name. Every request is recorded.
 *
 * <p>The user lookup answers that every user exists, unless {@link #userDirectory} is set (adding a
 * user sets it): then {@link #users} is a user directory that the view's Ranger user helpers read and
 * write, matching names partially on lookup like Ranger does and masking passwords in its answers.
 */
public final class FakeRangerAdmin implements AutoCloseable {

    public final Map<Long, JsonObject> policies = new LinkedHashMap<>();
    /** Users by name, with the password last set (Ranger would keep a hash). */
    public final Map<String, JsonObject> users = new LinkedHashMap<>();
    /** Whether the user lookup reads {@link #users} rather than answering that every user exists. */
    public volatile boolean userDirectory;
    /** Service repositories by name, with their id. */
    public final Map<String, Long> services = new LinkedHashMap<>();
    /** "METHOD path" of every request received, oldest first. */
    public final List<String> requests = new ArrayList<>();
    /** When set, listing a service's policies answers HTTP 500, as a Ranger in trouble would. */
    public volatile boolean failListing;
    /** When set, creating a policy answers HTTP 403, as Ranger does to an admin account without that right. */
    public volatile boolean refusePolicyCreate;
    private long nextId = 1;
    private final HttpServer server;

    public FakeRangerAdmin() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Stores an access policy over {@code resources} and returns its id. */
    public synchronized long add(String service, String name, Map<String, List<String>> resources, String... items) {
        JsonObject p = new JsonObject();
        p.addProperty("service", service);
        p.addProperty("name", name);
        p.addProperty("policyType", 0);
        JsonObject res = new JsonObject();
        resources.forEach((k, v) -> {
            JsonObject r = new JsonObject();
            JsonArray values = new JsonArray();
            v.forEach(values::add);
            r.add("values", values);
            r.addProperty("isExcludes", false);
            r.addProperty("isRecursive", false);
            res.add(k, r);
        });
        p.add("resources", res);
        JsonArray policyItems = new JsonArray();
        for (String item : items) policyItems.add(JsonParser.parseString(item));
        p.add("policyItems", policyItems);
        p.add("denyPolicyItems", new JsonArray());
        p.add("allowExceptions", new JsonArray());
        p.add("denyExceptions", new JsonArray());
        p.add("dataMaskPolicyItems", new JsonArray());
        p.add("rowFilterPolicyItems", new JsonArray());
        long id = nextId++;
        p.addProperty("id", id);
        policies.put(id, p);
        return id;
    }

    /** An allow item granting {@code accesses} to {@code users}, as JSON. */
    public static String item(List<String> users, String... accesses) {
        JsonObject it = new JsonObject();
        JsonArray u = new JsonArray();
        users.forEach(u::add);
        it.add("users", u);
        it.add("groups", new JsonArray());
        it.add("roles", new JsonArray());
        JsonArray a = new JsonArray();
        for (String type : accesses) {
            JsonObject access = new JsonObject();
            access.addProperty("type", type);
            access.addProperty("isAllowed", true);
            a.add(access);
        }
        it.add("accesses", a);
        it.addProperty("delegateAdmin", false);
        return it.toString();
    }

    public synchronized JsonObject byName(String service, String name) {
        for (JsonObject p : policies.values()) {
            if (service.equals(p.get("service").getAsString()) && name.equals(p.get("name").getAsString())) return p;
        }
        return null;
    }

    /** The users of every allow item of the policy that grants {@code access}, one list per item. */
    public static List<List<String>> grantees(JsonObject policy, String access) {
        List<List<String>> out = new ArrayList<>();
        for (JsonElement e : policy.getAsJsonArray("policyItems")) {
            JsonObject it = e.getAsJsonObject();
            boolean grants = false;
            for (JsonElement a : it.getAsJsonArray("accesses")) {
                if (access.equals(a.getAsJsonObject().get("type").getAsString())) grants = true;
            }
            if (!grants) continue;
            List<String> users = new ArrayList<>();
            it.getAsJsonArray("users").forEach(u -> users.add(u.getAsString()));
            out.add(users);
        }
        return out;
    }

    /** The values of resource {@code name} of the policy. */
    public static List<String> values(JsonObject policy, String name) {
        List<String> out = new ArrayList<>();
        policy.getAsJsonObject("resources").getAsJsonObject(name).getAsJsonArray("values")
                .forEach(v -> out.add(v.getAsString()));
        return out;
    }

    /** Stores an enabled user with {@code roles}; {@code userSource} 0 is an internal user, 1 one synced from a directory. */
    public synchronized JsonObject addUser(String name, int userSource, String password, String... roles) {
        userDirectory = true;
        JsonObject u = new JsonObject();
        u.addProperty("id", nextId++);
        u.addProperty("name", name);
        u.addProperty("status", 1);
        u.addProperty("userSource", userSource);
        JsonArray roleList = new JsonArray();
        for (String role : roles) roleList.add(role);
        u.add("userRoleList", roleList);
        u.addProperty("password", password);
        users.put(name, u);
        return u;
    }

    /** The roles of a stored user. */
    public static List<String> roles(JsonObject user) {
        List<String> out = new ArrayList<>();
        user.getAsJsonArray("userRoleList").forEach(r -> out.add(r.getAsString()));
        return out;
    }

    /** How many requests changed something (anything but a GET). */
    public long writes() {
        return requests.stream().filter(r -> !r.startsWith("GET ")).count();
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(method + " " + path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> query = new LinkedHashMap<>();
        String rawQuery = ex.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }

        String servicePrefix = "/service/public/v2/api/service/";
        String policyPrefix = "/service/public/v2/api/policy";
        if (path.startsWith(servicePrefix + "name/")) {
            String service = path.substring((servicePrefix + "name/").length());
            Long id = services.get(service);
            if (id == null) answer(ex, 404, "{\"msgDesc\":\"no service\"}");
            else answer(ex, 200, "{\"id\":" + id + ",\"name\":\"" + service + "\"}");
            return;
        }
        if (path.equals("/service/public/v2/api/service") && "POST".equals(method)) {
            String service = JsonParser.parseString(body).getAsJsonObject().get("name").getAsString();
            long id = nextId++;
            services.put(service, id);
            answer(ex, 200, "{\"id\":" + id + ",\"name\":\"" + service + "\"}");
            return;
        }
        if (path.startsWith(servicePrefix) && "DELETE".equals(method) && !path.contains("/policy")) {
            long id = Long.parseLong(path.substring(servicePrefix.length()));
            String service = services.entrySet().stream().filter(e -> e.getValue() == id).map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (service == null) {
                answer(ex, 404, "{\"msgDesc\":\"no service\"}");
                return;
            }
            services.remove(service);
            policies.values().removeIf(p -> service.equals(p.get("service").getAsString()));
            answer(ex, 204, null);
            return;
        }
        if (path.startsWith(servicePrefix) && path.contains("/policy")) {
            String rest = path.substring(servicePrefix.length());
            String service = rest.substring(0, rest.indexOf("/policy"));
            String name = rest.substring(rest.indexOf("/policy") + "/policy".length());
            if (name.startsWith("/")) name = name.substring(1);
            if (name.isEmpty()) {
                if (failListing) {
                    answer(ex, 500, "{\"msgDesc\":\"database unavailable\"}");
                    return;
                }
                List<JsonObject> all = new ArrayList<>();
                for (JsonObject p : policies.values()) {
                    if (service.equals(p.get("service").getAsString())) all.add(p);
                }
                int start = Integer.parseInt(query.getOrDefault("startIndex", "0"));
                int size = Integer.parseInt(query.getOrDefault("pageSize", "200"));
                JsonArray page = new JsonArray();
                for (int i = start; i < Math.min(all.size(), start + size); i++) page.add(all.get(i).deepCopy());
                answer(ex, 200, page.toString());
                return;
            }
            JsonObject found = byName(service, name);
            if (found == null) answer(ex, 404, "{\"msgDesc\":\"no policy\"}");
            else answer(ex, 200, found.toString());
            return;
        }
        if (path.equals(policyPrefix) && "POST".equals(method)) {
            if (refusePolicyCreate) {
                answer(ex, 403, "{\"statusCode\":1,\"msgDesc\":\"User doesn't have permission to create the policy\"}");
                return;
            }
            JsonObject p = JsonParser.parseString(body).getAsJsonObject();
            String service = p.get("service").getAsString();
            for (JsonObject other : policies.values()) {
                if (service.equals(other.get("service").getAsString()) && signature(other).equals(signature(p))) {
                    answer(ex, 400, "{\"statusCode\":1,\"msgDesc\":\"(0) Validation failure: error code[3010], "
                            + "reason[Another policy already exists for matching resource: policy-name=["
                            + other.get("name").getAsString() + "], service=[" + service + "]]\"}");
                    return;
                }
            }
            if (byName(service, p.get("name").getAsString()) != null) {
                answer(ex, 400, "{\"msgDesc\":\"duplicate policy name\"}");
                return;
            }
            long id = nextId++;
            p.addProperty("id", id);
            policies.put(id, p);
            answer(ex, 200, p.toString());
            return;
        }
        if (path.startsWith(policyPrefix + "/")) {
            long id = Long.parseLong(path.substring(policyPrefix.length() + 1));
            JsonObject p = policies.get(id);
            if (p == null) {
                answer(ex, 404, "{\"msgDesc\":\"no policy\"}");
                return;
            }
            switch (method) {
                case "GET" -> answer(ex, 200, p.toString());
                case "PUT" -> {
                    JsonObject updated = JsonParser.parseString(body).getAsJsonObject();
                    updated.addProperty("id", id);
                    policies.put(id, updated);
                    answer(ex, 200, updated.toString());
                }
                case "DELETE" -> {
                    policies.remove(id);
                    answer(ex, 204, null);
                }
                default -> answer(ex, 405, "{}");
            }
            return;
        }
        if (path.equals("/service/xusers/users") && !userDirectory) {
            answer(ex, 200, "{\"vXUsers\":[{\"id\":7,\"name\":\"" + query.get("name") + "\"}]}");
            return;
        }
        if (path.equals("/service/xusers/users")) {
            JsonArray found = new JsonArray();
            for (JsonObject u : users.values()) {
                if (u.get("name").getAsString().contains(query.getOrDefault("name", ""))) {
                    JsonObject masked = u.deepCopy();
                    masked.addProperty("password", "*****");
                    found.add(masked);
                }
            }
            JsonObject root = new JsonObject();
            root.add("vXUsers", found);
            answer(ex, 200, root.toString());
            return;
        }
        if (path.equals("/service/xusers/secure/users") && "POST".equals(method)) {
            JsonObject u = JsonParser.parseString(body).getAsJsonObject();
            u.addProperty("id", nextId++);
            users.put(u.get("name").getAsString(), u);
            answer(ex, 200, u.toString());
            return;
        }
        if (path.startsWith("/service/xusers/secure/users/") && "PUT".equals(method)) {
            long id = Long.parseLong(path.substring("/service/xusers/secure/users/".length()));
            JsonObject updated = JsonParser.parseString(body).getAsJsonObject();
            for (JsonObject u : users.values()) {
                if (u.get("id").getAsLong() == id) {
                    if (!updated.has("password") || "*****".equals(updated.get("password").getAsString())) {
                        updated.add("password", u.get("password"));
                    }
                    users.put(u.get("name").getAsString(), updated);
                    answer(ex, 200, updated.toString());
                    return;
                }
            }
            answer(ex, 404, "{\"msgDesc\":\"no user\"}");
            return;
        }
        answer(ex, 404, "{\"msgDesc\":\"unknown path " + path + "\"}");
    }

    /** What Ranger compares to refuse a second policy over the same resources. */
    private static String signature(JsonObject policy) {
        Map<String, TreeSet<String>> sig = new TreeMap<>();
        JsonObject res = policy.getAsJsonObject("resources");
        for (String k : res.keySet()) {
            TreeSet<String> values = new TreeSet<>();
            res.getAsJsonObject(k).getAsJsonArray("values").forEach(v -> values.add(v.getAsString()));
            sig.put(k, values);
        }
        return sig.toString();
    }

    private static void answer(HttpExchange ex, int code, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
