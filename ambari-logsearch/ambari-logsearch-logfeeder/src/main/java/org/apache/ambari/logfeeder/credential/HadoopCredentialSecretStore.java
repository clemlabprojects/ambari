/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logfeeder.credential;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HadoopCredentialSecretStore implements SecretStore {

  private static final Logger logger = LogManager.getLogger(HadoopCredentialSecretStore.class);
  private static final String CREDENTIAL_STORE_PROVIDER_PATH_PROPERTY = "hadoop.security.credential.provider.path";

  private final String credentialStoreProviderPath;
  private final String property;

  public HadoopCredentialSecretStore(String property, String credentialStoreProviderPath) {
    this.property = property;
    this.credentialStoreProviderPath = credentialStoreProviderPath;
  }

  @Override
  public char[] getSecret() {
    try {
      if (StringUtils.isBlank(credentialStoreProviderPath)) {
        return null;
      }
      org.apache.hadoop.conf.Configuration config = new org.apache.hadoop.conf.Configuration();
      config.set(CREDENTIAL_STORE_PROVIDER_PATH_PROPERTY, credentialStoreProviderPath);
      return config.getPassword(property);
    } catch (Exception e) {
      logger.warn("Could not load password {} from credential store.", property);
      return null;
    }
  }
}
