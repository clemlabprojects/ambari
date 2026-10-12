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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.ambari.view.k8s.model.FormField;
import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.apache.ambari.view.k8s.model.stack.StackProperty;
import org.apache.ambari.view.k8s.service.HelmSetValues;

/**
 * The passwords and secrets of a GitOps deploy, and the split of the chart values into what may be committed to Git
 * and what goes to the release's values Secret, which the HelmRelease reads through {@code spec.valuesFrom}.
 *
 * <p>Which values are secret comes from the definitions only: the password fields of the security profile, of the
 * service form and of the stack configurations, and the values the service's bindings copy those form fields into.
 * Key names are never used: keys such as {@code truststoreSecret} or {@code existingSecret} hold the name of a Secret
 * and must stay readable in Git.
 *
 * <p>The value at a secret path leaves the committed values, and so does any other value that contains one of the
 * secret strings, because bindings copy passwords into environment variables, configuration files and list entries.
 * A list leaves as a whole: Flux replaces lists instead of merging them, so a list split between the Secret and
 * {@code spec.values} would lose the entries of the Secret.
 *
 * <p>Once secret, always secret: what an earlier deploy stored in the values Secret is recorded again on the next one
 * ({@link #addStoredPaths}, {@link #addStoredValues}). A redeploy that re-submits the release's current values
 * carries no form, yet those values hold everything Flux merged in from the Secret.
 */
final class GitOpsSecretValues {

    /** Suffix of the Secret holding the secret values of a HelmRelease. */
    static final String SECRET_SUFFIX = "-kdps-values";
    /** Key of the values document in that Secret, named by {@code valuesFrom[].valuesKey}. */
    static final String VALUES_KEY = "values.yaml";
    /**
     * Shorter secrets are not searched for inside other values or in the files: they would match far too much
     * ("abc" in any word). The value at a secret path moves whatever its length.
     */
    static final int MIN_SEARCHED_LENGTH = 4;

    /** A path segment with an optional list index, read the same way as {@code FluxGitOpsBackend.setAtPath}. */
    private static final Pattern SEGMENT = Pattern.compile("^(?<key>[^\\[\\]]+)(\\[(?<idx>\\d+)])?$");
    /** A {@code ${name}} reference in a binding or variable template, read the same way as the wizard reads it. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private final Set<String> paths = new LinkedHashSet<>();
    /** Paths given key by key, for keys that contain dots (e.g. {@code extraConfigs."import.yaml"}). */
    private final Set<List<String>> keyPaths = new LinkedHashSet<>();
    private final Set<String> secrets = new LinkedHashSet<>();

    /**
     * The values of a deploy split for Git.
     *
     * @param publicValues  what may be written to {@code spec.values}
     * @param secretValues  what goes to the values Secret, with the same nesting
     */
    record Split(Map<String, Object> publicValues, Map<String, Object> secretValues) {
        boolean hasSecrets() {
            return !secretValues.isEmpty();
        }
    }

    /** The name of the Secret holding the secret values of a HelmRelease. */
    static String secretName(String helmRelease) {
        return helmRelease + SECRET_SUFFIX;
    }

