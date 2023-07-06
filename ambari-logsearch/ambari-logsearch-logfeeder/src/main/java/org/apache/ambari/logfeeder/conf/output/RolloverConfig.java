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
public class RolloverConfig {

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_ARCHIVE_LOCATION,
    description = "Location where the active and archives logs will be stored. Beware, it could require a large amount of space, use mounted disks if it is possible.",
    examples = {"/var/lib/ambari-logsearch-logfeeder/data"},
    defaultValue = "/tmp",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_ARCHIVE_LOCATION + ":/tmp}")
  private String rolloverArchiveBaseDir;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_MIN,
    description = "Rollover cloud log files after an interval (minutes)",
    examples = {"1"},
    defaultValue = "60",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_MIN + ":60}")
  private int rolloverThresholdTimeMins;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE,
    description = "Rollover cloud log files after the log file size reach this limit",
    examples = {"1024"},
    defaultValue = "80",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE + ":80}")
  private Integer rolloverSize;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_MAX_BACKUP_FILES,
    description = "The number of max backup log files for rolled over logs.",
    examples = {"50"},
    defaultValue = "10",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_MAX_BACKUP_FILES + ":10}")
  private Integer rolloverMaxBackupFiles;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE_UNIT,
    description = "Rollover cloud log file size unit (e.g: KB, MB etc.)",
    examples = {"KB"},
    defaultValue = "MB",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE_UNIT + ":MB}")
  private String rolloverSizeFormat;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_USE_GZIP,
    description = "Use GZip on archived logs.",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_USE_GZIP + ":true}")
  private boolean useGzip;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_IMMEDIATE_FLUSH,
    description = "Immediately flush temporal cloud logs (to active location).",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_IMMEDIATE_FLUSH + ":false}")
  private boolean immediateFlush;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_ON_SHUTDOWN,
    description = "Rollover temporal cloud log files on shutdown",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_ON_SHUTDOWN + ":false}")
  private boolean rolloverOnShutdown;

  @LogSearchPropertyDescription(
    name = LogFeederConstants.CLOUD_ROLLOVER_ON_STARTUP,
    description = "Rollover temporal cloud log files on startup",
    examples = {"false"},
    defaultValue = "true",
    sources = {LogFeederConstants.LOGFEEDER_PROPERTIES_FILE}
  )
  @Value("${"+ LogFeederConstants.CLOUD_ROLLOVER_ON_STARTUP + ":false}")
  private boolean rolloverOnStartup;

  public int getRolloverThresholdTimeMins() {
    return rolloverThresholdTimeMins;
  }

  public void setRolloverThresholdTimeMins(int rolloverThresholdTimeMins) {
    this.rolloverThresholdTimeMins = rolloverThresholdTimeMins;
  }

  public Integer getRolloverMaxBackupFiles() {
    return rolloverMaxBackupFiles;
  }

  public void setRolloverMaxBackupFiles(Integer rolloverMaxBackupFiles) {
    this.rolloverMaxBackupFiles = rolloverMaxBackupFiles;
  }

  public Integer getRolloverSize() {
    return rolloverSize;
  }

  public void setRolloverSize(Integer rolloverSize) {
    this.rolloverSize = rolloverSize;
  }

  public String getRolloverSizeFormat() {
    return rolloverSizeFormat;
  }

  public void setRolloverSizeFormat(String rolloverSizeFormat) {
    this.rolloverSizeFormat = rolloverSizeFormat;
  }

  public boolean isUseGzip() {
    return useGzip;
  }

  public void setUseGzip(boolean useGzip) {
    this.useGzip = useGzip;
  }

  public boolean isImmediateFlush() {
    return immediateFlush;
  }

  public void setImmediateFlush(boolean immediateFlush) {
    this.immediateFlush = immediateFlush;
  }

  public boolean isRolloverOnShutdown() {
    return rolloverOnShutdown;
  }

  public void setRolloverOnShutdown(boolean rolloverOnShutdown) {
    this.rolloverOnShutdown = rolloverOnShutdown;
  }

  public boolean isRolloverOnStartup() {
    return rolloverOnStartup;
  }

  public void setRolloverOnStartup(boolean rolloverOnStartup) {
    this.rolloverOnStartup = rolloverOnStartup;
  }

  public String getRolloverArchiveBaseDir() {
    return rolloverArchiveBaseDir;
  }

  public void setRolloverArchiveBaseDir(String rolloverArchiveBaseDir) {
    this.rolloverArchiveBaseDir = rolloverArchiveBaseDir;
  }
}
