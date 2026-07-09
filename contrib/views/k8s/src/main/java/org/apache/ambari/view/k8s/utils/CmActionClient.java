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

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal read-only client for the Cloudera Manager (CDP) REST API — the CDP analogue of
 * {@link AmbariActionClient}'s discovery reads. Used to resolve a KDPS context of kind {@code CDP}:
 * it discovers Hive (HiveServer2/Metastore), Ranger, and Atlas connection properties + the Kerberos
 * realm from a Cloudera-managed cluster so KDPS-deployed services (Trino, Superset, OpenMetadata)
 * can connect to it. HTTP Basic auth over TLS (7183) or plaintext (7180); trust-all SSL for the
 * typically self-signed CM cert (mirrors the {@code verifySsl=false} remote-Ambari path).
 *
 * <p>Read-only by construction: only issues GETs. All discovery flows through the well-known CM
 * endpoints: {@code /api/version}, {@code /clusters}, {@code /clusters/{c}/services},
 * {@code /services/{s}/roles}, {@code /services/{s}/config}, {@code roleConfigGroups/{rcg}/config},
 * and {@code /cm/config}.
 */
public class CmActionClient {

    private final String baseNoApi;   // e.g. https://cm-host:7183  (no trailing slash)
    private final String authHeader;  // "Basic ..." or null
    private final HttpClient http;
    private final Gson gson = new Gson();
    private volatile String apiVersion; // e.g. "v54" (lazy)

