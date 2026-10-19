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

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The settings of a KDPS view instance, shared by every user of the instance and by the background work of
 * the view: the cluster connection, the encrypted repository and platform-context secrets, the view settings
 * and the monitoring state. They are kept in the instance's DataStore as {@link KdpsSettingEntity} rows.
 *
 * <p>Earlier versions kept them in Ambari's instance data, which Ambari scopes to the user making the request:
 * only the administrator who configured the view saw it configured, every other user (operators included)
 * found it unconfigured with no credentials, and a deploy step read whichever user's data its worker thread
 * had inherited. Nothing in the view writes instance data any more.</p>
 *
 * <p>Carry-over: {@code KdpsDataMigrator} seeds the shared settings from every user's instance data when the
 * view is upgraded. For the instances it did not run for, a key the shared settings have never held is still
 * looked up in the caller's own instance data and copied over once (see {@link #get}). That fallback is meant
 * for this release only.</p>
 */
public class InstanceSettings {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceSettings.class);

    /** Longest value the DataStore accepts in a String column. */
    public static final int MAX_VALUE_LENGTH = 3000;

    /**
     * Longest secret {@link #putSecret} saves. Registry tokens of some cloud registries and service-account tokens
     * run to a few thousand characters, more than one row holds once encrypted, so a long secret is kept in parts.
     */
    public static final int MAX_SECRET_LENGTH = 16 * 1024;

    /** Longest text {@link #putText} saves (a GitOps profile with its file templates). */
    public static final int MAX_TEXT_LENGTH = 256 * 1024;

    /** Longest part of an encrypted secret kept in parts, safely under {@link #MAX_VALUE_LENGTH}. */
    static final int SECRET_PART_LENGTH = 2900;

    /**
     * Value of the setting of a secret kept in parts: followed by the number of parts, each under the key
     * {@code <key>}{@value #SECRET_PART_SUFFIX}{@code <n>}, n counting from 1. Neither Base64 nor the encrypted form
     * starts with '@', so it is never taken for a secret.
     */
    static final String SECRET_PARTS_MARKER = "@parts:";
    static final String SECRET_PART_SUFFIX = "@part";

    /**
     * Set to {@code "true"} once a kubeconfig, an OpenShift login or the in-cluster ServiceAccount connection was saved:
     * the view is configured.
     */
    public static final String CONFIGURED_KEY = "kubeconfig.uploaded";

    /**
     * Key prefixes of the cluster connection (kubeconfig, auth mode, OpenShift login, outbound proxy). These keys
     * only make sense together, so they are carried over from one configured user, never mixed across users.
     */
    private static final List<String> CONNECTION_PREFIXES = List.of("kubeconfig.", "auth.", "openshift.", "proxy.");

    /** Instance data Ambari itself writes when it upgrades a view; not a KDPS setting. */
    private static final Set<String> AMBARI_KEYS = Set.of("upgradedFrom");

    /**
     * Serializes writes and carry-overs, so a value copied from a user's own data can never overwrite one written
     * at the same moment. Writes are rare (settings pages, deploy bookkeeping), so one lock is enough.
     */
    private static final Object WRITE_LOCK = new Object();

    /** The view instance; {@code null} for settings read straight from a DataStore. */
    private final ViewContext viewContext;
    /** The DataStore of settings that have no view context; {@code null} otherwise. */
    private final DataStore ownDataStore;
    private volatile EncryptionService encryptionService;

    /**
     * @param viewContext the view instance whose settings are read and written
     */
    public InstanceSettings(ViewContext viewContext) {
        this(viewContext, null);
    }

    /**
     * @param viewContext       the view instance whose settings are read and written
     * @param encryptionService encrypts and decrypts the secrets; {@code null} to create one on first use
     */
    public InstanceSettings(ViewContext viewContext, EncryptionService encryptionService) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.ownDataStore = null;
        this.encryptionService = encryptionService;
    }

    /**
     * Settings read and written straight in a view instance's DataStore, for work done outside the view itself (the
     * data migration of a view version change). No user is calling, so nothing is carried over from a user's own
     * instance data.
     *
     * @param dataStore         the DataStore of the view instance
     * @param encryptionService encrypts and decrypts the secrets; {@code null} to create one on first use
     */
    public InstanceSettings(DataStore dataStore, EncryptionService encryptionService) {
        this.viewContext = null;
        this.ownDataStore = Objects.requireNonNull(dataStore, "dataStore");
        this.encryptionService = encryptionService;
    }

    /**
     * Returns a setting. A key the shared settings have never held (not even removed) is looked up in the caller's
     * own instance data, where earlier versions kept it, and shared from then on.
     *
     * @param key the setting key
     * @return the value, or {@code null} when the setting is not set
     */
    public String get(String key) {
        KdpsSettingEntity row = find(key);
        if (row != null) {
            return row.getValue();
        }
        return carryOverFromCallersOwnData(key);
    }

    /**
     * Sets a setting for every user of the view instance.
     *
     * @param key   the setting key
     * @param value the value; {@code null} removes the setting
     * @throws IllegalArgumentException when the value is longer than {@link #MAX_VALUE_LENGTH}
     */
    public void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            // The value is never echoed: it may be an encrypted secret.
            throw new IllegalArgumentException("The value of the view setting '" + key + "' is too long to be saved ("
                    + value.length() + " characters, at most " + MAX_VALUE_LENGTH + ").");
        }
        synchronized (WRITE_LOCK) {
            store(new KdpsSettingEntity(key, value));
        }
    }

    /**
     * Removes a setting. The row stays with no value, so the setting is not brought back from a user's own data
     * by the carry-over of {@link #get}.
     *
     * @param key the setting key
     */
    public void remove(String key) {
        synchronized (WRITE_LOCK) {
            KdpsSettingEntity row = find(key);
            if (row != null && row.getValue() == null) {
                return;
            }
            removeSecretParts(key, 1, secretParts(row));
            if (row != null) {
                // The DataStore skips null values when it updates a row, so the row is replaced instead.
                try {
                    dataStore().remove(row);
                } catch (PersistenceException e) {
                    throw new IllegalStateException("Could not remove the view setting '" + key + "'", e);
                }
            }
            store(new KdpsSettingEntity(key, null));
        }
    }

    /**
     * Deletes a setting that no earlier version kept in a user's own instance data (an encrypted secret of a security
     * profile or of an operation step): its row goes too, since there is nothing for the carry-over of {@link #get}
     * to bring back, and such keys are written and removed often enough that empty rows would pile up.
     *
     * @param key the setting key
     */
    public void delete(String key) {
        synchronized (WRITE_LOCK) {
            KdpsSettingEntity row = find(key);
            if (row == null) {
                return;
            }
            removeSecretParts(key, 1, secretParts(row));
            try {
                dataStore().remove(row);
            } catch (PersistenceException e) {
                throw new IllegalStateException("Could not remove the view setting '" + key + "'", e);
            }
        }
    }

    /**
     * Returns a secret saved with {@link #putSecret}.
     *
     * @param key the setting key
     * @return the plain secret, or {@code null} when none is saved
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when there is no master key
     */
    public String getSecret(String key) {
        String encrypted = get(key);
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        if (encrypted.startsWith(SECRET_PARTS_MARKER)) {
            encrypted = joinSecretParts(key, encrypted);
        }
        // Saved by this version: salt:iv:ciphertext as it is. Earlier versions wrapped that, or before them the
        // secret itself, in Base64, which never has a ':'.
        byte[] stored = encrypted.indexOf(':') >= 0
                ? encrypted.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(encrypted);
        EncryptionService.Decrypted decrypted = encryption().decryptStored(stored);
        String plain = new String(decrypted.plain(), StandardCharsets.UTF_8);
        if (decrypted.reencrypt()) {
            // Stored by an earlier version without the master key: from now on it is encrypted with it.
            try {
                putSecret(key, plain);
            } catch (RuntimeException e) {
                LOG.warn("Could not encrypt the view setting '{}' again with the Ambari master key: {}", key, e.toString());
            }
        }
        return plain;
    }

    /**
     * Encrypts a secret and saves it for every user of the view instance.
     *
     * <p>The encrypted form ({@code salt:iv:ciphertext}, already text) is stored as it is; a secret too long for one
     * setting once encrypted is kept in parts.
     *
     * @param key         the setting key
     * @param plainSecret the secret
     * @throws IllegalArgumentException when the secret is longer than {@link #MAX_SECRET_LENGTH}; nothing is saved
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when there is no master key; nothing
     *                                                                           is saved
     */
    public void putSecret(String key, String plainSecret) {
        Objects.requireNonNull(plainSecret, "plainSecret");
        checkSecretLength("secret '" + key + "'", plainSecret);
        String encrypted = new String(encryption().encrypt(plainSecret.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        putInParts(key, encrypted);
    }

    /**
     * Returns a text saved with {@link #putText}.
     *
     * @param key the setting key
     * @return the text, or {@code null} when none is saved
     */
    public String getText(String key) {
        String value = get(key);
        if (value != null && value.startsWith(SECRET_PARTS_MARKER)) {
            return joinSecretParts(key, value);
        }
        return value;
    }

    /**
     * Saves a text that may be longer than one setting holds (a JSON document), in parts when needed, for every user
     * of the view instance. Not encrypted: for configuration, never for a secret.
     *
     * @param key  the setting key
     * @param text the text; {@code null} deletes the setting
     * @throws IllegalArgumentException when the text is longer than {@link #MAX_TEXT_LENGTH}, or starts with the
     *                                  marker of a value kept in parts
     */
    public void putText(String key, String text) {
        if (text == null) {
            delete(key);
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("The view setting '" + key + "' is too long to be saved (" + text.length()
                    + " characters, at most " + MAX_TEXT_LENGTH + ").");
        }
        if (text.startsWith(SECRET_PARTS_MARKER)) {
            throw new IllegalArgumentException("The view setting '" + key + "' cannot start with '"
                    + SECRET_PARTS_MARKER + "'.");
        }
        putInParts(key, text);
    }

    /** Saves a value, in parts when it is longer than one setting holds; parts left from a longer value go. */
    private void putInParts(String key, String value) {
        synchronized (WRITE_LOCK) {
            int before = secretParts(find(key));
            int parts = 0;
            if (value.length() <= MAX_VALUE_LENGTH) {
                put(key, value);
            } else {
                parts = (value.length() + SECRET_PART_LENGTH - 1) / SECRET_PART_LENGTH;
                for (int i = 1; i <= parts; i++) {
                    String part = value.substring((i - 1) * SECRET_PART_LENGTH,
                            Math.min(value.length(), i * SECRET_PART_LENGTH));
                    store(new KdpsSettingEntity(key + SECRET_PART_SUFFIX + i, part));
                }
                // Written last, so it never names a part that is not written yet.
                put(key, SECRET_PARTS_MARKER + parts);
            }
            removeSecretParts(key, parts + 1, before);
        }
    }

    /**
     * Checks that {@link #putSecret} can save a secret, so that a caller saving several values together refuses the
     * request before writing any of them.
     *
     * @param what   what the secret is, for the message, e.g. {@code "registry password"}
     * @param secret the secret; {@code null} passes
     * @throws IllegalArgumentException when it is longer than {@link #MAX_SECRET_LENGTH}; the secret is not echoed
     */
    public static void checkSecretLength(String what, String secret) {
        if (secret != null && secret.length() > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException("The " + what + " is too long to be saved (" + secret.length()
                    + " characters, at most " + MAX_SECRET_LENGTH + ").");
        }
    }

    /** How many parts the secret a setting row holds is kept in; 0 when it is not kept in parts. */
    private static int secretParts(KdpsSettingEntity row) {
        String value = row == null ? null : row.getValue();
        if (value == null || !value.startsWith(SECRET_PARTS_MARKER)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(SECRET_PARTS_MARKER.length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The encrypted form of a secret kept in parts, from its parts. */
    private String joinSecretParts(String key, String marker) {
        int parts = secretParts(new KdpsSettingEntity(key, marker));
        StringBuilder encrypted = new StringBuilder();
        for (int i = 1; i <= parts; i++) {
            KdpsSettingEntity part = find(key + SECRET_PART_SUFFIX + i);
            if (part == null || part.getValue() == null) {
                throw new IllegalStateException("A stored credential is incomplete (the view setting '" + key
                        + "' misses part " + i + " of " + parts + "). Enter it again.");
            }
            encrypted.append(part.getValue());
        }
        return encrypted.toString();
    }

    /** Deletes the parts {@code from} to {@code to} of a secret kept in parts. Callers hold {@link #WRITE_LOCK}. */
    private void removeSecretParts(String key, int from, int to) {
        for (int i = from; i <= to; i++) {
            KdpsSettingEntity part = find(key + SECRET_PART_SUFFIX + i);
            if (part != null) {
                try {
                    dataStore().remove(part);
                } catch (PersistenceException e) {
                    throw new IllegalStateException("Could not remove the view setting '" + part.getId() + "'", e);
                }
            }
        }
    }

    /**
     * The secrets {@link #encryptLegacySecretsAgain} went through.
     *
     * @param encryptedAgain the keys of the secrets an earlier version had encrypted without the master key, now
     *                       encrypted with it
     * @param unreadable     the keys of the secrets that cannot be decrypted (encrypted with another key)
     */
    public record LegacySecrets(List<String> encryptedAgain, List<String> unreadable) {
    }

    /**
     * Encrypts again with the master key every secret an earlier version encrypted with its built-in passphrase,
     * whether it is ever read or not ({@link #getSecret} does it for those it reads). Secrets are told from other
     * settings by their form ({@code salt:iv:ciphertext}); those the earliest versions only Base64-encoded look like
     * any other setting, so they are only encrypted again when read.
     *
     * @return what was done
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when there is no master key
     */
    public LegacySecrets encryptLegacySecretsAgain() {
        requireMasterKey();
        Collection<KdpsSettingEntity> rows;
        try {
            rows = dataStore().findAll(KdpsSettingEntity.class, null);
        } catch (PersistenceException e) {
            throw new IllegalStateException("Could not read the view settings", e);
        }
        List<String> encryptedAgain = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (KdpsSettingEntity row : rows == null ? List.<KdpsSettingEntity>of() : rows) {
            byte[] encrypted = encryptedForm(row.getValue());
            if (encrypted == null) {
                continue;
            }
            EncryptionService.Decrypted decrypted;
            try {
                decrypted = encryption().decryptStored(encrypted);
            } catch (IllegalArgumentException | IllegalStateException e) {
                unreadable.add(row.getId());
                continue;
            }
            if (!decrypted.reencrypt()) {
                continue;
            }
            synchronized (WRITE_LOCK) {
                // Saved again meanwhile: it is encrypted with the master key already.
                KdpsSettingEntity current = find(row.getId());
                if (current != null && Objects.equals(current.getValue(), row.getValue())) {
                    putSecret(row.getId(), new String(decrypted.plain(), StandardCharsets.UTF_8));
                    encryptedAgain.add(row.getId());
                }
            }
        }
        return new LegacySecrets(encryptedAgain, unreadable);
    }

    /**
     * The bytes {@link EncryptionService#encrypt} returned that a setting holds, or {@code null} when it does not hold
     * any: {@code salt:iv:ciphertext}, each part Base64, with the sizes AES gives them.
     */
    private static byte[] encryptedForm(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            Base64.Decoder b64 = Base64.getDecoder();
            int salt = b64.decode(parts[0]).length;
            int iv = b64.decode(parts[1]).length;
            int cipher = b64.decode(parts[2]).length;
            return salt > 0 && iv == 16 && cipher > 0 && cipher % 16 == 0 ? bytes : null;
        } catch (IllegalArgumentException notEncrypted) {
            return null;
        }
    }

    /**
     * Checks that the Ambari master key was found, before saving anything a secret goes with.
     *
     * @throws org.apache.ambari.view.k8s.security.MasterKeyUnavailableException when it was not
     */
    public void requireMasterKey() {
        encryption().requireMasterKey();
    }

    /**
     * Whether a key belongs to the cluster connection, which is carried over as a whole from one configured user.
     *
     * @param key the setting key
     * @return {@code true} for kubeconfig, auth mode, OpenShift login and proxy keys
     */
    public static boolean isConnectionKey(String key) {
        return key != null && CONNECTION_PREFIXES.stream().anyMatch(key::startsWith);
    }

    /**
     * Whether a key of a user's instance data is a KDPS setting to carry over.
     *
     * @param key the instance data key
     * @return {@code false} for the keys Ambari writes itself
     */
    public static boolean isCarriedOver(String key) {
        return key != null && !AMBARI_KEYS.contains(key);
    }

    // ----- carry-over from per-user instance data -----

    /**
     * Looks a key the shared settings have never held up in the caller's own instance data, where earlier versions
     * kept it, and then copies the caller's settings into the shared ones (keys already shared are left alone).
     * The cluster connection is only copied from a user who had configured the view, and only while no connection
     * is shared yet, so the shared connection never mixes two users' kubeconfig and login.
     *
     * @param key the key that was not found
     * @return the value now shared, or {@code null}
     */
    private String carryOverFromCallersOwnData(String key) {
        Map<String, String> own = callersOwnData();
        if (own.get(key) == null || !isCarriedOver(key)) {
            return null;
        }
        boolean connection = isConnectionKey(key);
        if (connection && !mayCarryOverConnection(own)) {
            return null;
        }
        synchronized (WRITE_LOCK) {
            KdpsSettingEntity row = find(key);
            if (row != null) {
                return row.getValue(); // another request shared it meanwhile
            }
            boolean withConnection = mayCarryOverConnection(own);
            int copied = 0;
            for (Map.Entry<String, String> entry : own.entrySet()) {
                String k = entry.getKey();
                if (entry.getValue() == null || !isCarriedOver(k) || (isConnectionKey(k) && !withConnection)) {
                    continue;
                }
                if (find(k) == null) {
                    store(new KdpsSettingEntity(k, entry.getValue()));
                    copied++;
                }
            }
            LOG.info("Shared {} setting(s) that user '{}' had saved for themselves with every user of the view instance",
                    copied, viewContext.getUsername());
            row = find(key);
            return row == null ? null : row.getValue();
        }
    }

    /** The caller had configured the view and no connection is shared yet. */
    private boolean mayCarryOverConnection(Map<String, String> own) {
        return "true".equalsIgnoreCase(own.get(CONFIGURED_KEY)) && find(CONFIGURED_KEY) == null;
    }

    /** The instance data Ambari returns for the user of the current request (none for background work). */
    private Map<String, String> callersOwnData() {
        Map<String, String> own = viewContext == null ? null : viewContext.getInstanceData();
        return own == null ? Map.of() : own;
    }

    // ----- DataStore access -----

    private KdpsSettingEntity find(String key) {
        DataStore store = viewDataStore();
        if (store == null) {
            return null; // a context without an instance has no settings
        }
        try {
            return store.find(KdpsSettingEntity.class, Objects.requireNonNull(key, "key"));
        } catch (PersistenceException e) {
            throw new IllegalStateException("Could not read the view setting '" + key + "'", e);
        }
    }

    private void store(KdpsSettingEntity row) {
        try {
            dataStore().store(row);
        } catch (PersistenceException e) {
            throw new IllegalStateException("Could not save the view setting '" + row.getId() + "'", e);
        }
    }

    private DataStore viewDataStore() {
        return viewContext == null ? ownDataStore : viewContext.getDataStore();
    }

    private DataStore dataStore() {
        DataStore store = viewDataStore();
        if (store == null) {
            throw new IllegalStateException("This view context has no instance; its settings cannot be saved.");
        }
        return store;
    }

    private EncryptionService encryption() {
        EncryptionService service = encryptionService;
        if (service == null) {
            service = viewContext != null ? EncryptionService.forServer(viewContext) : EncryptionService.forServer();
            // Kept only once the key is found, so that a key set up meanwhile is used.
            if (service.isMasterKeyAvailable()) {
                encryptionService = service;
            }
        }
        return service;
    }
}
