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
 * Reports the TLS state of a Helm release: which Ingresses are exposed, which
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
 *   - Secret has annotation `cert-manager.io/certificate-name`            → "cert-manager"
 *   - Secret has annotation `external-secrets.io/managed-by` = "..."     → "external-secrets"
 *   - Secret has label `ambari.clemlab.com/tls-issuer`                   → "k8s-view-self-signed"
 *   - Otherwise                                                          → "external"
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
                try {
                    Secret secret = kubernetesService.getSecret(namespace, secretName);
                    if (secret == null) {
                        entry.put("status", "secret-missing");
                        result.add(entry);
                        continue;
                    }
                    entry.put("source", inferSource(secret));
                    parseAndPopulate(secret, entry);
                } catch (Exception ex) {
                    LOG.warn("Failed to read/parse TLS Secret {}/{}: {}", namespace, secretName, ex.toString());
                    entry.put("status", "read-error");
                    entry.put("error", ex.getMessage());
                }
                result.add(entry);
            }
        }
        return result;
    }

    private Map<String, Object> baseEntry(String ingressName, String namespace, String secretName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ingressName", ingressName);
        entry.put("namespace", namespace);
        entry.put("secretName", secretName);
        return entry;
    }

    private String inferSource(Secret secret) {
        Map<String, String> ann = secret.getMetadata().getAnnotations();
        Map<String, String> labels = secret.getMetadata().getLabels();
        if (ann != null && ann.containsKey("cert-manager.io/certificate-name")) {
            return "cert-manager";
        }
        if (ann != null && (ann.containsKey("external-secrets.io/managed-by")
                || ann.containsKey("reconcile.external-secrets.io/data-hash"))) {
            return "external-secrets";
        }
        if (labels != null && labels.containsKey("ambari.clemlab.com/tls-issuer")) {
            return "k8s-view-self-signed";
        }
        return "external";
    }

    private void parseAndPopulate(Secret secret, Map<String, Object> entry) throws Exception {
        Map<String, String> data = secret.getData();
        if (data == null || !data.containsKey("tls.crt")) {
            entry.put("status", "no-tls-crt");
            return;
        }
        byte[] pemBytes = Base64.getDecoder().decode(data.get("tls.crt"));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        // tls.crt may contain a chain (leaf + intermediates). We report the *leaf* —
        // that's what browsers validate against the SANs and what the operator
        // most cares about for expiry / issuer.
        Collection<?> certs;
        try (var in = new ByteArrayInputStream(pemBytes)) {
            certs = cf.generateCertificates(in);
        }
        if (certs == null || certs.isEmpty()) {
            entry.put("status", "no-cert-in-secret");
            return;
        }
        // First cert in a PEM chain is the leaf by convention.
        X509Certificate leaf = (X509Certificate) certs.iterator().next();
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
