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

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.conf.output.S3OutputConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tools.ant.util.FileUtils;

import java.io.File;

/**
 * S3 specific upload client
 */
public class S3UploadClient extends AbstractS3CloudClient implements UploadClient {

  private static final Logger logger = LogManager.getLogger(S3UploadClient.class);

  private final S3OutputConfig s3OutputConfig;
  private final CannedAccessControlList acl;
  private AmazonS3 s3Client;

  public S3UploadClient(S3OutputConfig s3OutputConfig) {
    this.s3OutputConfig = s3OutputConfig;
    this.acl = s3OutputConfig.calculateAcls(s3OutputConfig.getObjectAcl());
  }

  @Override
  public void init(LogFeederProps logFeederProps) {
    SecretKeyPair keyPair = getSecretKeyPair(logFeederProps, s3OutputConfig);
    AWSCredentials awsCredentials = new BasicAWSCredentials(new String(keyPair.getAccessKey()), new String(keyPair.getSecretKey()));
    AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(awsCredentials);
    AwsClientBuilder.EndpointConfiguration endpointConf = new AwsClientBuilder.EndpointConfiguration(s3OutputConfig.getEndpoint(), s3OutputConfig.getRegion());
    s3Client = AmazonS3ClientBuilder.standard()
      .withCredentials(credentialsProvider)
      .withEndpointConfiguration(endpointConf)
      .withPathStyleAccessEnabled(s3OutputConfig.isPathStyleAccess())
      .build();
    bootstrapBucket(s3OutputConfig.getBucketConfig().getBucket(), s3OutputConfig.getBucketConfig());
  }

  @Override
  public void upload(String source, String target) throws Exception {
    String bucket = this.s3OutputConfig.getBucketConfig().getBucket();
    File fileToUpload = new File(source);
    logger.info("Starting S3 upload {} -> bucket: {}, key: {}", source, bucket, target);
    s3Client.putObject(bucket, target, new File(source));
    s3Client.setObjectAcl(bucket, target, acl);
    FileUtils.delete(fileToUpload);
  }

  @Override
  public void close() {
  }

  @Override
  void createBucketIfNeeded(String bucket) {
    if (!s3Client.doesBucketExistV2(bucket)) {
      s3Client.createBucket(bucket);
    }
  }
}
