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

import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.plugin.manager.InputManager;
import org.apache.ambari.logfeeder.plugin.manager.OutputManager;
import org.apache.ambari.logsearch.config.api.LogSearchConfigLogFeeder;
import org.apache.ambari.logsearch.config.api.model.inputconfig.FilterDescriptor;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds common configuration/manager objects for input config managers/handlers in order to provide 1 object as input (instead of many)
 */
public class InputConfigHolder {

  private final LogFeederProps logFeederProps;
  private final LogSearchConfigLogFeeder config;
  private final List<InputDescriptor> inputConfigList = new ArrayList<>();
  private final List<FilterDescriptor> filterConfigList = new ArrayList<>();
  private final List<Map<String, Object>> outputConfigList = new ArrayList<>();

  private final InputManager inputManager;
  private final OutputManager outputManager;

  public InputConfigHolder(LogSearchConfigLogFeeder config, InputManager inputManager, OutputManager outputManager, LogFeederProps logFeederProps) {
    this.logFeederProps = logFeederProps;
    this.config = config;
    this.inputManager = inputManager;
    this.outputManager = outputManager;
  }

  public LogFeederProps getLogFeederProps() {
    return logFeederProps;
  }

  public List<InputDescriptor> getInputConfigList() {
    return inputConfigList;
  }

  public List<FilterDescriptor> getFilterConfigList() {
    return filterConfigList;
  }

  public List<Map<String, Object>> getOutputConfigList() {
    return outputConfigList;
  }

  public InputManager getInputManager() {
    return inputManager;
  }

  public OutputManager getOutputManager() {
    return outputManager;
  }

  public LogSearchConfigLogFeeder getConfig() {
    return config;
  }
}
