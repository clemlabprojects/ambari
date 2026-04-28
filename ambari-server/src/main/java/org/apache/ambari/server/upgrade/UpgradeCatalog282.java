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
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.ambari.server.AmbariException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * Upgrades Ambari from 2.8.1 to 2.8.2.
 * <p>
 * DDL: creates the {@code oidc_client} table that tracks provisioned OIDC clients per cluster.
 * </p>
 */
public class UpgradeCatalog282 extends AbstractUpgradeCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog282.class);

  static final String OIDC_CLIENT_TABLE = "oidc_client";
  static final String OIDC_CLIENT_ID_COLUMN = "id";
  static final String OIDC_CLIENT_CLUSTER_ID_COLUMN = "cluster_id";
  static final String OIDC_CLIENT_SERVICE_NAME_COLUMN = "service_name";
  static final String OIDC_CLIENT_NAME_COLUMN = "client_name";
  static final String OIDC_CLIENT_CLIENT_ID_COLUMN = "client_id";
  static final String OIDC_CLIENT_INTERNAL_ID_COLUMN = "internal_id";
  static final String OIDC_CLIENT_REALM_COLUMN = "realm";
  static final String OIDC_CLIENT_SECRET_ALIAS_COLUMN = "secret_alias";
  static final String OIDC_CLIENT_CREATED_AT_COLUMN = "created_at";
  static final String OIDC_CLIENT_UPDATED_AT_COLUMN = "updated_at";
  static final String OIDC_CLIENT_PK = "pk_oidc_client";
  static final String OIDC_CLIENT_FK_CLUSTER = "fk_oidc_client_cluster";
  static final String CLUSTERS_TABLE = "clusters";
  static final String CLUSTERS_CLUSTER_ID_COLUMN = "cluster_id";

  @Inject
  public UpgradeCatalog282(Injector injector) {
    super(injector);
  }

  @Override
  public String getSourceVersion() {
    return "2.8.1";
  }

  @Override
  public String getTargetVersion() {
    return "2.8.2";
  }

  @Override
  protected void executeDDLUpdates() throws AmbariException, SQLException {
    createOidcClientTable();
  }

  @Override
  protected void executePreDMLUpdates() throws AmbariException, SQLException {
    addOidcClientSequence();
  }

  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
  }

  private void createOidcClientTable() throws SQLException {
    if (!dbAccessor.tableExists(OIDC_CLIENT_TABLE)) {
      LOG.info("Creating table {}", OIDC_CLIENT_TABLE);
      List<DBAccessor.DBColumnInfo> columns = new ArrayList<>();
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_ID_COLUMN, Long.class, null, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_CLUSTER_ID_COLUMN, Long.class, null, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_SERVICE_NAME_COLUMN, String.class, 255, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_NAME_COLUMN, String.class, 255, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_CLIENT_ID_COLUMN, String.class, 512, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_INTERNAL_ID_COLUMN, String.class, 255, null, true));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_REALM_COLUMN, String.class, 255, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_SECRET_ALIAS_COLUMN, String.class, 255, null, true));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_CREATED_AT_COLUMN, Timestamp.class, null, null, false));
      columns.add(new DBAccessor.DBColumnInfo(OIDC_CLIENT_UPDATED_AT_COLUMN, Timestamp.class, null, null, false));

      dbAccessor.createTable(OIDC_CLIENT_TABLE, columns);
      dbAccessor.addPKConstraint(OIDC_CLIENT_TABLE, OIDC_CLIENT_PK, OIDC_CLIENT_ID_COLUMN);
      dbAccessor.addFKConstraint(OIDC_CLIENT_TABLE, OIDC_CLIENT_FK_CLUSTER,
          OIDC_CLIENT_CLUSTER_ID_COLUMN, CLUSTERS_TABLE, CLUSTERS_CLUSTER_ID_COLUMN, false);
    } else {
      LOG.info("Table {} already exists, skipping creation", OIDC_CLIENT_TABLE);
    }
  }

  private void addOidcClientSequence() throws SQLException {
    dbAccessor.insertRowIfMissing(
        "ambari_sequences",
        new String[]{"sequence_name", "sequence_value"},
        new String[]{"'oidc_client_id_seq'", "1"},
        false);
  }
}
