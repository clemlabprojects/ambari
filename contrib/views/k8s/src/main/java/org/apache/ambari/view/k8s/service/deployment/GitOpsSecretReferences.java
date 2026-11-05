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

package org.apache.ambari.view.k8s.service.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Keeps the secret values of a GitOps deploy with a repository-layout profile out of Git: each one goes to a Secret of
 * the release namespace, and the committed values only name that Secret ({@code StackServiceDef.gitOpsSecretReferences}).
 * Whatever applies the repository then needs nothing but its files, which Argo CD requires: unlike Flux, it cannot
 * merge values kept in a Secret.
 */
final class GitOpsSecretReferences {

    /**
     * A Secret the deploy writes before its commit.
     *
     * @param replace whether its data replaces what the Secret holds (a set of files or variables given whole), rather
     *                than being added to it (keys a later deploy may not carry, the bind password of a resync)
     */
    record SecretToWrite(String name, Map<String, String> data, boolean replace) {}

    /** What applying the rules did: the Secrets to write, and what the committed values now name. */
    record Applied(List<SecretToWrite> secrets, List<String> referenced) {}

    private GitOpsSecretReferences() {
    }

    /**
     * Moves the secret values of {@code values} into Secrets and names those Secrets in their place.
     *
     * @param values       the values about to be committed, changed in place: pass a copy
     * @param rules        the service definition's rules; null or empty for none
     * @param release      the release, which names the Secrets
     * @param chartVersion the chart version deployed, checked against each rule's range
     * @param secretExists whether a Secret of the release namespace exists: a deploy that carries no value for it (a
     *                     resync) still names it, so the chart keeps reading it
     * @param inRange      whether a chart version is in a range
     * @throws IllegalStateException when a rule applies and the chart version cannot read the Secret
     */
    static Applied apply(Map<String, Object> values, List<Map<String, Object>> rules, String release,
                         String chartVersion, Predicate<String> secretExists, BiPredicate<String, String> inRange) {
        Map<String, SecretToWrite> secrets = new LinkedHashMap<>();
        List<String> referenced = new ArrayList<>();
        for (Map<String, Object> rule : rules == null ? List.<Map<String, Object>>of() : rules) {
            if (rule == null || rule.isEmpty() || rule.keySet().stream().allMatch(k -> k.startsWith("_"))) continue;
            if (Boolean.TRUE.equals(rule.get("omit"))) {
                if (rule.get("keys") instanceof Map<?, ?> keys) keys.keySet().forEach(k -> remove(values, String.valueOf(k)));
                continue;
            }
            String name = release + "-" + text(rule.get("secret"));
            Map<String, String> data = new LinkedHashMap<>();
            boolean replace = false;
            if (rule.get("keys") instanceof Map<?, ?> keys) {
                String mode = text(get(values, "global.security.auth.mode")).toLowerCase(java.util.Locale.ROOT);
                for (Map.Entry<?, ?> key : keys.entrySet()) {
                    String path = String.valueOf(key.getKey());
                    Object value = get(values, path);
                    remove(values, path);
                    if (value == null || String.valueOf(value).isBlank()) continue;
                    String secretKey = String.valueOf(key.getValue());
                    // Two paths for one key (the LDAP and the AD bind password): the one of the security mode wins.
                    if (!data.containsKey(secretKey) || path.contains("." + mode + ".")) {
                        data.put(secretKey, String.valueOf(value));
                    }
                }
            } else if (rule.get("map") instanceof String mapPath) {
                replace = true;
                if (get(values, mapPath) instanceof Map<?, ?> entries) {
                    entries.forEach((k, v) -> {
                        if (k != null && v != null) data.put(String.valueOf(k), String.valueOf(v));
                    });
                }
                remove(values, mapPath);
            }
            if (data.isEmpty() && !secrets.containsKey(name) && !secretExists.test(name)) continue;
            String range = text(rule.get("range"));
            if (!range.isBlank() && !inRange.test(chartVersion, range)) {
                String label = text(rule.get("label"));
                throw new IllegalStateException("Chart " + chartVersion + " cannot read "
                        + (label.isBlank() ? "these secret values" : label) + " from a Secret, so they would have to be"
                        + " committed to Git; chart " + range + " can. Pick such a chart version under"
                        + " 'Version (override)'.");
            }
            if (rule.get("reference") instanceof String reference) {
                put(values, reference, name);
            } else if (rule.get("referenceList") instanceof String listPath) {
                List<Object> list = new ArrayList<>();
                if (get(values, listPath) instanceof List<?> existing) list.addAll(existing);
                if (!list.contains(name)) list.add(name);
                put(values, listPath, list);
            }
            if (!referenced.contains(name)) referenced.add(name);
            if (!data.isEmpty()) {
                SecretToWrite before = secrets.get(name);
                Map<String, String> merged = new LinkedHashMap<>(before == null ? Map.of() : before.data());
                merged.putAll(data);
                secrets.put(name, new SecretToWrite(name, merged, replace || (before != null && before.replace())));
            }
        }
        return new Applied(new ArrayList<>(secrets.values()), referenced);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> values, String path) {
        Object node = values;
        for (String segment : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return null;
            node = ((Map<String, Object>) map).get(segment);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void remove(Map<String, Object> values, String path) {
        String[] segments = path.split("\\.");
        Object node = values;
        for (int i = 0; i < segments.length - 1; i++) {
            if (!(node instanceof Map<?, ?> map)) return;
            node = ((Map<String, Object>) map).get(segments[i]);
        }
        if (node instanceof Map<?, ?> map) ((Map<String, Object>) map).remove(segments[segments.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> values, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> node = values;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = node.get(segments[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                node.put(segments[i], next);
            }
            node = (Map<String, Object>) next;
        }
        node.put(segments[segments.length - 1], value);
    }
}
