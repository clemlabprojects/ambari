/*
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

package org.apache.ambari.server.stack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OdpKafkaMetricsAssetsTest {
  private static final String BASEDIR = System.getProperty("basedir", ".");
  private static final String KAFKA_BROKER_XML =
      "src/main/resources/common-services/KAFKA/0.8.1/configuration/kafka-broker.xml";
  private static final String GRAFANA_DASHBOARD_DIR =
      "src/main/resources/stacks/ODP/1.0/services/AMBARI_METRICS/package/files/grafana-dashboards/ODP";

  @Test
  public void testKafkaTimelineMetricsPropertiesPresent() throws Exception {
    File configFile = new File(BASEDIR, KAFKA_BROKER_XML);
    assertTrue("Missing Kafka broker config: " + configFile.getAbsolutePath(), configFile.isFile());

    Map<String, String> properties = loadProperties(configFile);
    assertTrue(properties.containsKey("kafka.timeline.metrics.reporter.enabled"));
    assertEquals("true", properties.get("kafka.timeline.metrics.reporter.enabled"));
    assertTrue(properties.containsKey("kafka.timeline.metrics.hosts"));
    assertTrue(properties.containsKey("kafka.timeline.metrics.port"));
    assertTrue(properties.containsKey("kafka.timeline.metrics.protocol"));
    assertTrue(properties.containsKey("metric.reporters"));
  }

  @Test
  public void testKafkaGrafanaDashboardsPresent() throws Exception {
    File dashboardDir = new File(BASEDIR, GRAFANA_DASHBOARD_DIR);
    assertTrue("Missing Grafana dashboard directory: " + dashboardDir.getAbsolutePath(),
        dashboardDir.isDirectory());

    List<String> dashboards = Arrays.asList(
        "grafana-kafka-home.json",
        "grafana-kafka-hosts.json",
        "grafana-kafka-topics.json");

    for (String name : dashboards) {
      File dashboard = new File(dashboardDir, name);
      assertTrue("Missing dashboard: " + dashboard.getAbsolutePath(), dashboard.isFile());

      JsonObject root = readJson(dashboard);
      assertTrue(root.has("title"));
      String title = root.get("title").getAsString().toLowerCase();
      assertTrue("Unexpected title: " + title, title.contains("kafka"));

      assertTrue(root.has("tags"));
      boolean hasKafkaTag = false;
      for (JsonElement tag : root.getAsJsonArray("tags")) {
        if ("kafka".equalsIgnoreCase(tag.getAsString())) {
          hasKafkaTag = true;
          break;
        }
      }
      assertTrue("Missing kafka tag in " + dashboard.getName(), hasKafkaTag);
    }

    JsonObject topicsDashboard = readJson(new File(dashboardDir, "grafana-kafka-topics.json"));
    String topicsDashboardJson = topicsDashboard.toString();
    assertTrue("Kafka topics dashboard must use wildcard lag metric path",
      topicsDashboardJson.contains("kafka.server.FetcherLagMetrics.ConsumerLag.clientId.*.partition.*.topic.*"));
    assertFalse("Kafka topics dashboard must not hardcode a specific broker id or partition",
      topicsDashboardJson.contains("ReplicaFetcherThread-0-1001.partition.0.topic.*"));
  }

  private static Map<String, String> loadProperties(File file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document document = factory.newDocumentBuilder().parse(file);
    NodeList properties = document.getElementsByTagName("property");

    Map<String, String> results = new HashMap<>();
    for (int i = 0; i < properties.getLength(); i++) {
      Element property = (Element) properties.item(i);
      String name = textValue(property, "name");
      if (name != null) {
        String value = textValue(property, "value");
        results.put(name, value == null ? "" : value);
      }
    }
    return results;
  }

  private static String textValue(Element element, String tagName) {
    NodeList nodes = element.getElementsByTagName(tagName);
    if (nodes.getLength() == 0) {
      return null;
    }
    return nodes.item(0).getTextContent();
  }

  private static JsonObject readJson(File file) throws Exception {
    try (FileReader reader = new FileReader(file)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }
}
