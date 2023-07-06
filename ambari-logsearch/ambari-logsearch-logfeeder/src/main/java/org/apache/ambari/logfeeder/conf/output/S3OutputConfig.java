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
package org.apache.ambari.logfeeder.conf.output;

import com.amazonaws.services.s3.model.CannedAccessControlList;
import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.inject.Inject;

@Configuration
public class S3OutputConfig {

  private final BucketConfig bucketConfig;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_ENDPOINT,
    description = "Amazon S3 endpoint.",
    examples = {"https://s3.amazonaws.com"},
    defaultValue = LogFeederConstants.S3_ENDPOINT_DEFAULT,
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_ENDPOINT + ":" + LogFeederConstants.S3_ENDPOINT_DEFAULT +"}")
  private String endpoint;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_SECRET_KEY,
    description = "Amazon S3 secret key.",
    examples = {"MySecretKey"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_SECRET_KEY + ":}")
  private String secretKey;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_ACCESS_KEY,
    description = "Amazon S3 secret access key.",
    examples = {"MySecretAccessKey"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_ACCESS_KEY + ":}")
  private String accessKey;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_SECRET_KEY_FILE,
    description = "Amazon S3 secret key file (that contains only the key).",
    examples = {"/my/path/secret_key"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_ACCESS_KEY + ":}")
  private String secretKeyFileLocation;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_ACCESS_KEY_FILE,
    description = "Amazon S3 secret access key file (that contains only the key).",
    examples = {"/my/path/access_key"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_ACCESS_KEY_FILE + ":}")
  private String accessKeyFileLocation;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_USE_FILE,
    description = "Enable to get Amazon S3 secret/access keys from files.",
    examples = {"true"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_USE_FILE + ":false}")
  private boolean useFileSecrets;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_USE_HADOOP_CREDENTIAL_PROVIDER,
    description = "Enable to get Amazon S3 secret/access keys from Hadoop credential store API.",
    examples = {"true"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_USE_HADOOP_CREDENTIAL_PROVIDER + ":false}")
  private boolean useHadoopCredentialStorage;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_HADOOP_CREDENTIAL_SECRET_REF,
    description = "Amazon S3 secret access key reference in Hadoop credential store..",
    examples = {"logfeeder.s3.secret.key"},
    defaultValue = "logfeeder.s3.secret.key",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_HADOOP_CREDENTIAL_SECRET_REF + ":logfeeder.s3.secret.key}")
  private String secretKeyHadoopCredentialReference;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_HADOOP_CREDENTIAL_ACCESS_REF,
    description = "Amazon S3 access key reference in Hadoop credential store..",
    examples = {"logfeeder.s3.access.key"},
    defaultValue = "logfeeder.s3.access.key",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_HADOOP_CREDENTIAL_ACCESS_REF + ":logfeeder.s3.access.key}")
  private String accessKeyHadoopCredentialReference;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_REGION,
    description = "Amazon S3 region.",
    examples = {"us-east-2"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_REGION + ":us-east-2}")
  private String region;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_OBJECT_ACL,
    description = "Amazon S3 ACLs for new objects.",
    examples = {"logs"},
    defaultValue = "private",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_OBJECT_ACL + ":private}")
  private String objectAcl;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_PATH_STYLE_ACCESS,
    description = "Enable S3 path style access will disable the default virtual hosting behaviour (DNS).",
    defaultValue = "false",
    examples = {"true"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_PATH_STYLE_ACCESS + ":false}")
  private boolean pathStyleAccess;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.S3_MULTIOBJECT_DELETE_ENABLE,
    description = "When enabled, multiple single-object delete requests are replaced by a single 'delete multiple objects'-request, reducing the number of requests.",
    defaultValue = "true",
    examples = {"false"},
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.S3_MULTIOBJECT_DELETE_ENABLE + ":true}")
  private boolean multiobjectDeleteEnable;

  @Inject
  public S3OutputConfig(BucketConfig bucketConfig) {
    this.bucketConfig = bucketConfig;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public void setAccessKey(String accessKey) {
    this.accessKey = accessKey;
  }

  public String getSecretKeyFileLocation() {
    return secretKeyFileLocation;
  }

  public void setSecretKeyFileLocation(String secretKeyFileLocation) {
    this.secretKeyFileLocation = secretKeyFileLocation;
  }

  public String getAccessKeyFileLocation() {
    return accessKeyFileLocation;
  }

  public void setAccessKeyFileLocation(String accessKeyFileLocation) {
    this.accessKeyFileLocation = accessKeyFileLocation;
  }

  public boolean isUseFileSecrets() {
    return useFileSecrets;
  }

  public void setUseFileSecrets(boolean useFileSecrets) {
    this.useFileSecrets = useFileSecrets;
  }

  public boolean isUseHadoopCredentialStorage() {
    return useHadoopCredentialStorage;
  }

  public void setUseHadoopCredentialStorage(boolean useHadoopCredentialStorage) {
    this.useHadoopCredentialStorage = useHadoopCredentialStorage;
  }

  public String getSecretKeyHadoopCredentialReference() {
    return secretKeyHadoopCredentialReference;
  }

  public void setSecretKeyHadoopCredentialReference(String secretKeyHadoopCredentialReference) {
    this.secretKeyHadoopCredentialReference = secretKeyHadoopCredentialReference;
  }

  public String getAccessKeyHadoopCredentialReference() {
    return accessKeyHadoopCredentialReference;
  }

  public String getAccessKeyProperty() {
    return LogFeederConstants.S3_ACCESS_KEY;
  }

  public String getSecretKeyProperty() {
    return LogFeederConstants.S3_SECRET_KEY;
  }

  public String getAccessKeyEnvVariable() {
    return "AWS_ACCESS_KEY_ID";
  }

  public String getSecretKeyEnvVariable() {
    return "AWS_SECRET_ACCESS_KEY";
  }

  public String getDescription() {
    return "AWS S3";
  }

  public void setAccessKeyHadoopCredentialReference(String accessKeyHadoopCredentialReference) {
    this.accessKeyHadoopCredentialReference = accessKeyHadoopCredentialReference;
  }

  public String getObjectAcl() {
    return objectAcl;
  }

  public void setObjectAcl(String objectAcl) {
    this.objectAcl = objectAcl;
  }

  public boolean isPathStyleAccess() {
    return pathStyleAccess;
  }

  public void setPathStyleAccess(boolean pathStyleAccess) {
    this.pathStyleAccess = pathStyleAccess;
  }

  public boolean isMultiobjectDeleteEnable() {
    return multiobjectDeleteEnable;
  }

  public void setMultiobjectDeleteEnable(boolean multiobjectDeleteEnable) {
    this.multiobjectDeleteEnable = multiobjectDeleteEnable;
  }

  public BucketConfig getBucketConfig() {
    return bucketConfig;
  }

  public CannedAccessControlList calculateAcls(String aclStr) {
    for (CannedAccessControlList val : CannedAccessControlList.values()) {
      if (val.toString().equals(aclStr)) {
        return val;
      }
    }
    throw new IllegalArgumentException(String.format("'%s' is not a valid ACL setting", aclStr));
  }
}
