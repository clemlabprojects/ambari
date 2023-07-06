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
package org.apache.ambari.logfeeder.output.cloud;

import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.conf.output.RolloverConfig;
import org.apache.ambari.logfeeder.output.cloud.upload.UploadClient;
import org.apache.ambari.logfeeder.output.cloud.upload.UploadClientFactory;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.plugin.output.Output;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Output class for cloud outputs.
 * Holds loggers - those will ship logs into specific folders, those files can be rolled out to an archive folder,
 * from there an upload client will be able to ship the log archives to a cloud storage
 */
public class CloudStorageOutput extends Output<LogFeederProps, InputMarker> {

  private static final Logger logger = LogManager.getLogger(CloudStorageOutput.class);

  private final Map<String, Logger> cloudOutputLoggers = new ConcurrentHashMap<>();
  private final UploadClient uploadClient;
  private final LogFeederProps logFeederProps;
  private final LoggerContext loggerContext;
  private final CloudStorageUploader uploader;
  private final RolloverConfig rolloverConfig;

  public CloudStorageOutput(LogFeederProps logFeederProps) {
    this.uploadClient = UploadClientFactory.createUploadClient(logFeederProps);
    this.logFeederProps = logFeederProps;
    this.rolloverConfig = logFeederProps.getRolloverConfig();
    loggerContext = (LoggerContext) LogManager.getContext(false);
    uploader = new CloudStorageUploader(String.format("%s-uploader", logFeederProps.getCloudStorageDestination().getText()), uploadClient, logFeederProps);
    uploader.setDaemon(true);
  }

  @Override
  public void init(LogFeederProps logFeederProperties) throws Exception {
    logger.info("Initialize cloud output.");
    uploadClient.init(logFeederProperties);
    uploader.start();
  }

  @Override
  public String getOutputType() {
    return "cloud";
  }

  @Override
  public void copyFile(File inputFile, InputMarker inputMarker) throws Exception {
    throw new UnsupportedOperationException("Copy file is not supported yet");
  }

  @Override
  public void write(String jsonStr, InputMarker inputMarker) throws Exception {
    String uniqueThreadName = inputMarker.getInput().getThread().getName();
    Logger cloudLogger = null;
    if (cloudOutputLoggers.containsKey(uniqueThreadName)) {
      cloudLogger = cloudOutputLoggers.get(uniqueThreadName);
    } else {
      logger.info("New cloud input source found. Register: {}", uniqueThreadName);
      cloudLogger = CloudStorageLoggerFactory.createLogger(inputMarker.getInput(), loggerContext, logFeederProps);
      cloudOutputLoggers.put(uniqueThreadName, cloudLogger);
    }
    cloudLogger.info(jsonStr);
    inputMarker.getInput().checkIn(inputMarker);
  }

  @Override
  public Long getPendingCount() {
    return 0L;
  }

  @Override
  public String getWriteBytesMetricName() {
    return "write:cloud";
  }

  @Override
  public String getShortDescription() {
    return "cloud";
  }

  @Override
  public String getStatMetricName() {
    return null;
  }

  void removeWorker(Input input) {
    String uniqueName = input.getThread().getName();
    if (rolloverConfig.isRolloverOnShutdown() && cloudOutputLoggers.containsKey(uniqueName)) {
      rollover(cloudOutputLoggers.get(uniqueName));
    }
    logger.info("Remove logger: {}", uniqueName);
    Configuration config = loggerContext.getConfiguration();
    config.removeLogger(uniqueName);
    loggerContext.updateLoggers();
    cloudOutputLoggers.remove(uniqueName);
  }

  void stopUploader() {
    uploader.interrupt();
    if (logFeederProps.isCloudStorageUploadOnShutdown()) {
      logger.info("Do last upload before shutdown.");
      uploader.doUpload(2); // hard-coded 2 minutes timeout on shutdown
    }
  }

  @Override
  public void close() {
    super.close();
    try {
      if (uploadClient != null) {
        uploadClient.close();
      }
    } catch (Exception e) {
      logger.error("Error during closing uploader client", e);
    }
  }

  private void rollover(Logger logger) {
    Map<String, Appender> appenders = ((org.apache.logging.log4j.core.Logger) logger).getAppenders();
    for (Map.Entry<String, Appender> stringAppenderEntry : appenders.entrySet()) {
      Appender appender = stringAppenderEntry.getValue();
      if (appender instanceof RollingFileAppender) {
        ((RollingFileAppender) appender).getManager().rollover();
      }
    }
  }
}
