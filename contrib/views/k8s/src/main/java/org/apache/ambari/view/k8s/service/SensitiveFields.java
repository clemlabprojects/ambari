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

    /** The sensitive fields of a service form, and those of them that are passwords. */
    private record ServiceFields(Set<String> sensitive, Set<String> passwords) {
        static final ServiceFields NONE = new ServiceFields(Set.of(), Set.of());
    }

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
                return new ServiceFields(Set.copyOf(of(def.form)), Set.copyOf(passwordsOf(def.form)));
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
