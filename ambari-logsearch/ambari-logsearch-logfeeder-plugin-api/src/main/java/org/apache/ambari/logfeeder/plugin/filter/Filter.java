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
package org.apache.ambari.logfeeder.plugin.filter;

import org.apache.ambari.logfeeder.plugin.common.AliasUtil;
import org.apache.ambari.logfeeder.plugin.common.ConfigItem;
import org.apache.ambari.logfeeder.plugin.common.LogFeederProperties;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.filter.mapper.Mapper;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.plugin.manager.OutputManager;
import org.apache.ambari.logsearch.config.api.model.inputconfig.FilterDescriptor;
import org.apache.ambari.logsearch.config.api.model.inputconfig.MapFieldDescriptor;
import org.apache.ambari.logsearch.config.api.model.inputconfig.PostMapValues;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the filter in Log Feeder shipper input configurations.
 * At least 1 filter is required for a valid input config.
 * Can transform inputs (adding/removing/create fields), those will be shipped to outputs or other filters (in chain)
 * @param <PROP_TYPE> Log Feeder configuration holder object
 */
public abstract class Filter<PROP_TYPE extends LogFeederProperties> extends ConfigItem<PROP_TYPE> {

  private static final Logger logger = LogManager.getLogger(Filter.class);

  private final Map<String, List<Mapper>> postFieldValueMappers = new HashMap<>();
  private FilterDescriptor filterDescriptor;
  private Filter nextFilter = null;
  private Input input;
  private OutputManager outputManager;

  public FilterDescriptor getFilterDescriptor() {
    return filterDescriptor;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init(PROP_TYPE logFeederProperties) throws Exception {
    initializePostMapValues(logFeederProperties);
    if (nextFilter != null) {
      nextFilter.init(logFeederProperties);
    }
  }

  private void initializePostMapValues(PROP_TYPE logFeederProperties) {
    Map<String, ? extends List<? extends PostMapValues>> postMapValues = filterDescriptor.getPostMapValues();
    if (postMapValues == null) {
      return;
    }
    for (String fieldName : postMapValues.keySet()) {
      List<? extends PostMapValues> values = postMapValues.get(fieldName);
      for (PostMapValues pmv : values) {
        for (MapFieldDescriptor mapFieldDescriptor : pmv.getMappers()) {
          String mapClassCode = mapFieldDescriptor.getJsonName();
          Mapper mapper = (Mapper) AliasUtil.getClassInstance(mapClassCode, AliasUtil.AliasType.MAPPER);
          if (mapper == null) {
            logger.warn("Unknown mapper type: " + mapClassCode);
            continue;
          }
          if (mapper.init(logFeederProperties, getInput().getShortDescription(), fieldName, mapClassCode, mapFieldDescriptor)) {
            List<Mapper> fieldMapList = postFieldValueMappers.computeIfAbsent(fieldName, k -> new ArrayList<>());
            fieldMapList.add(mapper);
          }
        }
      }
    }
  }

  /**
   * Apply a filter on an input (input can be an output of an another filter). Deriving classes should implement this at the minimum.
   * @param inputStr Incoming input as a string
   * @param inputMarker Marker which can identify a specific input (like line number + input details)
   * @throws Exception Any error which happens during applying the filter
   */
  public void apply(String inputStr, InputMarker inputMarker) throws Exception {
    // TODO: There is no transformation for string types.
    if (nextFilter != null) {
      nextFilter.apply(inputStr, inputMarker);
    } else {
      outputManager.write(inputStr, inputMarker);
    }
  }

  /**
   * Apply a filter on an input (input can be an output of an another filter).
   * @param jsonObj Key/value pairs of incoming inputs - mostly fields and values
   * @param inputMarker Marker which can identify a specific input (like line number + input details)
   * @throws Exception Any error which happens during applying the filter
   */
  public void apply(Map<String, Object> jsonObj, InputMarker inputMarker) throws Exception {
    for (String fieldName : postFieldValueMappers.keySet()) {
      Object value = jsonObj.get(fieldName);
      if (value != null) {
        for (Mapper mapper : postFieldValueMappers.get(fieldName)) {
          value = mapper.apply(jsonObj, value);
        }
      }
    }
    fallbackFieldNames(jsonObj);
    if (nextFilter != null) {
      nextFilter.apply(jsonObj, inputMarker);
    } else {
      outputManager.write(jsonObj, inputMarker);
    }
  }

  /**
   * Set filter descriptor shipper configuration object for the filter
   * @param filterDescriptor Filter descriptor, stores filter configurations
   */
  public void loadConfig(FilterDescriptor filterDescriptor) {
    this.filterDescriptor = filterDescriptor;
  }

  public Filter getNextFilter() {
    return nextFilter;
  }

  public void setNextFilter(Filter nextFilter) {
    this.nextFilter = nextFilter;
  }

  public Input getInput() {
    return input;
  }

  public void setInput(Input input) {
    this.input = input;
  }

  public void setOutputManager(OutputManager outputManager) {
    this.outputManager = outputManager;
  }

  /**
   * Call flush on a filter - implement only if any kind of flush is required for the resources of a filter, which is different from the close operation.
   */
  public void flush() {
    // empty
  }

  /**
   * Implement this for specific filter if it is required to close resources properly. By default it tries to close the next chained filter.
   * (you can keep this behaviour if you are using with super.close() )
   */
  public void close() {
    if (nextFilter != null) {
      nextFilter.close();
    }
  }

  @Override
  public boolean isEnabled() {
    return filterDescriptor.isEnabled() != null ? filterDescriptor.isEnabled() : true;
  }

  @Override
  public void addMetricsContainers(List<MetricData> metricsList) {
    super.addMetricsContainers(metricsList);
    if (nextFilter != null) {
      nextFilter.addMetricsContainers(metricsList);
    }
  }

  @Override
  public boolean logConfigs() {
    logger.info("filter=" + getShortDescription());
    return true;
  }

  @Override
  public String getStatMetricName() {
    // no metrics yet
    return null;
  }

  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  /**
   * Fallback field names to use _ instead of spaces and use lowercase names with ws suffixes, built-in max: 100 characters - if the name is too big, probably it won't be valid anyway
   * @param jsonObj field / value pairs to process
   */
  protected void fallbackFieldNames(Map<String, Object> jsonObj) {
    final Set<String> fieldsToRemove = new HashSet<>();
    final Map<String, Object> fieldValuePairsToAdd = new HashMap<>();
    for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
      String name = entry.getKey();
      if (containsWhitespace(name) && name.length() < 100) {
        fieldsToRemove.add(name);
        name = "ws_" + name.toLowerCase().replaceAll(" ", "_");
        if (!jsonObj.containsKey(name)) {
          fieldValuePairsToAdd.put(name, entry.getValue());
        }
      }
    }
    for (String fieldToRemove : fieldsToRemove) {
      jsonObj.remove(fieldToRemove);
    }
    for (Map.Entry<String, Object> entry : fieldValuePairsToAdd.entrySet()) {
      jsonObj.put(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Check that string contains whitespaces or not - similar as StringUtils function in order to not include it as a dependency
   * @param seq character sequence
   * @return character sequence contains whitespace or not
   */
  private boolean containsWhitespace(CharSequence seq) {
    if (seq == null || seq.length() == 0) {
      return false;
    } else {
      int strLen = seq.length();

      for(int i = 0; i < strLen; ++i) {
        if (Character.isWhitespace(seq.charAt(i))) {
          return true;
        }
      }
      return false;
    }
  }
}
