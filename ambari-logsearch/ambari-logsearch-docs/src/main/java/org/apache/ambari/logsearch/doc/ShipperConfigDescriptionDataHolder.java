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
package org.apache.ambari.logsearch.doc;

import java.util.List;

public class ShipperConfigDescriptionDataHolder {
  private final List<ShipperConfigDescriptionData> topLevelConfigSections;
  private final List<ShipperConfigDescriptionData> inputConfigSections;
  private final List<ShipperConfigDescriptionData> filterConfigSections;
  private final List<ShipperConfigDescriptionData> postMapValuesConfigSections;
  private final List<ShipperConfigDescriptionData> outputConfigSections;

  public ShipperConfigDescriptionDataHolder(List<ShipperConfigDescriptionData> topLevelConfigSections,
                                            List<ShipperConfigDescriptionData> inputConfigSections,
                                            List<ShipperConfigDescriptionData> filterConfigSections,
                                            List<ShipperConfigDescriptionData> postMapValuesConfigSections,
                                            List<ShipperConfigDescriptionData> outputConfigSections) {
    this.topLevelConfigSections = topLevelConfigSections;
    this.inputConfigSections = inputConfigSections;
    this.filterConfigSections = filterConfigSections;
    this.postMapValuesConfigSections = postMapValuesConfigSections;
    this.outputConfigSections = outputConfigSections;
  }

  public List<ShipperConfigDescriptionData> getTopLevelConfigSections() {
    return topLevelConfigSections;
  }

  public List<ShipperConfigDescriptionData> getInputConfigSections() {
    return inputConfigSections;
  }

  public List<ShipperConfigDescriptionData> getFilterConfigSections() {
    return filterConfigSections;
  }

  public List<ShipperConfigDescriptionData> getPostMapValuesConfigSections() {
    return postMapValuesConfigSections;
  }

  public List<ShipperConfigDescriptionData> getOutputConfigSections() {
    return outputConfigSections;
  }
}
