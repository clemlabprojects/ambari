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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The view settings saved from the Configuration page: one JSON document, kept under {@value #SETTINGS_KEY} in the
 * instance settings (monitoring, Kerberos injection mode).
 *
 * <p>The credentials of the object store Thanos writes to ({@code monitoring.thanos.accessKey} and
 * {@code monitoring.thanos.secretKey}) are not kept in that document: they are encrypted with Ambari's master key
 * into settings of their own ({@code monitoring.thanos.<field>}). A document an earlier version saved with them in
 * clear has them moved there the first time it is read. The Configuration page only learns whether each one is set
 * ({@link #readMasked}); sent back masked or blank, the stored one is kept ({@link #save}).</p>
 */
public class ViewSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(ViewSettingsService.class);

    /** The instance setting holding the document. */
    public static final String SETTINGS_KEY = "view.settings.json";

    /** What a read shows in place of a stored credential. */
    public static final String MASKED_SECRET = "********";

    /** The credentials of the Thanos object store, by their name in the {@code thanos} section. */
    static final List<String> THANOS_SECRETS = List.of("accessKey", "secretKey");

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final InstanceSettings settings;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param viewContext the view instance whose settings are read and written
     */
    public ViewSettingsService(ViewContext viewContext) {
        this(new InstanceSettings(viewContext));
    }

    /**
     * @param settings the instance settings holding the document and its credentials
     */
    public ViewSettingsService(InstanceSettings settings) {
        this.settings = settings;
    }

    /** The instance setting holding a credential of the Thanos object store. */
    static String secretKey(String field) {
        return "monitoring.thanos." + field;
    }

    /**
     * The settings with their credentials in clear, for the view's own use: never for a response.
     *
     * @return the document; empty when none is saved
     * @throws IllegalStateException when the document cannot be read, or a credential cannot be decrypted
     */
    public Map<String, Object> read() {
        Map<String, Object> doc = stored();
        for (String field : THANOS_SECRETS) {
            String value = settings.getSecret(secretKey(field));
            if (value != null) {
                thanosSection(doc, true).put(field, value);
            }
        }
        return doc;
    }

    /**
     * The settings as the Configuration page shows them: each stored credential as {@link #MASKED_SECRET}, with a
     * {@code <field>Set} flag (e.g. {@code monitoring.thanos.secretKeySet}) saying whether one is stored.
     *
     * @return the document; empty when none is saved
     * @throws IllegalStateException when the document cannot be read
     */
    public Map<String, Object> readMasked() {
        Map<String, Object> doc = stored();
        boolean anySet = false;
        Map<String, Boolean> set = new LinkedHashMap<>();
        for (String field : THANOS_SECRETS) {
            boolean stored = isSet(settings.get(secretKey(field)));
            set.put(field, stored);
            anySet |= stored;
        }
        Map<String, Object> thanos = thanosSection(doc, anySet);
        if (thanos != null) {
            set.forEach((field, stored) -> {
                thanos.put(field, stored ? MASKED_SECRET : null);
                thanos.put(field + "Set", stored);
            });
        }
        return doc;
    }

    /**
     * Saves the settings. A credential sent masked or blank keeps the stored one; any other value replaces it. A
     * document without a {@code thanos} section drops the stored credentials.
     *
     * @param incoming the settings as the Configuration page sends them
     * @throws IllegalArgumentException      when the document is too large to be saved
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when a credential is given and the
     *                                       Ambari master key was not found; nothing is saved
     */
    public void save(Map<String, Object> incoming) {
        Map<String, Object> doc = copyOf(incoming);
        Map<String, Object> thanos = thanosSection(doc, false);
        Map<String, String> replaced = new LinkedHashMap<>();
        for (String field : THANOS_SECRETS) {
            Object value = thanos == null ? null : thanos.remove(field);
            if (thanos != null) {
                thanos.remove(field + "Set");
            }
            if (value instanceof String text && isSet(text) && !MASKED_SECRET.equals(text)) {
                replaced.put(field, text);
            }
        }
        if (!replaced.isEmpty()) {
            // Checked before anything is saved: the credentials can only be kept encrypted.
            settings.requireMasterKey();
        }
        // The document first: when it is refused for its size, no credential has changed.
        settings.put(SETTINGS_KEY, toJson(doc));
        for (String field : THANOS_SECRETS) {
            if (replaced.containsKey(field)) {
                settings.putSecret(secretKey(field), replaced.get(field));
            } else if (thanos == null) {
                settings.delete(secretKey(field));
            }
        }
    }

    /**
     * The saved document without its credentials. One that still holds them in clear, as earlier versions saved it,
     * has them moved to their encrypted settings first.
     */
    private Map<String, Object> stored() {
        String text = settings.get(SETTINGS_KEY);
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> doc;
        try {
            doc = json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            // Never quote the document: it may hold credentials.
            throw new IllegalStateException("The view settings could not be read: " + e.getOriginalMessage(), e);
        }
        if (doc == null) {
            return new LinkedHashMap<>();
        }
        if (!holdsCredentials(doc)) {
            return doc;
        }
        Map<String, String> clear = removeCredentials(doc);
        for (Map.Entry<String, String> credential : clear.entrySet()) {
            settings.putSecret(secretKey(credential.getKey()), credential.getValue());
        }
        settings.put(SETTINGS_KEY, toJson(doc));
        LOG.info("The view settings kept the Thanos object store credentials in clear; they are now encrypted");
        return doc;
    }

    /**
     * Moves the credentials a document saved by an earlier version holds in clear to their encrypted settings, as the
     * first read does. The upgrade to a new view version calls it at once, so they do not stay in clear until then.
     *
     * @throws IllegalStateException when the document cannot be read
     */
    public void encryptCredentialsKeptInClear() {
        stored();
    }

    /**
     * A settings document without the Thanos object store credentials, for a copy that is never read as the view's
     * settings any more (the one each user kept for themselves before the settings were shared).
     *
     * @param settingsJson the document as saved
     * @return the document without them, or {@code null} when it holds none or cannot be read
     */
    public static String withoutCredentials(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> doc = mapper.readValue(settingsJson, MAP);
            if (doc == null || !holdsCredentials(doc)) {
                return null;
            }
            removeCredentials(doc);
            return mapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Whether a {@code thanos} section of the document holds a credential, or the flag of one. */
    private static boolean holdsCredentials(Map<String, Object> doc) {
        return thanosSections(doc).stream().anyMatch(thanos -> THANOS_SECRETS.stream()
                .anyMatch(field -> thanos.containsKey(field) || thanos.containsKey(field + "Set")));
    }

    /**
     * Removes the credentials and their flags from every {@code thanos} section of the document.
     *
     * @return the credentials of the section the view reads (see {@link #thanosSection}), by field; masks and blanks
     *         left out
     */
    private static Map<String, String> removeCredentials(Map<String, Object> doc) {
        Map<String, Object> read = thanosSection(doc, false);
        Map<String, String> clear = new LinkedHashMap<>();
        for (Map<String, Object> thanos : thanosSections(doc)) {
            for (String field : THANOS_SECRETS) {
                Object value = thanos.remove(field);
                thanos.remove(field + "Set");
                if (thanos == read && value instanceof String text && isSet(text) && !MASKED_SECRET.equals(text)) {
                    clear.put(field, text);
                }
            }
        }
        return clear;
    }

    /**
     * Every {@code thanos} section of the document: under {@code monitoring}, and at the top. Only one is read (see
     * {@link #thanosSection}), but a document may hold both, and credentials are kept in clear in neither.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> thanosSections(Map<String, Object> doc) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (doc.get("monitoring") instanceof Map<?, ?> monitoring && monitoring.get("thanos") instanceof Map<?, ?> thanos) {
            sections.add((Map<String, Object>) thanos);
        }
        if (doc.get("thanos") instanceof Map<?, ?> thanos) {
            sections.add((Map<String, Object>) thanos);
        }
        return sections;
    }

    /**
     * The {@code thanos} section: under {@code monitoring}, or at the top for a document some earlier clients saved
     * without that wrapper (both are read, see {@code KubernetesService#loadMonitoringSettings}).
     *
     * @param create whether to add an empty one under {@code monitoring} when there is none
     * @return the section, or {@code null} when there is none and {@code create} is false
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> thanosSection(Map<String, Object> doc, boolean create) {
        Object monitoring = doc.get("monitoring");
        Map<String, Object> parent = monitoring instanceof Map<?, ?> m ? (Map<String, Object>) m
                : doc.get("thanos") instanceof Map<?, ?> ? doc : null;
        if (parent != null && parent.get("thanos") instanceof Map<?, ?> thanos) {
            return (Map<String, Object>) thanos;
        }
        if (!create) {
            return null;
        }
        if (parent == null) {
            parent = new LinkedHashMap<>();
            doc.put("monitoring", parent);
        }
        Map<String, Object> thanos = new LinkedHashMap<>();
        parent.put("thanos", thanos);
        return thanos;
    }

    private Map<String, Object> copyOf(Map<String, Object> incoming) {
        return incoming == null ? new LinkedHashMap<>() : json.convertValue(incoming, MAP);
    }

    private String toJson(Map<String, Object> doc) {
        try {
            return json.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The view settings could not be saved: " + e.getOriginalMessage(), e);
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
