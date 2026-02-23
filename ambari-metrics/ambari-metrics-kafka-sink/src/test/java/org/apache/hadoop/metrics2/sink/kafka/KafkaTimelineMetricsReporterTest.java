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

package org.apache.hadoop.metrics2.sink.kafka;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class KafkaTimelineMetricsReporterTest {

  private TestKafkaTimelineMetricsReporter kafkaTimelineMetricsReporter;
  private Map<String, Object> configs;

  @Before
  public void setUp() {
    kafkaTimelineMetricsReporter = new TestKafkaTimelineMetricsReporter();
    configs = new HashMap<String, Object>();
    configs.put("zookeeper.connect", "localhost:2181");
    configs.put("kafka.timeline.metrics.reporter.sendInterval", "5900");
    configs.put("kafka.timeline.metrics.maxRowCacheSize", "10000");
    configs.put("kafka.timeline.metrics.hosts", "localhost:6188");
    configs.put("kafka.timeline.metrics.port", "6188");
    configs.put("kafka.timeline.metrics.reporter.enabled", "false");
    configs.put("kafka.timeline.metrics.instanceId", "cluster");
    configs.put("kafka.timeline.metrics.set.instanceId", "false");
  }

  @Test
  public void testReporterStartStop() {
    kafkaTimelineMetricsReporter.configure(configs);
    kafkaTimelineMetricsReporter.startReporter(1);
    kafkaTimelineMetricsReporter.stopReporter();
  }

  @Test
  public void testReporterStartStopHttps() {
    configs.put("kafka.timeline.metrics.protocol", "https");
    configs.put("kafka.timeline.metrics.truststore.path", "");
    configs.put("kafka.timeline.metrics.truststore.type", "");
    configs.put("kafka.timeline.metrics.truststore.password", "");
    kafkaTimelineMetricsReporter.configure(configs);
    kafkaTimelineMetricsReporter.startReporter(1);
    kafkaTimelineMetricsReporter.stopReporter();
  }

  @Test
  public void testMetricsExclusionPolicy() {
    configs.put("external.kafka.metrics.exclude.prefix", "a.b.c");
    configs.put("external.kafka.metrics.include.prefix", "a.b.c.d");
    configs.put("external.kafka.metrics.include.regex", "a.b.c.*.f");

    kafkaTimelineMetricsReporter.configure(configs);

    Assert.assertTrue(kafkaTimelineMetricsReporter.isExcludedMetric("a.b.c"));
    Assert.assertFalse(kafkaTimelineMetricsReporter.isExcludedMetric("a.b"));
    Assert.assertFalse(kafkaTimelineMetricsReporter.isExcludedMetric("a.b.c.d"));
    Assert.assertFalse(kafkaTimelineMetricsReporter.isExcludedMetric("a.b.c.d.e"));
    Assert.assertFalse(kafkaTimelineMetricsReporter.isExcludedMetric("a.b.c.e.f"));
  }

  @Test
  public void testCollectMetricsFromJmx() throws Exception {
    MBeanServer mBeanServer = Mockito.mock(MBeanServer.class);
    ObjectName objectName = new ObjectName(
      "kafka.server:type=FetcherLagMetrics,name=ConsumerLag,topic=test-topic,partition=1,clientId=ReplicaFetcherThread-0-1");

    Set<ObjectName> objectNames = Collections.singleton(objectName);
    Mockito.when(mBeanServer.queryNames((ObjectName) Mockito.anyObject(),
      (javax.management.QueryExp) Mockito.isNull()))
      .thenReturn(objectNames);

    MBeanAttributeInfo[] attributeInfos = new MBeanAttributeInfo[] {
      new MBeanAttributeInfo("Count", "long", "count", true, false, false),
      new MBeanAttributeInfo("Value", "double", "value", true, false, false),
      new MBeanAttributeInfo("OneMinuteRate", "double", "one minute rate", true, false, false)
    };
    MBeanInfo mBeanInfo = new MBeanInfo("kafka.Test", "test", attributeInfos, null, null, null);
    Mockito.when(mBeanServer.getMBeanInfo(objectName)).thenReturn(mBeanInfo);

    AttributeList attributes = new AttributeList();
    attributes.add(new Attribute("Count", 42L));
    attributes.add(new Attribute("Value", 7.5d));
    attributes.add(new Attribute("OneMinuteRate", 3.0d));
    Mockito.when(mBeanServer.getAttributes(Mockito.eq(objectName), Mockito.<String[]>anyObject()))
      .thenReturn(attributes);

    kafkaTimelineMetricsReporter.configure(configs);
    kafkaTimelineMetricsReporter.setMBeanServer(mBeanServer);
    kafkaTimelineMetricsReporter.setMetricsCache(new TimelineMetricsCache(10000, 1));

    invokeCollectMetrics(kafkaTimelineMetricsReporter);
    Thread.sleep(5L);
    invokeCollectMetrics(kafkaTimelineMetricsReporter);

    TimelineMetrics emitted = kafkaTimelineMetricsReporter.getLastMetrics();
    Assert.assertNotNull(emitted);

    String baseMetric =
      "kafka.server.FetcherLagMetrics.ConsumerLag.clientId.ReplicaFetcherThread-0-1.partition.1.topic.test-topic";
    TimelineMetric countMetric = findMetric(emitted, baseMetric + ".count");
    TimelineMetric valueMetric = findMetric(emitted, baseMetric);
    TimelineMetric oneMinuteRateMetric = findMetric(emitted, baseMetric + ".1MinuteRate");

    Assert.assertNotNull(countMetric);
    Assert.assertEquals("COUNTER", countMetric.getType());

    Assert.assertNotNull(valueMetric);
    Assert.assertEquals("GAUGE", valueMetric.getType());

    Assert.assertNotNull(oneMinuteRateMetric);
    Assert.assertEquals("GAUGE", oneMinuteRateMetric.getType());
  }

  private static void invokeCollectMetrics(KafkaTimelineMetricsReporter reporter) throws Exception {
    Method method = KafkaTimelineMetricsReporter.class.getDeclaredMethod("collectMetrics");
    method.setAccessible(true);
    method.invoke(reporter);
  }

  private static TimelineMetric findMetric(TimelineMetrics metrics, String metricName) {
    List<TimelineMetric> allMetrics = metrics.getMetrics();
    if (allMetrics == null) {
      return null;
    }
    for (TimelineMetric metric : allMetrics) {
      if (metricName.equals(metric.getMetricName())) {
        return metric;
      }
    }
    return null;
  }

  private static class TestKafkaTimelineMetricsReporter extends KafkaTimelineMetricsReporter {
    private TimelineMetrics lastMetrics;

    @Override
    protected boolean emitMetrics(TimelineMetrics metrics) {
      this.lastMetrics = metrics;
      return true;
    }

    public TimelineMetrics getLastMetrics() {
      return lastMetrics;
    }
  }
}
