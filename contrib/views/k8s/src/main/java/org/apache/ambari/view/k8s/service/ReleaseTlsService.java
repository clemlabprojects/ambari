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

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reports the TLS state of a Helm release: which Ingresses and OpenShift Routes are exposed, which
 * TLS Secrets they reference, who issued each leaf, when it expires, and which
 * provisioning system delivered it.
 *
 * The result powers two operator-facing features:
 *   - The "TLS" column on the Helm Releases page (badges, tooltips).
 *   - The "Renew TLS" action, which needs to know the provisioning source so it
 *     can dispatch to the right renewal mechanism (re-mint, cert-manager
 *     re-issue, ESO refresh, or no-op for operator-managed).
 *
 * Source inference rules (in order, first match wins):
 *   - Secret kept by cert-manager (its annotations, labels or owner)      → "cert-manager"
 *   - Secret kept by the External Secrets Operator (same)                → "external-secrets"
 *   - Secret has label `ambari.clemlab.com/tls-issuer`                   → "k8s-view-self-signed"
 *   - Otherwise                                                          → "external"
 *
 * A self-signed entry also carries the CA it was minted with (`caMode`: ambariInternal | companyUploaded, and the
 * sanitized `caName` of a company CA), and an external one `uploaded: true` when KDPS wrote it from a certificate
 * the operator uploaded, so Upgrade/Config can tell the operator which certificate the release keeps.
 */
public class ReleaseTlsService {
    private static final Logger LOG = LoggerFactory.getLogger(ReleaseTlsService.class);

    private final KubernetesService kubernetesService;

    public ReleaseTlsService(ViewContext ctx, KubernetesService kubernetesService) {
        Objects.requireNonNull(ctx, "ctx");
        this.kubernetesService = Objects.requireNonNull(kubernetesService, "kubernetesService");
    }

    /**
     * Enumerate the Ingresses of a Helm release and for each TLS host return a snapshot.
     *
     * @return list of snapshot maps (one per host × Secret combo); never null
     */
    public List<Map<String, Object>> describe(String namespace, String releaseName) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(releaseName, "releaseName");

        List<Map<String, Object>> result = new ArrayList<>();
        List<Ingress> ingresses;
        try {
            ingresses = kubernetesService.listIngressesForRelease(namespace, releaseName);
        } catch (Exception ex) {
            LOG.warn("Failed to list Ingresses for {}/{}: {}", namespace, releaseName, ex.toString());
            return result;
        }

