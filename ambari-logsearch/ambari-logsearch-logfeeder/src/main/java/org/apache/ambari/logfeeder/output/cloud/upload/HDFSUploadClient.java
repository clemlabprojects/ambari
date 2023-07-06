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
import org.apache.ambari.logfeeder.conf.output.HdfsOutputConfig;
import org.apache.ambari.logfeeder.util.LogFeederHDFSUtil;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * HDFS client that uses core-site.xml file from the classpath to load the configuration.
 * Can connect to S3 / GCS / WASB / ADLS if the core-site.xml is configured to use one of those cloud storages
 */
public class HDFSUploadClient implements UploadClient {

  private static final String FS_DEFAULT_FS = "fs.defaultFS";
  private static final String HADOOP_SECURITY_AUTHENTICATION = "hadoop.security.authentication";

  private static final Logger logger = LogManager.getLogger(HDFSUploadClient.class);

  private final boolean externalHdfs;
  private final HdfsOutputConfig hdfsOutputConfig;
  private final FsPermission fsPermission;
  private final AtomicReference<Configuration> configurationRef = new AtomicReference<>();

  public HDFSUploadClient(HdfsOutputConfig hdfsOutputConfig, boolean externalHdfs) {
    this.hdfsOutputConfig = hdfsOutputConfig;
    this.externalHdfs = externalHdfs;
    this.fsPermission = new FsPermission(hdfsOutputConfig.getHdfsFilePermissions());
  }

  @Override
  public void init(LogFeederProps logFeederProps) {
    final Configuration configuration;
    if (externalHdfs) {
      configuration = LogFeederHDFSUtil.buildHdfsConfiguration(hdfsOutputConfig.getHdfsHost(), String.valueOf(hdfsOutputConfig.getHdfsPort()), "hdfs");
      logger.info("Using external HDFS client as core-site.xml is not located on the classpath.");
    } else {
      configuration = new Configuration();
      logger.info("Initialize HDFS client (cloud mode), using core-site.xml from the classpath.");
    }
    if (hasScheme(logFeederProps.getCloudBasePath())) {
      logger.info("Use cloud base path ({}) as fs.defaultFS", logFeederProps.getCloudBasePath());
      configuration.set(FS_DEFAULT_FS, logFeederProps.getCloudBasePath());
    }
    if (StringUtils.isNotBlank(logFeederProps.getCustomFs())) {
      logger.info("Override fs.defaultFS with {}", logFeederProps.getCustomFs());
      configuration.set(FS_DEFAULT_FS, logFeederProps.getCustomFs());
    }
    if (hdfsOutputConfig.isHdfsKerberos()) {
      logger.info("Kerberos is enabled for HDFS.");
      configuration.set(HADOOP_SECURITY_AUTHENTICATION, "kerberos");
      final String principal = hdfsOutputConfig.getPrincipal().replace("_HOST", LogFeederUtil.hostName);
      UserGroupInformation.setConfiguration(configuration);
      try {
        UserGroupInformation ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, hdfsOutputConfig.getKeytab());
        UserGroupInformation.setLoginUser(ugi);
      } catch (Exception e) {
        logger.error("Error during kerberos login", e);
        throw new RuntimeException(e);
      }
    } else {
      if (StringUtils.isNotBlank(hdfsOutputConfig.getLogfeederHdfsUser())) {
        logger.info("Using HADOOP_USER_NAME: {}", hdfsOutputConfig.getLogfeederHdfsUser());
        System.setProperty("HADOOP_USER_NAME", hdfsOutputConfig.getLogfeederHdfsUser());
      }
    }
    logger.info("HDFS client - will use '{}' permission for uploaded files", hdfsOutputConfig.getHdfsFilePermissions());
    configurationRef.set(configuration);
    LogFeederHDFSUtil.overrideFileSystemConfigs(logFeederProps, configurationRef.get());
  }

  private boolean hasScheme(String path) {
    return StringUtils.isNotBlank(path) && path.split(":/").length > 1;
  }

  @Override
  public void upload(String source, String target) throws Exception {
    final FileSystem fs = LogFeederHDFSUtil.buildFileSystem(configurationRef.get());
    LogFeederHDFSUtil.copyFromLocal(source, target, fs, true, true, this.fsPermission);
  }

  @Override
  public void close() {
  }

}
