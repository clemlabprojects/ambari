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
import java.util.Base64;
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
     */
    public String getSecret(String key) {
        String encrypted = get(key);
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        byte[] plain = encryption().decrypt(Base64.getDecoder().decode(encrypted));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * Encrypts a secret and saves it for every user of the view instance.
     *
     * @param key         the setting key
     * @param plainSecret the secret
     */
    public void putSecret(String key, String plainSecret) {
        Objects.requireNonNull(plainSecret, "plainSecret");
        put(key, Base64.getEncoder().encodeToString(encryption().encrypt(plainSecret.getBytes(StandardCharsets.UTF_8))));
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
            service = new EncryptionService();
            encryptionService = service;
        }
        return service;
    }
}
