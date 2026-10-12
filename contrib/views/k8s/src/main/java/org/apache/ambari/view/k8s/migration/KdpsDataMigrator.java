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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
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
 * properties left at their defaults, and saves the instance once the migration is done.</p>
 *
 * <p>Settings: versions before 1.0.0.9 kept the kubeconfig pointer, OpenShift login, proxy, view settings,
 * monitoring state and every encrypted secret in Ambari's per-user instance data, so each user only saw what
 * they had saved themselves. The migration seeds the settings shared by every user ({@link InstanceSettings})
 * from all users' instance data (see {@link #sharedSettingsFrom}). The per-user instance data is still copied
 * as stored, for the carry-over {@link InstanceSettings#get} does for this release. The encryption key is
 * Ambari's master key, so encrypted values stay readable wherever they are copied.</p>
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
    }

    @Override
    public void migrateInstanceData() {
        migrationContext.copyAllInstanceData();
        Map<String, Map<String, String>> byUser = migrationContext.getOriginInstanceDataByUser();
        int keys = byUser.values().stream().mapToInt(Map::size).sum();
        LOG.info("KDPS migration: copied {} instance data entries for {} user(s)", keys, byUser.size());
        seedSharedSettings(byUser);
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
