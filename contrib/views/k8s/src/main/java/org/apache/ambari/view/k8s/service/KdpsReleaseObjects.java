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

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The objects KDPS creates for a release outside its chart (keytab, truststore and krb5.conf, Ranger plugin
 * configuration, ingress certificate objects, the autoscaler's monitoring account), which Helm and Flux never remove
 * since they are not in the release's manifests.
 *
 * <p>Each carries one release selector, {@code app.kubernetes.io/managed-by=ambari-k8s-view} and
 * {@code ambari.clemlab.com/release=<release>}, plus the release's namespace in an annotation (a label value cannot
 * hold every namespace a cluster-scoped object may name). Only objects KDPS wrote get it: an object an administrator
 * provided, or one shared by the releases of a project, is never labelled, so the uninstall of a release removes
 * exactly what KDPS made for it (see {@link KubernetesService#revokeKdpsObjectsForRelease}).
 *
 * <p>Objects another uninstall step removes (the single sign-on and Atlas tag sync Secrets, the SCC RoleBindings)
 * carry the release label with their own {@code managed-by} value: they are found by the same release label, but
 * left to that step, which also takes back what they give access to.
 */
public final class KdpsReleaseObjects {

    /** Label naming what manages the object; with {@link #MANAGED_BY} it marks the objects of this sweep. */
    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY = "ambari-k8s-view";
    /** Label naming the release an object was created for (the same key as the SCC RoleBindings of a release). */
    public static final String LABEL_RELEASE = "ambari.clemlab.com/release";
    /** Annotation naming the release's namespace, which a cluster-scoped object cannot be listed by. */
    public static final String ANNOTATION_NAMESPACE = "ambari.clemlab.com/release-namespace";
    /**
     * Annotation of an object of a release that KDPS cannot prove it created: one an earlier version made without
     * labels, which looks the same as one an administrator made by its instructions. It carries the release label but
     * not the {@code managed-by} one, so the uninstall reports it with the command that deletes it rather than
     * deleting it.
     */
    public static final String ANNOTATION_ORIGIN = "ambari.clemlab.com/kdps-origin";
    public static final String ORIGIN_UNCONFIRMED = "unconfirmed";
    /**
     * Annotation set only when KDPS creates a cert-manager Certificate or an ExternalSecret, never when it writes over
     * one that was there: the {@code managed-by} label alone does not tell them apart, since every write sets it.
     */
    public static final String ANNOTATION_CREATED = "ambari.clemlab.com/kdps-created";
    /** The annotation and label Helm marks the objects of a release with. */
    static final String HELM_RELEASE_NAME = "meta.helm.sh/release-name";
    static final String HELM_RELEASE_NAMESPACE = "meta.helm.sh/release-namespace";
    static final String LABEL_INSTANCE = "app.kubernetes.io/instance";

    /** The kinds the uninstall of a release looks for, in the order they are removed. */
    public static final String CERTIFICATE = "Certificate";
    public static final String EXTERNAL_SECRET = "ExternalSecret";
    public static final String SECRET = "Secret";
    public static final String CONFIG_MAP = "ConfigMap";
    public static final String ROLE_BINDING = "RoleBinding";
    public static final String ROLE = "Role";
    public static final String SERVICE_ACCOUNT = "ServiceAccount";
    public static final String CLUSTER_ROLE_BINDING = "ClusterRoleBinding";

    private KdpsReleaseObjects() {
    }

    /**
     * Whether {@code release} can be a label value: a Helm release name always can. One that cannot gets no labels
     * rather than a write the API server refuses.
     */
    static boolean labelable(String release) {
        return release != null && release.matches("[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?");
    }

    /**
     * The labels of an object KDPS creates for {@code release}, which its uninstall removes; empty when there is no
     * release, so that a shared object is never marked as one release's.
     */
    public static Map<String, String> labels(String release) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (labelable(release)) {
            labels.put(LABEL_MANAGED_BY, MANAGED_BY);
            labels.put(LABEL_RELEASE, release);
        }
        return labels;
    }

    /** {@code base} with the release labels added (see {@link #labels}). */
    public static Map<String, String> withLabels(Map<String, String> base, String release) {
        Map<String, String> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        merged.putAll(labels(release));
        return merged;
    }

    /**
     * {@code base} with the release label only, for an object another uninstall step removes (its own
     * {@code managed-by} value is kept): {@code oc get ... -l ambari.clemlab.com/release=<release>} still lists it.
     */
    public static Map<String, String> withReleaseLabel(Map<String, String> base, String release) {
        Map<String, String> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (labelable(release)) merged.put(LABEL_RELEASE, release);
        return merged;
    }

    /** The annotations of an object KDPS creates for a release of {@code namespace}; empty without a namespace. */
    public static Map<String, String> annotations(String namespace) {
        Map<String, String> annotations = new LinkedHashMap<>();
        if (namespace != null && !namespace.isBlank()) annotations.put(ANNOTATION_NAMESPACE, namespace);
        return annotations;
    }

    /**
     * The release labels an object KDPS writes for {@code release} gets, given the object already there under that
     * name: those of the release when there is none, when it already is the release's, or when KDPS wrote it
     * ({@code writtenByKdps}) before it labelled what it creates and no release claims it. An object someone else
     * made, or one another release claims, gets none, so the uninstall of the release leaves it.
     */
    static Map<String, String> labelsFor(ObjectMeta existing, boolean writtenByKdps, String namespace,
                                         String release) {
        if (existing == null || ofRelease(existing, namespace, release)) return labels(release);
        boolean claimed = existing.getLabels() != null && existing.getLabels().containsKey(LABEL_RELEASE);
        return writtenByKdps && !claimed ? labels(release) : new LinkedHashMap<>();
    }

    /**
     * The release labels to add to an object KDPS wrote and now leaves as it is (a keytab reused, a certificate kept),
     * which an earlier version wrote before it labelled what it creates: those of {@code release} when KDPS wrote it
     * ({@code writtenByKdps}), it lacks them, and no other release claims it, whether by the release label or by
     * {@code releaseLabelKey}, another label naming its release. Empty otherwise, and then nothing is to be changed.
     *
     * <p>Unlike {@link #labelsFor}, a release label naming {@code release} itself does not count as a claim: earlier
     * versions set it, without the {@code managed-by} label, on what they wrote for the release.
     */
    static Map<String, String> missingLabels(ObjectMeta meta, boolean writtenByKdps, String namespace,
                                             String release, String releaseLabelKey) {
        if (meta == null || !writtenByKdps || ofRelease(meta, namespace, release)) return new LinkedHashMap<>();
        Map<String, String> existing = meta.getLabels() == null ? Map.of() : meta.getLabels();
        for (String key : releaseLabelKey == null ? List.of(LABEL_RELEASE) : List.of(LABEL_RELEASE, releaseLabelKey)) {
            String claimedBy = existing.get(key);
            if (claimedBy != null && !claimedBy.isBlank() && !claimedBy.equals(release)) return new LinkedHashMap<>();
        }
        String recorded = meta.getAnnotations() == null ? null : meta.getAnnotations().get(ANNOTATION_NAMESPACE);
        if (recorded != null && !recorded.equals(namespace)) return new LinkedHashMap<>();
        return labels(release);
    }

    /** The label selector of the objects KDPS created for {@code release}, always in the same order. */
    static Map<String, String> selector(String release) {
        Map<String, String> selector = new LinkedHashMap<>();
        selector.put(LABEL_MANAGED_BY, MANAGED_BY);
        selector.put(LABEL_RELEASE, release);
        return selector;
    }

    /**
     * Whether an object carries the release selector of {@code release} of {@code namespace}. An object naming another
     * namespace in its annotation belongs to a release of the same name there.
     */
    static boolean ofRelease(ObjectMeta meta, String namespace, String release) {
        if (meta == null || meta.getLabels() == null || release == null) return false;
        if (!MANAGED_BY.equals(meta.getLabels().get(LABEL_MANAGED_BY))
                || !release.equals(meta.getLabels().get(LABEL_RELEASE))) {
            return false;
        }
        String recorded = meta.getAnnotations() == null ? null : meta.getAnnotations().get(ANNOTATION_NAMESPACE);
        return recorded == null || recorded.equals(namespace);
    }

    /** How an object is named in a report and in the release's record: {@code <Kind>/<name>}. */
    static String ref(String kind, String name) {
        return kind + "/" + name;
    }

    /** The resource name {@code oc} knows a kind by, with its API group where a short name is ambiguous. */
    private static String resource(String kind) {
        return switch (kind) {
            case CERTIFICATE -> "certificate.cert-manager.io";
            case EXTERNAL_SECRET -> "externalsecret.external-secrets.io";
            default -> kind.toLowerCase(Locale.ROOT);
        };
    }

    /** The command that deletes an object an uninstall had to leave. */
    static String deleteCommand(String kind, String name, String namespace) {
        return CLUSTER_ROLE_BINDING.equals(kind)
                ? "oc delete " + resource(kind) + " " + name
                : "oc delete " + resource(kind) + " " + name + " -n " + namespace;
    }

    /** The command that deletes every object of a kind KDPS created for {@code release} in {@code namespace}. */
    static String deleteAllCommand(String kind, String release, String namespace) {
        return "oc delete " + resource(kind) + " -l " + LABEL_MANAGED_BY + "=" + MANAGED_BY + "," + LABEL_RELEASE + "="
                + release + " -n " + namespace;
    }

    /** An object the uninstall of a release could not remove, why, and the command that removes it. */
    public record Leftover(String object, String reason, String command) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("object", object);
            m.put("reason", reason);
            if (command != null) m.put("command", command);
            return m;
        }
    }

    /**
     * What the uninstall of a release removed of the objects KDPS created for it, and what it left.
     *
     * @param removed the objects removed, as {@code <Kind>/<name>}
     * @param left    the objects it could not remove (the account may not delete them, or the API refused)
     * @param kept    the objects it left on purpose, and why: one another release still uses, or one KDPS cannot
     *                prove it created; with the command that deletes it when deleting it is the operator's call
     */
    public record Cleanup(List<String> removed, List<Leftover> left, List<Leftover> kept) {

        public Cleanup {
            removed = List.copyOf(removed);
            left = List.copyOf(left);
            kept = List.copyOf(kept);
        }

        public Cleanup(List<String> removed, List<Leftover> left) {
            this(removed, left, List.of());
        }

        public boolean isEmpty() {
            return removed.isEmpty() && left.isEmpty() && kept.isEmpty();
        }

        /** What the operator reads: what was removed, then what is left, each with the command that removes it. */
        public String note() {
            List<String> lines = new ArrayList<>();
            if (!removed.isEmpty()) {
                lines.add("Removed what KDPS created for the release outside its chart: " + String.join(", ", removed)
                        + ".");
            }
            if (!left.isEmpty()) {
                lines.add("Could not remove " + (left.size() > 1 ? "these objects" : "this object")
                        + " KDPS created for the release; delete " + (left.size() > 1 ? "them" : "it") + " by hand:");
                left.forEach(l -> lines.add(line(l)));
            }
            if (!kept.isEmpty()) {
                lines.add("Left in place:");
                kept.forEach(l -> lines.add(line(l)));
            }
            return String.join("\n", lines);
        }

        private static String line(Leftover l) {
            return "  " + l.object() + " (" + l.reason() + ")" + (l.command() == null ? "" : ": " + l.command());
        }

        /**
         * The report the uninstall answers with: {@code removed}, {@code left}, {@code kept} when there is any, and the
         * {@code note} above.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("removed", removed);
            List<Map<String, Object>> leftovers = new ArrayList<>();
            left.forEach(l -> leftovers.add(l.toMap()));
            m.put("left", leftovers);
            if (!kept.isEmpty()) {
                List<Map<String, Object>> keptObjects = new ArrayList<>();
                kept.forEach(l -> keptObjects.add(l.toMap()));
                m.put("kept", keptObjects);
            }
            m.put("note", note());
            return m;
        }
    }
}
