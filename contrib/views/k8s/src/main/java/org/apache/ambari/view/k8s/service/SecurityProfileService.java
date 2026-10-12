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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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

/**
 * Persistence helper for security profiles: one {@link SecurityProfileEntity} row per profile.
 *
 * <p>Every change writes or removes single rows (the DataStore's {@code store} is an upsert), never a purge followed
 * by a rewrite: each DataStore call is its own transaction, so a rewrite that fails halfway, or that runs on a read
 * that missed rows, loses profiles. The writers of one view instance take {@link #writeLock()} around their
 * read-modify-write so they cannot interleave.</p>
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

    private final ViewContext viewContext;
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
     * Load all stored security profiles for the current view instance. A row that cannot be read is left out, its
     * name logged, and the result marked {@link SecurityProfilesDTO#incomplete}.
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
            profiles.put(entity.getId(), stored.profile());
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
     * Persist the profiles of the Global Security page: each given profile is stored (an upsert), then the stored
     * profiles the request no longer lists are removed. Nothing is written when a profile is too large to be stored,
     * when the stored profiles cannot be read, or when a profile to remove is still used by a release.
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
            jsonById.put(id, serializeForStore(id, entry.getValue()));
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
                storeRow(entry.getKey(), entry.getValue(), entry.getKey().equals(profiles.defaultProfile));
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
     * Resolve a profile by name or default/first.
     *
     * @param requestedProfile profile name requested by caller; may be null/blank to use the default
     * @return the resolved security configuration, or null when none exist
     * @throws IllegalStateException when the profiles cannot be read
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
        return cfg;
    }

    /**
     * The profile a deploy names, which the deploy must not go without: a release deployed without its profile
     * silently loses its LDAP or OIDC login settings.
     *
     * @param profileName the profile the deploy asks for
     * @return the profile
     * @throws IllegalArgumentException when no readable profile has that name
     * @throws IllegalStateException    when the profiles cannot be read
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
            return cfg;
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
     * Compute a stable fingerprint for a security profile (canonical JSON SHA-256).
     */
    public String fingerprint(SecurityConfigDTO cfg) {
        if (cfg == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(cfg);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            LOG.warn("Failed to fingerprint security profile: {}", ex.toString());
            return null;
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
        storeRow(id, serializeForStore(id, profile), isDefault);
    }

    /**
     * Removes one profile row of this view instance, if there is one.
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

    private String serializeForStore(String id, SecurityConfigDTO profile) {
        String json;
        try {
            json = objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The security profile '" + id + "' could not be serialized: "
                    + e.getOriginalMessage(), e);
        }
        if (json.length() > MAX_PROFILE_JSON_LENGTH) {
            // Never echo the profile: it holds passwords and client secrets.
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
            // The DataStore's message would quote the whole profile, secrets included.
            throw new IllegalStateException("The security profile '" + id + "' could not be saved.", e);
        }
    }
}
