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
package org.apache.ambari.logfeeder.conf;

import com.google.common.collect.Maps;
import org.apache.ambari.logfeeder.common.LogFeederSolrClientFactory;
import org.apache.ambari.logfeeder.conf.condition.CloudStorageCondition;
import org.apache.ambari.logfeeder.conf.condition.NonCloudStorageCondition;
import org.apache.ambari.logfeeder.container.docker.DockerContainerRegistry;
import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logfeeder.manager.operations.InputConfigHandler;
import org.apache.ambari.logfeeder.manager.operations.impl.CloudStorageInputConfigHandler;
import org.apache.ambari.logfeeder.input.InputConfigUploader;
import org.apache.ambari.logfeeder.input.InputManagerImpl;
import org.apache.ambari.logfeeder.manager.InputConfigManager;
import org.apache.ambari.logfeeder.output.cloud.CloudStorageOutputManager;
import org.apache.ambari.logfeeder.plugin.manager.CheckpointManager;
import org.apache.ambari.logfeeder.input.file.checkpoint.FileCheckpointManager;
import org.apache.ambari.logfeeder.loglevelfilter.LogLevelFilterHandler;
import org.apache.ambari.logfeeder.manager.operations.impl.DefaultInputConfigHandler;
import org.apache.ambari.logfeeder.metrics.MetricsManager;
import org.apache.ambari.logfeeder.metrics.StatsLogger;
import org.apache.ambari.logfeeder.output.OutputManagerImpl;
import org.apache.ambari.logfeeder.plugin.manager.InputManager;
import org.apache.ambari.logfeeder.plugin.manager.OutputManager;
import org.apache.ambari.logsearch.config.api.LogLevelFilterManager;
import org.apache.ambari.logsearch.config.api.LogLevelFilterUpdater;
import org.apache.ambari.logsearch.config.api.LogSearchConfigFactory;
import org.apache.ambari.logsearch.config.api.LogSearchConfigLogFeeder;
import org.apache.ambari.logsearch.config.local.LogSearchConfigLogFeederLocal;
import org.apache.ambari.logsearch.config.solr.LogLevelFilterManagerSolr;
import org.apache.ambari.logsearch.config.solr.LogLevelFilterUpdaterSolr;
import org.apache.ambari.logsearch.config.zookeeper.LogLevelFilterManagerZK;
import org.apache.ambari.logsearch.config.zookeeper.LogSearchConfigLogFeederZK;
import org.apache.solr.client.solrj.SolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import javax.inject.Inject;
import java.util.HashMap;

@Configuration
@PropertySource(value = {
  "classpath:" + LogFeederConstants.LOGFEEDER_PROPERTIES_FILE
})
public class ApplicationConfig {

  @Inject
  private LogFeederProps logFeederProps;

