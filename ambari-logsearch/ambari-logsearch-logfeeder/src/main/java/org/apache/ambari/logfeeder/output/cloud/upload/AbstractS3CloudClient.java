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
package org.apache.ambari.logfeeder.output.cloud.upload;

import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.conf.output.BucketConfig;
import org.apache.ambari.logfeeder.conf.output.S3OutputConfig;
import org.apache.ambari.logfeeder.credential.CompositeSecretStore;
import org.apache.ambari.logfeeder.credential.EnvSecretStore;
import org.apache.ambari.logfeeder.credential.FileSecretStore;
import org.apache.ambari.logfeeder.credential.HadoopCredentialSecretStore;
import org.apache.ambari.logfeeder.credential.PlainTextSecretStore;
import org.apache.ambari.logfeeder.credential.PropertySecretStore;
import org.apache.ambari.logfeeder.credential.SecretStore;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds common cloud based client operations
 */
abstract class AbstractS3CloudClient {

  private static final Logger logger = LogManager.getLogger(AbstractS3CloudClient.class);

  /**
   * Create a cloud specific bucket if it does not exists
   * @param bucket name of the bucket
   */
  abstract void createBucketIfNeeded(String bucket);

  /**
   * Try to bootstrap the default bucket, until it is not successful.
   * @param bucket name of the bucket
   * @param bucketConfig bucket config holder
   */
  void bootstrapBucket(String bucket, BucketConfig bucketConfig) {
    if (bucketConfig.isCreateBucketOnStartup()) {
      boolean ready = false;
      while (!ready) {
        try {
          createBucketIfNeeded(bucket);
          ready = true;
        } catch (Exception e) {
          logger.error("Error during bucket creation - bucket : " + bucket, e);
        }
        if (!ready) {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            logger.error("Error during thread sleep (bucket bootstrap)", e);
            Thread.currentThread().interrupt();
            ready = true;
          }
        } else {
          logger.warn("Bucket ('{}') creation failed. Retry ...", bucket);
        }
      }
    }
  }

  /**
   * Get secret key pair (access / secret) key
   *
   * @param props  global configuration holder
   * @param config cloud based configuration
   * @return secret key pair
   */
  SecretKeyPair getSecretKeyPair(LogFeederProps props, S3OutputConfig config) {
    String secretFile = config.isUseFileSecrets() ? config.getSecretKeyFileLocation() : null;
    String secretRef = config.isUseHadoopCredentialStorage() ? config.getSecretKeyHadoopCredentialReference() : null;
    CompositeSecretStore secretKeyStore = createCompositeSecretStore(props, config.getSecretKey(), config.getSecretKeyProperty(),
      config.getSecretKeyEnvVariable(), secretFile, secretRef);

    String accessFile = config.isUseFileSecrets() ? config.getAccessKeyFileLocation() : null;
    String accessRef = config.isUseHadoopCredentialStorage() ? config.getAccessKeyHadoopCredentialReference() : null;
    CompositeSecretStore accessKeyStore = createCompositeSecretStore(props, config.getAccessKey(), config.getAccessKeyProperty(),
      config.getAccessKeyEnvVariable(), accessFile, accessRef);
    if (secretKeyStore.getSecret() == null) {
      throw new RuntimeException(String.format("No any %s credentials found for secret access key.", config.getDescription()));
    }
    if (accessKeyStore.getSecret() == null) {
      throw new RuntimeException(String.format("No any %s credentials found for access key id.", config.getDescription()));
    }
    return new SecretKeyPair(accessKeyStore.getSecret(), secretKeyStore.getSecret());
  }

  /**
   * Common operation to create secret stores for cloud secrets
   * @param props global property holder
   * @param property java property to check for secret
   * @param env env variable to check for secret
   * @param file file to check for secret
   * @param credentialRef credential provider referece to check for secret
   * @return composite secret store that contains multiple way to get a secret
   */
  private CompositeSecretStore createCompositeSecretStore(LogFeederProps props, String plainTextSecret, String property, String env,
                                                          String file, String credentialRef) {
    List<SecretStore> secretStores = new ArrayList<>();
    if (StringUtils.isNotBlank(plainTextSecret)) {
      secretStores.add(new PlainTextSecretStore(plainTextSecret));
    }
    if (StringUtils.isNotBlank(credentialRef)) {
      secretStores.add(new HadoopCredentialSecretStore(credentialRef, props.getLogFeederSecurityConfig().getCredentialStoreProviderPath()));
    }
    if (StringUtils.isNotBlank(file)) {
      secretStores.add(new FileSecretStore(file));
    }
    if (StringUtils.isNotBlank(env)) {
      secretStores.add(new EnvSecretStore(env));
    }
    secretStores.add(new PropertySecretStore(property));
    return new CompositeSecretStore(secretStores.toArray(new SecretStore[0]));
  }

}
