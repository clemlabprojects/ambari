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

import org.apache.ambari.view.k8s.model.FormField;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fields of the wizard that hold a secret, read from the definitions:
 * <ul>
 *   <li>the form fields of a service definition of type {@code password} or {@code content} (a pasted file, such as
 *       the private key of an uploaded TLS certificate);</li>
 *   <li>its text fields flagged {@code "sensitive": true}, for a secret that is not typed as a password (an S3 access
 *       key, free-text catalog properties that may carry a connection password);</li>
 *   <li>the password fields of the security profile ({@code type: "password"} in KDPS/globals/security.json, e.g.
 *       {@code ldap.bindPassword}).</li>
 * </ul>
 * A field is named by its dotted path in the form values (e.g. {@code ingress.tlsUpload.key}).
 */
public final class SensitiveFields {

    private static final Logger LOG = LoggerFactory.getLogger(SensitiveFields.class);

    /**
     * The sensitive fields of a service form, those of them that are passwords, the chart values they are read back
     * from ({@code seedFromValues}) when they are kept out of the values themselves, and the chart values its bindings
     * copy its passwords into ({@link #passwordCopyTargets}, keys joined with dots).
     */
    private record ServiceFields(Set<String> sensitive, Set<String> passwords, Set<String> seededFrom,
                                 Set<String> passwordCopies) {
        static final ServiceFields NONE = new ServiceFields(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** A {@code ${name}} reference in a binding or variable template, read the same way as the wizard reads it. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static final StackDefinitionService DEFINITIONS = new StackDefinitionService();
    private static final Map<String, ServiceFields> BY_SERVICE = new ConcurrentHashMap<>();

    private static volatile Set<String> securityProfilePasswords;
    private static final AtomicBoolean warnedUnreadable = new AtomicBoolean();

    private SensitiveFields() {
    }

    /** Whether the form field holds a secret: a password, a pasted file, or a field flagged sensitive. */
    public static boolean isSensitive(FormField field) {
        return field != null && (isPassword(field) || "content".equals(field.type) || field.sensitive);
    }

    /** Whether the form field is a password ({@code type: "password"}). */
    public static boolean isPassword(FormField field) {
        return field != null && "password".equals(field.type);
    }

    /**
     * The sensitive fields of a form, groups included.
     *
     * @param form the form of a service definition, may be {@code null}
     * @return their dotted names
     */
    public static Set<String> of(List<FormField> form) {
        Set<String> names = new LinkedHashSet<>();
        collect(form, names, false);
        return names;
    }

    /**
     * The password fields of a form, groups included.
     *
     * @param form the form of a service definition, may be {@code null}
     * @return their dotted names
     */
    public static Set<String> passwordsOf(List<FormField> form) {
        Set<String> names = new LinkedHashSet<>();
        collect(form, names, true);
        return names;
    }

    /**
     * The sensitive fields of a service's form.
     *
     * @param serviceKey the service (e.g. {@code TRINO})
     * @return their dotted names; none for an unknown service
     */
    public static Set<String> ofService(String serviceKey) {
        return fieldsOf(serviceKey).sensitive();
    }

    /**
     * The password fields of a service's form.
     *
     * @param serviceKey the service (e.g. {@code SUPERSET})
     * @return their dotted names; none for an unknown service
     */
    public static Set<String> passwordsOfService(String serviceKey) {
        return fieldsOf(serviceKey).passwords();
    }

    /**
     * The chart values of a service that hold what its sensitive fields hold: the fields themselves, the values a
     * field kept out of the values is read back from ({@code seedFromValues}, e.g. the Superset SECRET_KEY in
     * {@code extraSecretEnv.SUPERSET_SECRET_KEY}), and the values its bindings copy a password into
     * ({@link #passwordCopyTargets}, e.g. the Superset datasource file whose URI carries the Hive LDAP password). A
     * copy target is given with its keys joined by dots, whatever dots the keys hold themselves
     * ({@code extraConfigs.import_datasources.yaml}); one that is a list (a binding appends to it) is secret as a
     * whole.
     *
     * @param serviceKey the service (e.g. {@code SUPERSET}); may be {@code null}
     * @return their dotted paths; none for an unknown service
     */
    public static Set<String> valuePathsOfService(String serviceKey) {
        ServiceFields fields = fieldsOf(serviceKey);
        Set<String> paths = new LinkedHashSet<>(fields.sensitive());
        paths.addAll(fields.seededFrom());
        paths.addAll(fields.passwordCopies());
        return paths;
    }

    /**
     * Where the bindings of a service definition copy its password fields: each chart value a binding writes from a
     * password field, directly or through a variable built from one. A list a binding appends to is secret as a
     * whole; a map merged in is secret entry by entry, only the entries that hold a password.
     *
     * @param form      the form of the service definition; may be null
     * @param variables the variables of the service definition; may be null
     * @param bindings  the bindings of the service definition; may be null
     * @return the keys of each such value, down to the first list ({@link #bindingKeys})
     */
    public static Set<List<String>> passwordCopyTargets(List<FormField> form, List<Map<String, Object>> variables,
                                                        List<Map<String, Object>> bindings) {
        Set<String> sources = passwordsOf(form);
        Set<List<String>> targets = new LinkedHashSet<>();
        if (sources.isEmpty() || bindings == null) {
            return targets;
        }
        addPasswordVariables(variables, sources);
        for (Map<String, Object> binding : bindings) {
            if (binding == null || !(binding.get("targets") instanceof List<?> list)) {
                continue;
            }
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> target && target.get("path") instanceof String path
                        && target.get("from") instanceof Map<?, ?> from) {
                    addCopyTarget(targets, path, target.get("op"), from, sources);
                }
            }
        }
        return targets;
    }

