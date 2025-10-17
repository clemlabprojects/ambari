package org.apache.ambari.view.k8s.utils;

import com.google.gson.*;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Tiny client around Ambari Server REST needed for:
 *  - POST /api/v1/clusters/{cluster}/requests  (submit action)
 *  - GET  /api/v1/clusters/{cluster}/requests/{id} (poll status)
 *  - GET  /api/v1/clusters/{cluster}/requests/{id}/tasks?fields=structured_out (fetch keytab b64)
 *
 * Authentication/headers:
 *  - Uses the View's URLStreamProvider so we don't hand-roll cookies/auth.
 */
public class AmbariActionClient {

    private final ViewContext ctx;
    private final URLStreamProvider stream;
    private final String ambariApiBase; // e.g. "http://localhost:8080/api/v1"
    private final String clusterName;

    public AmbariActionClient(ViewContext ctx, String ambariApiBase, String clusterName) {
        this.ctx = ctx;
        this.stream = ctx.getURLStreamProvider();
        this.ambariApiBase = ambariApiBase.endsWith("/") ? ambariApiBase.substring(0, ambariApiBase.length()-1) : ambariApiBase;
        this.clusterName = clusterName;
    }

    public int submitGenerateAdhocKeytab(String principal, boolean returnBase64Inline, String outputPathIfAny) throws Exception {
        JsonObject req = new JsonObject();

        JsonObject requestInfo = new JsonObject();
        requestInfo.addProperty("context", "Generate ad-hoc keytab");
        requestInfo.addProperty("action", "GENERATE_ADHOC_KEYTAB");

        JsonObject params = new JsonObject();
        params.addProperty("principal", principal);

        // two modes:
        //  A) inline base64 (default): action writes "keytab_b64" in structured_out
        //  B) file: you can pass "output_path" so the action writes the keytab to disk
        if (returnBase64Inline) {
            params.addProperty("return_base64", "true");
        } else if (outputPathIfAny != null && !outputPathIfAny.isEmpty()) {
            params.addProperty("output_path", outputPathIfAny);
        }

        requestInfo.add("parameters", params);
        req.add("RequestInfo", requestInfo);

        // resource_filters must exist; empty list means “server-side only”
        req.add("Requests/resource_filters", new JsonArray());

        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/requests";
        String payload = req.toString();

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-By", "ambari");

        try (InputStream is = stream.readFrom(url, "POST", Arrays.toString(payload.getBytes(StandardCharsets.UTF_8)), headers)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            // Typical response contains "Requests" with "id"
            JsonObject ro = o.getAsJsonObject("Requests");
            if (ro == null || !ro.has("id")) {
                throw new IllegalStateException("Ambari returned unexpected payload for request submission: " + json);
            }
            return ro.get("id").getAsInt();
        }
    }

    public boolean waitUntilComplete(int requestId, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (System.nanoTime() < deadline) {
            String status = getRequestStatus(requestId);
            if ("COMPLETED".equalsIgnoreCase(status)) return true;
            if ("FAILED".equalsIgnoreCase(status) || "ABORTED".equalsIgnoreCase(status) || "TIMEDOUT".equalsIgnoreCase(status)) {
                return false;
            }
            Thread.sleep(1000L);
        }
        return false;
    }

    public String getRequestStatus(int requestId) throws Exception {
        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/requests/" + requestId;
        Map<String, String> headers = Map.of("X-Requested-By", "ambari");
        try (InputStream is = stream.readFrom(url, "GET", (InputStream) null, headers)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonObject r = o.getAsJsonObject("Requests");
            return (r != null && r.has("request_status")) ? r.get("request_status").getAsString() : "UNKNOWN";
        }
    }

    /**
     * Read keytab as base64 from structured_out of the tasks for this request.
     * We look through tasks’ "structured_out" for a "keytab_b64" field (the ServerAction must place it there).
     */
    public Optional<String> fetchKeytabBase64FromTasks(int requestId) throws Exception {
        String url = ambariApiBase + "/clusters/" + encode(clusterName) + "/requests/" + requestId + "/tasks?fields=structured_out";
        Map<String, String> headers = Map.of("X-Requested-By", "ambari");
        try (InputStream is = stream.readFrom(url, "GET", (String) null, headers)) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("items")) return Optional.empty();

            JsonArray items = root.getAsJsonArray("items");
            for (JsonElement el : items) {
                JsonObject task = el.getAsJsonObject();
                JsonObject so = task.getAsJsonObject("structured_out");
                if (so != null && so.has("keytab_b64")) {
                    String b64 = so.get("keytab_b64").getAsString();
                    if (b64 != null && !b64.isBlank()) {
                        return Optional.of(b64);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String encode(String s) {
        return s.replace(" ", "%20");
    }
}
