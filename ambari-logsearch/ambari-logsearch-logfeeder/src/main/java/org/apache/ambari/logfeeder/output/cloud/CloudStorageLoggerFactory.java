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

import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.OnStartupTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.File;
import java.nio.file.Paths;

/**
 * Create a custom logger that will be used to ship inputs into specific files
 * and rollover those files to an archive folder that will be monitored by a
 * thread which will upload archived files to a cloud storage
 */
public class CloudStorageLoggerFactory {

  private static final String ACTIVE_FOLDER = "active";
  private static final String ARCHIVED_FOLDER = "archived";
  private static final String DATE_PATTERN_SUFFIX_GZ = "-%d{yyyy-MM-dd-HH-mm-ss-SSS}.log.gz";
  private static final String DATE_PATTERN_SUFFIX = "-%d{yyyy-MM-dd-HH-mm-ss-SSS}.log";
  private static final String JSON_DATE_PATTERN_SUFFIX_GZ = "-%d{yyyy-MM-dd-HH-mm-ss-SSS}.json.gz";
  private static final String JSON_DATE_PATTERN_SUFFIX = "-%d{yyyy-MM-dd-HH-mm-ss-SSS}.json";

  public static Logger createLogger(Input input, LoggerContext loggerContext, LogFeederProps logFeederProps) {
    boolean useJsonFormat = logFeederProps.isCloudStorageUseFilters();
    String type = input.getLogType().replace(LogFeederConstants.CLOUD_PREFIX, "");
    String uniqueThreadName = input.getThread().getName();
    Configuration config = loggerContext.getConfiguration();
    String baseDir = logFeederProps.getRolloverConfig().getRolloverArchiveBaseDir();
    String destination = logFeederProps.getCloudStorageDestination().getText();
    String clusterHostnameBaseDir = Paths.get(baseDir, destination, logFeederProps.getClusterName(), LogFeederUtil.hostName).toFile().getAbsolutePath();
    String activeLogDir = Paths.get(clusterHostnameBaseDir, ACTIVE_FOLDER, type).toFile().getAbsolutePath();
    String archiveLogDir = Paths.get(clusterHostnameBaseDir, ARCHIVED_FOLDER, type).toFile().getAbsolutePath();

    boolean useGzip = logFeederProps.getRolloverConfig().isUseGzip();
    final String archiveFilePattern;
    if (useJsonFormat) {
      archiveFilePattern = useGzip ? JSON_DATE_PATTERN_SUFFIX_GZ : JSON_DATE_PATTERN_SUFFIX;
    } else {
      archiveFilePattern = useGzip ? DATE_PATTERN_SUFFIX_GZ : DATE_PATTERN_SUFFIX;
    }

    String logSuffix = useJsonFormat ? ".json" : ".log";
    String fileName = String.join(File.separator, activeLogDir, type + logSuffix);
    String filePattern = String.join(File.separator, archiveLogDir, type + archiveFilePattern);
    PatternLayout layout = PatternLayout.newBuilder()
      .withPattern(PatternLayout.DEFAULT_CONVERSION_PATTERN).build();

    String rolloverSize = logFeederProps.getRolloverConfig().getRolloverSize().toString() + logFeederProps.getRolloverConfig().getRolloverSizeFormat();
    SizeBasedTriggeringPolicy sizeBasedTriggeringPolicy = SizeBasedTriggeringPolicy.createPolicy(rolloverSize);

    final Integer thresholdMin = logFeederProps.getRolloverConfig().getRolloverThresholdTimeMins();
    final Integer thresholdInterval = thresholdMin * 60000; // 1 min = 60000 milliseconds

    TimeBasedTriggeringPolicy timeBasedTriggeringPolicy = TimeBasedTriggeringPolicy.newBuilder()
      .withInterval(thresholdInterval)
      .build();

    final CompositeTriggeringPolicy compositeTriggeringPolicy;

    if (logFeederProps.getRolloverConfig().isRolloverOnStartup()) {
      OnStartupTriggeringPolicy onStartupTriggeringPolicy = OnStartupTriggeringPolicy.createPolicy(1);
      compositeTriggeringPolicy = CompositeTriggeringPolicy
        .createPolicy(sizeBasedTriggeringPolicy, timeBasedTriggeringPolicy, onStartupTriggeringPolicy);
    } else {
      compositeTriggeringPolicy = CompositeTriggeringPolicy
        .createPolicy(sizeBasedTriggeringPolicy, timeBasedTriggeringPolicy);
    }

    DefaultRolloverStrategy defaultRolloverStrategy = DefaultRolloverStrategy.newBuilder()
      .withMax(String.valueOf(logFeederProps.getRolloverConfig().getRolloverMaxBackupFiles()))
      .build();

    boolean immediateFlush = logFeederProps.getRolloverConfig().isImmediateFlush();
    RollingFileAppender appender = RollingFileAppender.newBuilder()
      .withFileName(fileName)
      .withFilePattern(filePattern)
      .withLayout(layout)
      .withName(type)
      .withPolicy(compositeTriggeringPolicy)
      .withStrategy(defaultRolloverStrategy)
      .withImmediateFlush(immediateFlush)
      .build();

    appender.start();
    config.addAppender(appender);

    AppenderRef ref = AppenderRef.createAppenderRef(type, null, null);
    AppenderRef[] refs = new AppenderRef[] {ref};

    LoggerConfig loggerConfig = LoggerConfig
      .createLogger(false, Level.ALL, input.getThread().getName(),
        "true", refs, null, config, null);
    loggerConfig.addAppender(appender, null, null);
    config.addLogger(uniqueThreadName, loggerConfig);
    loggerContext.updateLoggers();
    return loggerContext.getLogger(uniqueThreadName);
  }

}
