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
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.orm.DBAccessor;
import org.apache.ambari.server.orm.dao.ArtifactDAO;
import org.apache.ambari.server.orm.entities.ArtifactEntity;
import org.apache.ambari.server.state.kerberos.KerberosComponentDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosIdentityDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptor;
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

  static final String OZONE_SERVICE = "OZONE";
  static final String OZONE_DATANODE_COMPONENT = "OZONE_DATANODE";
  static final String OZONE_DATANODE_IDENTITY = "ozone_datanode_ozone";
  static final String OZONE_DATANODE_PREVIOUS_PRINCIPAL = "dn/_HOST@${realm}";
  static final String OZONE_DATANODE_PRINCIPAL = "hdds/_HOST@${realm}";

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
    updateKerberosDescriptorArtifacts();
  }

  /**
   * Brings the stored Kerberos descriptor of each cluster in line with the 2.8.2 stack descriptor: the
   * Ozone DataNode principal moved from dn/_HOST, shared with the HDFS DataNode (so auth_to_local mapped
   * it to the HDFS user), to hdds/_HOST. A principal customized by the administrator is left alone. The
   * new principal is used from the next keytab regeneration on, which needs a restart of Ozone.
   */
  @Override
  protected void updateKerberosDescriptorArtifact(ArtifactDAO artifactDAO, ArtifactEntity artifactEntity)
      throws AmbariException {
    Map<String, Object> data = (artifactEntity == null) ? null : artifactEntity.getArtifactData();
    if (data == null) {
      return;
    }
    KerberosDescriptor kerberosDescriptor = new KerberosDescriptorFactory().createInstance(data);
    if (kerberosDescriptor != null && updateOzoneDataNodePrincipal(kerberosDescriptor)) {
      artifactEntity.setArtifactData(kerberosDescriptor.toMap());
      artifactDAO.merge(artifactEntity);
    }
  }

  /**
   * @return whether the Ozone DataNode principal was changed
   */
  boolean updateOzoneDataNodePrincipal(KerberosDescriptor kerberosDescriptor) {
    KerberosServiceDescriptor ozone = kerberosDescriptor.getService(OZONE_SERVICE);
    KerberosComponentDescriptor dataNode = (ozone == null) ? null : ozone.getComponent(OZONE_DATANODE_COMPONENT);
    KerberosIdentityDescriptor identity = (dataNode == null) ? null : dataNode.getIdentity(OZONE_DATANODE_IDENTITY);
    if (identity == null || identity.getPrincipalDescriptor() == null
        || !OZONE_DATANODE_PREVIOUS_PRINCIPAL.equals(identity.getPrincipalDescriptor().getValue())) {
      return false;
    }
    LOG.info("Changing the Ozone DataNode principal from {} to {}", OZONE_DATANODE_PREVIOUS_PRINCIPAL,
        OZONE_DATANODE_PRINCIPAL);
    identity.getPrincipalDescriptor().setValue(OZONE_DATANODE_PRINCIPAL);
    return true;
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
