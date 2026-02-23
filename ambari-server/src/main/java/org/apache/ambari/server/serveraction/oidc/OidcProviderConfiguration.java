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

package org.apache.ambari.server.serveraction.oidc;

public class OidcProviderConfiguration {
  private final String provider;
  private final String baseUrl;
  private final String realm;
  private final String adminRealm;
  private final String adminClientId;
  private final String adminClientSecret;
  private final boolean verifyTls;

  public OidcProviderConfiguration(String provider, String baseUrl, String realm, String adminRealm,
                                   String adminClientId, String adminClientSecret, boolean verifyTls) {
    this.provider = provider;
    this.baseUrl = baseUrl;
    this.realm = realm;
    this.adminRealm = adminRealm;
    this.adminClientId = adminClientId;
    this.adminClientSecret = adminClientSecret;
    this.verifyTls = verifyTls;
  }

  public String getProvider() {
    return provider;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getRealm() {
    return realm;
  }

  public String getAdminRealm() {
    return adminRealm;
  }

  public String getAdminClientId() {
    return adminClientId;
  }

  public String getAdminClientSecret() {
    return adminClientSecret;
  }

  public boolean isVerifyTls() {
    return verifyTls;
  }
}
