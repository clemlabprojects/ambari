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

import javax.inject.Inject;

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
 * the new version dropped are skipped. Instance data (kubeconfig pointer, OpenShift login, proxy, view
 * settings and every encrypted secret) is copied per user as stored: the encryption key is Ambari's master
 * key, so the values stay readable. Ambari itself copies the user permissions, carries over view
 * properties left at their defaults, and saves the instance once the migration is done.</p>
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
        int keys = migrationContext.getOriginInstanceDataByUser().values().stream().mapToInt(java.util.Map::size).sum();
        LOG.info("KDPS migration: copied {} instance data entries for {} user(s)", keys,
                migrationContext.getOriginInstanceDataByUser().size());
    }

    @Override
    public void afterMigration() {
        // nothing: Ambari saves the instance after this hook
    }
}
