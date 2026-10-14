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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory stand-in for the part of Ambari's REST API that {@link AmbariActionClient} uses to read
 * and write cluster configs, with Ambari's password handling: a property marked {@code password}
 * is masked as {@code SECRET:<type>:<version>:<key>} on every read, and a PUT resolves such a
 * reference back to the stored value only when it marks the key as a password too (otherwise the
 * reference is stored literally, as Ambari does).
 *
 * <p>Also the component side of a config change: writing a type marks the components that read it
 * ({@link #readBy}) as having stale configs, and a restart request that completes clears that.
 */
public final class FakeAmbariConfigs implements URLStreamProvider {

    /** One stored config version. */
    public static final class Stored {
        public int version;
        public String tag;
        public final Map<String, String> properties = new LinkedHashMap<>();
        public final Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
    }

    private final Map<String, Stored> desired = new LinkedHashMap<>();
    /** Bodies of every PUT received, oldest first. */
    public final List<String> puts = new ArrayList<>();

    /** Hosts of each {@code SERVICE/COMPONENT}; one not listed answers 404, like a service the cluster lacks. */
    public final Map<String, List<String>> componentHosts = new LinkedHashMap<>();
    /**
     * The component that reads each config type: writing the type marks it stale. Ranger TagSync's are
     * the stack's (RANGER metainfo.xml): Ambari keeps one restart flag per component, whichever of
     * them changed.
     */
    public final Map<String, String> readBy = new LinkedHashMap<>(Map.of(
            "ranger-tagsync-site", "RANGER_TAGSYNC",
            "tagsync-application-properties", "RANGER_TAGSYNC",
            "ranger-tagsync-policymgr-ssl", "RANGER_TAGSYNC",
            "atlas-tagsync-ssl", "RANGER_TAGSYNC",
            "tagsync-log4j", "RANGER_TAGSYNC",
            "tagsync-logback", "RANGER_TAGSYNC"));
    /** Components Ambari reports with stale configs ("Restart required"). */
    public final Set<String> staleComponents = new LinkedHashSet<>();
    /** How the next restart requests end, in order; {@code COMPLETED} once empty. */
    public final Deque<String> restartOutcomes = new ArrayDeque<>();
    /** The component of every restart request received, oldest first. */
    public final List<String> restarts = new ArrayList<>();
    private final Map<Integer, String> requestStatus = new LinkedHashMap<>();

    /** Seeds the desired version of {@code type}; returns it so the test can add attributes. */
    public Stored seed(String type, Map<String, String> properties) {
        Stored s = new Stored();
        s.version = 1;
        s.tag = "version1";
        s.properties.putAll(properties);
        desired.put(type, s);
        return s;
    }

    /** The desired version of {@code type} as stored, secrets in clear. */
    public Stored stored(String type) {
        return desired.get(type);
    }

    /** A client of this fake for cluster {@code c1}. */
    public AmbariActionClient client() {
        ViewContext ctx = Mockito.mock(ViewContext.class);
        Mockito.when(ctx.getURLStreamProvider()).thenReturn(this);
        return new AmbariActionClient(ctx, "http://ambari:8080/api/v1", "c1", Map.of());
    }

    @Override
    public InputStream readFrom(String url, String method, String body, Map<String, String> headers) throws IOException {
        URI uri = URI.create(url);
        Map<String, String> query = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if ("PUT".equals(method)) {
            puts.add(body);
            applyPut(JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("Clusters").getAsJsonObject("desired_config"));
            return json("{}");
        }
        String path = uri.getPath();
        if ("POST".equals(method) && path.endsWith("/requests")) {
            return restart(JsonParser.parseString(body).getAsJsonObject());
        }
        Matcher request = Pattern.compile(".*/requests/(\\d+)$").matcher(path);
        if (request.matches()) {
            return json("{\"Requests\":{\"request_status\":\"" + requestStatus.get(Integer.parseInt(request.group(1))) + "\"}}");
        }
        Matcher component = Pattern.compile(".*/services/([^/]+)/components/([^/]+)$").matcher(path);
        if (component.matches()) {
            List<String> hosts = componentHosts.get(component.group(1) + "/" + component.group(2));
            if (hosts == null) throw new IOException("HTTP 404 for " + url);
            JsonArray items = new JsonArray();
            hosts.forEach(h -> items.add(JsonParser.parseString("{\"HostRoles\":{\"host_name\":\"" + h + "\"}}")));
            JsonObject root = new JsonObject();
            root.add("host_components", items);
            return json(root.toString());
        }
        if (path.endsWith("/host_components")) {
            String name = query.get("HostRoles/component_name");
            JsonArray items = new JsonArray();
            if (staleComponents.contains(name)) {
                items.add(JsonParser.parseString("{\"HostRoles\":{\"component_name\":\"" + name + "\",\"stale_configs\":true}}"));
            }
            JsonObject root = new JsonObject();
            root.add("items", items);
            return json(root.toString());
        }
        if (uri.getPath().endsWith("/configurations")) {
            Stored s = desired.get(query.get("type"));
            JsonObject item = new JsonObject();
            item.addProperty("type", query.get("type"));
            item.addProperty("tag", s.tag);
            item.addProperty("version", s.version);
            JsonObject props = new JsonObject();
            Map<String, String> passwords = s.attributes.getOrDefault("password", Map.of());
            s.properties.forEach((k, v) -> props.addProperty(k,
                    "true".equals(passwords.get(k)) ? "SECRET:" + query.get("type") + ":" + s.version + ":" + k : v));
            item.add("properties", props);
            JsonObject attrs = new JsonObject();
            s.attributes.forEach((name, perKey) -> {
                JsonObject o = new JsonObject();
                perKey.forEach(o::addProperty);
                attrs.add(name, o);
            });
            if (attrs.size() > 0) item.add("properties_attributes", attrs);
            return json("{\"items\":[" + item + "]}");
        }
        String type = query.getOrDefault("fields", "").replace("Clusters/desired_configs/", "");
        JsonObject configs = new JsonObject();
        if (desired.containsKey(type)) {
            JsonObject tag = new JsonObject();
            tag.addProperty("tag", desired.get(type).tag);
            configs.add(type, tag);
        }
        JsonObject clusters = new JsonObject();
        clusters.add("desired_configs", configs);
        JsonObject root = new JsonObject();
        root.add("Clusters", clusters);
        return json(root.toString());
    }

    private void applyPut(JsonObject desiredConfig) {
        String type = desiredConfig.get("type").getAsString();
        Stored previous = desired.get(type);
        Stored next = new Stored();
        next.version = previous == null ? 1 : previous.version + 1;
        next.tag = desiredConfig.get("tag").getAsString();
        if (desiredConfig.has("properties_attributes")) {
            for (Map.Entry<String, JsonElement> byName : desiredConfig.getAsJsonObject("properties_attributes").entrySet()) {
                Map<String, String> perKey = new LinkedHashMap<>();
                byName.getValue().getAsJsonObject().entrySet().forEach(e -> perKey.put(e.getKey(), e.getValue().getAsString()));
                next.attributes.put(byName.getKey(), perKey);
            }
        }
        Map<String, String> passwords = next.attributes.getOrDefault("password", Map.of());
        for (Map.Entry<String, JsonElement> e : desiredConfig.getAsJsonObject("properties").entrySet()) {
            String value = e.getValue().getAsString();
            if (value.startsWith("SECRET:") && "true".equals(passwords.get(e.getKey())) && previous != null) {
                value = previous.properties.get(e.getKey());
            }
            next.properties.put(e.getKey(), value);
        }
        desired.put(type, next);
        if (readBy.containsKey(type)) staleComponents.add(readBy.get(type));
    }

    /** A RESTART request: recorded, ended as {@link #restartOutcomes} says; one that completes clears the stale flag. */
    private InputStream restart(JsonObject body) {
        String component = body.getAsJsonArray("Requests/resource_filters").get(0).getAsJsonObject()
                .get("component_name").getAsString();
        restarts.add(component);
        int id = restarts.size();
        String outcome = restartOutcomes.isEmpty() ? "COMPLETED" : restartOutcomes.poll();
        requestStatus.put(id, outcome);
        if ("COMPLETED".equals(outcome)) staleComponents.remove(component);
        return json("{\"Requests\":{\"id\":" + id + ",\"status\":\"Accepted\"}}");
    }

    private static InputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream readFrom(String url, String method, InputStream body, Map<String, String> headers) throws IOException {
        return readFrom(url, method, body == null ? null : new String(body.readAllBytes(), StandardCharsets.UTF_8), headers);
    }

    @Override
    public InputStream readAs(String url, String method, String body, Map<String, String> headers, String user) throws IOException {
        return readFrom(url, method, body, headers);
    }

    @Override
    public InputStream readAs(String url, String method, InputStream body, Map<String, String> headers, String user) throws IOException {
        return readFrom(url, method, body, headers);
    }

    @Override
    public InputStream readAsCurrent(String url, String method, String body, Map<String, String> headers) throws IOException {
        return readFrom(url, method, body, headers);
    }

    @Override
    public InputStream readAsCurrent(String url, String method, InputStream body, Map<String, String> headers) throws IOException {
        return readFrom(url, method, body, headers);
    }
}
