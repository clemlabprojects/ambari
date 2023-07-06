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
package org.apache.ambari.logfeeder.plugin.manager;

import org.apache.ambari.logfeeder.plugin.common.MetricData;

import java.util.List;

/**
 * Stores common operations for input and output managers
 */
public interface BlockManager {

  /**
   * Init input or output configuration block
   * @throws Exception Error during initialization
   */
  void init() throws Exception;

  /**
   * Close input or output manager
   */
  void close();

  /**
   * Log Statistics - needs to be implemented
   */
  void logStats();

  /**
   * Adding a list of metrics to input or output manager, which can be processed (if implemented)
   * @param metricsList List of metrics
   */
  void addMetricsContainers(List<MetricData> metricsList);

}
