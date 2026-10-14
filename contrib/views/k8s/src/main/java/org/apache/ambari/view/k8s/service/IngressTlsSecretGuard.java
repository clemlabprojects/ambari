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

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;

import java.util.List;
import java.util.Map;

/**
 * Decides whether KDPS may write over an ingress TLS Secret that already exists.
 *
 * <p>The certificate an Ingress serves is often the production one: signed by the company CA, uploaded by the
 * operator, or kept by cert-manager or the External Secrets Operator. Overwriting it with a leaf from the Ambari
 * Internal CA breaks every browser, JDBC/ODBC client and BI tool that connects to the release, and an uploaded
 * certificate is lost for good. So a deploy only writes over a Secret that KDPS minted itself (it carries
 * {@link #ISSUER_LABEL}), or one the caller explicitly asked to replace. A Secret kept by cert-manager or the External
 * Secrets Operator is never written: its controller would take it back, and until it did the ingress would serve
 * the wrong certificate.
 */
public final class IngressTlsSecretGuard {

    /** Set on every ingress TLS Secret KDPS mints (see WebHookConfigurationService#issueIngressTlsSecret). */
    static final String ISSUER_LABEL = "ambari.clemlab.com/tls-issuer";

    static final String CERT_MANAGER = "cert-manager";
    static final String EXTERNAL_SECRETS = "external-secrets";

    /** How to serve the Secret as it is, on a first install as on an upgrade. */
    private static final String SERVE_AS_IT_IS = "To serve it as it is, choose 'Bring your own Secret' with this"
            + " Secret name, or 'Keep current certificate' when you upgrade the release.";

    private IngressTlsSecretGuard() {
    }

    /**
     * A deploy refused because its ingress certificate would replace an existing Secret KDPS did not mint, and the
     * caller did not confirm the replacement. Unlike a Secret cert-manager or the External Secrets Operator keeps,
     * confirming lets it through, so the wizard asks the operator and deploys again: on a first install the Secret is
     * mostly the one an earlier install of the release left behind (an uninstall by an earlier KDPS version kept it).
     */
    public static final class ReplacementNotConfirmedException extends IllegalStateException {
        /** The code the deploy endpoint answers this refusal with (409), which the wizard recognises. */
        public static final String CODE = "INGRESS_TLS_REPLACEMENT_NOT_CONFIRMED";

        private final String namespace;
        private final String secretName;

        public ReplacementNotConfirmedException(String namespace, String secretName, String message) {
            super(message);
            this.namespace = namespace;
            this.secretName = secretName;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getSecretName() {
            return secretName;
        }
    }

    /**
     * The controller that keeps the Secret up to date, from the annotations, labels and owner references it sets.
     *
     * @return {@link #CERT_MANAGER}, {@link #EXTERNAL_SECRETS}, or null when no controller owns the Secret
     */
    static String controllerOf(Secret secret) {
        ObjectMeta meta = secret == null ? null : secret.getMetadata();
        if (meta == null) return null;
        Map<String, String> annotations = meta.getAnnotations() == null ? Map.of() : meta.getAnnotations();
        Map<String, String> labels = meta.getLabels() == null ? Map.of() : meta.getLabels();
        List<OwnerReference> owners = meta.getOwnerReferences() == null ? List.of() : meta.getOwnerReferences();
        if (annotations.containsKey("cert-manager.io/certificate-name")
                || labels.containsKey("controller.cert-manager.io/fao")
                || ownedBy(owners, "cert-manager.io/")) {
            return CERT_MANAGER;
        }
        if (annotations.containsKey("external-secrets.io/managed-by")
                || annotations.containsKey("reconcile.external-secrets.io/data-hash")
                || labels.containsKey("reconcile.external-secrets.io/managed")
                || labels.containsKey("reconcile.external-secrets.io/created-by")
                || ownedBy(owners, "external-secrets.io/")) {
            return EXTERNAL_SECRETS;
        }
        return null;
    }

    /** Whether KDPS minted the Secret's certificate (Ambari Internal CA or a company CA of the PKI registry). */
    static boolean mintedByView(Secret secret) {
        ObjectMeta meta = secret == null ? null : secret.getMetadata();
        return meta != null && meta.getLabels() != null && meta.getLabels().containsKey(ISSUER_LABEL);
    }

    /**
     * Refuses to write over {@code existing} unless KDPS may: nothing is there, KDPS minted it, or the caller asked to
     * replace it. A Secret kept by cert-manager or the External Secrets Operator is refused even then.
     *
     * @param existing        the Secret as it is in the cluster, or null when there is none
     * @param namespace       its namespace, for the message
     * @param secretName      its name, for the message
     * @param replaceExisting whether the caller explicitly asked to replace a Secret KDPS did not mint
     * @throws ReplacementNotConfirmedException when KDPS did not mint the Secret and the caller did not ask to
     *                                           replace it
     * @throws IllegalStateException when the Secret must be left as it is; the message says why and what to do
     */
    static void checkReplaceable(Secret existing, String namespace, String secretName, boolean replaceExisting) {
        if (existing == null) return;
        String where = namespace + "/" + secretName;
        String controller = controllerOf(existing);
        if (CERT_MANAGER.equals(controller)) {
            throw new IllegalStateException("The ingress certificate Secret " + where + " is managed by cert-manager,"
                    + " so KDPS leaves it as it is (cert-manager renews it). " + SERVE_AS_IT_IS + " To have KDPS"
                    + " issue this one instead, delete its cert-manager Certificate first.");
        }
        if (EXTERNAL_SECRETS.equals(controller)) {
            throw new IllegalStateException("The ingress certificate Secret " + where + " is synced by the External"
                    + " Secrets Operator, so KDPS leaves it as it is. " + SERVE_AS_IT_IS + " To have KDPS provide"
                    + " this one instead, delete its ExternalSecret first.");
        }
        if (mintedByView(existing) || replaceExisting) return;
        throw new ReplacementNotConfirmedException(namespace, secretName, "The ingress certificate Secret " + where
                + " already exists and was not issued by KDPS (a certificate uploaded for an earlier install of the"
                + " release, or one created outside KDPS), so it was left as it is. " + SERVE_AS_IT_IS + " To replace"
                + " it, deploy again and confirm the replacement (API callers set replaceExistingTls=true).");
    }

    private static boolean ownedBy(List<OwnerReference> owners, String apiGroupPrefix) {
        for (OwnerReference owner : owners) {
            if (owner != null && owner.getApiVersion() != null && owner.getApiVersion().startsWith(apiGroupPrefix)) {
                return true;
            }
        }
        return false;
    }
}
