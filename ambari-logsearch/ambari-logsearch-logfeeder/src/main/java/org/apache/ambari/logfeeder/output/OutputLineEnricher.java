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
package org.apache.ambari.logfeeder.output;

import com.google.common.hash.Hashing;
import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Utility class for fill output with other fields
 */
public class OutputLineEnricher {

  private static final Logger logger = LogManager.getLogger(OutputLineEnricher.class);

  private static final int MAX_OUTPUT_SIZE = 32765; // 32766-1

  public Map<String, Object> enrichFields(final Map<String, Object> jsonObj, final InputMarker inputMarker, final MetricData messageTruncateMetric) {
    Input input = inputMarker.getInput();
    // Update the block with the context fields
    for (Map.Entry<String, String> entry : input.getInputDescriptor().getAddFields().entrySet()) {
      if (jsonObj.get(entry.getKey()) == null || "cluster".equals(entry.getKey()) && "null".equals(jsonObj.get(entry.getKey()))) {
        jsonObj.put(entry.getKey(), entry.getValue());
      }
    }
    // TODO: Ideally most of the overrides should be configurable
    LogFeederUtil.fillMapWithFieldDefaults(jsonObj, inputMarker, true);
    if (input.isUseEventMD5() || input.isGenEventMD5()) {
      String prefix = "";
      Object logtimeObj = jsonObj.get("logtime");
      if (logtimeObj != null) {
        if (logtimeObj instanceof Date) {
          prefix = "" + ((Date) logtimeObj).getTime();
        } else {
          prefix = logtimeObj.toString();
        }
      }
      byte[] bytes = LogFeederUtil.getGson().toJson(jsonObj).getBytes();
      long eventMD5 = Hashing.md5().hashBytes(bytes).asLong();
      if (input.isGenEventMD5()) {
        jsonObj.put("event_md5", prefix + Long.toString(eventMD5));
      }
      if (input.isUseEventMD5()) {
        jsonObj.put("id", prefix + Long.toString(eventMD5));
      }
    }
    jsonObj.computeIfAbsent("event_count", k -> 1);
    if (StringUtils.isNotBlank(input.getInputDescriptor().getGroup())) {
      jsonObj.put("group", input.getInputDescriptor().getGroup());
    }
    if (inputMarker.getAllProperties().containsKey("line_number") &&
      (Integer) inputMarker.getAllProperties().get("line_number") > 0) {
      jsonObj.put("logfile_line_number", inputMarker.getAllProperties().get("line_number"));
    }
    if (!jsonObj.containsKey("level")) {
      jsonObj.put("level", LogFeederConstants.LOG_LEVEL_UNKNOWN);
    }
    if (jsonObj.containsKey("log_message")) {
      // TODO: Let's check size only for log_message for now
      String logMessage = (String) jsonObj.get("log_message");
      logMessage = truncateLongLogMessage(messageTruncateMetric, jsonObj, input, logMessage);
      jsonObj.put("message_md5", "" + Hashing.md5().hashBytes(logMessage.getBytes()).asLong());
    }

    return jsonObj;
  }

  @SuppressWarnings("unchecked")
  private String truncateLongLogMessage(MetricData messageTruncateMetric, Map<String, Object> jsonObj, Input input, String logMessage) {
    if (logMessage != null && logMessage.getBytes().length > MAX_OUTPUT_SIZE) {
      messageTruncateMetric.value++;
      String logMessageKey = input.getOutputManager().getClass().getSimpleName() + "_MESSAGESIZE";
      LogFeederUtil.logErrorMessageByInterval(logMessageKey, "Message is too big. size=" + logMessage.getBytes().length +
        ", input=" + input.getShortDescription() + ". Truncating to " + MAX_OUTPUT_SIZE + ", first upto 200 characters=" +
        StringUtils.abbreviate(logMessage, 200), null, logger, Level.WARN);
      logMessage = new String(logMessage.getBytes(), 0, MAX_OUTPUT_SIZE);
      jsonObj.put("log_message", logMessage);
      List<String> tagsList = (List<String>) jsonObj.get("tags");
      if (tagsList == null) {
        tagsList = new ArrayList<>();
        jsonObj.put("tags", tagsList);
      }
      tagsList.add("error_message_truncated");
    }
    return logMessage;
  }
}
