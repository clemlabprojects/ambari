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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.k8s.service.KubernetesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports cluster-level capability flags so the UI can drive its
 * discovery-driven flows (TLS modes, secret stores, OpenShift Route
 * detection, …). The result is cached for {@link #TTL_MILLIS} ms to
 * avoid hitting the K8s API on every page navigation.
 *
 * Probes performed (all cheap, all GET-on-CRD):
 *   - cert-manager.io CRDs (clusterissuers.cert-manager.io, certificates.cert-manager.io)
 *   - external-secrets.io CRDs (secretstores, clustersecretstores, externalsecrets)
 *   - route.openshift.io CRD (presence implies OpenShift cluster)
 *
 * The endpoint returns a stable JSON shape the UI can rely on; any future
 * capability check should add a new top-level key rather than reshape the
 * existing ones.
 */
public class ClusterCapabilitiesResource {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterCapabilitiesResource.class);

    /** TTL for the cached capability snapshot. */
    private static final long TTL_MILLIS = 60_000L;

    private static final AtomicReference<CachedCapabilities> CACHE = new AtomicReference<>();

    private final KubernetesService kubernetesService;

    public ClusterCapabilitiesResource(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCapabilities() {
        CachedCapabilities cached = CACHE.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAt) < TTL_MILLIS) {
            return Response.ok(cached.payload).build();
        }
        Map<String, Object> payload = probe();
        CACHE.set(new CachedCapabilities(payload, now));
        return Response.ok(payload).build();
    }

    private Map<String, Object> probe() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ---- OpenShift platform detection ----
        boolean routeCrd = safeCrdExists("routes.route.openshift.io");
        Map<String, Object> openshift = new LinkedHashMap<>();
        openshift.put("routeCrd", routeCrd);
        result.put("openshift", openshift);
        result.put("platform", routeCrd ? "openshift" : "kubernetes");

        // ---- cert-manager (https://cert-manager.io) ----
        // We only check the GA v1 CRDs. Old v1alpha2/v1beta1 are deprecated and
        // out of scope per the product decision to target current versions only.
        boolean cmClusterIssuer = safeCrdExists("clusterissuers.cert-manager.io");
        boolean cmCertificate = safeCrdExists("certificates.cert-manager.io");
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("installed", cmClusterIssuer && cmCertificate);
        cm.put("clusterIssuerCrd", cmClusterIssuer);
        cm.put("certificateCrd", cmCertificate);
        result.put("certManager", cm);

        // ---- external-secrets (https://external-secrets.io) ----
        boolean esoSecretStore = safeCrdExists("secretstores.external-secrets.io");
        boolean esoClusterSecretStore = safeCrdExists("clustersecretstores.external-secrets.io");
        boolean esoExternalSecret = safeCrdExists("externalsecrets.external-secrets.io");
        Map<String, Object> eso = new LinkedHashMap<>();
        eso.put("installed", esoExternalSecret && (esoSecretStore || esoClusterSecretStore));
        eso.put("secretStoreCrd", esoSecretStore);
        eso.put("clusterSecretStoreCrd", esoClusterSecretStore);
        eso.put("externalSecretCrd", esoExternalSecret);
        result.put("externalSecrets", eso);

        LOG.info("Cluster capability probe: platform={} certManager={} externalSecrets={}",
                result.get("platform"), cm.get("installed"), eso.get("installed"));
        return result;
    }

    /**
     * Wraps {@link KubernetesService#crdExists(String)} with a try/catch so a single
     * unreachable API doesn't crash the whole capability probe — caller still gets
     * a payload, just with that flag set to {@code false}.
     */
    private boolean safeCrdExists(String crdName) {
        try {
            return kubernetesService.crdExists(crdName);
        } catch (Exception ex) {
            LOG.debug("CRD probe '{}' failed: {}", crdName, ex.toString());
            return false;
        }
    }

    /**
     * Invalidates the cache. Called by the install flow whenever it has reason to
     * believe a capability changed (e.g. operator just installed cert-manager via the
     * Cluster Security page). Public so future code paths can poke it from the same JVM.
     */
    public static void invalidate() {
        CACHE.set(null);
    }

    private static final class CachedCapabilities {
        final Map<String, Object> payload;
        final long fetchedAt;
        CachedCapabilities(Map<String, Object> payload, long fetchedAt) {
            this.payload = payload;
            this.fetchedAt = fetchedAt;
        }
    }
}
