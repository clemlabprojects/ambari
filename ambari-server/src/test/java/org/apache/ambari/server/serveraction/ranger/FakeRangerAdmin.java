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

package org.apache.ambari.server.serveraction.ranger;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Just enough of the Ranger Admin public REST API, in memory, to run {@link EnsureRangerPolicyServerAction}
 * against: policies by id and by name, the per-service listing (paged with {@code startIndex}/{@code pageSize}
 * like Ranger), Ranger's refusal of a second policy over the same resources (error 3010, naming the policy that
 * owns them) and the user lookup. Every request is recorded.
 */
final class FakeRangerAdmin implements AutoCloseable {

  final Map<Long, JsonObject> policies = new LinkedHashMap<>();
  /** "METHOD path" of every request received, oldest first. */
  final List<String> requests = new ArrayList<>();
  private long nextId = 1;
  private final HttpServer server;

  FakeRangerAdmin() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  String url() {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** Stores an access policy over {@code resources} and returns its id. */
  synchronized long add(String service, String name, Map<String, List<String>> resources, String... items) {
    JsonObject p = new JsonObject();
    p.addProperty("service", service);
    p.addProperty("name", name);
    p.addProperty("policyType", 0);
    JsonObject res = new JsonObject();
    for (Map.Entry<String, List<String>> e : resources.entrySet()) {
      JsonObject r = new JsonObject();
      JsonArray values = new JsonArray();
      e.getValue().forEach(values::add);
      r.add("values", values);
      r.addProperty("isExcludes", false);
      r.addProperty("isRecursive", false);
      res.add(e.getKey(), r);
    }
    p.add("resources", res);
    JsonArray policyItems = new JsonArray();
    for (String item : items) {
      policyItems.add(JsonParser.parseString(item));
    }
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
  static String item(List<String> users, String... accesses) {
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

  synchronized JsonObject byName(String service, String name) {
    for (JsonObject p : policies.values()) {
      if (service.equals(p.get("service").getAsString()) && name.equals(p.get("name").getAsString())) {
        return p;
      }
    }
    return null;
  }

  /** The users of every allow item of the policy that grants {@code access}, one list per item. */
  static List<List<String>> grantees(JsonObject policy, String access) {
    List<List<String>> out = new ArrayList<>();
    for (JsonElement e : policy.getAsJsonArray("policyItems")) {
      JsonObject it = e.getAsJsonObject();
      boolean grants = false;
      for (JsonElement a : it.getAsJsonArray("accesses")) {
        if (access.equals(a.getAsJsonObject().get("type").getAsString())) {
          grants = true;
        }
      }
      if (grants) {
        List<String> users = new ArrayList<>();
        it.getAsJsonArray("users").forEach(u -> users.add(u.getAsString()));
        out.add(users);
      }
    }
    return out;
  }

  /** The values of resource {@code name} of the policy. */
  static List<String> values(JsonObject policy, String name) {
    List<String> out = new ArrayList<>();
    policy.getAsJsonObject("resources").getAsJsonObject(name).getAsJsonArray("values")
        .forEach(v -> out.add(v.getAsString()));
    return out;
  }

  /** How many requests changed something (anything but a GET). */
  long writes() {
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
    if (path.startsWith(servicePrefix) && path.contains("/policy")) {
      String rest = path.substring(servicePrefix.length());
      String service = rest.substring(0, rest.indexOf("/policy"));
      String name = rest.substring(rest.indexOf("/policy") + "/policy".length());
      if (name.startsWith("/")) {
        name = name.substring(1);
      }
      if (name.isEmpty()) {
        List<JsonObject> all = new ArrayList<>();
        for (JsonObject p : policies.values()) {
          if (service.equals(p.get("service").getAsString())) {
            all.add(p);
          }
        }
        int start = Integer.parseInt(query.getOrDefault("startIndex", "0"));
        int size = Integer.parseInt(query.getOrDefault("pageSize", "200"));
        JsonArray page = new JsonArray();
        for (int i = start; i < Math.min(all.size(), start + size); i++) {
          page.add(all.get(i).deepCopy());
        }
        answer(ex, 200, page.toString());
        return;
      }
      JsonObject found = byName(service, name);
      answer(ex, found == null ? 404 : 200, found == null ? "{\"msgDesc\":\"no policy\"}" : found.toString());
      return;
    }
    if (path.equals(policyPrefix) && "POST".equals(method)) {
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
      } else if ("GET".equals(method)) {
        answer(ex, 200, p.toString());
      } else if ("PUT".equals(method)) {
        JsonObject updated = JsonParser.parseString(body).getAsJsonObject();
        updated.addProperty("id", id);
        policies.put(id, updated);
        answer(ex, 200, updated.toString());
      } else if ("DELETE".equals(method)) {
        policies.remove(id);
        answer(ex, 204, null);
      } else {
        answer(ex, 405, "{}");
      }
      return;
    }
    if (path.equals("/service/xusers/users")) {
      answer(ex, 200, "{\"vXUsers\":[{\"id\":7,\"name\":\"" + query.get("name") + "\"}]}");
      return;
    }
    answer(ex, 404, "{\"msgDesc\":\"unknown path\"}");
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
