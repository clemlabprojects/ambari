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
import org.apache.ambari.logfeeder.conf.output.S3OutputConfig;
import org.apache.ambari.logfeeder.util.LogFeederHDFSUtil;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class HDFSS3UploadClient extends AbstractS3CloudClient implements UploadClient {

  private static final Logger logger = LogManager.getLogger(HDFSS3UploadClient.class);

  private final S3OutputConfig s3OutputConfig;

  private FileSystem fs;

  public HDFSS3UploadClient(S3OutputConfig s3OutputConfig) {
    this.s3OutputConfig = s3OutputConfig;
  }

  @Override
  void createBucketIfNeeded(String bucket) {
    logger.warn("HDFS based S3 client won't bootstrap default bucket ('{}')", s3OutputConfig.getBucketConfig().getBucket());
  }

  @Override
  public void init(LogFeederProps logFeederProps) {
    SecretKeyPair keyPair = getSecretKeyPair(logFeederProps, s3OutputConfig);
    Configuration conf = LogFeederHDFSUtil.buildHdfsConfiguration(s3OutputConfig.getBucketConfig().getBucket(), "s3a");
    conf.set("fs.s3a.access.key", new String(keyPair.getAccessKey()));
    conf.set("fs.s3a.secret.key", new String(keyPair.getSecretKey()));
    conf.set("fs.s3a.aws.credentials.provider", SimpleAWSCredentialsProvider.NAME);
    conf.set("fs.s3a.endpoint", s3OutputConfig.getEndpoint());
    conf.set("fs.s3a.path.style.access", String.valueOf(s3OutputConfig.isPathStyleAccess()));
    conf.set("fs.s3a.multiobjectdelete.enable", String.valueOf(s3OutputConfig.isMultiobjectDeleteEnable()));
    LogFeederHDFSUtil.overrideFileSystemConfigs(logFeederProps, conf);
    this.fs = LogFeederHDFSUtil.buildFileSystem(conf);
  }

  @Override
  public void upload(String source, String target) throws Exception {
    LogFeederHDFSUtil.copyFromLocal(source, target, this.fs, true, true, null);
  }

  @Override
  public void close() throws IOException {
    LogFeederHDFSUtil.closeFileSystem(fs);
  }
}