        for (Ingress ing : ingresses) {
            if (ing.getSpec() == null) continue;
            List<IngressTLS> tlsList = ing.getSpec().getTls();
            if (tlsList == null || tlsList.isEmpty()) {
                // Ingress exists but has no TLS section — surface as "no TLS" so the UI
                // can show a clear badge instead of hiding the row entirely.
                Map<String, Object> entry = baseEntry(ing.getMetadata().getName(), namespace, null);
                entry.put("hosts", ing.getSpec().getRules() == null ? List.of()
                        : ing.getSpec().getRules().stream()
                                .map(r -> r.getHost() == null ? "" : r.getHost()).toList());
                entry.put("status", "no-tls");
                result.add(entry);
                continue;
            }
            for (IngressTLS tls : tlsList) {
                String secretName = tls.getSecretName();
                List<String> hosts = tls.getHosts() == null ? List.of() : tls.getHosts();
                Map<String, Object> entry = baseEntry(ing.getMetadata().getName(), namespace, secretName);
                entry.put("hosts", hosts);
                describeSecret(namespace, secretName, entry);
                result.add(entry);
            }
        }
        describeRoutes(namespace, releaseName, result);
        return result;
    }

    /**
     * The OpenShift Routes of the release, one entry each. A Route reading its certificate from a Secret
     * ({@code spec.tls.externalCertificate}) is reported from that Secret, as an Ingress's. A Route holding a copy of
     * the certificate is reported from the copy, with the Secret it was copied from when one in the namespace holds
     * the same certificate ({@code copiedToRoute}: the Route serves a renewed certificate only once the release is
     * rendered again). A passthrough Route serves the certificate of the service itself: nothing to renew there.
     */
    private void describeRoutes(String namespace, String releaseName, List<Map<String, Object>> result) {
        List<Map<String, Object>> routes;
        try {
            routes = kubernetesService.listRoutesForRelease(namespace, releaseName);
        } catch (Exception ex) {
            LOG.warn("Failed to list Routes for {}/{}: {}", namespace, releaseName, ex.toString());
            return;
        }
        for (Map<String, Object> route : routes) {
            Map<String, Object> spec = map(route.get("spec"));
            Map<String, Object> tls = map(spec.get("tls"));
            String host = spec.get("host") == null ? "" : String.valueOf(spec.get("host"));
            String external = map(tls.get("externalCertificate")).get("name") instanceof String n && !n.isBlank()
                    ? n : null;
            Map<String, Object> entry = routeEntry(String.valueOf(route.get("name")), namespace, external, host);
            String termination = tls.get("termination") == null ? null : String.valueOf(tls.get("termination"));
            if (termination == null || termination.isBlank()) {
                entry.put("status", "no-tls");
            } else if ("passthrough".equals(termination)) {
                entry.put("status", "passthrough");
            } else if (external != null) {
                describeSecret(namespace, external, entry);
            } else if (tls.get("certificate") instanceof String copy && !copy.isBlank()) {
                describeCopy(namespace, copy, entry);
            } else {
                // The router serves its own default certificate.
                entry.put("status", "no-tls");
            }
            result.add(entry);
        }
    }

    /** Reports the certificate a Route holds a copy of, and the Secret of the namespace it was copied from. */
    private void describeCopy(String namespace, String copy, Map<String, Object> entry) {
        try {
            X509Certificate leaf = leafOf(copy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (leaf == null) {
                entry.put("status", "no-cert-in-secret");
                return;
            }
            entry.put("copiedToRoute", true);
            for (Secret secret : kubernetesService.listCertificateSecrets(namespace)) {
                String crt = secret.getData() == null ? null : secret.getData().get("tls.crt");
                X509Certificate held = crt == null ? null : leafOf(Base64.getDecoder().decode(crt));
                if (held != null && java.util.Arrays.equals(held.getEncoded(), leaf.getEncoded())) {
                    entry.put("secretName", secret.getMetadata().getName());
                    String source = inferSource(secret);
                    entry.put("source", source);
                    describeOrigin(secret, source, entry);
                    break;
                }
            }
            populate(leaf, entry);
        } catch (Exception ex) {
            LOG.warn("Failed to read the certificate of Route {}/{}: {}", namespace, entry.get("routeName"), ex.toString());
            entry.put("status", "read-error");
            entry.put("error", ex.getMessage());
        }
    }

    /** Reports the Secret an Ingress or a Route reads its certificate from. */
    private void describeSecret(String namespace, String secretName, Map<String, Object> entry) {
        try {
            Secret secret = kubernetesService.getSecret(namespace, secretName);
            if (secret == null) {
                entry.put("status", "secret-missing");
                return;
            }
            String source = inferSource(secret);
            entry.put("source", source);
            describeOrigin(secret, source, entry);
            parseAndPopulate(secret, entry);
        } catch (Exception ex) {
            LOG.warn("Failed to read/parse TLS Secret {}/{}: {}", namespace, secretName, ex.toString());
            entry.put("status", "read-error");
            entry.put("error", ex.getMessage());
        }
    }

    private Map<String, Object> baseEntry(String ingressName, String namespace, String secretName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "Ingress");
        entry.put("ingressName", ingressName);
        entry.put("namespace", namespace);
        entry.put("secretName", secretName);
        return entry;
    }

    private Map<String, Object> routeEntry(String routeName, String namespace, String secretName, String host) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "Route");
        entry.put("routeName", routeName);
        entry.put("namespace", namespace);
        entry.put("secretName", secretName);
        entry.put("hosts", host.isBlank() ? List.of() : List.of(host));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String inferSource(Secret secret) {
        String controller = IngressTlsSecretGuard.controllerOf(secret);
        if (controller != null) {
            return controller;
        }
        if (IngressTlsSecretGuard.mintedByView(secret)) {
            return "k8s-view-self-signed";
        }
        return "external";
    }

    /** The CA a self-signed leaf was minted with, or whether KDPS wrote an external one from an upload. */
    private void describeOrigin(Secret secret, String source, Map<String, Object> entry) {
        Map<String, String> labels = secret.getMetadata().getLabels() == null
                ? Map.of() : secret.getMetadata().getLabels();
        Map<String, String> ann = secret.getMetadata().getAnnotations() == null
                ? Map.of() : secret.getMetadata().getAnnotations();
        if ("k8s-view-self-signed".equals(source)) {
            entry.put("caMode", labels.getOrDefault("ambari.clemlab.com/ca-mode", "ambariInternal"));
            if (labels.containsKey("ambari.clemlab.com/ca-name")) {
                entry.put("caName", labels.get("ambari.clemlab.com/ca-name"));
            }
        } else if ("external".equals(source) && "ambari-k8s-view".equals(ann.get("managed-by"))) {
            // KubernetesService#createOrUpdateTlsSecret writes the certificate and key the operator uploaded.
            entry.put("uploaded", true);
        }
    }

    private void parseAndPopulate(Secret secret, Map<String, Object> entry) throws Exception {
        Map<String, String> data = secret.getData();
        if (data == null || !data.containsKey("tls.crt")) {
            entry.put("status", "no-tls-crt");
            return;
        }
        X509Certificate leaf = leafOf(Base64.getDecoder().decode(data.get("tls.crt")));
        if (leaf == null) {
            entry.put("status", "no-cert-in-secret");
            return;
        }
        populate(leaf, entry);
    }

    /**
     * The leaf of a PEM certificate or chain, or null when it holds none. tls.crt may contain a chain (leaf +
     * intermediates): the leaf, first by convention, is what browsers validate against the SANs and what the operator
     * most cares about for expiry / issuer.
     */
    private static X509Certificate leafOf(byte[] pemBytes) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<?> certs;
        try (var in = new ByteArrayInputStream(pemBytes)) {
            certs = cf.generateCertificates(in);
        }
        return certs == null || certs.isEmpty() ? null : (X509Certificate) certs.iterator().next();
    }

    private static void populate(X509Certificate leaf, Map<String, Object> entry) throws Exception {
        Instant notBefore = leaf.getNotBefore().toInstant();
        Instant notAfter = leaf.getNotAfter().toInstant();
        Instant now = Instant.now();
        long daysUntilExpiry = Duration.between(now, notAfter).toDays();
        String status;
        if (now.isAfter(notAfter))         status = "expired";
        else if (daysUntilExpiry <= 14)    status = "expiring-soon";
        else if (daysUntilExpiry <= 30)    status = "expiring-warning";
        else                                status = "valid";

        entry.put("status", status);
        entry.put("subject", leaf.getSubjectX500Principal().getName());
        entry.put("issuer", leaf.getIssuerX500Principal().getName());
        entry.put("notBefore", notBefore.toString());
        entry.put("notAfter", notAfter.toString());
        entry.put("daysUntilExpiry", daysUntilExpiry);
        entry.put("serial", leaf.getSerialNumber().toString(16));
        // SubjectAlternativeNames: 2=DNS, 7=IP. We expose DNS names only.
        List<String> sans = new ArrayList<>();
        Collection<List<?>> rawSans = leaf.getSubjectAlternativeNames();
        if (rawSans != null) {
            for (List<?> san : rawSans) {
                if (san.size() < 2) continue;
                Object type = san.get(0);
                Object value = san.get(1);
                if (type instanceof Integer i && i == 2 && value instanceof String s) {
                    sans.add(s);
                }
            }
        }
        entry.put("sans", sans);
    }
}