  @Bean
  public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
    return new PropertySourcesPlaceholderConfigurer();
  }

  @Bean
  public LogFeederSecurityConfig logFeederSecurityConfig() {
    return new LogFeederSecurityConfig();
  }

  @Bean
  public CheckpointManager checkpointHandler() {
    return new FileCheckpointManager();
  }

  @Bean
  public DockerContainerRegistry containerRegistry() {
    if (logFeederProps.isDockerContainerRegistryEnabled()) {
      return DockerContainerRegistry.getInstance(logFeederProps.getProperties());
    } else {
      return null;
    }
  }

  @Bean
  public MetricsManager metricsManager() {
    return new MetricsManager();
  }

  // Non-cloud configurations

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  public StatsLogger statsLogger() throws Exception {
    return new StatsLogger("statsLogger", inputConfigManager());
  }

  @Bean
  @DependsOn({"logSearchConfigLogFeeder", "propertyConfigurer"})
  @Conditional(NonCloudStorageCondition.class)
  public DefaultInputConfigHandler inputConfigHandler() throws Exception {
    return new DefaultInputConfigHandler();
  }

  @Bean
  @DependsOn("logFeederSecurityConfig")
  @Conditional(NonCloudStorageCondition.class)
  public LogSearchConfigLogFeeder logSearchConfigLogFeeder() throws Exception {
    if (logFeederProps.isUseLocalConfigs()) {
      LogSearchConfigLogFeeder logfeederConfig = LogSearchConfigFactory.createLogSearchConfigLogFeeder(
        Maps.fromProperties(logFeederProps.getProperties()),
        logFeederProps.getClusterName(),
        LogSearchConfigLogFeederLocal.class, false);
      logfeederConfig.setLogLevelFilterManager(logLevelFilterManager());
      return logfeederConfig;
    } else {
      return LogSearchConfigFactory.createLogSearchConfigLogFeeder(
        Maps.fromProperties(logFeederProps.getProperties()),
        logFeederProps.getClusterName(),
        LogSearchConfigLogFeederZK.class, false);
    }
  }

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  public LogLevelFilterManager logLevelFilterManager() throws Exception {
    if (logFeederProps.isSolrFilterStorage()) {
      SolrClient solrClient = new LogFeederSolrClientFactory().createSolrClient(
        logFeederProps.getSolrZkConnectString(), logFeederProps.getSolrUrls(), logFeederProps.getSolrMetadataCollection(),
        logFeederProps.isSolrCloudDiscover());
      return new LogLevelFilterManagerSolr(solrClient);
    } else if (logFeederProps.isUseLocalConfigs() && logFeederProps.isZkFilterStorage()) {
      final HashMap<String, String> map = new HashMap<>();
      for (final String name : logFeederProps.getProperties().stringPropertyNames()) {
        map.put(name, logFeederProps.getProperties().getProperty(name));
      }
      return new LogLevelFilterManagerZK(map);
    } else {
      return null;
    }
  }

  @Bean
  @DependsOn("logLevelFilterHandler")
  @Conditional(NonCloudStorageCondition.class)
  public LogLevelFilterUpdater logLevelFilterUpdater() throws Exception {
    if (logFeederProps.isSolrFilterStorage() && logFeederProps.isSolrFilterMonitor()) {
      LogLevelFilterUpdater logLevelFilterUpdater = new LogLevelFilterUpdaterSolr(
        "filter-updater-solr", logLevelFilterHandler(),
        logFeederProps.getSolrFilterMonitorInterval(), (LogLevelFilterManagerSolr) logLevelFilterManager(), logFeederProps.getClusterName());
      logLevelFilterUpdater.start();
      return logLevelFilterUpdater;
    }
    return null;
  }

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  public LogLevelFilterHandler logLevelFilterHandler() throws Exception {
    return new LogLevelFilterHandler(logSearchConfigLogFeeder());
  }

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  @DependsOn({"inputConfigHandler"})
  public InputConfigUploader inputConfigUploader() throws Exception {
    return new InputConfigUploader("Input Config Loader", logSearchConfigLogFeeder(),
      inputConfigManager(), logLevelFilterHandler());
  }

  @Bean
  @DependsOn({"containerRegistry", "checkpointHandler"})
  @Conditional(NonCloudStorageCondition.class)
  public InputManager inputManager() {
    return new InputManagerImpl("InputIsNotReadyMonitor");
  }

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  public OutputManager outputManager() throws Exception {
    return new OutputManagerImpl();
  }

  @Bean
  @Conditional(NonCloudStorageCondition.class)
  public InputConfigManager inputConfigManager() throws Exception {
    return new InputConfigManager(logSearchConfigLogFeeder(), inputManager(), outputManager(),
      inputConfigHandler(), logFeederProps, true);
  }

  // Cloud configurations

  @Bean(name = "cloudLogSearchLogFeederConfig")
  @Conditional(CloudStorageCondition.class)
  public LogSearchConfigLogFeeder cloudLogSearchLogFeederConfig() throws Exception {
    return LogSearchConfigFactory.createLogSearchConfigLogFeeder(
      Maps.fromProperties(logFeederProps.getProperties()),
      logFeederProps.getClusterName(),
      LogSearchConfigLogFeederLocal.class, false);
  }

  @Bean
  @Conditional(CloudStorageCondition.class)
  @DependsOn({"cloudInputConfigHandler"})
  public InputConfigUploader cloudInputConfigUploader() throws Exception {
    return new InputConfigUploader("Cloud Input Config Loader", cloudLogSearchLogFeederConfig(),
      cloudInputConfigManager(),null);
  }

  @Bean
  @DependsOn({"containerRegistry", "checkpointHandler"})
  @Conditional(CloudStorageCondition.class)
  public InputManager cloudInputManager() {
    return new InputManagerImpl("CloudInputIsNotReady");
  }

  @Bean
  @Conditional(CloudStorageCondition.class)
  public OutputManager cloudOutputManager() throws Exception {
    return new CloudStorageOutputManager();
  }

  @Bean
  @Conditional(CloudStorageCondition.class)
  public InputConfigHandler cloudInputConfigHandler() {
    return new CloudStorageInputConfigHandler();
  }

  @Bean
  @Conditional(CloudStorageCondition.class)
  public InputConfigManager cloudInputConfigManager() throws Exception {
    return new InputConfigManager(cloudLogSearchLogFeederConfig(), cloudInputManager(), cloudOutputManager(),
      cloudInputConfigHandler(), logFeederProps, false);
  }

  @Bean
  @Conditional(CloudStorageCondition.class)
  public StatsLogger cloudStatsLogger() throws Exception {
    return new StatsLogger("cloudStatsLogger", cloudInputConfigManager());
  }
}
