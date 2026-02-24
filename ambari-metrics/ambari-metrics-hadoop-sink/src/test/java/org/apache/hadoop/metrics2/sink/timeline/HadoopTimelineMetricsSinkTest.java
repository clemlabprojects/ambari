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

package org.apache.hadoop.metrics2.sink.timeline;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.SubsetConfiguration;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.hadoop.metrics2.AbstractMetric;
import org.apache.hadoop.metrics2.MetricType;
import org.apache.hadoop.metrics2.MetricsInfo;
import org.apache.hadoop.metrics2.MetricsRecord;
import org.apache.hadoop.metrics2.MetricsTag;
import org.apache.hadoop.metrics2.MetricsVisitor;
import org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.COLLECTOR_HOSTS_PROPERTY;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.COLLECTOR_PORT;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.COLLECTOR_PROTOCOL;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.HOST_IN_MEMORY_AGGREGATION_PROTOCOL_PROPERTY;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.INSTANCE_ID_PROPERTY;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.MAX_METRIC_ROW_CACHE_SIZE;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.METRICS_SEND_INTERVAL;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.RPC_METRIC_PREFIX;
import static org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink.SET_INSTANCE_ID_PROPERTY;

public class HadoopTimelineMetricsSinkTest {
  private static final String PREFIX = "service";

  @Before
  public void setup() {
    Logger.getLogger("org.apache.hadoop.metrics2.sink.timeline").setLevel(Level.DEBUG);
  }

  @Test
  public void testPutMetricsSetsInstanceIdOnEvictedMetrics() throws Exception {
    TestableHadoopTimelineMetricsSink sink = new TestableHadoopTimelineMetricsSink();

    Map<String, Object> values = baseConfig(1000);
    values.put(SET_INSTANCE_ID_PROPERTY, true);
    values.put(INSTANCE_ID_PROPERTY, "instanceId");

    SubsetConfiguration conf = createConfiguration(values);

    AbstractMetric metric = new SequenceMetric("metricName", MetricType.COUNTER, 9.5687, 9.5687);
    MetricsRecord record = new TestMetricsRecord(
      "testName",
      "testContext",
      Collections.singletonList(metric),
      Arrays.asList(1_000L, 3_500L),
      Collections.<Collection<MetricsTag>>emptyList()
    );

    sink.init(conf);
    sink.putMetrics(record);
    sink.putMetrics(record);

    Assert.assertEquals(1, sink.getEmittedMetrics().size());
    TimelineMetric emittedMetric = sink.getEmittedMetrics().get(0).getMetrics().get(0);
    Assert.assertEquals("instanceId", emittedMetric.getInstanceId());
    Assert.assertEquals(2, emittedMetric.getMetricValues().size());
  }

  @Test
  public void testDuplicateTimeSeriesNotSaved() throws Exception {
    TestableHadoopTimelineMetricsSink sink = new TestableHadoopTimelineMetricsSink();
    SubsetConfiguration conf = createConfiguration(baseConfig(1_000_000));

    long baseTime = 1_000_000L;
    AbstractMetric metric = new SequenceMetric("metricName", null, 1.0, 2.0, 3.0, 4.0);
    MetricsRecord record = new TestMetricsRecord(
      "testName",
      "testContext",
      Collections.singletonList(metric),
      Arrays.asList(baseTime, baseTime, baseTime + 100L, baseTime + 100L),
      Collections.<Collection<MetricsTag>>emptyList()
    );

    sink.init(conf);
    sink.putMetrics(record);
    sink.putMetrics(record);
    sink.putMetrics(record);
    sink.putMetrics(record);

    TimelineMetricsCache cache = getMetricsCache(sink);
    TimelineMetrics metrics = cache.getAllMetrics();
    TimelineMetric timelineMetric = metricByName(metrics, "testContext.testName.metricName");

    Assert.assertNotNull(timelineMetric);
    TreeMap<Long, Double> values = timelineMetric.getMetricValues();
    Assert.assertEquals(2, values.size());
    Assert.assertEquals(Double.valueOf(1.0), values.get(baseTime));
    Assert.assertEquals(Double.valueOf(3.0), values.get(baseTime + 100L));
  }

  @Test
  public void testRPCPortSuffixHandledCorrectly() throws Exception {
    TestableHadoopTimelineMetricsSink sink = new TestableHadoopTimelineMetricsSink();

    Map<String, Object> values = baseConfig(1_000_000);
    values.put(RPC_METRIC_PREFIX + ".client.port", "8020");
    values.put(RPC_METRIC_PREFIX + ".datanode.port", "8040");
    values.put(RPC_METRIC_PREFIX + ".healthcheck.port", "8060");

    SubsetConfiguration conf = createConfiguration(values);

    AbstractMetric metric = new SequenceMetric("rpc.metricName", null, 1.0, 2.0, 3.0, 4.0);

    List<Collection<MetricsTag>> tagsPerPut = Arrays.asList(
      Collections.singletonList(portTag("8020")),
      Collections.singletonList(portTag("8020")),
      Collections.singletonList(portTag("8040")),
      Collections.singletonList(portTag("8040"))
    );

    MetricsRecord record = new TestMetricsRecord(
      "testMetric",
      "rpc",
      Collections.singletonList(metric),
      Arrays.asList(1_000L, 1_100L, 2_000L, 2_100L),
      tagsPerPut
    );

    sink.init(conf);
    sink.putMetrics(record);
    sink.putMetrics(record);
    sink.putMetrics(record);
    sink.putMetrics(record);

    TimelineMetricsCache cache = getMetricsCache(sink);
    TimelineMetrics metrics = cache.getAllMetrics();

    TimelineMetric clientMetric = metricByName(metrics, "rpc.testMetric.client.rpc.metricName");
    TimelineMetric datanodeMetric = metricByName(metrics, "rpc.testMetric.datanode.rpc.metricName");

    Assert.assertNotNull(clientMetric);
    Assert.assertNotNull(datanodeMetric);
    Assert.assertEquals(2, clientMetric.getMetricValues().size());
    Assert.assertEquals(2, datanodeMetric.getMetricValues().size());
  }

