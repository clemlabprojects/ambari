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
package org.apache.ambari.logfeeder.manager.operations.impl;

import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logfeeder.conf.LogFeederMode;
import org.apache.ambari.logfeeder.filter.FilterDummy;
import org.apache.ambari.logfeeder.manager.operations.InputConfigHandler;
import org.apache.ambari.logfeeder.manager.InputConfigHolder;
import org.apache.ambari.logfeeder.plugin.common.AliasUtil;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.output.Output;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputConfig;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputDescriptor;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputSocketDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Holds input/filter/output operations in cloud Log Feeder mode.
 */
public class CloudStorageInputConfigHandler extends AbstractInputConfigHandler {

  private static final Logger logger = LogManager.getLogger(CloudStorageInputConfigHandler.class);

  @Override
  public void init(InputConfigHolder inputConfigHolder) {
    logger.info("Call init of cloud input config handler...");
  }

  @Override
  public void loadInputs(String serviceName, InputConfigHolder inputConfigHolder, InputConfig inputConfig) {
    final boolean useFilters = inputConfigHolder.getLogFeederProps().isCloudStorageUseFilters();
    for (InputDescriptor inputDescriptor : inputConfigHolder.getInputConfigList()) {
      if (inputDescriptor == null) {
        logger.warn("Input descriptor is empty. Skipping...");
        continue;
      }
      LogFeederMode mode = inputConfigHolder.getLogFeederProps().getCloudStorageMode();
      if (inputDescriptor instanceof InputSocketDescriptor && LogFeederMode.HYBRID.equals(mode)) {
        logger.info("Socket input is skipped (won't be sent to cloud storage) in hybrid mode");
        continue;
      }
      String source = inputDescriptor.getSource();
      if (StringUtils.isEmpty(source)) {
        logger.error("Input block doesn't have source element");
        continue;
      }
      Input input = (Input) AliasUtil.getClassInstance(source, AliasUtil.AliasType.INPUT);
      if (input == null) {
        logger.error("Input object could not be found");
        continue;
      }
      input.setType(source);
      input.setLogType(LogFeederConstants.CLOUD_PREFIX + inputDescriptor.getType());
      input.loadConfig(inputDescriptor);
      if (!useFilters) {
        FilterDummy filter = new FilterDummy();
        filter.setOutputManager(inputConfigHolder.getOutputManager());
        input.setFirstFilter(filter);
      }
      input.setCloudInput(true);

      if (input.isEnabled()) {
        input.setOutputManager(inputConfigHolder.getOutputManager());
        input.setInputManager(inputConfigHolder.getInputManager());
        inputConfigHolder.getInputManager().add(serviceName, input);
        logger.info("New cloud input object registered for service '{}': '{}'", serviceName, input.getLogType());
        input.logConfigs();
      } else {
        logger.info("Input is disabled. So ignoring it. " + input.getShortDescription());
      }
    }
    if (useFilters) {
      loadFilters(serviceName, inputConfigHolder);
    }
  }

  @Override
  public void assignInputsToOutputs(String serviceName, InputConfigHolder inputConfigHolder, InputConfig config) {
    for (Input input : inputConfigHolder.getInputManager().getInputList(serviceName)) {
      List<Output> outputs = inputConfigHolder.getOutputManager().getOutputs();
      for (Output output : outputs) {
        input.addOutput(output);
      }
    }
  }
}
