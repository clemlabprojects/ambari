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
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.filter.Filter;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.ambari.logsearch.config.api.model.inputconfig.FilterKeyValueDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Filter for parsing lines as key value pairs (it is required to provide delimiters for splitting values/fields and borders as well)
 * Example configuration: (input: "User(admin), RemoteIp(10.0.0.1)")
 * <pre>
 * "filter": [
 *    "filter": "keyvalue",
 *    "sort_order": 1,
 *    "conditions": {
 *      "fields": {
 *         "type": [
 *           "ambari_audit"
 *         ]
 *      }
 *    },
 *    "source_field": "log_message",
 *    "field_split": ", ",
 *    "value_borders": "()",
 *    "post_map_values": {
 *      "User": {
 *        "map_field_value": {
 *           "pre_value": "null",
 *           "post_value": "unknown"
 *        }
 *      }
 *    }
 * ]
 * </pre>
 */
public class FilterKeyValue extends Filter<LogFeederProps> {

  private static final Logger logger = LogManager.getLogger(FilterKeyValue.class);

  private String sourceField = null;
  private String valueSplit = "=";
  private String fieldSplit = "\t";
  private String valueBorders = null;
  
  private MetricData errorMetric = new MetricData("filter.error.keyvalue", false);

  @Override
  public void init(LogFeederProps logFeederProps) throws Exception {
    super.init(logFeederProps);

    sourceField = getFilterDescriptor().getSourceField();
    valueSplit = StringUtils.defaultString(((FilterKeyValueDescriptor)getFilterDescriptor()).getValueSplit(), valueSplit);
    fieldSplit = StringUtils.defaultString(((FilterKeyValueDescriptor)getFilterDescriptor()).getFieldSplit(), fieldSplit);
    valueBorders = ((FilterKeyValueDescriptor)getFilterDescriptor()).getValueBorders();

    logger.info("init() done. source_field=" + sourceField + ", value_split=" + valueSplit + ", " + ", field_split=" +
        fieldSplit + ", " + getShortDescription());
    if (StringUtils.isEmpty(sourceField)) {
      logger.fatal("source_field is not set for filter. Thiss filter will not be applied");
    }
  }

  @Override
  public void apply(String inputStr, InputMarker inputMarker) throws Exception {
    apply(LogFeederUtil.toJSONObject(inputStr), inputMarker);
  }

  @Override
  public void apply(Map<String, Object> jsonObj, InputMarker inputMarker) throws Exception {
    if (sourceField == null) {
      return;
    }
    if (jsonObj.containsKey(sourceField)) {
      String keyValueString = (String) jsonObj.get(sourceField);
      Map<String, String> valueMap = new HashMap<>();
      if (valueBorders != null) {
        keyValueString = preProcessBorders(keyValueString, valueMap);
      }
      
      String splitPattern = Pattern.quote(fieldSplit);
      String[] tokens = keyValueString.split(splitPattern);
      for (String nv : tokens) {
        String[] nameValue = getNameValue(nv);
        String name = nameValue.length == 2 ? nameValue[0] : null;
        String value = nameValue.length == 2 ? nameValue[1] : null;
        if (name != null && value != null) {
          if (valueMap.containsKey(value)) {
            value = valueMap.get(value);
          }
          jsonObj.put(name, value);
        } else {
         logParseError("name=" + name + ", pair=" + nv + ", field=" + sourceField + ", field_value=" + keyValueString);
        }
      }
    }
    super.apply(jsonObj, inputMarker);
    statMetric.value++;
  }

  private String preProcessBorders(String keyValueString, Map<String, String> valueMap) {
    char openBorder = valueBorders.charAt(0);
    char closeBorder = valueBorders.charAt(1);
    
    StringBuilder processed = new StringBuilder();
    int lastPos = 0;
    int openBorderNum = 0;
    int valueNum = 0;
    for (int pos = 0; pos < keyValueString.length(); pos++) {
      char c = keyValueString.charAt(pos);
      if (c == openBorder) {
        if (openBorderNum == 0 ) {
          processed.append(keyValueString.substring(lastPos, pos));
          lastPos = pos + 1;
        }
        openBorderNum++;
      }
      if (c == closeBorder) {
        openBorderNum--;
        if (openBorderNum == 0) {
          String value = keyValueString.substring(lastPos, pos).trim();
          String valueId = "$VALUE" + (++valueNum);
          valueMap.put(valueId, value);
          processed
            .append(valueSplit)
            .append(valueId);
          lastPos = pos + 1;
        }
      }
    }
    
    return processed.toString();
  }

  private String[] getNameValue(String nv) {
    String splitPattern = Pattern.quote(valueSplit);
    return nv.split(splitPattern, 2);
  }

  private void logParseError(String inputStr) {
    errorMetric.value++;
    String logMessageKey = this.getClass().getSimpleName() + "_PARSEERROR";
    LogFeederUtil.logErrorMessageByInterval(logMessageKey, "Error parsing string. length=" + inputStr.length() + ", input=" +
        getInput().getShortDescription() + ". First upto 200 characters=" + StringUtils.abbreviate(inputStr, 200), null, logger,
        Level.ERROR);
  }

  @Override
  public String getShortDescription() {
    return "filter:filter=keyvalue,regex=" + sourceField;
  }

  @Override
  public void addMetricsContainers(List<MetricData> metricsList) {
    super.addMetricsContainers(metricsList);
    metricsList.add(errorMetric);
  }
}
