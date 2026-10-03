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
package org.apache.ambari.view.k8s.service.trino;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs SQL on a Trino coordinator <em>as a given end user</em>, from the KDPS view.
 *
 * <p>How: the view execs {@code curl} inside the coordinator pod against
 * {@code https://localhost:<port>/v1/statement} (Trino's REST protocol), authenticating with the
 * KDPS service user ({@code kdps.serviceUser} in the clemlab-trino chart) and setting
 * {@code X-Trino-User: <operator>}. Trino honours that header only when Ranger grants the service
 * user {@code impersonate}; every statement is then authorised by Ranger for the OPERATOR. Loopback
 * inside the pod means the TLS hop never leaves the container, so {@code -k} is acceptable there.
 * Exec needs {@code pods/exec}, which the view already uses for OpenMetadata.
 */
public class TrinoStatementClient {
    private static final Logger LOG = LoggerFactory.getLogger(TrinoStatementClient.class);
    private static final Gson GSON = new Gson();

    /** Outcome of one statement: either {@code error} is set, or {@code columns}/{@code rows} are. */
    public static final class Result {
        public List<String> columns = new ArrayList<>();
        public List<List<Object>> rows = new ArrayList<>();
        public String error;
        public String errorName;
        public String errorType;
        public boolean ok() { return error == null; }
    }

    private final KubernetesClient client;
    private final String namespace;
    private final String podName;
    private final String container;
    private final int httpsPort;
    private final String serviceUser;
    private final String servicePassword;

    public TrinoStatementClient(KubernetesClient client, String namespace, String podName, String container,
                                int httpsPort, String serviceUser, String servicePassword) {
        this.client = client;
        this.namespace = namespace;
        this.podName = podName;
        this.container = container;
        this.httpsPort = httpsPort;
        this.serviceUser = serviceUser;
        this.servicePassword = servicePassword;
    }

    /** Execute {@code sql} as {@code asUser} (Trino session user), following nextUri until the query finishes. */
    public Result execute(String sql, String asUser) {
        String base = "https://localhost:" + httpsPort;
        String body = curl("POST", base + "/v1/statement", sql, asUser);
        Result r = new Result();
        int hops = 0;
        while (true) {
            JsonObject o;
            try {
                o = GSON.fromJson(body, JsonObject.class);
            } catch (Exception e) {
                r.error = "Trino returned a non-JSON response: " + abbreviate(body);
                return r;
            }
            if (o == null) { r.error = "Empty response from Trino"; return r; }
            if (o.has("error") && o.get("error").isJsonObject()) {
                JsonObject err = o.getAsJsonObject("error");
                r.error = text(err, "message");
                r.errorName = text(err, "errorName");
                r.errorType = text(err, "errorType");
                return r;
            }
            if (o.has("columns") && o.get("columns").isJsonArray() && r.columns.isEmpty()) {
                for (JsonElement c : o.getAsJsonArray("columns")) r.columns.add(text(c.getAsJsonObject(), "name"));
            }
            if (o.has("data") && o.get("data").isJsonArray()) {
                for (JsonElement row : o.getAsJsonArray("data")) {
                    List<Object> vals = new ArrayList<>();
                    for (JsonElement v : row.getAsJsonArray()) vals.add(v.isJsonNull() ? null : (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : v.toString()));
                    r.rows.add(vals);
                }
            }
            String next = o.has("nextUri") && !o.get("nextUri").isJsonNull() ? o.get("nextUri").getAsString() : null;
            if (next == null) return r;
            if (++hops > 500) { r.error = "Query did not finish after 500 protocol hops"; return r; }
            body = curl("GET", next, null, asUser);
        }
    }

    /** Read a file inside the coordinator container (used for the dynamic catalog store); null when absent. */
    public String readFile(String path) {
        try {
            return exec("cat " + sq(path) + " 2>/dev/null");
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private String curl(String method, String url, String data, String asUser) {
        StringBuilder cmd = new StringBuilder("curl -sS -k --max-time 60 -u ")
                .append(sq(serviceUser + ":" + servicePassword))
                .append(" -H ").append(sq("X-Trino-User: " + asUser))
                .append(" -H ").append(sq("X-Trino-Source: kdps-catalog-editor"))
                .append(" -X ").append(method);
        if (data != null) cmd.append(" --data-binary ").append(sq(data));
        cmd.append(' ').append(sq(url));
        return exec(cmd.toString());
    }

    private String exec(String command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        try (ExecWatch ignored = client.pods().inNamespace(namespace).withName(podName).inContainer(container)
                .writingOutput(out).writingError(err)
                .usingListener(new ExecListener() {
                    @Override public void onClose(int code, String reason) { done.countDown(); }
                    @Override public void onFailure(Throwable t, Response r) { done.countDown(); }
                })
                .exec("bash", "-c", command)) {
            if (!done.await(90, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out talking to the Trino coordinator (" + podName + ")");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to the Trino coordinator", ie);
        }
        String stdout = out.toString(StandardCharsets.UTF_8);
        if (stdout.isBlank()) {
            String stderr = err.toString(StandardCharsets.UTF_8).trim();
            throw new IllegalStateException("No answer from the Trino coordinator" + (stderr.isEmpty() ? "" : ": " + abbreviate(stderr)));
        }
        return stdout;
    }

    /** Single-quote for bash. */
    static String sq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String text(JsonObject o, String k) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
    private static String abbreviate(String s) { return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s); }
}
