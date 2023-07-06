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

import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logsearch.config.api.LogSearchPropertyDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BucketConfig {

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_STORAGE_BUCKET,
    description = "Amazon S3 bucket.",
    examples = {"logs"},
    defaultValue = "logfeeder",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_STORAGE_BUCKET + ":logfeeder}")
  private String bucket;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_STORAGE_BUCKET_BOOTSTRAP,
    description = "Create bucket on startup.",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_STORAGE_BUCKET_BOOTSTRAP + ":false}")
  private boolean createBucketOnStartup;

  public String getBucket() {
    return this.bucket;
  }

  public void setBucket(String bucketName) {
    this.bucket = bucketName;
  }

  public boolean isCreateBucketOnStartup() {
    return createBucketOnStartup;
  }

  public void setCreateBucketOnStartup(boolean createBucketOnStartup) {
    this.createBucketOnStartup = createBucketOnStartup;
  }
}
