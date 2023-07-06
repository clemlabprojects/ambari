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
package org.apache.ambari.logfeeder.manager.operations;

import org.apache.ambari.logfeeder.manager.InputConfigHolder;;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputConfig;

/**
 * Holds operations regarding input config handling. (init configs, load input configs and assign inputs to outputs)
 */
public interface InputConfigHandler {

  /**
   * Initialization step before loading inputs/filter/outputs
   * @param inputConfigHolder object that holds input/filter/output configuration details
   * @throws Exception error during initialization
   */
  void init(InputConfigHolder inputConfigHolder) throws Exception;

  /**
   * Step during input/filter configurations initialization
   * @param serviceName group of input configs
   * @param inputConfigHolder object that holds input/filter/output configuration details
   * @param config input/filter config object
   */
  void loadInputs(String serviceName, InputConfigHolder inputConfigHolder, InputConfig config);

  /**
   * Assign inputs to outputs - after inputs/filters/outputs are loaded
   * @param serviceName group of input configs
   * @param inputConfigHolder object that holds input/filter/output configuration details
   * @param config input/filter config object
   * @throws Exception error during input/output assignment
   */
  void assignInputsToOutputs(String serviceName, InputConfigHolder inputConfigHolder, InputConfig config) throws Exception;

}
