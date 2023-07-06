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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hashing;
import org.apache.ambari.logfeeder.common.IdGeneratorHelper;
import org.apache.ambari.logfeeder.common.LogFeederConstants;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.loglevelfilter.LogLevelFilterHandler;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.plugin.manager.OutputManager;
import org.apache.ambari.logfeeder.plugin.output.Output;
import org.apache.ambari.logfeeder.util.LogFeederUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class OutputManagerImpl extends OutputManager {
  private static final Logger logger = LogManager.getLogger(OutputManagerImpl.class);

  private static final int MAX_OUTPUT_SIZE = 32765; // 32766-1

  private List<Output> outputs = new ArrayList<>();

  private static long docCounter = 0;
  private MetricData messageTruncateMetric = new MetricData(null, false);

  @Inject
  private LogLevelFilterHandler logLevelFilterHandler;

  @Inject
  private LogFeederProps logFeederProps;

  private final OutputLineEnricher outputLineEnricher = new OutputLineEnricher();
  private final OutputLineFilter outputLineFilter = new OutputLineFilter();

  public List<Output> getOutputs() {
    return outputs;
  }

  public void add(Output output) {
    this.outputs.add(output);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init() throws Exception {
    logger.info("Called init with default output manager.");
    for (Output output : outputs) {
      output.init(logFeederProps);
    }
  }

  @SuppressWarnings("unchecked")
  public void write(Map<String, Object> jsonObj, InputMarker inputMarker) {
    jsonObj.put("seq_num", docCounter++);
    if (docCounter == Long.MAX_VALUE) {
      docCounter = 1;
    }
    jsonObj = outputLineEnricher.enrichFields(jsonObj, inputMarker, messageTruncateMetric);
    Input input = inputMarker.getInput();
    List<String> defaultLogLevels = getDefaultLogLevels(input);
    if (logLevelFilterHandler.isAllowed(jsonObj, inputMarker, defaultLogLevels)
      && !outputLineFilter.apply(jsonObj, inputMarker.getInput())) {
      List<? extends Output> outputList = input.getOutputList();
      for (Output output : outputList) {
        try {
          if (jsonObj.get("id") == null) {
            jsonObj.put("id", IdGeneratorHelper.generateUUID(jsonObj, output.getIdFields()));
          }
          output.write(jsonObj, inputMarker);
        } catch (Exception e) {
          logger.error("Error writing. to " + output.getShortDescription(), e);
        }
      }
    }
  }

  private List<String> getDefaultLogLevels(Input input) {
    List<String> defaultLogLevels = logFeederProps.getIncludeDefaultLogLevels();
    List<String> overrideDefaultLogLevels = input.getInputDescriptor().getDefaultLogLevels();
    if (CollectionUtils.isNotEmpty(overrideDefaultLogLevels)) {
      return overrideDefaultLogLevels;
    } else {
      return defaultLogLevels;
    }
  }

  @SuppressWarnings("unchecked")
  public void write(String jsonBlock, InputMarker inputMarker) {
    List<String> defaultLogLevels = getDefaultLogLevels(inputMarker.getInput());
    if (logLevelFilterHandler.isAllowed(jsonBlock, inputMarker, defaultLogLevels)) {
      List<? extends Output> outputList = inputMarker.getInput().getOutputList();
      for (Output output : outputList) {
        try {
          output.write(jsonBlock, inputMarker);
        } catch (Exception e) {
          logger.error("Error writing. to " + output.getShortDescription(), e);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public void copyFile(File inputFile, InputMarker inputMarker) {
    Input input = inputMarker.getInput();
    List<? extends Output> outputList = input.getOutputList();
    for (Output output : outputList) {
      try {
        output.copyFile(inputFile, inputMarker);
      }catch (Exception e) {
        logger.error("Error coyping file . to " + output.getShortDescription(), e);
      }
    }
  }

  public void logStats() {
    for (Output output : outputs) {
      output.logStat();
    }
    LogFeederUtil.logStatForMetric(messageTruncateMetric, "Stat: Messages Truncated", "");
  }

  public void addMetricsContainers(List<MetricData> metricsList) {
    metricsList.add(messageTruncateMetric);
    for (Output output : outputs) {
      output.addMetricsContainers(metricsList);
    }
  }

  public void close() {
    logger.info("Close called for outputs ...");
    for (Output output : outputs) {
      try {
        output.setDrain(true);
        output.close();
      } catch (Exception e) {
        // Ignore
      }
    }

    // Need to get this value from property
    int iterations = 30;
    int waitTimeMS = 1000;
    for (int i = 0; i < iterations; i++) {
      boolean allClosed = true;
      for (Output output : outputs) {
        if (!output.isClosed()) {
          try {
            allClosed = false;
            logger.warn("Waiting for output to close. " + output.getShortDescription() + ", " + (iterations - i) + " more seconds");
            Thread.sleep(waitTimeMS);
          } catch (Throwable t) {
            // Ignore
          }
        }
      }
      if (allClosed) {
        logger.info("All outputs are closed. Iterations=" + i);
        return;
      }
    }

    logger.warn("Some outpus were not closed after " + iterations + "  iterations");
    for (Output output : outputs) {
      if (!output.isClosed()) {
        logger.warn("Output not closed. Will ignore it." + output.getShortDescription() + ", pendingCound=" + output.getPendingCount());
      }
    }
  }

  public LogLevelFilterHandler getLogLevelFilterHandler() {
    return logLevelFilterHandler;
  }

  public void setLogLevelFilterHandler(LogLevelFilterHandler logLevelFilterHandler) {
    this.logLevelFilterHandler = logLevelFilterHandler;
  }

  public LogFeederProps getLogFeederProps() {
    return logFeederProps;
  }

  @VisibleForTesting
  public void setLogFeederProps(LogFeederProps logFeederProps) {
    this.logFeederProps = logFeederProps;
  }
}
