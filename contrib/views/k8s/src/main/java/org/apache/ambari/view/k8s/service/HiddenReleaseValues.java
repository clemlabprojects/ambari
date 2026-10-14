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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The values of a deployed release as the browser gets them (GET /helm/{namespace}/{release}/values), and back.
 *
 * <p>Every secret among them reads as {@link #PLACEHOLDER}: a text or number under a key named like a secret
 * ({@link #isSecretKey}: password, secret, token...), the value of a sensitive field of the release's service
 * definition, the value such a field is read back from and the values its bindings copy a password into
 * ({@link SensitiveFields#valuePathsOfService}; everything under such a path when it is a list or a map), and a PEM
 * private key wherever it is, as text or Base64. The Releases page and the upgrade wizard send the values back to
 * redeploy the release; the server then puts the deployed value back wherever the placeholder still is
 * ({@link #restore}), so a secret nobody retyped is kept and a retyped one replaces it.</p>
 */
public final class HiddenReleaseValues {

    /** What a hidden value reads as. */
    public static final String PLACEHOLDER = "********";

    /** A key whose value names a Kubernetes Secret rather than holding one: existingSecret, tlsSecret... */
    private static final Pattern SECRET_NAME_KEY = Pattern.compile(
            "(?i).*(existing|tls|keytab|truststore|keystore|cert|certificate|ca)secret");

    /** How a PEM block starts once Base64-encoded, as a chart takes a key to write into a Secret's data. */
    private static final String ENCODED_PEM_START = "LS0tLS1CRUdJTi";

    private HiddenReleaseValues() {
    }

    /**
     * A copy of the values with every secret replaced by {@link #PLACEHOLDER}. An empty text is left as it is: it hides
     * nothing, and a placeholder would show a value that is not there.
     *
     * @param values         the values of a release; may be {@code null}
     * @param sensitivePaths dotted paths of values that are secrets whatever their key
     * @return the copy, or {@code null} for {@code null} values
     */
    public static Map<String, Object> hide(Map<String, Object> values, Collection<String> sensitivePaths) {
        if (values == null) {
            return null;
        }
        return hideMap(values, "", sensitivePaths == null ? Set.of() : new HashSet<>(sensitivePaths));
    }

    /**
     * Whether values sent for a deploy carry {@link #PLACEHOLDER} anywhere: values read back from the release.
     *
     * @param values the values; may be {@code null}
     */
    public static boolean containsPlaceholder(Object values) {
        if (values instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(HiddenReleaseValues::containsPlaceholder);
        }
        if (values instanceof Collection<?> list) {
            return list.stream().anyMatch(HiddenReleaseValues::containsPlaceholder);
        }
        return values instanceof String text && text.contains(PLACEHOLDER);
    }

    /**
     * Puts the deployed value back, in place, wherever values sent for a redeploy still carry {@link #PLACEHOLDER},
     * matched by path: by key, and in a list by the entry's {@code name} when it has one, or else by its position when
     * the list still has as many entries as the deployed one. Values without it are left untouched and the deployed
     * ones are only read when there is one.
     *
     * @param values   the values of the redeploy; may be {@code null}
     * @param deployed reads the values the release is deployed with
     * @throws IllegalArgumentException naming the setting, when the release has no value to put back there, when a
     *                                  setting holds the placeholder within a longer text (a hidden value copied into
     *                                  another setting), or when entries were added to or removed from a list holding
     *                                  a hidden value in an entry without a name; the redeploy must not go on with the
     *                                  placeholder, or another entry's value, as a secret
     */
    public static void restore(Map<String, Object> values, Supplier<Map<String, Object>> deployed) {
        if (values == null || !containsPlaceholder(values)) {
            return;
        }
        Map<String, Object> current = deployed.get();
        restoreMap(values, current == null ? Map.of() : current, "");
    }

    /**
     * The deployed values as a redeploy under the current service definition places them: each value found at the
     * source of one of its {@code valueAliases} is also given at the alias's target, unless the release has a value
     * there already. A release deployed before a value moved (the Superset SECRET_KEY, from {@code extraEnv} to
     * {@code extraSecretEnv}) has it at the old place only, while a redeploy built from its values sends the
     * placeholder at the new one: {@link #restore} finds it there. The deployed values are left unchanged.
     *
     * @param deployed the values the release is deployed with; may be {@code null}
     * @param aliases  the {@code valueAliases} of the release's service definition, dotted source to dotted target;
     *                 may be {@code null}
     * @return the values with the alias targets added, or the deployed values themselves when there is nothing to add
     */
    public static Map<String, Object> withAliasTargets(Map<String, Object> deployed, Map<String, String> aliases) {
        if (deployed == null || aliases == null || aliases.isEmpty()) {
            return deployed;
        }
        Map<String, Object> out = null;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            String from = alias.getKey();
            String to = alias.getValue();
            if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) {
                continue;
            }
            Object value = valueAt(deployed, from);
            if (value == null || valueAt(deployed, to) != null) {
                continue;
            }
            if (out == null) {
                out = copyOf(deployed);
            }
            putAt(out, to, value);
        }
        return out == null ? deployed : out;
    }

    private static Object valueAt(Map<String, Object> values, String dottedPath) {
        Object node = values;
        for (String key : dottedPath.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void putAt(Map<String, Object> values, String dottedPath, Object value) {
        String[] keys = dottedPath.split("\\.");
        Map<String, Object> parent = values;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = parent.get(keys[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                parent.put(keys[i], next);
            }
            parent = (Map<String, Object>) next;
        }
        parent.put(keys[keys.length - 1], value);
    }

    /** A copy of the maps of the values, deep enough for {@link #putAt} to leave the original unchanged. */
    private static Map<String, Object> copyOf(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v instanceof Map<?, ?> nested ? copyOf(nested) : v));
        return copy;
    }

    private static Map<String, Object> hideMap(Map<?, ?> map, String prefix, Set<String> paths) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            String key = String.valueOf(k);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            out.put(key, hideValue(key, path, v, paths));
        });
        return out;
    }

    /** The value hidden; an item of a list is judged by the key of the list. */
    private static Object hideValue(String key, String path, Object value, Set<String> paths) {
        if (paths.contains(path) && (value instanceof Map || value instanceof List)) {
            return hideAll(value);
        }
        if (value instanceof Map<?, ?> map) {
            return hideMap(map, path, paths);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(hideValue(key, path + "[" + i + "]", list.get(i), paths));
            }
            return out;
        }
        if (value instanceof String text && !text.isEmpty()
                && (isSecretKey(key) || paths.contains(path) || containsPrivateKey(text))) {
            return PLACEHOLDER;
        }
        if (value instanceof Number && (isSecretKey(key) || paths.contains(path))) {
            return PLACEHOLDER;
        }
        return value;
    }

    /**
     * Whether a value under this key is hidden by the key's name: the secret names of {@link HelmSetValues#isSecretKey}
     * but two. The bare key {@code key} is that of tolerations, affinity terms, the {@code secretKeyRef} of an env
     * entry and the items of a mounted ConfigMap, none of which is a secret (a private key under it is still found by
     * its content). And a key such as {@code existingSecret} or {@code tlsSecret} names a Secret.
     */
    static boolean isSecretKey(String key) {
        return HelmSetValues.isSecretKey(key) && !"key".equalsIgnoreCase(key)
                && !SECRET_NAME_KEY.matcher(key).matches();
    }

    /** Whether a text holds a PEM private key, as it is or Base64-encoded. */
    static boolean containsPrivateKey(String text) {
        if (HelmSetValues.containsPrivateKey(text)) {
            return true;
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith(ENCODED_PEM_START)) {
            return false;
        }
        try {
            return HelmSetValues.containsPrivateKey(
                    new String(Base64.getMimeDecoder().decode(trimmed), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    /** A list or map with every text and number under it hidden: a binding writes a password somewhere in it. */
    private static Object hideAll(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), hideAll(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(hideAll(item)));
            return out;
        }
        if (value instanceof String text && !text.isEmpty() || value instanceof Number) {
            return PLACEHOLDER;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static void restoreMap(Map<String, Object> values, Object deployed, String prefix) {
        Map<?, ?> deployedMap = deployed instanceof Map<?, ?> map ? map : Map.of();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object restored = restoreValue(entry.getValue(), deployedMap.get(entry.getKey()), path);
            if (restored != entry.getValue()) {
                entry.setValue(restored);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object restoreValue(Object value, Object deployed, String path) {
        if (value instanceof Map<?, ?> map) {
            restoreMap((Map<String, Object>) map, deployed, path);
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> items = (List<Object>) list;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (!containsPlaceholder(item)) {
                    continue;
                }
                // No deployed list there: no value to put back, which the entry's hidden value then says.
                Object deployedItem = deployed instanceof List<?> deployedList
                        ? deployedEntry(item, i, items.size(), deployedList, path) : null;
                Object restored = restoreValue(item, deployedItem, path + "[" + i + "]");
                if (restored != item) {
                    items.set(i, restored);
                }
            }
            return value;
        }
        if (!(value instanceof String text) || !text.contains(PLACEHOLDER)) {
            return value;
        }
        if (PLACEHOLDER.equals(text)) {
            if (deployed == null || deployed instanceof Map || deployed instanceof Collection) {
                throw new IllegalArgumentException("The value of '" + path + "' was hidden when the values of the"
                        + " release were read, and the release has no value there to keep. Type the value again.");
            }
            return deployed;
        }
        if (!text.equals(deployed)) {
            throw new IllegalArgumentException("The value of '" + path + "' contains a hidden value (" + PLACEHOLDER
                    + ") read from the release, which cannot be put back inside a longer value. Type it in full.");
        }
        return value;
    }

    /**
     * The deployed entry of a list a hidden value of the sent entry is put back from. An entry with a {@code name}
     * (an env variable, a container, a volume) is the deployed entry of that name, wherever it now is in the list;
     * {@code null} when there is none, which refuses the hidden value. Any other entry is the deployed one at the same
     * position, and only while the list has as many entries as the deployed one: once an entry was added or removed
     * ahead of it, the one at its position is another entry, whose value would be deployed in its place.
     */
    private static Object deployedEntry(Object item, int index, int size, List<?> deployedList, String listPath) {
        if (item instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.contains(PLACEHOLDER)) {
            List<Object> named = new ArrayList<>();
            for (Object entry : deployedList) {
                if (entry instanceof Map<?, ?> deployedMap && name.equals(deployedMap.get("name"))) {
                    named.add(entry);
                }
            }
            if (named.size() <= 1) {
                return named.isEmpty() ? null : named.get(0);
            }
            // Several deployed entries share the name: only the position tells them apart.
        }
        if (size != deployedList.size()) {
            throw new IllegalArgumentException("Entries were added to or removed from '" + listPath + "' since the"
                    + " values of the release were read, so the hidden values (" + PLACEHOLDER + ") in it can no longer"
                    + " be matched to the deployed ones. Type the hidden values of this list again.");
        }
        return deployedList.get(index);
    }
}
