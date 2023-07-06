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

import org.apache.ambari.logfeeder.manager.InputConfigHolder;
import org.apache.ambari.logfeeder.manager.operations.InputConfigHandler;
import org.apache.ambari.logfeeder.plugin.common.AliasUtil;
import org.apache.ambari.logfeeder.plugin.filter.Filter;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logsearch.config.api.model.inputconfig.FilterDescriptor;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds common operations for input config handlers
 */
public abstract class AbstractInputConfigHandler implements InputConfigHandler {

  private static final Logger logger = LogManager.getLogger(AbstractInputConfigHandler.class);

  protected void loadFilters(String serviceName, InputConfigHolder inputConfigHolder) {
    sortFilters(inputConfigHolder);

    List<Input> toRemoveInputList = new ArrayList<>();
    for (Input input : inputConfigHolder.getInputManager().getInputList(serviceName)) {
      for (FilterDescriptor filterDescriptor : inputConfigHolder.getFilterConfigList()) {
        if (filterDescriptor == null) {
          logger.warn("Filter descriptor is empty. Skipping...");
          continue;
        }
        if (BooleanUtils.isFalse(filterDescriptor.isEnabled())) {
          logger.debug("Ignoring filter " + filterDescriptor.getFilter() + " because it is disabled");
          continue;
        }
        if (!input.isFilterRequired(filterDescriptor)) {
          logger.debug("Ignoring filter " + filterDescriptor.getFilter() + " for input " + input.getShortDescription());
          continue;
        }

        String value = filterDescriptor.getFilter();
        if (StringUtils.isEmpty(value)) {
          logger.error("Filter block doesn't have filter element");
          continue;
        }
        Filter filter = (Filter) AliasUtil.getClassInstance(value, AliasUtil.AliasType.FILTER);
        if (filter == null) {
          logger.error("Filter object could not be found");
          continue;
        }
        filter.loadConfig(filterDescriptor);
        filter.setInput(input);

        filter.setOutputManager(inputConfigHolder.getOutputManager());
        input.addFilter(filter);
        filter.logConfigs();
      }

      if (input.getFirstFilter() == null) {
        toRemoveInputList.add(input);
      }
    }

    for (Input toRemoveInput : toRemoveInputList) {
      logger.warn("There are no filters, we will ignore this input. " + toRemoveInput.getShortDescription());
      inputConfigHolder.getInputManager().removeInput(toRemoveInput);
    }
  }

  protected void sortFilters(InputConfigHolder inputConfigHolder) {
    Collections.sort(inputConfigHolder.getFilterConfigList(), (o1, o2) -> {
      Integer o1Sort = o1.getSortOrder();
      Integer o2Sort = o2.getSortOrder();
      if (o1Sort == null || o2Sort == null) {
        return 0;
      }

      return o1Sort - o2Sort;
    });
  }
}
