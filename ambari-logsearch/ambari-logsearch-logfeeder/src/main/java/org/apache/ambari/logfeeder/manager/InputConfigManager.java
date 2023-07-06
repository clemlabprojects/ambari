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
package org.apache.ambari.logfeeder.manager;

import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.manager.operations.InputConfigHandler;
import org.apache.ambari.logfeeder.input.InputSimulate;
import org.apache.ambari.logfeeder.plugin.common.AliasUtil;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.manager.InputManager;
import org.apache.ambari.logfeeder.plugin.manager.OutputManager;
import org.apache.ambari.logfeeder.plugin.output.Output;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.ambari.logsearch.config.api.InputConfigMonitor;
import org.apache.ambari.logsearch.config.api.LogSearchConfigLogFeeder;
import org.apache.ambari.logsearch.config.api.model.inputconfig.FilterDescriptor;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputConfig;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputDescriptor;
import org.apache.ambari.logsearch.config.json.model.inputconfig.impl.FilterDescriptorImpl;
import org.apache.ambari.logsearch.config.json.model.inputconfig.impl.InputConfigImpl;
import org.apache.ambari.logsearch.config.json.model.inputconfig.impl.InputDescriptorImpl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.ClassPathResource;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facade class for input config operations (add / load / remove configs and start or close input monitoring)
 */
public class InputConfigManager implements InputConfigMonitor {

  private Logger logger = LogManager.getLogger(InputConfigManager.class);

  private final InputConfigHandler inputConfigHandler;
  private final LogSearchConfigLogFeeder logSearchConfig;
  private final LogFeederProps logFeederProps;
  private final InputConfigHolder inputConfigHolder;
  private final boolean loadOutput;

  private final Map<String, Object> globalConfigs = new HashMap<>();
  private final List<String> globalConfigJsons = new ArrayList<>();

  private boolean simulateMode = false;

  public InputConfigManager(LogSearchConfigLogFeeder logSearchConfig, InputManager inputManager,
                            OutputManager outputManager, InputConfigHandler inputConfigHandler,
                            LogFeederProps logFeederProps, boolean loadOutput) {
    this.logSearchConfig = logSearchConfig;
    this.inputConfigHandler = inputConfigHandler;
    this.logFeederProps = logFeederProps;
    this.loadOutput = loadOutput;
    this.inputConfigHolder = new InputConfigHolder(logSearchConfig, inputManager, outputManager, logFeederProps);
  }

  @PostConstruct
  public void init() throws Exception {
    loadConfigFiles();
    logSearchConfig.init(Maps.fromProperties(logFeederProps.getProperties()), logFeederProps.getClusterName());
    inputConfigHandler.init(inputConfigHolder);
    simulateIfNeeded();
    if (loadOutput) {
      loadOutputs();
    }
    inputConfigHolder.getInputManager().init();
    inputConfigHolder.getOutputManager().init();
  }

  @Override
  public List<String> getGlobalConfigJsons() {
    return this.globalConfigJsons;
  }

  @Override
  public void loadInputConfigs(String serviceName, InputConfig inputConfig) throws Exception {
    inputConfigHolder.getInputConfigList().clear();
    inputConfigHolder.getFilterConfigList().clear();
    inputConfigHolder.getInputConfigList().addAll(inputConfig.getInput());
    inputConfigHolder.getFilterConfigList().addAll(inputConfig.getFilter());
    if (simulateMode) {
      InputSimulate.loadTypeToFilePath(inputConfigHolder.getInputConfigList());
    } else {
      inputConfigHandler.loadInputs(serviceName, inputConfigHolder, inputConfig);
      inputConfigHandler.assignInputsToOutputs(serviceName, inputConfigHolder, inputConfig);
    }
    inputConfigHolder.getInputManager().startInputs(serviceName);
  }

  @Override
  public void removeInputs(String serviceName) {
    inputConfigHolder.getInputManager().removeInputsForService(serviceName);
  }

  public void cleanCheckPointFiles() {
    inputConfigHolder.getInputManager().getCheckpointHandler().cleanupCheckpoints();
  }

  public void logStats() {
    inputConfigHolder.getInputManager().logStats();
    inputConfigHolder.getOutputManager().logStats();
  }

  public void addMetrics(List<MetricData> metricsList) {
    inputConfigHolder.getInputManager().addMetricsContainers(metricsList);
    inputConfigHolder.getOutputManager().addMetricsContainers(metricsList);
  }

  @PreDestroy
  public void close() {
    inputConfigHolder.getInputManager().close();
    inputConfigHolder.getOutputManager().close();
    inputConfigHolder.getInputManager().checkInAll();
  }

  public Input getTestInput(InputConfig inputConfig, String logId) {
    for (InputDescriptor inputDescriptor : inputConfig.getInput()) {
      if (inputDescriptor.getType().equals(logId)) {
        inputConfigHolder.getInputConfigList().add(inputDescriptor);
        break;
      }
    }
    if (inputConfigHolder.getInputConfigList().isEmpty()) {
      throw new IllegalArgumentException("Log Id " + logId + " was not found in shipper configuriaton");
    }

    for (FilterDescriptor filterDescriptor : inputConfig.getFilter()) {
      inputConfigHolder.getFilterConfigList().add(filterDescriptor);
    }
    inputConfigHandler.loadInputs("test", inputConfigHolder, inputConfig);
    List<Input> inputList = inputConfigHolder.getInputManager().getInputList("test");

    return inputList != null && inputList.size() == 1 ? inputList.get(0) : null;
  }