    public CmActionClient(String cmUrl, String username, String password, boolean verifySsl) {
        this.baseNoApi = cmUrl == null ? "" : cmUrl.replaceAll("/+$", "").replaceAll("/api(/v\\d+)?$", "");
        this.authHeader = (username != null && !username.isBlank() && password != null)
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8))
                : null;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8));
        if (!verifySsl) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{ new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                // The trust-all TrustManager skips chain validation, but java.net.http still enforces
                // HTTPS endpoint-identity (hostname/SAN) checks — which fail on an IP-addressed URL or
                // a self-signed cert whose SAN doesn't match the CM host. verifySsl=false means "trust
                // this endpoint completely", so disable hostname verification too. HttpClient only
                // honors this via the global system property (it ignores SSLParameters'
                // endpointIdentificationAlgorithm), so set it before building the client.
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                b.sslContext(sc);
            } catch (Exception ignore) { /* fall back to default SSL */ }
        }
        this.http = b.build();
    }

    // ---- low-level GET returning parsed JSON (Map or List) ----
    @SuppressWarnings("unchecked")
    private <T> T getJson(String url, Class<T> type) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET();
        if (authHeader != null) rb.header("Authorization", authHeader);
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("CM GET " + url + " -> HTTP " + resp.statusCode()
                    + (resp.body() == null ? "" : (": " + resp.body().substring(0, Math.min(200, resp.body().length())))));
        }
        return (T) gson.fromJson(resp.body(), type);
    }

    /** CM API version string, e.g. {@code v54}. Cached after first call. */
    public String apiVersion() throws Exception {
        if (apiVersion == null) {
            String v = getJson(baseNoApi + "/api/version", String.class);
            apiVersion = (v == null || v.isBlank()) ? "v40" : v.trim();
        }
        return apiVersion;
    }

    private String apiBase() throws Exception { return baseNoApi + "/api/" + apiVersion(); }

    /** {@code /cm/version} product info (e.g. "7.11.3"); best-effort. */
    public String cmProductVersion() {
        try {
            Map<?, ?> m = getJson(apiBase() + "/cm/version", Map.class);
            return m == null ? null : String.valueOf(m.get("version"));
        } catch (Exception e) { return null; }
    }

    /** List cluster names (+ runtime version). Each entry: {name, fullVersion, displayName}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listClusters() throws Exception {
        Map<String, Object> m = getJson(apiBase() + "/clusters", Map.class);
        Object items = m == null ? null : m.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : new ArrayList<>();
    }

    /** Services in a cluster. Each: {name, type} (HIVE, HIVE_ON_TEZ, RANGER, ATLAS, ...). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listServices(String cluster) throws Exception {
        Map<String, Object> m = getJson(apiBase() + "/clusters/" + enc(cluster) + "/services", Map.class);
        Object items = m == null ? null : m.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : new ArrayList<>();
    }

    /** Roles of a service. Each: {type, hostRef:{hostname}}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRoles(String cluster, String service) throws Exception {
        Map<String, Object> m = getJson(apiBase() + "/clusters/" + enc(cluster) + "/services/" + enc(service) + "/roles", Map.class);
        Object items = m == null ? null : m.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : new ArrayList<>();
    }

    /** Service-level config as name->value (prefers value, falls back to default). view=full. */
    public Map<String, String> serviceConfig(String cluster, String service) throws Exception {
        return flattenConfig(getJson(apiBase() + "/clusters/" + enc(cluster) + "/services/" + enc(service)
                + "/config?view=full", Map.class));
    }

    /** Role-config-group config (where ports live) as name->value. view=full. */
    public Map<String, String> roleConfigGroupConfig(String cluster, String service, String rcg) throws Exception {
        return flattenConfig(getJson(apiBase() + "/clusters/" + enc(cluster) + "/services/" + enc(service)
                + "/roleConfigGroups/" + enc(rcg) + "/config?view=full", Map.class));
    }

    /** CM-global config (SECURITY_REALM, KDC_TYPE, KDC_HOST). view=summary. */
    public Map<String, String> cmConfig() throws Exception {
        return flattenConfig(getJson(apiBase() + "/cm/config?view=summary", Map.class));
    }

    /** Role config groups of a service. Each: {name, roleType}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRoleConfigGroups(String cluster, String service) throws Exception {
        Map<String, Object> m = getJson(apiBase() + "/clusters/" + enc(cluster) + "/services/" + enc(service)
                + "/roleConfigGroups", Map.class);
        Object items = m == null ? null : m.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : new ArrayList<>();
    }

    /** The hostname of the first role of {@code roleType} in a service, or null. */
    public String firstRoleHost(String cluster, String service, String roleType) {
        try {
            for (Map<String, Object> role : listRoles(cluster, service)) {
                if (roleType.equalsIgnoreCase(String.valueOf(role.get("type")))) {
                    Object ref = role.get("hostRef");
                    if (ref instanceof Map) {
                        Object h = ((Map<?, ?>) ref).get("hostname");
                        if (h != null) return String.valueOf(h);
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Merged config a role type sees: service-level config overlaid with its role-config-group's
     * config (where CDP keeps ports). Best-effort — returns whatever it can read. */
    public Map<String, String> configForRoleType(String cluster, String service, String roleType) {
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        try { merged.putAll(serviceConfig(cluster, service)); } catch (Exception ignore) {}
        try {
            for (Map<String, Object> rcg : listRoleConfigGroups(cluster, service)) {
                if (roleType.equalsIgnoreCase(String.valueOf(rcg.get("roleType")))) {
                    Map<String, String> c = roleConfigGroupConfig(cluster, service, String.valueOf(rcg.get("name")));
                    c.forEach((k, v) -> { if (v != null) merged.put(k, v); });
                    break;
                }
            }
        } catch (Exception ignore) {}
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> flattenConfig(Map<String, Object> m) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Object items = m == null ? null : m.get("items");
        if (items instanceof List) {
            for (Object o : (List<Object>) items) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> c = (Map<?, ?>) o;
                Object name = c.get("name");
                if (name == null) continue;
                Object val = c.get("value");
                if (val == null) val = c.get("default");
                out.put(String.valueOf(name), val == null ? null : String.valueOf(val));
            }
        }
        return out;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Test connection: returns {ok, cmVersion, clusters[]} or throws with a friendly message. */
    public Map<String, Object> probe() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            String v = apiVersion();
            String prod = cmProductVersion();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> c : listClusters()) names.add(String.valueOf(c.get("name")));
            out.put("ok", true);
            out.put("apiVersion", v);
            out.put("cmVersion", prod);
            out.put("clusters", names);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
