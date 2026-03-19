/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.requests;

import java.util.Map;

/**
 * Payload for upgrade (or install+upgrade) requests coming from the UI.
 * We expose both chartRef and chartName-style getters to match existing Resource code.
 */
public class HelmUpgradeRequest {

  // required
  private String releaseName;
  private String namespace;

  // chart identification (one of these will be used depending on mode)
  private String chartRef;   // e.g. "repo/name" or "oci://..."
  private String chartName;  // e.g. "trino" when paired with a repo
  private String chart; // and map it in setters

  // optional deployment options
  private Map<String, Object> values;
  private String repoId;     // repo id saved in Ambari store
  private String version;    // chart version if specified

  // --- getters / setters (keep names exactly as your Resource expects) ---

  public String getReleaseName() {
    return releaseName;
  }
  public void setReleaseName(String releaseName) {
    this.releaseName = releaseName;
  }

  public String getNamespace() {
    return namespace;
  }
  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  // Your Resource calls getChartRef() and getChartName(), so we provide both.
  public String getChartRef() {
    return chartRef;
  }
  public void setChartRef(String chartRef) {
    this.chartRef = chartRef;
  }

  public String getChartName() {
    return chartName;
  }
  public void setChartName(String chartName) {
    this.chartName = chartName;
  }

  public Map<String, Object> getValues() {
    return values;
  }
  public void setValues(Map<String, Object> values) {
    this.values = values;
  }

  public String getRepoId() {
    return repoId;
  }
  public void setRepoId(String repoId) {
    this.repoId = repoId;
  }

  public String getVersion() {
    return version;
  }
  public void setVersion(String version) {
    this.version = version;
  }
  public void setChart(String chart) { this.chart = chart; this.chartRef = chart; }
  
  public String getChart() { return chart; }
}