  private void loadConfigFiles() throws Exception {
    List<String> configFiles = getConfigFiles();
    for (String configFileName : configFiles) {
      logger.info("Going to load config file:" + configFileName);
      configFileName = configFileName.replace("\\ ", "%20");
      File configFile = new File(configFileName);
      if (configFile.exists() && configFile.isFile()) {
        logger.info("Config file exists in path." + configFile.getAbsolutePath());
        loadConfigsUsingFile(configFile);
      } else {
        logger.info("Trying to load config file from classloader: " + configFileName);
        loadConfigsUsingClassLoader(configFileName);
        logger.info("Loaded config file from classloader: " + configFileName);
      }
    }
  }

  private List<String> getConfigFiles() {
    List<String> configFiles = new ArrayList<>();
    String logFeederConfigFilesProperty = logFeederProps.getConfigFiles();
    logger.info("logfeeder.config.files=" + logFeederConfigFilesProperty);
    if (logFeederConfigFilesProperty != null) {
      configFiles.addAll(Arrays.asList(logFeederConfigFilesProperty.split(",")));
    }
    return configFiles;
  }

  private void loadConfigsUsingFile(File configFile) throws Exception {
    try {
      String configData = FileUtils.readFileToString(configFile, Charset.defaultCharset());
      loadConfigs(configData);
    } catch (Exception t) {
      logger.error("Error opening config file. configFilePath=" + configFile.getAbsolutePath());
      throw t;
    }
  }

  private void loadConfigsUsingClassLoader(String configFileName) throws Exception {
    try (BufferedInputStream fis = (BufferedInputStream) this.getClass().getClassLoader().getResourceAsStream(configFileName)) {
      ClassPathResource configFile = new ClassPathResource(configFileName);
      if (!configFile.exists()) {
        throw new FileNotFoundException(configFileName);
      }
      String configData = IOUtils.toString(fis, Charset.defaultCharset());
      loadConfigs(configData);
    }
  }

  @SuppressWarnings("unchecked")
  private void loadConfigs(String configData) throws Exception {
    Type type = new TypeToken<Map<String, Object>>() {}.getType();
    Map<String, Object> configMap = LogFeederUtil.getGson().fromJson(configData, type);

    // Get the globals
    for (String key : configMap.keySet()) {
      switch (key) {
        case "global" :
          globalConfigs.putAll((Map<String, Object>) configMap.get(key));
          globalConfigJsons.add(configData);
          break;
        case "output" :
          List<Map<String, Object>> outputConfig = (List<Map<String, Object>>) configMap.get(key);
          inputConfigHolder.getOutputConfigList().addAll(outputConfig);
          break;
        default :
          logger.warn("Unknown config key: " + key);
      }
    }
  }

  private void loadOutputs() {
    for (Map<String, Object> map : inputConfigHolder.getOutputConfigList()) {
      if (map == null) {
        logger.warn("Output map is empty. Skipping...");
        continue;
      }
      BlockMerger.mergeBlocks(globalConfigs, map);

      String value = (String) map.get("destination");
      if (StringUtils.isEmpty(value)) {
        logger.error("Output block doesn't have destination element");
        continue;
      }
      Output output = (Output) AliasUtil.getClassInstance(value, AliasUtil.AliasType.OUTPUT);
      if (output == null) {
        logger.error("Output object could not be found");
        continue;
      }
      output.setDestination(value);
      output.loadConfig(map);
      output.setLogSearchConfig(logSearchConfig);

      // We will only check for is_enabled out here. Down below we will check whether this output is enabled for the input
      if (output.isEnabled()) {
        output.logConfigs();
        inputConfigHolder.getOutputManager().add(output);
      } else {
        logger.info("Output is disabled. So ignoring it. " + output.getShortDescription());
      }
    }
  }

  private void simulateIfNeeded() throws Exception {
    int simulatedInputNumber = inputConfigHolder.getLogFeederProps().getInputSimulateConfig().getSimulateInputNumber();
    if (simulatedInputNumber == 0)
      return;

    InputConfigImpl simulateInputConfig = new InputConfigImpl();
    List<InputDescriptorImpl> inputConfigDescriptors = new ArrayList<>();
    simulateInputConfig.setInput(inputConfigDescriptors);
    simulateInputConfig.setFilter(new ArrayList<FilterDescriptorImpl>());
    for (int i = 0; i < simulatedInputNumber; i++) {
      InputDescriptorImpl inputDescriptor = new InputDescriptorImpl() {};
      inputDescriptor.setSource("simulate");
      inputDescriptor.setRowtype("service");
      inputDescriptor.setAddFields(new HashMap<String, String>());
      inputConfigDescriptors.add(inputDescriptor);
    }

    loadInputConfigs("Simulation", simulateInputConfig);

    simulateMode = true;
  }
}