    /**
     * The keys of a binding path down to the first list, which is taken as a whole. A dot escaped with a backslash is
     * part of the key, as the wizard reads it.
     *
     * @param path a binding target path (e.g. {@code conf\.d.file}, {@code coordinator.additionalConfigProperties[]})
     * @return its keys; none for a path that names no key
     */
    public static List<String> bindingKeys(String path) {
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

    private static void addCopyTarget(Set<List<String>> targets, String path, Object op, Map<?, ?> from,
                                      Set<String> sources) {
        List<String> keys = bindingKeys(path);
        if (keys.isEmpty()) {
            return;
        }
        Object type = from.get("type");
        if ("form".equals(type) && sources.contains(from.get("field"))
                || "var".equals(type) && sources.contains(from.get("name"))) {
            targets.add(keys);
        } else if ("template".equals(type)) {
            Object template = from.get("template");
            if ("merge".equals(op) && template instanceof Map<?, ?> entries && path.indexOf('[') < 0) {
                // Only the merged entries that hold the password: the map may carry other files or settings.
                entries.forEach((key, value) -> {
                    if (references(value, sources)) {
                        List<String> entryKeys = new ArrayList<>(keys);
                        entryKeys.add(String.valueOf(key));
                        targets.add(List.copyOf(entryKeys));
                    }
                });
            } else if (references(template, sources)) {
                targets.add(keys);
            }
        }
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

    /** The security profile fields of type password, read once from KDPS/globals/security.json. */
    public static Set<String> ofSecurityProfile() {
        Set<String> fields = securityProfilePasswords;
        if (fields != null) {
            return fields;
        }
        try (InputStream in = SensitiveFields.class.getClassLoader().getResourceAsStream("KDPS/globals/security.json")) {
            if (in == null) {
                throw new FileNotFoundException("not on the classpath");
            }
            Set<String> found = new HashSet<>();
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).path("properties").forEach(p -> {
                if ("password".equals(p.path("type").asText()) && !p.path("name").asText().isBlank()) {
                    found.add(p.path("name").asText());
                }
            });
            fields = Set.copyOf(found);
            securityProfilePasswords = fields;
            return fields;
        } catch (Exception e) {
            // Not remembered, so a later call tries again; meanwhile nothing is a password. Warned once.
            if (warnedUnreadable.compareAndSet(false, true)) {
                LOG.warn("Could not read the password fields of KDPS/globals/security.json: {}", e.toString());
            }
            return Set.of();
        }
    }

    /**
     * A copy of form values without the given fields. The values may be nested ({@code {"ingress": {"tlsUpload":
     * {"key": ...}}}}, as the wizard sends them) or keyed by dotted names; both are removed. The values passed in
     * are left unchanged.
     *
     * @param values form values, may be {@code null}
     * @param paths  dotted names of the fields to remove
     * @return the copy, or {@code null} for {@code null} values
     */
    public static Map<String, Object> without(Map<String, Object> values, Collection<String> paths) {
        if (values == null) {
            return null;
        }
        Map<String, Object> copy = deepCopy(values);
        if (paths != null) {
            for (String path : paths) {
                if (path != null && !path.isBlank()) {
                    remove(copy, path.split("\\."), 0);
                }
            }
        }
        return copy;
    }

    private static ServiceFields fieldsOf(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return ServiceFields.NONE;
        }
        return BY_SERVICE.computeIfAbsent(serviceKey.trim().toUpperCase(Locale.ROOT), key -> {
            try {
                StackServiceDef def = DEFINITIONS.getServiceDefinition(key);
                Set<String> seededFrom = new LinkedHashSet<>();
                collectSeeds(def.form, seededFrom);
                Set<String> copies = new LinkedHashSet<>();
                passwordCopyTargets(def.form, def.variables, def.bindings).forEach(keys -> copies.add(String.join(".", keys)));
                return new ServiceFields(Set.copyOf(of(def.form)), Set.copyOf(passwordsOf(def.form)),
                        Set.copyOf(seededFrom), Set.copyOf(copies));
            } catch (RuntimeException unknown) {
                LOG.debug("No service definition for {}: none of its fields is known to be sensitive", key);
                return ServiceFields.NONE;
            }
        });
    }

