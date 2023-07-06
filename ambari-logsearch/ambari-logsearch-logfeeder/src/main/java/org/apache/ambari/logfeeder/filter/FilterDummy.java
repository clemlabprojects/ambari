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
package org.apache.ambari.logfeeder.filter;

import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.input.InputFile;
import org.apache.ambari.logfeeder.plugin.filter.Filter;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logsearch.config.api.model.inputconfig.InputFileDescriptor;
import org.apache.commons.lang3.BooleanUtils;

/**
 * Simple dummy filter, not supported by config api, create it manually
 */
public class FilterDummy extends Filter<LogFeederProps> {

  private boolean dockerEnabled = false;

  @Override
  public void init(LogFeederProps logFeederProps) throws Exception {
    if (logFeederProps.isDockerContainerRegistryEnabled()) {
      Input input = getInput();
      if (input instanceof InputFile) {
        dockerEnabled = BooleanUtils.toBooleanDefaultIfNull(((InputFileDescriptor) input.getInputDescriptor()).getDockerEnabled(), false);
      }
    }
  }

  @Override
  public void apply(String inputStr, InputMarker inputMarker) throws Exception {
    if (dockerEnabled) {
      inputStr = DockerLogFilter.getLogFromDockerJson(inputStr);
    }
    super.apply(inputStr, inputMarker);
  }

  @Override
  public String getShortDescription() {
    return "filter:filter=dummy";
  }
}
