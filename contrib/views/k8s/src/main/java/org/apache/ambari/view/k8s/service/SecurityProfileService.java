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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Persistence helper for security profiles: one {@link SecurityProfileEntity} row per profile.
 *
 * <p>Every change writes or removes single rows (the DataStore's {@code store} is an upsert), never a purge followed
 * by a rewrite: each DataStore call is its own transaction, so a rewrite that fails halfway, or that runs on a read
 * that missed rows, loses profiles. The writers of one view instance take {@link #writeLock()} around their
 * read-modify-write so they cannot interleave.</p>
 *
 * <p>The secrets of a profile (the LDAP and Active Directory bind passwords, the OIDC client secret) are not kept in
 * its row: they are encrypted with Ambari's master key into the view settings ({@link InstanceSettings}), under
 * {@code secprofile.<profile>.<field>}. A row written by an earlier version with them in clear is moved there the
 * first time the profiles are loaded. Only {@link #resolveProfile} and {@link #requireProfile}, which deploys use,
 * give a profile with its secrets; a read for the browser only says which ones are set
 * ({@link #loadMaskedProfiles}).</p>
 */
public class SecurityProfileService {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityProfileService.class);

    /**
     * Longest serialized profile the DataStore accepts: Ambari's view persistence caps every String column at 3000
     * characters, whatever the entity's {@code @Column(length)} says.
     */
    public static final int MAX_PROFILE_JSON_LENGTH = 3000;

    /** One lock per view instance, shared by every writer of its profiles. */
    private static final ConcurrentMap<String, Object> WRITE_LOCKS = new ConcurrentHashMap<>();

    /**
     * What a read of the profiles shows in place of a stored secret. Sent back unchanged, or left blank, it keeps the
     * stored secret.
     */
    public static final String MASKED_SECRET = "********";

    /** Prefix of the view settings holding the profiles' secrets: {@code secprofile.<profile>.<field>}. */
    static final String SECRET_KEY_PREFIX = "secprofile.";

    /**
     * The view setting holding the key of the secrets' share of the profile fingerprints ({@link #fingerprint}):
     * random, made the first time it is needed, encrypted with Ambari's master key like the secrets.
     */
    static final String FINGERPRINT_KEY_SETTING = "secprofile-fingerprint.key";

    /**
     * A field of a profile that holds a secret, with how to read and write it, how to flag it as set on a read, and
     * whether the profile has the section it belongs to.
     */
    private record SecretField(String name, Function<SecurityConfigDTO, String> get,
                               BiConsumer<SecurityConfigDTO, String> set, BiConsumer<SecurityConfigDTO, Boolean> flag,
                               Predicate<SecurityConfigDTO> inProfile) {
    }

    /** The password fields of KDPS/globals/security.json. */
    private static final List<SecretField> SECRET_FIELDS = List.of(
            new SecretField("ldap.bindPassword", p -> p.ldap == null ? null : p.ldap.bindPassword,
                    (p, v) -> p.ldap.bindPassword = v, (p, set) -> p.ldap.bindPasswordSet = set, p -> p.ldap != null),
            new SecretField("ldap.adBindPassword", p -> p.ldap == null ? null : p.ldap.adBindPassword,
                    (p, v) -> p.ldap.adBindPassword = v, (p, set) -> p.ldap.adBindPasswordSet = set,
                    p -> p.ldap != null),
            new SecretField("oidc.clientSecret", p -> p.oidc == null ? null : p.oidc.clientSecret,
                    (p, v) -> p.oidc.clientSecret = v, (p, set) -> p.oidc.clientSecretSet = set, p -> p.oidc != null));

    private final ViewContext viewContext;
    private InstanceSettings settings;
    /** Reads rows written by other versions of the view: a field it does not know is ignored, not fatal. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SecurityProfileService(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    /**
     * A stored profile row of this view instance.
     *
     * @param id        the profile name
     * @param json      the profile as stored
     * @param isDefault whether the row is marked as the default profile
     * @param profile   the profile read from {@code json}; {@code null} when it cannot be read
     */
    record StoredProfile(String id, String json, boolean isDefault, SecurityConfigDTO profile) {
    }

    /**
     * Refusal to remove security profiles that releases still use: their next deploy would have no profile to apply.
     */
    public static final class ProfilesInUseException extends RuntimeException {
        private final Map<String, List<String>> releasesByProfile;

        ProfilesInUseException(Map<String, List<String>> releasesByProfile) {
            super(describe(releasesByProfile));
            this.releasesByProfile = Collections.unmodifiableMap(new LinkedHashMap<>(releasesByProfile));
        }

        /** The releases ({@code namespace/release}) that use each profile the request would have removed. */
        public Map<String, List<String>> releasesByProfile() {
            return releasesByProfile;
        }

        private static String describe(Map<String, List<String>> releasesByProfile) {
            List<String> parts = new ArrayList<>();
            releasesByProfile.forEach((profile, releases) -> parts.add("Security profile '" + profile
                    + "' is still used by " + String.join(", ", releases) + "."));
            return String.join(" ", parts) + " Nothing was saved: keep the profile, or deploy these releases with"
                    + " another profile before removing it.";
        }
    }

    /** The lock the writers of this view instance's profiles hold around their read-modify-write. */
    Object writeLock() {
        return WRITE_LOCKS.computeIfAbsent(String.valueOf(viewContext.getInstanceName()), k -> new Object());
    }

    /**
     * Load all stored security profiles for the current view instance, without their secrets (see
     * {@link #resolveProfile} for a profile with them). A row that cannot be read is left out, its name logged, and the
     * result marked {@link SecurityProfilesDTO#incomplete}. A row that still holds secrets in clear, as earlier
     * versions wrote it, has them moved to the encrypted view settings first; should that fail, the profile is given as
     * read, never to the browser (see {@link #loadMaskedProfiles}).
     *
     * @return security profiles DTO containing the default profile name and the map of profiles
     * @throws IllegalStateException when the profiles cannot be read from the DataStore: an empty set would read as
     *                               "no profiles" to the caller
     */
    public SecurityProfilesDTO loadProfiles() {
        SecurityProfilesDTO dto = new SecurityProfilesDTO();
        Map<String, SecurityConfigDTO> profiles = new HashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (SecurityProfileEntity entity : storedRows()) {
            StoredProfile stored = toStored(entity);
            if (stored.profile() == null) {
                unreadable.add(entity.getId());
                continue;
            }
            SecurityConfigDTO profile = stored.profile();
            if (holdsSecrets(profile)) {
                SecurityConfigDTO moved = moveSecretsOutOfRow(entity.getId());
                if (moved != null) {
                    profile = moved;
                }
            }
            profiles.put(entity.getId(), profile);
            if (entity.isDefault()) {
                dto.defaultProfile = entity.getId();
            }
        }
        dto.incomplete = !unreadable.isEmpty();
        dto.profiles = profiles;
        if (dto.defaultProfile == null && !profiles.isEmpty()) {
            dto.defaultProfile = profiles.keySet().iterator().next();
        }
        return dto;
    }

    /**
     * The profiles as the browser may see them: each stored secret shows as {@link #MASKED_SECRET}, with its
     * {@code <field>Set} flag (e.g. {@code ldap.bindPasswordSet}) saying whether one is stored. Nothing is decrypted.
     *
     * @return the profiles, as {@link #loadProfiles} reads them
     * @throws IllegalStateException when the profiles cannot be read
     */
    public SecurityProfilesDTO loadMaskedProfiles() {
        SecurityProfilesDTO dto = loadProfiles();
        dto.profiles.forEach((id, profile) -> {
            for (SecretField field : SECRET_FIELDS) {
                if (field.inProfile().test(profile)) {
                    boolean set = isSet(settings().get(secretKey(id, field.name())))
                            || isSet(field.get().apply(profile));
                    field.set().accept(profile, set ? MASKED_SECRET : null);
                    field.flag().accept(profile, set);
                }
            }
        });
        return dto;
    }

    /**
     * Persist the profiles of the Global Security page: each given profile is stored (an upsert), then the stored
     * profiles the request no longer lists are removed. Nothing is written when a profile is too large to be stored,
     * when the stored profiles cannot be read, or when a profile to remove is still used by a release.
     *
     * <p>A secret sent as {@link #MASKED_SECRET} or left blank keeps the one stored for the profile (the page never
     * has it to send back); any other value replaces it. A profile without an LDAP or OIDC section loses that
     * section's stored secrets.</p>
     *
     * <p>Only a profile the sender saw can be removed by leaving it out: one missing from
     * {@link SecurityProfilesDTO#loadedProfiles} (when given) was created after the sender read the profiles, or
     * never shown to it, and stays. So does a stored profile that cannot be read, and a profile derived from a
     * platform context, which that context's reconcile owns (an explicit delete of that one profile still works).</p>
     *
     * @param profiles the DTO containing all profiles to keep, including the default profile name
     * @throws IllegalArgumentException when a profile has no name, no content or is too large to be stored
     * @throws ProfilesInUseException   when a profile the request leaves out is used by a release
     * @throws IllegalStateException    when the stored profiles cannot be read or a row cannot be written
     */
    public void saveProfiles(SecurityProfilesDTO profiles) {
        if (profiles == null || profiles.profiles == null) {
            return;
        }
        // Everything that can be checked is checked before the first write: a refusal leaves every profile as it was.
        Map<String, String> jsonById = new LinkedHashMap<>();
        for (Map.Entry<String, SecurityConfigDTO> entry : profiles.profiles.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Every security profile needs a name.");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("The security profile '" + id + "' has no settings.");
            }
            jsonById.put(id, serializeForStore(id, withoutSecrets(entry.getValue())));
        }

        synchronized (writeLock()) {
            SecurityProfilesDTO current = loadProfiles();
            List<String> removed = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (String id : new TreeSet<>(current.profiles.keySet())) {
                if (jsonById.containsKey(id)) {
                    continue;
                }
                boolean seen = profiles.loadedProfiles == null || profiles.loadedProfiles.contains(id);
                if (seen && !isDerived(current.profiles.get(id))) {
                    removed.add(id);
                } else {
                    kept.add(id);
                }
            }
            Map<String, List<String>> inUse = new LinkedHashMap<>();
            if (!removed.isEmpty()) {
                ReleaseMetadataService releases = new ReleaseMetadataService(viewContext);
                for (String id : removed) {
                    List<String> users = releases.findReleasesUsingProfile(id);
                    if (!users.isEmpty()) {
                        inUse.put(id, users);
                    }
                }
            }
            if (!inUse.isEmpty()) {
                throw new ProfilesInUseException(inUse);
            }

            for (Map.Entry<String, String> entry : jsonById.entrySet()) {
                String id = entry.getKey();
                storeSecrets(id, profiles.profiles.get(id), current.profiles.get(id));
                storeRow(id, entry.getValue(), id.equals(profiles.defaultProfile));
            }
            // Removed only once every profile is stored: when a store fails, no profile has been removed yet.
            for (String id : removed) {
                removeStored(id);
            }
            if (jsonById.containsKey(profiles.defaultProfile)) {
                clearDefaultFlagExcept(profiles.defaultProfile);
            }
            LOG.info("Persisted {} security profiles (default={}), removed {}, kept {} the request did not list",
                    jsonById.size(), profiles.defaultProfile, removed, kept);
        }
    }

    /**
     * Resolve a profile by name or default/first, with its secrets decrypted: for the deploys that apply it, never for
     * the browser.
     *
     * @param requestedProfile profile name requested by caller; may be null/blank to use the default
     * @return the resolved security configuration, or null when none exist
     * @throws IllegalStateException when the profiles cannot be read, or the profile's secrets cannot be decrypted
     */
    public SecurityConfigDTO resolveProfile(String requestedProfile) {
        SecurityProfilesDTO profiles = loadProfiles();
        if (profiles == null || profiles.profiles == null || profiles.profiles.isEmpty()) {
            return null;
        }
        String profileName = requestedProfile;
        if (profileName == null || profileName.isBlank()) {
            profileName = profiles.defaultProfile;
        }
        if (profileName == null || profileName.isBlank()) {
            profileName = profiles.profiles.keySet().stream().findFirst().orElse(null);
        }
        SecurityConfigDTO cfg = profileName != null ? profiles.profiles.get(profileName) : null;
        LOG.info("Loaded security profile '{}' with mode {}", profileName, cfg != null ? cfg.mode : "none");
        return cfg == null ? null : withSecrets(profileName, cfg);
    }

    /**
     * The profile a deploy names, which the deploy must not go without: a release deployed without its profile
     * silently loses its LDAP or OIDC login settings.
     *
     * @param profileName the profile the deploy asks for
     * @return the profile, with its secrets decrypted
     * @throws IllegalArgumentException when no readable profile has that name
     * @throws IllegalStateException    when the profiles cannot be read, or the profile's secrets cannot be decrypted
     */
    public SecurityConfigDTO requireProfile(String profileName) {
        SecurityProfilesDTO profiles;
        try {
            profiles = loadProfiles();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("The security profile '" + profileName + "' that this deploy asks for"
                    + " could not be read, so nothing was deployed. " + e.getMessage(), e);
        }
        SecurityConfigDTO cfg = profiles.profiles.get(profileName);
        if (cfg != null) {
            try {
                return withSecrets(profileName, cfg);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("The security profile '" + profileName + "' that this deploy asks for"
                        + " could not be read, so nothing was deployed. " + e.getMessage(), e);
            }
        }
        if (profiles.incomplete) {
            throw new IllegalArgumentException("The security profile '" + profileName + "' that this deploy asks for"
                    + " is not among the profiles that could be read (some stored profiles are in a form this version"
                    + " cannot read; the server log names them), so nothing was deployed.");
        }
        throw new IllegalArgumentException("The security profile '" + profileName + "' that this deploy asks for"
                + " does not exist (it may have been deleted or renamed), so nothing was deployed: a release is never"
                + " deployed without the security settings it asks for. Deploy again with an existing profile, or with"
                + " none.");
    }

    /**
     * A stable fingerprint of a security profile, recorded with each release deployed with it to tell when the profile
     * changed since. It is kept in the view's database next to the profile, so it must not let anyone who reads that
     * database test guesses of the profile's secrets: it is the SHA-256 of the profile without its secrets and, for each
     * secret, an HMAC-SHA256 of it under a key only this view instance holds, encrypted with Ambari's master key
     * ({@link #FINGERPRINT_KEY_SETTING}). It still changes when only a secret does.
     *
     * @param cfg the profile, with its secrets ({@link #resolveProfile}, {@link #requireProfile})
     * @return the fingerprint, or {@code null} for no profile or when it cannot be computed
     */
    public String fingerprint(SecurityConfigDTO cfg) {
        if (cfg == null) {
            return null;
        }
        try {
            StringBuilder canonical = new StringBuilder(objectMapper.writeValueAsString(withoutSecrets(cfg)));
            Mac mac = null;
            for (SecretField field : SECRET_FIELDS) {
                String secret = field.inProfile().test(cfg) ? field.get().apply(cfg) : null;
                if (!isSet(secret)) {
                    continue;
                }
                if (mac == null) {
                    mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(fingerprintKey(), "HmacSHA256"));
                }
                canonical.append('\n').append(field.name()).append('=')
                        .append(hex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8))));
            }
            return sha256Hex(canonical.toString());
        } catch (Exception ex) {
            LOG.warn("Failed to fingerprint security profile: {}", ex.toString());
            return null;
        }
    }

    /**
     * Whether a release whose recorded fingerprint is {@code storedHash} was deployed with the profile as it is now. A
     * release deployed by an earlier version has a fingerprint taken over the profile with its secrets in clear: that
     * one is computed too, in memory only, so such a release is not reported as deployed with an older profile.
     *
     * @param cfg        the profile now, with its secrets
     * @param storedHash the fingerprint recorded with the release
     * @return {@code false} only when the profile is known to have changed since; {@code true} when either side is
     *         missing or cannot be computed
     */
    public boolean matchesFingerprint(SecurityConfigDTO cfg, String storedHash) {
        String current = fingerprint(cfg);
        if (current == null || storedHash == null || current.equals(storedHash)) {
            return true;
        }
        try {
            return storedHash.equals(sha256Hex(objectMapper.writeValueAsString(cfg)));
        } catch (Exception ex) {
            LOG.warn("Failed to fingerprint security profile: {}", ex.toString());
            return false;
        }
    }

    /**
     * Remove a profile for the current view instance.
     *
     * @param profileName logical profile identifier
     */
    public void deleteProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        synchronized (writeLock()) {
            removeStored(profileName);
        }
    }

    // ----- single rows, for the writers that own one (callers hold writeLock()) -----

    /**
     * The stored row of a profile.
     *
     * @return the row, or {@code null} when this view instance stores no profile with that name
     * @throws IllegalStateException when the row cannot be read from the DataStore
     */
    StoredProfile readStored(String id) {
        DataStore store = viewContext.getDataStore();
        if (store == null || id == null) {
            return null;
        }
        SecurityProfileEntity entity;
        try {
            entity = store.find(SecurityProfileEntity.class, id);
        } catch (PersistenceException | RuntimeException e) {
            throw new IllegalStateException("The security profile '" + id + "' could not be read from the view's"
                    + " database: " + e.getMessage(), e);
        }
        if (entity == null || !Objects.equals(viewContext.getInstanceName(), entity.getViewInstance())) {
            return null;
        }
        return toStored(entity);
    }

    /**
     * Whether a stored profile of this view instance is marked as the default one.
     *
     * @throws IllegalStateException when the profiles cannot be read
     */
    boolean anyMarkedDefault() {
        for (SecurityProfileEntity entity : storedRows()) {
            if (entity.isDefault()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a stored row holds exactly this profile, compared field by field rather than as text. */
    boolean sameContent(StoredProfile stored, SecurityConfigDTO profile) {
        if (stored == null || stored.json() == null) {
            return false;
        }
        try {
            return objectMapper.readTree(stored.json()).equals(objectMapper.valueToTree(profile));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Stores one profile row (an upsert), leaving every other row alone.
     *
     * @throws IllegalArgumentException when the profile is too large to be stored
     * @throws IllegalStateException    when the row cannot be written
     */
    void writeProfile(String id, SecurityConfigDTO profile, boolean isDefault) {
        String json = serializeForStore(id, withoutSecrets(profile));
        StoredProfile stored = readStored(id);
        storeSecrets(id, profile, stored == null ? null : stored.profile());
        storeRow(id, json, isDefault);
    }

    /**
     * Whether secrets of a profile are stored in the view settings: for a stored row that must not be mistaken for
     * one without them, now that a row never holds them.
     */
    boolean hasStoredSecrets(String id) {
        for (SecretField field : SECRET_FIELDS) {
            if (isSet(settings().get(secretKey(id, field.name())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes one profile row of this view instance, if there is one, and its secrets.
     *
     * @return whether a row was removed
     * @throws IllegalStateException when the row cannot be read or removed
     */
    boolean removeStored(String id) {
        StoredProfile stored = readStored(id);
        if (stored == null) {
            return false;
        }
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId(id);
        entity.setViewInstance(viewContext.getInstanceName());
        try {
            viewContext.getDataStore().remove(entity);
        } catch (PersistenceException | RuntimeException e) {
            LOG.warn("Failed to delete security profile {}: {}", id, e.toString());
            throw new IllegalStateException("Unable to delete security profile '" + id + "'", e);
        }
        for (SecretField field : SECRET_FIELDS) {
            settings().delete(secretKey(id, field.name()));
        }
        return true;
    }

    // ----- helpers -----

    /** Whether a profile is derived from a platform context, whose reconcile writes and removes it. */
    private static boolean isDerived(SecurityConfigDTO profile) {
        return profile != null && profile.derivedFromContext != null && !profile.derivedFromContext.isBlank();
    }

    /**
     * Takes the default flag off every row but the given one. A save keeps rows the sender never saw, and one of
     * them may carry the flag: two defaults would make a deploy that names no profile pick either. Callers hold
     * {@link #writeLock()}.
     */
    private void clearDefaultFlagExcept(String defaultId) {
        for (SecurityProfileEntity entity : storedRows()) {
            if (entity.isDefault() && !entity.getId().equals(defaultId)) {
                storeRow(entity.getId(), entity.getProfileJson(), false);
            }
        }
    }

    /** This view instance's rows; a read failure is never mistaken for "no profiles". */
    private Collection<SecurityProfileEntity> storedRows() {
        DataStore store = viewContext.getDataStore();
        if (store == null) {
            return List.of(); // a context without an instance has no profiles
        }
        Collection<SecurityProfileEntity> found;
        try {
            found = store.findAll(SecurityProfileEntity.class, null);
        } catch (PersistenceException | RuntimeException e) {
            throw new IllegalStateException("The security profiles could not be read from the view's database: "
                    + e.getMessage(), e);
        }
        List<SecurityProfileEntity> ours = new ArrayList<>();
        if (found != null) {
            for (SecurityProfileEntity entity : found) {
                if (Objects.equals(viewContext.getInstanceName(), entity.getViewInstance())) {
                    ours.add(entity);
                }
            }
        }
        return ours;
    }

    private StoredProfile toStored(SecurityProfileEntity entity) {
        SecurityConfigDTO profile = null;
        try {
            profile = entity.getProfileJson() == null ? null
                    : objectMapper.readValue(entity.getProfileJson(), SecurityConfigDTO.class);
        } catch (Exception e) {
            LOG.warn("Security profile '{}' could not be read; it is left out and kept as stored: {}", entity.getId(),
                    e.toString());
        }
        return new StoredProfile(entity.getId(), entity.getProfileJson(), entity.isDefault(), profile);
    }

    // ----- secrets -----

    /**
     * A stored profile without the secrets an earlier version kept in clear in it, for rows this version does not
     * load itself: those the previous version of the view keeps in Ambari's database after an upgrade. Every other
     * field is kept as stored.
     *
     * @param profileJson a profile row as stored
     * @return the row without them, or {@code null} when it holds none (or is not a JSON object)
     */
    public static String rowWithoutSecrets(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(profileJson);
        } catch (JsonProcessingException notJson) {
            return null;
        }
        if (!(root instanceof ObjectNode)) {
            return null;
        }
        boolean changed = false;
        for (SecretField field : SECRET_FIELDS) {
            String[] path = field.name().split("\\.", 2);
            JsonNode value = root.path(path[0]).path(path[1]);
            if (root.get(path[0]) instanceof ObjectNode section && value.isTextual() && !value.asText().isEmpty()) {
                section.putNull(path[1]);
                changed = true;
            }
        }
        return changed ? root.toString() : null;
    }

    /** The view setting holding a secret of a profile. */
    static String secretKey(String profile, String field) {
        return SECRET_KEY_PREFIX + profile + "." + field;
    }

    private InstanceSettings settings() {
        if (settings == null) {
            settings = new InstanceSettings(viewContext);
        }
        return settings;
    }

    /** The key of the profile fingerprints, made and stored the first time ({@link #FINGERPRINT_KEY_SETTING}). */
    private byte[] fingerprintKey() {
        String stored = settings().getSecret(FINGERPRINT_KEY_SETTING);
        if (stored == null) {
            synchronized (writeLock()) {
                stored = settings().getSecret(FINGERPRINT_KEY_SETTING);
                if (stored == null) {
                    byte[] key = new byte[32];
                    new SecureRandom().nextBytes(key);
                    stored = Base64.getEncoder().encodeToString(key);
                    settings().putSecret(FINGERPRINT_KEY_SETTING, stored);
                }
            }
        }
        return Base64.getDecoder().decode(stored);
    }

    private static String sha256Hex(String text) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Whether a value sent for a secret is a new secret, rather than the mask or a blank that keep the stored one. */
    private static boolean isNewSecret(String value) {
        return isSet(value) && !MASKED_SECRET.equals(value);
    }

    /** Whether a profile as read from its row holds a secret: a row written by an earlier version. */
    private static boolean holdsSecrets(SecurityConfigDTO profile) {
        return SECRET_FIELDS.stream().anyMatch(field -> isSet(field.get().apply(profile)));
    }

    /** A copy of a profile as its row keeps it: without its secrets, nor the flags a read adds. */
    private SecurityConfigDTO withoutSecrets(SecurityConfigDTO profile) {
        SecurityConfigDTO copy = objectMapper.convertValue(profile, SecurityConfigDTO.class);
        for (SecretField field : SECRET_FIELDS) {
            if (field.inProfile().test(copy)) {
                field.set().accept(copy, null);
                field.flag().accept(copy, null);
            }
        }
        return copy;
    }

    /**
     * The profile with its stored secrets decrypted into it. A secret its row still holds in clear (see
     * {@link #moveSecretsOutOfRow}) is used when none is stored apart.
     */
    private SecurityConfigDTO withSecrets(String id, SecurityConfigDTO profile) {
        for (SecretField field : SECRET_FIELDS) {
            if (!field.inProfile().test(profile)) {
                continue;
            }
            try {
                String stored = settings().getSecret(secretKey(id, field.name()));
                if (stored != null) {
                    field.set().accept(profile, stored);
                }
            } catch (RuntimeException e) {
                // Never the value: only which secret could not be read.
                throw new IllegalStateException("The " + field.name() + " of the security profile '" + id + "' could"
                        + " not be decrypted (" + e.getClass().getSimpleName() + "). Was Ambari's master key changed?"
                        + " Enter it again on the Global Security page.", e);
            }
        }
        return profile;
    }

    /**
     * Saves the secrets of a profile about to be stored: a new value replaces the stored one; the mask or a blank keeps
     * it, for a profile that is already stored; a section the profile no longer has loses its secrets, and so does a
     * new profile given none (a secret left over by a save that failed is not handed to it).
     *
     * @param before the profile as stored until now, {@code null} for a new one; a secret its row still holds in
     *               clear is kept by moving it, since the row is about to be written without it
     */
    private void storeSecrets(String id, SecurityConfigDTO profile, SecurityConfigDTO before) {
        for (SecretField field : SECRET_FIELDS) {
            String key = secretKey(id, field.name());
            boolean inProfile = field.inProfile().test(profile);
            String value = inProfile ? field.get().apply(profile) : null;
            if (isNewSecret(value)) {
                settings().putSecret(key, value);
            } else if (!inProfile || before == null) {
                settings().delete(key);
            } else {
                String inRow = field.inProfile().test(before) ? field.get().apply(before) : null;
                if (isNewSecret(inRow)) {
                    settings().putSecret(key, inRow);
                }
            }
        }
    }

    /**
     * Moves the secrets a row holds in clear, as earlier versions wrote it, to the encrypted view settings and writes
     * the row again without them. The row is read again under the write lock, so a profile saved meanwhile is never
     * written over.
     *
     * @return the profile, without its secrets; {@code null} when they were not moved (the row could not be read again,
     *         or written), in which case the profile is used as read, secrets in clear, until a later read moves them
     */
    private SecurityConfigDTO moveSecretsOutOfRow(String id) {
        synchronized (writeLock()) {
            try {
                StoredProfile stored = readStored(id);
                if (stored == null || stored.profile() == null) {
                    return null;
                }
                SecurityConfigDTO profile = stored.profile();
                if (!holdsSecrets(profile)) {
                    return profile;
                }
                for (SecretField field : SECRET_FIELDS) {
                    String value = field.get().apply(profile);
                    if (isNewSecret(value)) {
                        settings().putSecret(secretKey(id, field.name()), value);
                    }
                }
                SecurityConfigDTO row = withoutSecrets(profile);
                storeRow(id, serializeForStore(id, row), stored.isDefault());
                LOG.info("Security profile '{}' kept its secrets in clear in its row; they are now encrypted", id);
                return row;
            } catch (RuntimeException e) {
                LOG.warn("Security profile '{}' still holds its secrets in clear, as an earlier version stored it, and"
                        + " they could not be encrypted yet: {}", id, e.toString());
                return null;
            }
        }
    }

    private String serializeForStore(String id, SecurityConfigDTO profile) {
        String json;
        try {
            json = objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The security profile '" + id + "' could not be serialized: "
                    + e.getOriginalMessage(), e);
        }
        if (json.length() > MAX_PROFILE_JSON_LENGTH) {
            // Never echo the profile: its extra properties may hold credentials.
            throw new IllegalArgumentException("The security profile '" + id + "' is too large to be saved ("
                    + json.length() + " characters once stored, at most " + MAX_PROFILE_JSON_LENGTH + "). Shorten"
                    + " its extra properties or other long values. No profile was changed.");
        }
        return json;
    }

    private void storeRow(String id, String json, boolean isDefault) {
        DataStore store = viewContext.getDataStore();
        if (store == null) {
            throw new IllegalStateException("This view context has no instance; no security profile can be saved.");
        }
        SecurityProfileEntity entity = new SecurityProfileEntity();
        entity.setId(id);
        entity.setViewInstance(viewContext.getInstanceName());
        entity.setDefault(isDefault);
        entity.setProfileJson(json);
        try {
            store.store(entity);
        } catch (PersistenceException | RuntimeException e) {
            // The DataStore's message would quote the whole profile, extra properties included.
            throw new IllegalStateException("The security profile '" + id + "' could not be saved.", e);
        }
    }
}
