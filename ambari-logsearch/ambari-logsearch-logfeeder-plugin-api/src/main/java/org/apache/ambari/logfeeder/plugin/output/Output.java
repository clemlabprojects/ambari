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
package org.apache.ambari.logfeeder.plugin.output;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.ambari.logfeeder.plugin.common.ConfigItem;
import org.apache.ambari.logfeeder.plugin.common.LogFeederProperties;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logsearch.config.api.LogSearchConfigLogFeeder;
import org.apache.ambari.logsearch.config.api.OutputConfigMonitor;
import org.apache.ambari.logsearch.config.api.ShipperConfigElementDescription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Output is responsible about to ship transformed inputs to a destination which should be implemented by extending this class.
 * @param <PROP_TYPE> Log Feeder configuration holder object
 * @param <INPUT_MARKER> Type of the input marker - can be anything which can store unique data about an input
 */
public abstract class Output<PROP_TYPE extends LogFeederProperties, INPUT_MARKER extends InputMarker> extends ConfigItem<PROP_TYPE> implements OutputConfigMonitor {

  private static final Logger LOG = LogManager.getLogger(Output.class);

  private final static String GSON_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
  private static Gson gson = new GsonBuilder().setDateFormat(GSON_DATE_FORMAT).create();

  private LogSearchConfigLogFeeder logSearchConfig;

  @ShipperConfigElementDescription(
    path = "/output/[]/destination",
    type = "string",
    description = "Alias of a supported output (e.g.: solr). The class-alias mapping should exist in the alias config.",
    examples = {"\"solr\"", "\"hdfs\""}
  )
  private String destination = null;
  private boolean isClosed;
  protected MetricData writeBytesMetric = new MetricData(getWriteBytesMetricName(), false);
  /**
   * Obtain the output type
   * @return Text which represents the output type in shipper configuration. (e.g.: "solr")
   */
  public abstract String getOutputType();

  /**
   * Copy input file, can be used instead or with processing an input
   * @param inputFile File to copy
   * @param inputMarker Marker that stores input details
   * @throws Exception Error during input copy
   */
  public abstract void copyFile(File inputFile, InputMarker inputMarker) throws Exception;

  /**
   * Call write operation - should ship inputs to a destination
   * @param jsonStr Input string to process (JSON string)
   * @param inputMarker Marker that stores input details
   * @throws Exception Error during output writing
   */
  public abstract void write(String jsonStr, INPUT_MARKER inputMarker) throws Exception;

  /**
   * Get pending output count
   * @return Pending outputs (used during closing the outputs - mainly at shutdown phase)
   */
  public abstract Long getPendingCount();

  /**
   * Obtain writes metric name - if there are any metric sinks in the application it can identify the specific metric for the output
   * @return metric name
   */
  public abstract String getWriteBytesMetricName();

  /**
   * Converts key/value map to JSON string and call write() on that input
   * @param jsonObj Key/value map which contains the fields to process
   * @param inputMarker Marker that stores input details
   * @throws Exception Error during output writing
   */
  public void write(Map<String, Object> jsonObj, INPUT_MARKER inputMarker) throws Exception {
    write(gson.toJson(jsonObj), inputMarker);
  }

  protected String getNameForThread() {
    return this.getClass().getSimpleName();
  }

  public void setLogSearchConfig(LogSearchConfigLogFeeder logSearchConfig) {
    this.logSearchConfig = logSearchConfig;
  }

  public LogSearchConfigLogFeeder getLogSearchConfig() {
    return logSearchConfig;
  }

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  /**
   * Get the list of fields that will be used for ID generation of log entries.
   * @return list of string
   */
  public List<String> getIdFields() {
    return new ArrayList<>();
  }

  public boolean isClosed() {
    return isClosed;
  }

  /**
   * Flag an output to be closed
   */
  protected void shouldCloseOutput() {
    isClosed = true;
  }

  @Override
  public void addMetricsContainers(List<MetricData> metricsList) {
    super.addMetricsContainers(metricsList);
    metricsList.add(writeBytesMetric);
  }

  @Override
  public synchronized void logStat() {
    super.logStat();
    logStatForMetric(writeBytesMetric, "Stat: Bytes Written");
  }

  @Override
  public boolean logConfigs() {
    // TODO: log something about the configs
    return true;
  }

  protected void trimStrValue(Map<String, Object> jsonObj) {
    if (jsonObj != null) {
      for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (value instanceof String) {
          String valueStr = value.toString().trim();
          jsonObj.put(key, valueStr);
        }
      }
    }
  }

  public void close() {
    LOG.info("Calling base close() = " + getShortDescription());
    isClosed = true;
  }
}
