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
package org.apache.ambari.view.k8s.migration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.k8s.service.OidcClientSecrets;
import org.apache.ambari.view.k8s.service.SecurityProfileService;
import org.apache.ambari.view.k8s.service.ViewSettingsService;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.migration.ViewDataMigrationContext;
import org.apache.ambari.view.migration.ViewDataMigrationException;
import org.apache.ambari.view.migration.ViewDataMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries a KDPS view instance across a view version change (declared in view.xml as
 * {@code data-migrator-class}). Ambari calls it once, on the first start of the new version, when the
 * previous version's instance of the same name is still extracted under {@code views/work} and the new
 * instance is empty, and only when the two versions declare different {@code data-version} values (equal
 * values make Ambari fall back to its own copy, which loses BeanInfo-hidden fields).
 *
 * <p>Every entity the new version still declares is copied with {@link AccessorCopyConverter}; entities
 * the new version dropped are skipped. Ambari itself copies the user permissions, carries over view
 * properties left at their defaults, and saves the instance once the migration is done. The secrets the
 * previous version kept in clear in its repository rows, Keycloak registration results and security profiles are
 * then cleared from its own rows, which stay in Ambari's database.</p>
 *
 * <p>Settings: versions before 1.0.0.9 kept the kubeconfig pointer, OpenShift login, proxy, view settings,
 * monitoring state and every encrypted secret in Ambari's per-user instance data, so each user only saw what
 * they had saved themselves. The migration seeds the settings shared by every user ({@link InstanceSettings})
 * from all users' instance data (see {@link #sharedSettingsFrom}). The per-user instance data is still copied
 * as stored, for the carry-over {@link InstanceSettings#get} does for this release, except for the Thanos object
 * store credentials the view settings document held in clear: those are encrypted in the shared settings and removed
 * from every user's copy. The encryption key is Ambari's master key, so encrypted values stay readable wherever they
 * are copied.</p>
 */
public class KdpsDataMigrator implements ViewDataMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(KdpsDataMigrator.class);

    @Inject
    private ViewDataMigrationContext migrationContext;

    public KdpsDataMigrator() {
        // injected by Ambari
    }

    KdpsDataMigrator(ViewDataMigrationContext migrationContext) {
        this.migrationContext = migrationContext;
    }

    @Override
    public boolean beforeMigration() {
        LOG.info("KDPS migration of instance {}: data version {} -> {}",
                migrationContext.getCurrentInstanceDefinition() == null ? "?"
                        : migrationContext.getCurrentInstanceDefinition().getInstanceName(),
                migrationContext.getOriginDataVersion(), migrationContext.getCurrentDataVersion());
        return true;
    }

    @Override
    public void migrateEntity(Class originEntityClass, Class currentEntityClass) throws ViewDataMigrationException {
        if (currentEntityClass == null) {
            LOG.info("KDPS migration: {} is no longer persisted by this version, skipped",
                    originEntityClass == null ? "?" : originEntityClass.getName());
            return;
        }
        migrationContext.copyAllObjects(originEntityClass, currentEntityClass, new AccessorCopyConverter());
        LOG.info("KDPS migration: copied {}", currentEntityClass.getSimpleName());
        clearSecretsOfPreviousVersion(originEntityClass);
    }

    /**
     * Clears, in the previous version's rows, the secrets it kept there in clear and that version no longer needs once
     * this one runs: the registry secret a repository row kept next to the encrypted one (the previous version read
     * the encrypted one only), the OIDC client secret a Keycloak registration step kept in its result, and the bind
     * passwords and client secret a security profile kept in its row. Done after the copy, so this version gets the
     * rows as they were and moves their secrets itself: it encrypts those of the profiles the first time it loads them
     * and clears the step results ({@code CommandService#clearOidcClientSecretsFromResults}). A failure is logged and
     * does not stop the migration.
     */
    private void clearSecretsOfPreviousVersion(Class<?> originEntityClass) {
        if (originEntityClass == null) {
            return;
        }
        String entity = originEntityClass.getSimpleName();
        if (!"HelmRepoEntity".equals(entity) && !"CommandStatusEntity".equals(entity)
                && !"SecurityProfileEntity".equals(entity)) {
            return;
        }
        DataStore origin = migrationContext.getOriginDataStore();
        if (origin == null) {
            return;
        }
        int cleared = 0;
        try {
            for (Object row : origin.findAll(originEntityClass, null)) {
                boolean changed = switch (entity) {
                    case "HelmRepoEntity" -> blankProperty(row, "PlainSecret");
                    case "SecurityProfileEntity" -> clearProfileSecrets(row);
                    default -> clearOidcClientSecret(row);
                };
                if (changed) {
                    origin.store(row);
                    cleared++;
                }
            }
            if (cleared > 0) {
                LOG.info("KDPS migration: cleared the secrets the previous version kept in clear in {} {} row(s)",
                        cleared, entity);
            }
        } catch (PersistenceException | ReflectiveOperationException | RuntimeException e) {
            LOG.warn("KDPS migration: the secrets the previous version kept in its {} rows could not all be cleared"
                    + " ({} cleared): {}", entity, cleared, e.toString());
        }
    }

    /**
     * Sets a String property of a row to empty when it holds a value. Empty rather than {@code null}: the DataStore
     * leaves a property it is given as {@code null} unchanged.
     *
     * @return whether the row changed
     */
    private static boolean blankProperty(Object row, String property) throws ReflectiveOperationException {
        Method getter;
        try {
            getter = row.getClass().getMethod("get" + property);
        } catch (NoSuchMethodException none) {
            return false;
        }
        Object value = getter.invoke(row);
        if (!(value instanceof String text) || text.isEmpty()) {
            return false;
        }
        row.getClass().getMethod("set" + property, String.class).invoke(row, "");
        return true;
    }

    /** Removes the client secret from the result of a Keycloak registration step. @return whether the row changed */
    private static boolean clearOidcClientSecret(Object row) throws ReflectiveOperationException {
        Object result = row.getClass().getMethod("getResultJson").invoke(row);
        String cleared = result instanceof String json ? OidcClientSecrets.withoutClientSecret(json) : null;
        if (cleared == null) {
            return false;
        }
        row.getClass().getMethod("setResultJson", String.class).invoke(row, cleared);
        return true;
    }

    /**
     * Removes the bind passwords and the client secret from a security profile row. The whole profile is written back,
     * never {@code null}: the DataStore leaves a property it is given as {@code null} unchanged.
     *
     * @return whether the row changed
     */
    private static boolean clearProfileSecrets(Object row) throws ReflectiveOperationException {
        Object profile = row.getClass().getMethod("getProfileJson").invoke(row);
        String cleared = profile instanceof String json ? SecurityProfileService.rowWithoutSecrets(json) : null;
        if (cleared == null) {
            return false;
        }
        row.getClass().getMethod("setProfileJson", String.class).invoke(row, cleared);
        return true;
    }

    @Override
    public void migrateInstanceData() {
        migrationContext.copyAllInstanceData();
        Map<String, Map<String, String>> byUser = migrationContext.getOriginInstanceDataByUser();
        int keys = byUser.values().stream().mapToInt(Map::size).sum();
        LOG.info("KDPS migration: copied {} instance data entries for {} user(s)", keys, byUser.size());
        seedSharedSettings(byUser);
        encryptSharedThanosCredentials();
    }

    /**
     * Earlier versions kept the Thanos object store credentials in clear in the view settings document. Those of the
     * shared document are encrypted into settings of their own now, rather than on its first read. Then the copies of
     * the document each user kept for themselves, in this version's instance data and in the previous version's, lose
     * theirs: they are no longer read once the shared document exists, and only kept the credentials in clear. They
     * are left as they are when there is no shared document, since the first read still carries one over from them.
     * A failure is logged and does not stop the migration; the first read of the settings encrypts them then.
     */
    private void encryptSharedThanosCredentials() {
        try {
            DataStore store = migrationContext.getCurrentDataStore();
            KdpsSettingEntity shared = store.find(KdpsSettingEntity.class, ViewSettingsService.SETTINGS_KEY);
            if (shared == null || shared.getValue() == null) {
                return;
            }
            new ViewSettingsService(new InstanceSettings(store, null)).encryptCredentialsKeptInClear();
        } catch (PersistenceException | RuntimeException e) {
            LOG.warn("KDPS migration: the Thanos object store credentials of the view settings could not be encrypted"
                    + " ({}); they are encrypted the first time the settings are read", e.toString());
            return;
        }
        int cleared = clearThanosCredentials(migrationContext.getCurrentInstanceDataByUser(),
                migrationContext::putCurrentInstanceData)
                + clearThanosCredentials(migrationContext.getOriginInstanceDataByUser(),
                migrationContext::putOriginInstanceData);
        if (cleared > 0) {
            LOG.info("KDPS migration: removed the Thanos object store credentials from {} copy(ies) of the view"
                    + " settings that users kept for themselves", cleared);
        }
    }

    /** Writes a user's instance data entry. */
    private interface InstanceDataWriter {
        void put(String user, String key, String value);
    }

    /**
     * Removes the Thanos object store credentials from each user's copy of the view settings document.
     *
     * @return how many copies changed
     */
    private static int clearThanosCredentials(Map<String, Map<String, String>> byUser, InstanceDataWriter writer) {
        if (byUser == null) {
            return 0;
        }
        int cleared = 0;
        for (Map.Entry<String, Map<String, String>> user : byUser.entrySet()) {
            String settings = user.getValue() == null ? null : user.getValue().get(ViewSettingsService.SETTINGS_KEY);
            String without = ViewSettingsService.withoutCredentials(settings);
            if (without == null) {
                continue;
            }
            try {
                writer.put(user.getKey(), ViewSettingsService.SETTINGS_KEY, without);
                cleared++;
            } catch (RuntimeException e) {
                LOG.warn("KDPS migration: the Thanos object store credentials could not be removed from the view"
                        + " settings user '{}' kept: {}", user.getKey(), e.toString());
            }
        }
        return cleared;
    }

    /**
     * Stores the settings derived from the users' instance data in the new instance, once: a key the new
     * instance already holds (copied with the entities from a version that had shared settings) is left alone.
     * A failure does not stop the migration: the per-user data was copied, so each user's settings are still
     * carried over the first time they are read.
     */
    private void seedSharedSettings(Map<String, Map<String, String>> byUser) {
        Map<String, String> shared = sharedSettingsFrom(byUser);
        if (shared.isEmpty()) {
            return;
        }
        int seeded = 0;
        try {
            DataStore store = migrationContext.getCurrentDataStore();
            for (Map.Entry<String, String> setting : shared.entrySet()) {
                if (store.find(KdpsSettingEntity.class, setting.getKey()) == null) {
                    store.store(new KdpsSettingEntity(setting.getKey(), setting.getValue()));
                    seeded++;
                }
            }
            String owner = connectionOwner(byUser);
            LOG.info("KDPS migration: {} setting(s) are now shared by every user of the instance; cluster connection: {}",
                    seeded, owner == null ? "none, the view was not configured" : "the one saved by " + owner);
        } catch (PersistenceException | RuntimeException e) {
            LOG.warn("KDPS migration: the shared settings could not be seeded ({}); each user's own settings are "
                    + "carried over when they are first read", e.toString());
        }
    }

    /**
     * The settings to share, from the per-user instance data of the previous version. The cluster connection
     * (kubeconfig, auth mode, OpenShift login, proxy: {@link InstanceSettings#isConnectionKey}) only makes sense as a
     * whole, so it is taken from one user who had configured the view ({@code kubeconfig.uploaded=true}), the first
     * by name when several had. Every other key (repository and context secrets, view settings, monitoring state)
     * is taken from the first user who holds it, that user first, then the others by name. Keys Ambari writes
     * itself are left out.
     *
     * @param byUser instance data by user name
     * @return the settings to share, by key
     */
    static Map<String, String> sharedSettingsFrom(Map<String, Map<String, String>> byUser) {
        List<String> users = new ArrayList<>(byUser.keySet());
        users.sort(Comparator.comparing((String user) -> !configured(byUser.get(user)))
                .thenComparing(Comparator.nullsLast(Comparator.<String>naturalOrder())));
        String connectionOwner = connectionOwner(byUser);
        Map<String, String> shared = new LinkedHashMap<>();
        for (String user : users) {
            Map<String, String> data = byUser.get(user);
            if (data == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                if (entry.getValue() == null || shared.containsKey(key) || !InstanceSettings.isCarriedOver(key)) {
                    continue;
                }
                if (InstanceSettings.isConnectionKey(key) && (connectionOwner == null || !connectionOwner.equals(user))) {
                    continue;
                }
                shared.put(key, entry.getValue());
            }
        }
        return shared;
    }

    /** The user whose cluster connection is shared: the first by name who had configured the view, or null. */
    private static String connectionOwner(Map<String, Map<String, String>> byUser) {
        return byUser.keySet().stream()
                .filter(user -> user != null && configured(byUser.get(user)))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static boolean configured(Map<String, String> data) {
        return data != null && "true".equalsIgnoreCase(data.get(InstanceSettings.CONFIGURED_KEY));
    }

    @Override
    public void afterMigration() {
        // nothing: Ambari saves the instance after this hook
    }
}