    private static void collect(List<FormField> form, Set<String> names, boolean passwordsOnly) {
        if (form == null) {
            return;
        }
        for (FormField field : form) {
            if (field == null) {
                continue;
            }
            boolean wanted = passwordsOnly ? isPassword(field) : isSensitive(field);
            if (wanted && field.name != null && !field.name.isBlank()) {
                names.add(field.name);
            }
            collect(field.fields, names, passwordsOnly);
        }
    }

    /** The {@code seedFromValues} paths of the sensitive fields of a form, groups included. */
    private static void collectSeeds(List<FormField> form, Set<String> paths) {
        if (form == null) {
            return;
        }
        for (FormField field : form) {
            if (field == null) {
                continue;
            }
            if (isSensitive(field) && field.seedFromValues != null) {
                field.seedFromValues.stream().filter(p -> p != null && !p.isBlank()).forEach(paths::add);
            }
            collectSeeds(field.fields, paths);
        }
    }

    /** Removes the path from {@code segments[from]} on, whichever of its dots are nesting and which are in a key. */
    @SuppressWarnings("unchecked")
    private static void remove(Map<String, Object> map, String[] segments, int from) {
        StringBuilder key = new StringBuilder();
        for (int i = from; i < segments.length; i++) {
            if (i > from) {
                key.append('.');
            }
            key.append(segments[i]);
            String k = key.toString();
            if (i == segments.length - 1) {
                map.remove(k);
            } else if (map.get(k) instanceof Map<?, ?> nested) {
                remove((Map<String, Object>) nested, segments, i + 1);
            }
        }
    }

    private static Map<String, Object> deepCopy(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(k, copyValue(v)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), copyValue(v)));
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            l.forEach(v -> copy.add(copyValue(v)));
            return copy;
        }
        return value;
    }
}
