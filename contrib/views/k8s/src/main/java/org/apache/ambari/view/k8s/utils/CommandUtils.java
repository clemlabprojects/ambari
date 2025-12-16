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

package org.apache.ambari.view.k8s.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.CommandPlanFactory;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class CommandUtils {

    private static final Logger LOG = LoggerFactory.getLogger(CommandUtils.class);

    private final boolean isAutoDiscoveryEnabled;
    private final ViewContext ctx;
    private final KubernetesService kubernetesService;
    private final Gson gson = new GsonBuilder().create();

    public CommandUtils(ViewContext ctx, KubernetesService kubernetesService){
        this.ctx = ctx;
        this.isAutoDiscoveryEnabled = this.isAutoDiscoveryEnabled();
        this.kubernetesService = kubernetesService;
    }

    /**
     * resolve the cluster name by doscvering list of cluster and picking one
     * @return
     */
    public String resolveClusterName(String baseUriStr, Map<String,String> headers) throws Exception {
        // 1) Normal path: the View is bound to a cluster
        if (ctx.getCluster() != null && ctx.getCluster().getName() != null && !ctx.getCluster().getName().isBlank()) {
            return ctx.getCluster().getName();
        }

        // 2) If not bound, allow discovery only when explicitly enabled
        if (!isAutoDiscoveryEnabled()) {
            throw new IllegalStateException("No View cluster bound and auto-discovery disabled (set ambari.cluster.discovery.enabled=true to enable).");
        }

        // 3) Ask Ambari for clusters
        URI baseUri = URI.create(baseUriStr);
        // Build Ambari API base from the same host the View is running
        String ambariApiBase = baseUri.resolve("/api/v1").toString();
        var ambariActionClient = new AmbariActionClient(ctx, ambariApiBase, headers);
        List<String> all = ambariActionClient.listClusters();
        if (all == null || all.isEmpty()) {
            throw new IllegalStateException("Cluster auto-discovery found no clusters in Ambari.");
        }

        // 4) Prefer a configured name if present
        String preferred = preferredClusterName();
        if (preferred != null && !preferred.isBlank()) {
            Optional<String> hit = all.stream().filter(n -> n.equalsIgnoreCase(preferred)).findFirst();
            if (hit.isPresent()) return hit.get();
        }

        // 5) If only one, take it; else pick a stable choice (alphabetical first)
        if (all.size() == 1) return all.get(0);
        String chosen = all.stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().get();

        // optional: log the choice to help operators
        LOG.warn("View is unbound to a cluster; auto-discovery selected '{}' among {}", chosen, String.join(",", all));
        return chosen;
    }


    /**
     * helpers which check of the auto discovery of ambari managed cluster is enabled
     * @return
     */
    public boolean isAutoDiscoveryEnabled() {
        // view property toggle: "ambari.cluster.discovery.enabled"
        String v = ctx.getProperties() == null ? null : ctx.getProperties().get("ambari.cluster.discovery.enabled");
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1"));
    }

    /**
     * helpers which allow administrator to pick the cluster they prefer from the discovered one
     * @return
     */
    public String preferredClusterName() {
        // optional view property to bias the choice
        return ctx.getProperties() == null ? null : ctx.getProperties().get("ambari.cluster.preferred");
    }

    public static final Map<String, AmbariConfigRef> DYNAMIC_SOURCE_MAP = Map.of(
            // token                      // configType     // property key
            "AMBARI_KERBEROS_REALM",      new AmbariConfigRef("kerberos-env", "realm")
            // add more here as needed
    );

    public static final class AmbariConfigRef {
        public final String type;
        public final String key;
        AmbariConfigRef(String type, String key) {
            this.type = type; this.key = key;
        }
    }

    /**
     * Read the ranger spec back from params["_rangerSpec"] into a Map<String, Map<String,Object>>.
     * @param params
     * @return
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getRangerSpec(Map<String, Object> params) {
        if (params == null) return Collections.emptyMap();

        Object raw = params.get("_rangerSpec");
        if (raw == null) return Collections.emptyMap();

        // If someone already parsed it, accept the Map directly
        if (raw instanceof Map) {
            try {
                // Trust but verify shape at runtime; cast will throw if insane
                return (Map<String, Map<String, Object>>) raw;
            } catch (ClassCastException ignore) {
                // fall through to JSON parse attempt below
            }
        }

        // Otherwise parse from JSON string
        String json = String.valueOf(raw);
        java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<
                Map<String, Map<String, Object>>>() {}.getType();
        try {
            Map<String, Map<String, Object>> m = gson.fromJson(json, t);
            return m != null ? m : Collections.emptyMap();
        } catch (Exception e) {
            LOG.warn("Invalid _rangerSpec JSON in params: {}", e.toString());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public static void addOverride(Map<String, Object> params, String helmPath, String value) {
        if (helmPath == null || helmPath.isBlank()) return;
        if (value == null) return;

        Map<String, String> ov = (Map<String, String>) params.get("_ov");
        if (ov == null) {
            ov = new LinkedHashMap<>();
            params.put("_ov", ov);
        }
        ov.put(helmPath, value);
    }


    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeMountsObject(Object raw) {
        if (raw == null) return Collections.emptyMap();
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s) {
            if (s.isBlank()) return Collections.emptyMap();
            try {
                return new com.google.gson.Gson().fromJson(s, Map.class);
            } catch (Exception ignore) {
                return Collections.emptyMap();
            }
        }
        // tolerate the (wrong) array-of-specs case by ignoring
        return Collections.emptyMap();
    }
    // -------------------- Errors --------------------

    /** Marker for transient failures deserving a retry/backoff. */
    public static class RetryableException extends RuntimeException {
        public RetryableException(String m) { super(m); }
        public RetryableException(String m, Throwable t) { super(m, t); }
    }
    /** Build a HelmDeployRequest from the root command's paramsJson (chart, releaseName, namespace, version, values). */
    public HelmDeployRequest buildRequestFromRootParams(CommandEntity root) {
        HelmDeployRequest req = new HelmDeployRequest();
        try {
            Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
            String rawParams = resolveParamsPayload(root.getParamsJson());
            Map<String, Object> p = (rawParams != null && !rawParams.isBlank())
                    ? gson.fromJson(rawParams, mapType) : Map.of();

            Object chart = p.get("chart");
            Object releaseName = p.get("releaseName");
            Object namespace = p.get("namespace");
            Object version = p.get("version");
            Object serviceKey = p.get("serviceKey");
            Object repoId = p.get("repoId");
            Object secretName = p.get("secretName");
            Object dependencies = p.get("dependencies");
            Object mounts = p.get("mounts");
            Object imgRegistryProp = p.get("imageGlobalRegistryProperty");
            Object stackOverrides = p.get("stackOverrides");
            Object requiredConfigMaps = p.get("requiredConfigMaps");
            Object ranger = p.get("ranger");
            Object endpoints = p.get("endpoints");
            Object securityProfile = p.get("securityProfile");
            Object deploymentMode = p.get("deploymentMode");
            Object git = p.get("git");
            Object configInstantiations = p.get("configInstantiations");

            Map<String,Object> values = loadValuesFromParams(p);

            // These setters assume your HelmDeployRequest has standard mutators.
            if (chart instanceof String) req.setChart((String) chart);
            if (releaseName instanceof String) req.setReleaseName((String) releaseName);
            if (namespace instanceof String) req.setNamespace((String) namespace);
            if (version instanceof String) req.setVersion((String) version);
            if (serviceKey instanceof String) req.setServiceKey((String) serviceKey);
            if (repoId instanceof String) req.setRepoId((String) repoId);
            if (secretName instanceof String) req.setSecretName((String) secretName);
            if (dependencies instanceof Map) {
                req.setDependencies(new LinkedHashMap<>((Map<String, Object>) dependencies));
            }
            if (mounts != null) {
                req.setMounts(mounts);
            }
            if (imgRegistryProp instanceof String) {
                req.setImageGlobalRegistryProperty((String) imgRegistryProp);
            }
            if (stackOverrides instanceof Map) {
                req.setStackConfigOverrides((Map<String, Object>) stackOverrides);
            }
            if (requiredConfigMaps != null) {
                req.setRequiredConfigMaps(requiredConfigMaps);
            }
            Map<String, Map<String, Object>> rangerMap = getRangerSpec(p);
            if (ranger instanceof Map) {
                req.setRanger(ranger);
            } else if (!rangerMap.isEmpty()) {
                req.setRanger(rangerMap);
            }
            if (endpoints != null) {
                req.setEndpoints(endpoints);
            }
            if (securityProfile instanceof String) {
                req.setSecurityProfile((String) securityProfile);
            }
            if (deploymentMode instanceof String) {
                req.setDeploymentMode((String) deploymentMode);
            }
            if (git instanceof Map<?, ?> gitMap) {
                HelmDeployRequest.GitOptions opts = new HelmDeployRequest.GitOptions();
                Object repoUrl = gitMap.get("repoUrl");
                Object baseBranch = gitMap.get("baseBranch");
                Object pathPrefix = gitMap.get("pathPrefix");
                Object authToken = gitMap.get("authToken");
                Object sshKey = gitMap.get("sshKey");
                Object commitMode = gitMap.get("commitMode");
                if (repoUrl instanceof String) opts.setRepoUrl((String) repoUrl);
                if (baseBranch instanceof String) opts.setBaseBranch((String) baseBranch);
                if (pathPrefix instanceof String) opts.setPathPrefix((String) pathPrefix);
                if (authToken instanceof String) opts.setAuthToken((String) authToken);
                if (sshKey instanceof String) opts.setSshKey((String) sshKey);
                if (commitMode instanceof String) opts.setCommitMode((String) commitMode);
                req.setGit(opts);
            } else if (git instanceof String s && !s.isBlank()) {
                try {
                    Map<String, Object> gitObj = gson.fromJson(s, Map.class);
                    if (gitObj != null) {
                        HelmDeployRequest.GitOptions opts = new HelmDeployRequest.GitOptions();
                        Object repoUrl = gitObj.get("repoUrl");
                        Object baseBranch = gitObj.get("baseBranch");
                        Object pathPrefix = gitObj.get("pathPrefix");
                        Object authToken = gitObj.get("authToken");
                        Object sshKey = gitObj.get("sshKey");
                        Object commitMode = gitObj.get("commitMode");
                        if (repoUrl instanceof String) opts.setRepoUrl((String) repoUrl);
                        if (baseBranch instanceof String) opts.setBaseBranch((String) baseBranch);
                        if (pathPrefix instanceof String) opts.setPathPrefix((String) pathPrefix);
                        if (authToken instanceof String) opts.setAuthToken((String) authToken);
                        if (sshKey instanceof String) opts.setSshKey((String) sshKey);
                        if (commitMode instanceof String) opts.setCommitMode((String) commitMode);
                        req.setGit(opts);
                    }
                } catch (Exception ignore) {
                    // ignore malformed git payloads
                }
            }
            if (configInstantiations instanceof List<?>) {
                //noinspection unchecked
                req.setConfigInstantiations((List<HelmDeployRequest.ConfigInstantiation>) configInstantiations);
            }
            if (values != null) {
                req.setValues(values);
            }

        } catch (Exception ex) {
            LOG.warn("[{}] Could not reconstruct HelmDeployRequest from paramsJson: {}", root.getId(), ex.toString());
        }
        return req;
    }

    /**
     * Resolves a params payload, optionally loading it from a file reference.
     * When paramsJson is stored as "@@file:/absolute/path", the file contents are returned.
     *
     * @param raw raw paramsJson value from the entity
     * @return resolved JSON string (file contents) or the raw value when not a file reference
     */
    public static String resolveParamsPayload(String raw) {
        if (raw == null) {
            return null;
        }
        final String prefix = "@@file:";
        if (raw.startsWith(prefix)) {
            String path = raw.substring(prefix.length());
            try {
                return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException missing) {
                // Older commands may point to files deleted during upgrade/cleanup; degrade gracefully.
                LOG.info("Params payload file missing (likely purged): {}", path);
                return null;
            } catch (Exception ex) {
                LOG.warn("Failed to read params payload from {}: {}", path, ex.toString());
                return null;
            }
        }
        return raw;
    }


    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractValues(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        return Collections.emptyMap();
    }

    public Map<String, Object> loadValuesFromParams(Map<String, Object> params) {
        // Prefer file path if present
        Object valuesPathObj = params.get("valuesPath");
        if (valuesPathObj instanceof String path && !path.isBlank()) {
            String json = this.kubernetesService.getConfigurationService().readCacheFile(path);
            try {
                Type t = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String,Object> m = gson.fromJson(json, t);
                return m != null ? m : Collections.emptyMap();
            } catch (Exception e) {
                LOG.warn("Invalid JSON in valuesPath {}: {}", path, e.toString());
                return Collections.emptyMap();
            }
        }
        // Fallback to inlined "values" if any (keeps backward compat)
        return extractValues(params.get("values"));
    }

    // ----- helpers for submitKeytabRequest -----
    public static void copySessionHeaders(java.net.http.HttpRequest.Builder b, Map<String,Object> persisted) {
        if (persisted == null) return;
        // We mainly need Cookie / Authorization (depending on your Ambari auth mode)
        for (var e : persisted.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            String value;
            if (val instanceof java.util.List<?> l) {
                value = String.join("; ", l.stream().map(String::valueOf).toList());
            } else {
                value = String.valueOf(val);
            }
            if (value.isBlank()) continue;

            if ("x-requested-by".equalsIgnoreCase(key)) continue; // we set it ourselves
            if ("cookie".equalsIgnoreCase(key)) { b.header("Cookie", value); continue; }
            if ("authorization".equalsIgnoreCase(key)) { b.header("Authorization", value); continue; }
            // copy other headers defensively
            b.header(key, value);
        }
    }

    public static String url(String s) {
        try { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
    public static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static String ambariApiBaseFrom(UriInfo ui) {
        String base = ui.getBaseUri().toString(); // ends with '/api/v1/views/...'
        int i = base.indexOf("/api/v1/views/");
        if (i > 0) {
            return base.substring(0, i + "/api/v1".length()); // -> http(s)://host:port/api/v1
        }
        // Fallback: if for some reason we didn’t find views segment, assume {base}/api/v1
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base; // already points to /api/v1 in all normal View requests
    }



    // --- Ranger spec (de)serialization helpers ---


    /**
     * read the local krb5 config
     * @param namespace
     * @param configMapName
     */
    public void ensureKrb5ConfConfigMap(String namespace, String configMapName) {
        final String krb5Path = "/etc/krb5.conf";
        File f = new File(krb5Path);

        if (!f.exists() || !f.isFile()) {
            LOG.warn("Kerberos enabled but {} not found on this host; skipping krb5.conf ConfigMap creation (cm={})",
                    krb5Path, configMapName);
            return;
        }

        try {
            LOG.info("Reading Kerberos config from {} to create/update ConfigMap {} in namespace {}",
                    krb5Path, configMapName, namespace);

            String content = Files.readString(
                    f.toPath(),
                    StandardCharsets.UTF_8
            );

            Map<String, String> data = new LinkedHashMap<>();
            // key name inside the CM; this is what you mount with subPath: "krb5.conf"
            data.put("krb5.conf", content);

            Map<String, String> labels = Map.of(
                    "managed-by", "ambari-k8s-view",
                    "purpose", "kerberos-krb5-conf"
            );

            Map<String, String> annotations = Map.of(
                    "managed-at", java.time.Instant.now().toString()
            );

            this.kubernetesService.createNamespace(namespace);
            this.kubernetesService.createOrUpdateConfigMap(
                    namespace,
                    configMapName,
                    data,
                    labels,
                    annotations
            );

            LOG.info("Successfully created/updated krb5.conf ConfigMap {} in namespace {}", configMapName, namespace);
        } catch (Exception ex) {
            LOG.error("Failed to create/update krb5.conf ConfigMap {} in namespace {}: {}",
                    configMapName, namespace, ex.toString(), ex);
            throw new RuntimeException("Unable to create krb5.conf ConfigMap", ex);
        }
    }
}