  private static Map<String, Object> baseConfig(int sendInterval) {
    Map<String, Object> values = new HashMap<String, Object>();
    values.put("slave.host.name", "localhost");
    values.put("serviceName-prefix", "");
    values.put(COLLECTOR_HOSTS_PROPERTY, "localhost, localhost2");
    values.put(COLLECTOR_PROTOCOL, "http");
    values.put(COLLECTOR_PORT, "6188");
    values.put(HOST_IN_MEMORY_AGGREGATION_PROTOCOL_PROPERTY, "http");
    values.put(MAX_METRIC_ROW_CACHE_SIZE, 10);
    values.put(METRICS_SEND_INTERVAL, sendInterval);
    return values;
  }

  private static SubsetConfiguration createConfiguration(Map<String, Object> values) {
    BaseConfiguration base = new BaseConfiguration();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      base.addProperty(PREFIX + "." + entry.getKey(), entry.getValue());
    }

    SubsetConfiguration subset = new SubsetConfiguration(base, PREFIX, ".");
    subset.setListDelimiterHandler(new DefaultListDelimiterHandler(','));
    return subset;
  }

  private static TimelineMetricsCache getMetricsCache(HadoopTimelineMetricsSink sink) throws Exception {
    Field field = HadoopTimelineMetricsSink.class.getDeclaredField("metricsCache");
    field.setAccessible(true);
    return (TimelineMetricsCache) field.get(sink);
  }

  private static TimelineMetric metricByName(TimelineMetrics metrics, String name) {
    if (metrics == null || metrics.getMetrics() == null) {
      return null;
    }
    for (TimelineMetric metric : metrics.getMetrics()) {
      if (name.equals(metric.getMetricName())) {
        return metric;
      }
    }
    return null;
  }

  private static MetricsTag portTag(String port) {
    return new MetricsTag(metricsInfo("port", "rpc port"), port);
  }

  private static MetricsInfo metricsInfo(final String name, final String description) {
    return new MetricsInfo() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }
    };
  }

  private static final class SequenceMetric extends AbstractMetric {
    private final MetricType metricType;
    private final List<Double> values;
    private int index = 0;

    private SequenceMetric(String name, MetricType metricType, Double... values) {
      super(metricsInfo(name, name));
      this.metricType = metricType;
      this.values = values == null ? Collections.<Double>emptyList() : Arrays.asList(values);
    }

    @Override
    public Number value() {
      if (values.isEmpty()) {
        return 0.0d;
      }
      int valueIndex = Math.min(index, values.size() - 1);
      double value = values.get(valueIndex);
      index++;
      return value;
    }

    @Override
    public MetricType type() {
      return metricType;
    }

    @Override
    public void visit(MetricsVisitor visitor) {
      // Not needed by the sink logic under test.
    }
  }

  private static final class TestMetricsRecord implements MetricsRecord {
    private final String name;
    private final String context;
    private final List<AbstractMetric> metrics;
    private final long[] timestamps;
    private final List<Collection<MetricsTag>> tagsPerPut;
    private int currentPutIndex = 0;
    private boolean advanceIndexOnNextNameCall = false;

    private TestMetricsRecord(
      String name,
      String context,
      List<AbstractMetric> metrics,
      List<Long> timestamps,
      List<Collection<MetricsTag>> tagsPerPut
    ) {
      this.name = name;
      this.context = context;
      this.metrics = metrics == null ? Collections.<AbstractMetric>emptyList() : metrics;
      this.timestamps = toLongArray(timestamps);
      this.tagsPerPut = tagsPerPut == null ? Collections.<Collection<MetricsTag>>emptyList() : tagsPerPut;
    }

    @Override
    public long timestamp() {
      advanceIndexOnNextNameCall = true;
      return timestamps[currentPutIndex];
    }

    @Override
    public String name() {
      if (advanceIndexOnNextNameCall && currentPutIndex < timestamps.length - 1) {
        currentPutIndex++;
      }
      advanceIndexOnNextNameCall = false;
      return name;
    }

    @Override
    public String description() {
      return "";
    }

    @Override
    public String context() {
      return context;
    }

    @Override
    public Collection<MetricsTag> tags() {
      if (tagsPerPut.isEmpty()) {
        return Collections.emptyList();
      }
      int index = Math.min(currentPutIndex, tagsPerPut.size() - 1);
      return tagsPerPut.get(index);
    }

    @Override
    public Iterable<AbstractMetric> metrics() {
      return metrics;
    }

    private static long[] toLongArray(List<Long> values) {
      if (values == null || values.isEmpty()) {
        return new long[]{0L};
      }
      long[] result = new long[values.size()];
      for (int i = 0; i < values.size(); i++) {
        result[i] = values.get(i);
      }
      return result;
    }
  }

  private static final class TestableHadoopTimelineMetricsSink extends HadoopTimelineMetricsSink {
    private final List<TimelineMetrics> emittedMetrics = new ArrayList<TimelineMetrics>();

    @Override
    protected Collection<String> findLiveCollectorHostsFromKnownCollector(String host, String port) {
      return Collections.singletonList(host);
    }

    @Override
    protected boolean emitMetrics(TimelineMetrics metrics) {
      emittedMetrics.add(metrics);
      return true;
    }

    private List<TimelineMetrics> getEmittedMetrics() {
      return emittedMetrics;
    }
  }
}
