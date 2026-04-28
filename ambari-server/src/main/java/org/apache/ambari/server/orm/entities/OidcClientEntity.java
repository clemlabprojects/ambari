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

package org.apache.ambari.server.orm.entities;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.TableGenerator;

/**
 * Tracks OIDC clients that have been provisioned in the external OIDC provider for this cluster.
 * <p>
 * Each row records one client registration: the Ambari service that owns it, the provider-assigned
 * client id, the provider-internal id (useful for subsequent API calls), the target realm, and the
 * Ambari credential-store alias where the client secret is held.
 * </p>
 */
@Entity
@Table(name = "oidc_client")
@TableGenerator(
    name = "oidc_client_id_generator",
    table = "ambari_sequences",
    pkColumnName = "sequence_name",
    valueColumnName = "sequence_value",
    pkColumnValue = "oidc_client_id_seq",
    initialValue = 1,
    allocationSize = 100)
@NamedQueries({
    @NamedQuery(
        name = "OidcClientEntityFindAll",
        query = "SELECT oc FROM OidcClientEntity oc"),
    @NamedQuery(
        name = "OidcClientEntityFindByCluster",
        query = "SELECT oc FROM OidcClientEntity oc WHERE oc.clusterId = :clusterId"),
    @NamedQuery(
        name = "OidcClientEntityFindByClusterAndService",
        query = "SELECT oc FROM OidcClientEntity oc WHERE oc.clusterId = :clusterId AND oc.serviceName = :serviceName"),
    @NamedQuery(
        name = "OidcClientEntityFindByClusterAndClientId",
        query = "SELECT oc FROM OidcClientEntity oc WHERE oc.clusterId = :clusterId AND oc.clientId = :clientId"),
    @NamedQuery(
        name = "OidcClientEntityDeleteByCluster",
        query = "DELETE FROM OidcClientEntity oc WHERE oc.clusterId = :clusterId")
})
public class OidcClientEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.TABLE, generator = "oidc_client_id_generator")
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "cluster_id", nullable = false, updatable = false)
  private Long clusterId;

  @Column(name = "service_name", nullable = false, length = 255)
  private String serviceName;

  @Column(name = "client_name", nullable = false, length = 255)
  private String clientName;

  @Column(name = "client_id", nullable = false, length = 512)
  private String clientId;

  @Column(name = "internal_id", length = 255)
  private String internalId;

  @Column(name = "realm", nullable = false, length = 255)
  private String realm;

  @Column(name = "secret_alias", length = 255)
  private String secretAlias;

  @Column(name = "created_at", nullable = false)
  private Timestamp createdAt;

  @Column(name = "updated_at", nullable = false)
  private Timestamp updatedAt;

  public Long getId() {
    return id;
  }

  public Long getClusterId() {
    return clusterId;
  }

  public void setClusterId(Long clusterId) {
    this.clusterId = clusterId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public String getClientName() {
    return clientName;
  }

  public void setClientName(String clientName) {
    this.clientName = clientName;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getInternalId() {
    return internalId;
  }

  public void setInternalId(String internalId) {
    this.internalId = internalId;
  }

  public String getRealm() {
    return realm;
  }

  public void setRealm(String realm) {
    this.realm = realm;
  }

  public String getSecretAlias() {
    return secretAlias;
  }

  public void setSecretAlias(String secretAlias) {
    this.secretAlias = secretAlias;
  }

  public Timestamp getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Timestamp createdAt) {
    this.createdAt = createdAt;
  }

  public Timestamp getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Timestamp updatedAt) {
    this.updatedAt = updatedAt;
  }
}
