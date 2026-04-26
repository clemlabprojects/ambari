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

package org.apache.ambari.server.state.oidc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OidcClientDescriptor {
  static final String KEY_NAME = "name";
  static final String KEY_CLIENT_ID = "client_id";
  static final String KEY_REALM = "realm";
  static final String KEY_PUBLIC_CLIENT = "public_client";
  static final String KEY_SERVICE_ACCOUNTS_ENABLED = "service_accounts_enabled";
  static final String KEY_DIRECT_ACCESS_GRANTS_ENABLED = "direct_access_grants_enabled";
  static final String KEY_STANDARD_FLOW_ENABLED = "standard_flow_enabled";
  static final String KEY_REDIRECT_URIS = "redirect_uris";
  static final String KEY_ATTRIBUTES = "attributes";
  static final String KEY_CONFIGURATIONS = "configurations";
  static final String KEY_SECRET_ALIAS = "secret_alias";
  static final String KEY_PROTOCOL_MAPPERS = "protocol_mappers";

  private String name;
  private String clientId;
  private String realm;
  private Boolean publicClient;
  private Boolean serviceAccountsEnabled;
  private Boolean directAccessGrantsEnabled;
  private Boolean standardFlowEnabled;
  private List<String> redirectUris;
  private Map<String, String> attributes;
  private List<Map<String, Map<String, String>>> configurations;
  private String secretAlias;
  /**
   * Optional list of Keycloak protocol mapper definitions to create on the client.
   * Each entry is a free-form map matching the Keycloak protocol-mapper API body
   * (keys: {@code name}, {@code protocol}, {@code protocolMapper}, {@code config}).
   * Mappers are applied idempotently: existing mappers with the same {@code name}
   * are left untouched.
   */
  private List<Map<String, Object>> protocolMappers;

  public OidcClientDescriptor(String name, String clientId, String realm, Boolean publicClient,
                              Boolean serviceAccountsEnabled, Boolean directAccessGrantsEnabled,
                              Boolean standardFlowEnabled, List<String> redirectUris,
                              Map<String, String> attributes,
                              List<Map<String, Map<String, String>>> configurations, String secretAlias,
                              List<Map<String, Object>> protocolMappers) {
    this.name = name;
    this.clientId = clientId;
    this.realm = realm;
    this.publicClient = publicClient;
    this.serviceAccountsEnabled = serviceAccountsEnabled;
    this.directAccessGrantsEnabled = directAccessGrantsEnabled;
    this.standardFlowEnabled = standardFlowEnabled;
    this.redirectUris = redirectUris;
    this.attributes = attributes;
    this.configurations = configurations;
    this.secretAlias = secretAlias;
    this.protocolMappers = protocolMappers;
  }

  OidcClientDescriptor(Map<?, ?> data) {
    if (data != null) {
      name = OidcDescriptorUtils.getStringValue(data, KEY_NAME);
      clientId = OidcDescriptorUtils.getStringValue(data, KEY_CLIENT_ID);
      realm = OidcDescriptorUtils.getStringValue(data, KEY_REALM);
      publicClient = OidcDescriptorUtils.getBooleanValue(data, KEY_PUBLIC_CLIENT);
      serviceAccountsEnabled = OidcDescriptorUtils.getBooleanValue(data, KEY_SERVICE_ACCOUNTS_ENABLED);
      directAccessGrantsEnabled = OidcDescriptorUtils.getBooleanValue(data, KEY_DIRECT_ACCESS_GRANTS_ENABLED);
      standardFlowEnabled = OidcDescriptorUtils.getBooleanValue(data, KEY_STANDARD_FLOW_ENABLED);
      redirectUris = OidcDescriptorUtils.getStringList(data.get(KEY_REDIRECT_URIS));
      attributes = OidcDescriptorUtils.getStringMap(data.get(KEY_ATTRIBUTES));
      configurations = OidcDescriptorUtils.getConfigurations(data.get(KEY_CONFIGURATIONS));
      secretAlias = OidcDescriptorUtils.getStringValue(data, KEY_SECRET_ALIAS);
      protocolMappers = OidcDescriptorUtils.getProtocolMapperList(data.get(KEY_PROTOCOL_MAPPERS));
    }
  }

  public String getName() {
    return name;
  }

  public String getClientId() {
    return clientId;
  }

  public String getRealm() {
    return realm;
  }

  public Boolean isPublicClient() {
    return publicClient;
  }

  public Boolean isServiceAccountsEnabled() {
    return serviceAccountsEnabled;
  }

  public Boolean isDirectAccessGrantsEnabled() {
    return directAccessGrantsEnabled;
  }

  public Boolean isStandardFlowEnabled() {
    return standardFlowEnabled;
  }

  public List<String> getRedirectUris() {
    return redirectUris;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public List<Map<String, Map<String, String>>> getConfigurations() {
    return configurations;
  }

  public String getSecretAlias() {
    return secretAlias;
  }

  /**
   * Returns optional Keycloak protocol mapper definitions for this client, or {@code null}
   * if none were declared in the descriptor.
   */
  public List<Map<String, Object>> getProtocolMappers() {
    return protocolMappers;
  }

  Map<String, Object> toMap() {
    Map<String, Object> data = new TreeMap<>();
    if (name != null) {
      data.put(KEY_NAME, name);
    }
    if (clientId != null) {
      data.put(KEY_CLIENT_ID, clientId);
    }
    if (realm != null) {
      data.put(KEY_REALM, realm);
    }
    if (publicClient != null) {
      data.put(KEY_PUBLIC_CLIENT, publicClient);
    }
    if (serviceAccountsEnabled != null) {
      data.put(KEY_SERVICE_ACCOUNTS_ENABLED, serviceAccountsEnabled);
    }
    if (directAccessGrantsEnabled != null) {
      data.put(KEY_DIRECT_ACCESS_GRANTS_ENABLED, directAccessGrantsEnabled);
    }
    if (standardFlowEnabled != null) {
      data.put(KEY_STANDARD_FLOW_ENABLED, standardFlowEnabled);
    }
    if (redirectUris != null) {
      data.put(KEY_REDIRECT_URIS, new ArrayList<>(redirectUris));
    }
    if (attributes != null) {
      data.put(KEY_ATTRIBUTES, new TreeMap<>(attributes));
    }
    if (configurations != null) {
      data.put(KEY_CONFIGURATIONS, configurations);
    }
    if (secretAlias != null) {
      data.put(KEY_SECRET_ALIAS, secretAlias);
    }
    if (protocolMappers != null) {
      data.put(KEY_PROTOCOL_MAPPERS, new ArrayList<>(protocolMappers));
    }
    return data;
  }
}