    /**
     * A new name for the values Secret of a change proposed in a pull request. The HelmRelease on the cluster keeps
     * reading its own Secret until the change is merged. The suffix is random, never derived from the values.
     */
    static String changeSecretName(String helmRelease) {
        return secretName(helmRelease) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Whether a Secret name is one of the values Secrets of a HelmRelease (its own, or one of a proposed change). */
    static boolean isValuesSecretOf(String helmRelease, String name) {
        String own = secretName(helmRelease);
        return name != null && (name.equals(own) || name.startsWith(own + "-"));
    }

    /**
     * Reads the values document of a values Secret.
     *
     * @throws IOException when it is not a YAML map
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readValuesYaml(String yaml) throws IOException {
        if (yaml == null || yaml.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object doc = new ObjectMapper(new YAMLFactory()).readValue(yaml, Object.class);
        if (doc == null) {
            return new LinkedHashMap<>();
        }
        if (!(doc instanceof Map)) {
            throw new IOException("the values document is not a map");
        }
        return (Map<String, Object>) doc;
    }

    /**
     * The {@code <config>/<property>} keys found in the values, with their values. Stack configuration overrides are
     * written under such keys, and a redeploy that re-submits the release's values carries them back without naming
     * them as overrides. A property name with dots was nested at each dot, as the overrides are applied.
     */
    static Map<String, Object> configOverridesIn(Map<String, Object> values) {
        Map<String, Object> found = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && key.indexOf('/') > 0) {
                    flattenInto(found, key, value);
                }
            });
        }
        return found;
    }

    private static void flattenInto(Map<String, Object> out, String path, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((k, v) -> flattenInto(out, path + "." + k, v));
        } else {
            out.put(path, value);
        }
    }

    /** Records a values path holding a secret (Helm syntax, e.g. {@code global.security.auth.ldap.bindPassword}). */
    void addPath(String path) {
        if (path != null && !path.isBlank()) {
            paths.add(path);
        }
    }

    /** Records a secret value; for a map or a list, every value inside it. Blank values are ignored. */
    void addValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(this::addValue);
        } else if (value instanceof List<?> list) {
            list.forEach(this::addValue);
        } else if (value != null) {
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                secrets.add(text);
            }
        }
    }

    /**
     * Records the overrides that come from password fields of the security profile ({@link HelmSetValues#textIfSecret}).
     * Read before the overrides are applied, since applying them removes the mark.
     */
    void addMarkedOverrides(Map<String, String> overrides) {
        if (overrides == null) {
            return;
        }
        overrides.forEach((path, value) -> {
            if (HelmSetValues.isMarkedSecret(value)) {
                addPath(path);
                addValue(HelmSetValues.typed(value));
            }
        });
    }

    /**
     * Records the password fields of a service form, groups included. A field sent in the values is a secret path
     * (its name); every password typed in the form is a secret value, also those kept out of the values, since
     * bindings copy them elsewhere.
     *
     * @param form       the form of the service definition
     * @param formValues the form as filled in, nested or with dotted keys; may be null
     */
    void addPasswordFields(List<FormField> form, Map<String, Object> formValues) {
        if (form == null) {
            return;
        }
        for (FormField field : form) {
            if (field == null) {
                continue;
            }
            if ("password".equals(field.type) && field.name != null && !field.name.isBlank()) {
                if (!field.excludeFromValues) {
                    addPath(field.name);
                }
                if (formValues != null) {
                    addValue(formValues.get(field.name));
                    addValue(valueAt(formValues, field.name));
                }
            }
            addPasswordFields(field.fields, formValues);
        }
    }

    /**
     * Records where the bindings of a service definition copy its password fields: each value a binding writes from a
     * password field, directly or through a variable built from one. Known from the definition alone, so such a copy
     * stays out of Git even when the deploy carries no form (a redeploy of the release's values, a value typed in the
     * YAML view). A list a binding appends to is secret as a whole; a map merged in is secret entry by entry.
     *
     * @param form      the form of the service definition
     * @param variables the variables of the service definition; may be null
     * @param bindings  the bindings of the service definition; may be null
     */
    void addPasswordCopies(List<FormField> form, List<Map<String, Object>> variables,
                           List<Map<String, Object>> bindings) {
        Set<String> sources = new LinkedHashSet<>();
        collectPasswordFields(form, sources);
        if (sources.isEmpty() || bindings == null) {
            return;
        }
        addPasswordVariables(variables, sources);
        for (Map<String, Object> binding : bindings) {
            if (binding == null || !(binding.get("targets") instanceof List<?> targets)) {
                continue;
            }
            for (Object entry : targets) {
                if (entry instanceof Map<?, ?> target && target.get("path") instanceof String path
                        && target.get("from") instanceof Map<?, ?> from) {
                    addCopyTarget(path, target.get("op"), from, sources);
                }
            }
        }
    }

    private static void collectPasswordFields(List<FormField> form, Set<String> names) {
        if (form == null) {
            return;
        }
        for (FormField field : form) {
            if (field == null) {
                continue;
            }
            if ("password".equals(field.type) && field.name != null && !field.name.isBlank()) {
                names.add(field.name);
            }
            collectPasswordFields(field.fields, names);
        }
    }

    /** Adds to the password sources every variable whose value comes from one of them, also through other variables. */
    private static void addPasswordVariables(List<Map<String, Object>> variables, Set<String> sources) {
        if (variables == null) {
            return;
        }
        boolean added = true;
        while (added) {
            added = false;
            for (Map<String, Object> variable : variables) {
                if (variable != null && variable.get("name") instanceof String name && !sources.contains(name)
                        && readsPassword(variable, sources)) {
                    sources.add(name);
                    added = true;
                }
            }
        }
    }

    private static boolean readsPassword(Map<String, Object> variable, Set<String> sources) {
        if (references(variable.get("template"), sources) || sources.contains(variable.get("overrideField"))) {
            return true;
        }
        if (!(variable.get("from") instanceof Map<?, ?> from)) {
            return false;
        }
        Object type = from.get("type");
        if ("form".equals(type)) {
            return sources.contains(from.get("field"));
        }
        if ("template".equals(type)) {
            return references(from.get("template"), sources);
        }
        // A context value the operator may override with a password field.
        return "context".equals(type) && from.get("overrideFields") instanceof List<?> fields
                && fields.stream().anyMatch(sources::contains);
    }

    private void addCopyTarget(String path, Object op, Map<?, ?> from, Set<String> sources) {
        List<String> keys = bindingKeys(path);
        if (keys.isEmpty()) {
            return;
        }
        Object type = from.get("type");
        if ("form".equals(type) && sources.contains(from.get("field"))
                || "var".equals(type) && sources.contains(from.get("name"))) {
            keyPaths.add(keys);
        } else if ("template".equals(type)) {
            Object template = from.get("template");
            if ("merge".equals(op) && template instanceof Map<?, ?> entries && path.indexOf('[') < 0) {
                // Only the merged entries that hold the password: the map may carry other files or settings.
                entries.forEach((key, value) -> {
                    if (references(value, sources)) {
                        List<String> entryKeys = new ArrayList<>(keys);
                        entryKeys.add(String.valueOf(key));
                        keyPaths.add(List.copyOf(entryKeys));
                    }
                });
            } else if (references(template, sources)) {
                keyPaths.add(keys);
            }
        }
    }

    /**
     * The keys of a binding path down to the first list, which moves as a whole. A dot escaped with a backslash is part
     * of the key, as the wizard reads it.
     */
    static List<String> bindingKeys(String path) {
        List<String> keys = new ArrayList<>();
        for (String segment : path.split("(?<!\\\\)\\.")) {
            String key = segment.replace("\\.", ".");
            int bracket = key.indexOf('[');
            if (bracket >= 0) {
                if (bracket > 0) {
                    keys.add(key.substring(0, bracket));
                }
                break;
            }
            if (key.isBlank()) {
                return List.of();
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    /** Whether a template, or any text inside it, references one of the names. */
    private static boolean references(Object template, Set<String> names) {
        if (template instanceof String text) {
            Matcher m = REFERENCE.matcher(text);
            while (m.find()) {
                if (names.contains(m.group(1).trim())) {
                    return true;
                }
            }
            return false;
        }
        if (template instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(v -> references(v, names));
        }
        if (template instanceof List<?> list) {
            return list.stream().anyMatch(v -> references(v, names));
        }
        return false;
    }

    /**
     * Records the stack configuration overrides of password properties. Their keys are {@code <config>/<property>},
     * which is also their path in the values.
     *
     * @param configs   the configurations of the service
     * @param overrides the overrides of the deploy; may be null
     */
    void addPasswordConfigs(List<StackConfig> configs, Map<String, Object> overrides) {
        if (configs == null || overrides == null) {
            return;
        }
        overrides.forEach((key, value) -> {
            int slash = key == null ? -1 : key.indexOf('/');
            if (slash <= 0) {
                return;
            }
            String configName = key.substring(0, slash);
            String propertyName = key.substring(slash + 1);
            boolean password = configs.stream()
                    .filter(c -> c != null && configName.equals(c.name) && c.properties != null)
                    .flatMap(c -> c.properties.stream())
                    .anyMatch(p -> isPassword(p) && propertyName.equals(p.name));
            if (password) {
                addPath(key);
                addValue(value);
            }
        });
    }

    /** Records a configuration property applied at {@code path}, when it is a password. */
    void addPasswordProperty(String path, StackProperty property) {
        if (isPassword(property)) {
            addPath(path);
            addValue(property.value);
        }
    }

    /** Records the values found at the secret paths, so they are also searched for elsewhere. */
    void addValuesAtPaths(Map<String, Object> values) {
        for (String path : paths) {
            addValue(valueAt(values, path));
        }
        for (List<String> keys : keyPaths) {
            // Scalars only, as for the stored values: the other entries of a list are not secrets.
            Object value = valueAt(values, keys.stream().map(k -> new Segment(k, null)).toList());
            if (!(value instanceof List) && !(value instanceof Map)) {
                addValue(value);
            }
        }
    }

    /**
     * Records every place the release's values Secret holds a value as a secret path: each value outside a map, a
     * list as a whole. Keys are taken as they are, dots included.
     *
     * @param stored the values document of the Secret an earlier deploy wrote; may be null
     */
    void addStoredPaths(Map<String, Object> stored) {
        collectStoredPaths(stored, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private void collectStoredPaths(Map<String, Object> node, List<String> prefix) {
        if (node == null) {
            return;
        }
        node.forEach((key, value) -> {
            List<String> path = new ArrayList<>(prefix);
            path.add(key);
            if (value instanceof Map<?, ?> child && !child.isEmpty()) {
                collectStoredPaths((Map<String, Object>) child, path);
            } else {
                keyPaths.add(List.copyOf(path));
            }
        });
    }

    /**
     * Records the values a values Secret holds outside lists as secret values. List entries are left out: a list moves
     * as a whole when one entry holds a secret, so its other entries ("query.max-memory=4GB") are not secrets.
     *
     * @param stored the values document of the Secret an earlier deploy wrote; may be null
     */
    void addStoredValues(Map<String, Object> stored) {
        if (stored == null) {
            return;
        }
        stored.values().forEach(value -> {
            if (value instanceof Map<?, ?> child) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) child;
                addStoredValues(nested);
            } else if (!(value instanceof List)) {
                addValue(value);
            }
        });
    }

    /** Whether no secret path or value was recorded. */
    boolean isEmpty() {
        return paths.isEmpty() && keyPaths.isEmpty() && secrets.isEmpty();
    }

    Set<String> paths() {
        return Collections.unmodifiableSet(paths);
    }

    Set<List<String>> keyPaths() {
        return Collections.unmodifiableSet(keyPaths);
    }

    Set<String> secrets() {
        return Collections.unmodifiableSet(secrets);
    }

    /**
     * Splits the values of the release: the secret paths, then any value containing a secret.
     *
     * @param values the chart values; not modified
     */
    Split split(Map<String, Object> values) {
        Map<String, Object> publicValues = values == null ? new LinkedHashMap<>() : deepCopy(values);
        Map<String, Object> secretValues = new LinkedHashMap<>();
        for (String path : paths) {
            moveAt(publicValues, secretValues, segments(path));
        }
        for (List<String> keys : keyPaths) {
            moveAt(publicValues, secretValues, keys.stream().map(k -> new Segment(k, null)).toList());
        }
        moveContaining(publicValues, secretValues, List.of(), searched());
        return new Split(publicValues, secretValues);
    }

    /**
     * Splits the values of a dependency, which has no definitions of its own: what its own values Secret held before,
     * then the values containing a secret of the deploy.
     *
     * @param values the dependency's chart values; not modified
     * @param stored the values documents of the dependency's Secrets from an earlier deploy
     */
    Split splitContaining(Map<String, Object> values, List<Map<String, Object>> stored) {
        GitOpsSecretValues own = new GitOpsSecretValues();
        stored.forEach(own::addStoredPaths);
        Map<String, Object> publicValues = values == null ? new LinkedHashMap<>() : deepCopy(values);
        Map<String, Object> secretValues = new LinkedHashMap<>();
        for (List<String> keys : own.keyPaths) {
            moveAt(publicValues, secretValues, keys.stream().map(k -> new Segment(k, null)).toList());
        }
        moveContaining(publicValues, secretValues, List.of(), searched());
        return new Split(publicValues, secretValues);
    }

    /**
     * Whether a text about to be committed contains a secret, as written or as escaped in a double-quoted YAML string.
     */
    boolean leaksInto(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String secret : searched()) {
            if (text.contains(secret) || text.contains(yamlEscaped(secret))) {
                return true;
            }
        }
        return false;
    }

    /** The value at a path in Helm syntax, or null. */
    static Object valueAt(Map<String, Object> values, String path) {
        return valueAt(values, segments(path));
    }

    @SuppressWarnings("unchecked")
    private static Object valueAt(Map<String, Object> values, List<Segment> segments) {
        if (segments.isEmpty()) {
            // Not the whole map: that would make every value a secret.
            return null;
        }
        Object cursor = values;
        for (Segment segment : segments) {
            if (!(cursor instanceof Map)) {
                return null;
            }
            cursor = ((Map<String, Object>) cursor).get(segment.key());
            if (segment.index() != null) {
                if (!(cursor instanceof List<?> list) || segment.index() >= list.size()) {
                    return null;
                }
                cursor = list.get(segment.index());
            }
        }
        return cursor;
    }

    private record Segment(String key, Integer index) {
    }

    private static List<Segment> segments(String path) {
        List<Segment> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (String part : path.split("\\.")) {
            Matcher m = SEGMENT.matcher(part);
            // Skipped like setAtPath skips it, so the value is looked for where it was written.
            if (!m.matches()) {
                continue;
            }
            String idx = m.group("idx");
            out.add(new Segment(m.group("key"), idx == null ? null : Integer.parseInt(idx)));
        }
        return out;
    }

    /**
     * Moves what a path holds; when the path goes through a list, the whole list moves. Parent maps left empty are
     * removed.
     */
    @SuppressWarnings("unchecked")
    private static void moveAt(Map<String, Object> from, Map<String, Object> to, List<Segment> path) {
        List<Map<String, Object>> parents = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Map<String, Object> cursor = from;
        for (int i = 0; i < path.size(); i++) {
            Segment segment = path.get(i);
            parents.add(cursor);
            keys.add(segment.key());
            if (segment.index() != null || i == path.size() - 1) {
                if (cursor.containsKey(segment.key())) {
                    putAt(to, keys, cursor.remove(segment.key()));
                    pruneEmptyParents(parents, keys);
                }
                return;
            }
            Object next = cursor.get(segment.key());
            if (!(next instanceof Map)) {
                return;
            }
            cursor = (Map<String, Object>) next;
        }
    }

    /** {@code parents.get(i)} holds {@code keys.get(i)}; the last key was just removed from the last parent. */
    private static void pruneEmptyParents(List<Map<String, Object>> parents, List<String> keys) {
        for (int i = parents.size() - 1; i > 0 && parents.get(i).isEmpty(); i--) {
            parents.get(i - 1).remove(keys.get(i - 1));
        }
    }

    /** Moves every value, or whole list, that contains a secret. Maps left empty by the moves are removed. */
    @SuppressWarnings("unchecked")
    private static void moveContaining(Map<String, Object> from, Map<String, Object> to, List<String> prefix,
                                       List<String> searched) {
        if (searched.isEmpty()) {
            return;
        }
        for (String key : new ArrayList<>(from.keySet())) {
            Object value = from.get(key);
            List<String> path = new ArrayList<>(prefix);
            path.add(key);
            if (value instanceof Map<?, ?> child) {
                boolean hadEntries = !child.isEmpty();
                moveContaining((Map<String, Object>) child, to, path, searched);
                if (hadEntries && child.isEmpty()) {
                    from.remove(key);
                }
            } else if (containsSecret(value, searched)) {
                putAt(to, path, from.remove(key));
            }
        }
    }

    private static boolean containsSecret(Object node, List<String> searched) {
        if (node instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(v -> containsSecret(v, searched));
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(v -> containsSecret(v, searched));
        }
        if (node == null) {
            return false;
        }
        String text = String.valueOf(node);
        return searched.stream().anyMatch(text::contains);
    }

    private List<String> searched() {
        return secrets.stream().filter(s -> s.length() >= MIN_SEARCHED_LENGTH).toList();
    }

    @SuppressWarnings("unchecked")
    private static void putAt(Map<String, Object> root, List<String> keys, Object value) {
        Map<String, Object> cursor = root;
        for (int i = 0; i < keys.size() - 1; i++) {
            Object next = cursor.get(keys.get(i));
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(keys.get(i), next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(keys.get(keys.size() - 1), value);
    }

    /** A copy made of mutable maps and lists, so moving values never touches the caller's map. */
    @SuppressWarnings("unchecked")
    private static <T> T deepCopy(T node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), deepCopy(v)));
            return (T) copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(deepCopy(v)));
            return (T) copy;
        }
        return node;
    }

    /** The secret as a double-quoted YAML string writes it (without the quotes). */
    private static String yamlEscaped(String secret) {
        return secret.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static boolean isPassword(StackProperty property) {
        return property != null && "password".equals(property.type);
    }
}
