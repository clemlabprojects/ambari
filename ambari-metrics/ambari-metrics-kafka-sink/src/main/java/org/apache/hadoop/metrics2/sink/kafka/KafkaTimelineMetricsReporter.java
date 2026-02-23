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

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;

import static org.apache.hadoop.metrics2.sink.timeline.TimelineMetricMetadata.MetricType;
import static org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache.MAX_EVICTION_TIME_MILLIS;
import static org.apache.hadoop.metrics2.sink.timeline.cache.TimelineMetricsCache.MAX_RECS_PER_NAME_DEFAULT;

public class KafkaTimelineMetricsReporter extends AbstractTimelineMetricsSink
    implements MetricsReporter, KafkaTimelineMetricsReporterMBean {

  private static final Log LOG = LogFactory.getLog(KafkaTimelineMetricsReporter.class);

  private static final String APP_ID = "kafka_broker";
  private static final String TIMELINE_METRICS_KAFKA_PREFIX = "kafka.timeline.metrics.";
  private static final String TIMELINE_METRICS_REPORTER_SEND_INTERVAL_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + "reporter.sendInterval";
  private static final String TIMELINE_METRICS_SEND_INTERVAL_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + "sendInterval";
  private static final String TIMELINE_METRICS_MAX_ROW_CACHE_SIZE_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + "maxRowCacheSize";
  private static final String TIMELINE_HOSTS_PROPERTY = TIMELINE_METRICS_KAFKA_PREFIX + "hosts";
  private static final String TIMELINE_PORT_PROPERTY = TIMELINE_METRICS_KAFKA_PREFIX + "port";
  private static final String TIMELINE_PROTOCOL_PROPERTY = TIMELINE_METRICS_KAFKA_PREFIX + "protocol";
  private static final String TIMELINE_REPORTER_ENABLED_PROPERTY = TIMELINE_METRICS_KAFKA_PREFIX + "reporter.enabled";
  private static final String TIMELINE_METRICS_SSL_KEYSTORE_PATH_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + SSL_KEYSTORE_PATH_PROPERTY;
  private static final String TIMELINE_METRICS_SSL_KEYSTORE_TYPE_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + SSL_KEYSTORE_TYPE_PROPERTY;
  private static final String TIMELINE_METRICS_SSL_KEYSTORE_PASSWORD_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + SSL_KEYSTORE_PASSWORD_PROPERTY;
  private static final String TIMELINE_METRICS_KAFKA_INSTANCE_ID_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + INSTANCE_ID_PROPERTY;
  private static final String TIMELINE_METRICS_KAFKA_SET_INSTANCE_ID_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + SET_INSTANCE_ID_PROPERTY;
  private static final String TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_ENABLED_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + HOST_IN_MEMORY_AGGREGATION_ENABLED_PROPERTY;
  private static final String TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_PORT_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + HOST_IN_MEMORY_AGGREGATION_PORT_PROPERTY;
  private static final String TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_PROTOCOL_PROPERTY =
      TIMELINE_METRICS_KAFKA_PREFIX + HOST_IN_MEMORY_AGGREGATION_PROTOCOL_PROPERTY;
  private static final String TIMELINE_DEFAULT_HOST = "localhost";
  private static final String TIMELINE_DEFAULT_PORT = "6188";
  private static final String TIMELINE_DEFAULT_PROTOCOL = "http";
  private static final int DEFAULT_POLLING_INTERVAL_SECS = 10;
  private static final String KAFKA_METRICS_POLLING_INTERVAL_PROPERTY = "kafka.metrics.polling.interval.secs";
  private static final String EXCLUDED_METRICS_PROPERTY = "external.kafka.metrics.exclude.prefix";
  private static final String INCLUDED_METRICS_PROPERTY = "external.kafka.metrics.include.prefix";
  private static final String INCLUDED_METRICS_REGEX_PROPERTY = "external.kafka.metrics.include.regex";
  private static final String KAFKA_MBEAN_PATTERN = "kafka.*:type=*,name=*";

  private static final Map<String, String> ATTRIBUTE_SUFFIXES;
  static {
    Map<String, String> suffixes = new HashMap<>();
    suffixes.put("Count", ".count");
    suffixes.put("OneMinuteRate", ".1MinuteRate");
    suffixes.put("MeanRate", ".meanRate");
    suffixes.put("FiveMinuteRate", ".5MinuteRate");
    suffixes.put("FifteenMinuteRate", ".15MinuteRate");
    suffixes.put("Mean", ".mean");
    suffixes.put("Min", ".min");
    suffixes.put("Max", ".max");
    suffixes.put("StdDev", "stddev");
    suffixes.put("50thPercentile", ".median");
    suffixes.put("75thPercentile", ".75percentile");
    suffixes.put("95thPercentile", ".95percentile");
    suffixes.put("98thPercentile", ".98percentile");
    suffixes.put("99thPercentile", ".99percentile");
    suffixes.put("999thPercentile", ".999percentile");
    suffixes.put("Value", "");
    ATTRIBUTE_SUFFIXES = Collections.unmodifiableMap(suffixes);
  }

  private static final ObjectName KAFKA_METRICS_PATTERN;
  static {
    ObjectName pattern = null;
    try {
      pattern = new ObjectName(KAFKA_MBEAN_PATTERN);
    } catch (MalformedObjectNameException e) {
      LOG.error("Invalid Kafka metrics ObjectName pattern: " + KAFKA_MBEAN_PATTERN, e);
    }
    KAFKA_METRICS_PATTERN = pattern;
  }

  private volatile boolean initialized = false;
  private boolean running = false;
  private final Object lock = new Object();
  private String hostname;
  private String metricCollectorPort;
  private Collection<String> collectorHosts;
  private String metricCollectorProtocol;
  private TimelineMetricsCache metricsCache;
  private int timeoutSeconds = DEFAULT_POST_TIMEOUT_SECONDS;
  private String zookeeperQuorum;
  private boolean setInstanceId;
  private String instanceId;
  private String[] excludedMetricsPrefixes;
  private String[] includedMetricsPrefixes;
  private String[] includedMetricsRegex = new String[0];
  private final Set<String> excludedMetrics = new HashSet<>();
  private boolean hostInMemoryAggregationEnabled;
  private int hostInMemoryAggregationPort;
  private String hostInMemoryAggregationProtocol;
  private ScheduledExecutorService scheduler;
  private MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
  private int pollingIntervalSecs = DEFAULT_POLLING_INTERVAL_SECS;

  @Override
  protected String getCollectorUri(String host) {
    return constructTimelineMetricUri(metricCollectorProtocol, host, metricCollectorPort);
  }

  @Override
  protected String getCollectorProtocol() {
    return metricCollectorProtocol;
  }

  @Override
  protected String getCollectorPort() {
    return metricCollectorPort;
  }

  @Override
  protected int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  @Override
  protected String getZookeeperQuorum() {
    return zookeeperQuorum;
  }

  @Override
  protected Collection<String> getConfiguredCollectorHosts() {
    return collectorHosts;
  }

  @Override
  protected String getHostname() {
    return hostname;
  }

  @Override
  protected boolean isHostInMemoryAggregationEnabled() {
    return hostInMemoryAggregationEnabled;
  }

  @Override
  protected int getHostInMemoryAggregationPort() {
    return hostInMemoryAggregationPort;
  }

  @Override
  protected String getHostInMemoryAggregationProtocol() {
    return hostInMemoryAggregationProtocol;
  }

  void setMetricsCache(TimelineMetricsCache metricsCache) {
    this.metricsCache = metricsCache;
  }

  void setMBeanServer(MBeanServer mBeanServer) {
    this.mBeanServer = mBeanServer;
  }

  @Override
  public void configure(Map<String, ?> configs) {
    synchronized (lock) {
      if (initialized) {
        return;
      }
      LOG.info("Initializing Kafka Timeline Metrics Sink");
      hostname = resolveHostname();
      super.init();

      timeoutSeconds = getIntProperty(configs, METRICS_POST_TIMEOUT_SECONDS, DEFAULT_POST_TIMEOUT_SECONDS);
      int metricsSendInterval = getIntProperty(configs, TIMELINE_METRICS_REPORTER_SEND_INTERVAL_PROPERTY,
        getIntProperty(configs, TIMELINE_METRICS_SEND_INTERVAL_PROPERTY, MAX_EVICTION_TIME_MILLIS));
      int maxRowCacheSize = getIntProperty(configs, TIMELINE_METRICS_MAX_ROW_CACHE_SIZE_PROPERTY,
        MAX_RECS_PER_NAME_DEFAULT);

      zookeeperQuorum = getStringProperty(configs, COLLECTOR_ZOOKEEPER_QUORUM,
        getStringProperty(configs, "zookeeper.connect", null));
      metricCollectorPort = getStringProperty(configs, TIMELINE_PORT_PROPERTY, TIMELINE_DEFAULT_PORT);
      collectorHosts = parseHostsStringIntoCollection(getStringProperty(configs, TIMELINE_HOSTS_PROPERTY, TIMELINE_DEFAULT_HOST));
      metricCollectorProtocol = getStringProperty(configs, TIMELINE_PROTOCOL_PROPERTY, TIMELINE_DEFAULT_PROTOCOL);

      instanceId = getStringProperty(configs, TIMELINE_METRICS_KAFKA_INSTANCE_ID_PROPERTY, null);
      setInstanceId = getBooleanProperty(configs, TIMELINE_METRICS_KAFKA_SET_INSTANCE_ID_PROPERTY, false);

      hostInMemoryAggregationEnabled = getBooleanProperty(configs, TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_ENABLED_PROPERTY, false);
      hostInMemoryAggregationPort = getIntProperty(configs, TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_PORT_PROPERTY, 61888);
      hostInMemoryAggregationProtocol = getStringProperty(configs, TIMELINE_METRICS_KAFKA_HOST_IN_MEMORY_AGGREGATION_PROTOCOL_PROPERTY, "http");
      setMetricsCache(new TimelineMetricsCache(maxRowCacheSize, metricsSendInterval));

      if (metricCollectorProtocol.contains("https") || hostInMemoryAggregationProtocol.contains("https")) {
        String trustStorePath = getStringProperty(configs, TIMELINE_METRICS_SSL_KEYSTORE_PATH_PROPERTY, "").trim();
        String trustStoreType = getStringProperty(configs, TIMELINE_METRICS_SSL_KEYSTORE_TYPE_PROPERTY, "").trim();
        String trustStorePwd = getStringProperty(configs, TIMELINE_METRICS_SSL_KEYSTORE_PASSWORD_PROPERTY, "").trim();
        if (!StringUtils.isEmpty(trustStorePath)) {
          loadTruststore(trustStorePath, trustStoreType, trustStorePwd);
        } else {
          LOG.info("Kafka timeline metrics HTTPS is enabled but no truststore path is configured; skipping truststore loading.");
        }
      }

      String excludedMetricsStr = getStringProperty(configs, EXCLUDED_METRICS_PROPERTY, "");
      if (!StringUtils.isEmpty(excludedMetricsStr.trim())) {
        excludedMetricsPrefixes = excludedMetricsStr.trim().split(",");
      }

      String includedMetricsStr = getStringProperty(configs, INCLUDED_METRICS_PROPERTY, "");
      if (!StringUtils.isEmpty(includedMetricsStr.trim())) {
        includedMetricsPrefixes = includedMetricsStr.trim().split(",");
      }

      String includedMetricsRegexStr = getStringProperty(configs, INCLUDED_METRICS_REGEX_PROPERTY, "");
      if (!StringUtils.isEmpty(includedMetricsRegexStr.trim())) {
        LOG.info("Including metrics which match the following regex patterns : " + includedMetricsRegexStr);
        includedMetricsRegex = includedMetricsRegexStr.trim().split(",");
      }

      pollingIntervalSecs = Math.max(1, getIntProperty(configs, KAFKA_METRICS_POLLING_INTERVAL_PROPERTY,
        DEFAULT_POLLING_INTERVAL_SECS));

      initialized = true;
      if (getBooleanProperty(configs, TIMELINE_REPORTER_ENABLED_PROPERTY, false)) {
        startReporter(pollingIntervalSecs);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("MetricsSendInterval = " + metricsSendInterval);
        LOG.debug("MaxRowCacheSize = " + maxRowCacheSize);
        LOG.debug("Excluded metrics prefixes = " + excludedMetricsStr);
        LOG.debug("Included metrics prefixes = " + includedMetricsStr);
      }
    }
  }

  @Override
  public void init(List<KafkaMetric> metrics) {
  }

  @Override
  public void metricChange(KafkaMetric metric) {
  }

  @Override
  public void metricRemoval(KafkaMetric metric) {
  }

  @Override
  public void close() {
    stopReporter();
  }

  public String getMBeanName() {
    return "kafka:type=org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter";
  }

  public void startReporter(long period) {
    synchronized (lock) {
      if (initialized && !running) {
        scheduler = Executors.newSingleThreadScheduledExecutor(new ReporterThreadFactory());
        scheduler.scheduleAtFixedRate(new JmxMetricsCollector(), 0, period, TimeUnit.SECONDS);
        running = true;
        LOG.info(String.format("Started Kafka Timeline metrics reporter with polling period %d seconds", period));
      }
    }
  }

  public void stopReporter() {
    synchronized (lock) {
      if (initialized && running) {
        scheduler.shutdownNow();
        scheduler = null;
        running = false;
        LOG.info("Stopped Kafka Timeline metrics reporter");
      }
    }
  }

  protected boolean isExcludedMetric(String metricName) {
    if (excludedMetrics.contains(metricName)) {
      return true;
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("metricName => " + metricName +
        ", exclude: " + StringUtils.startsWithAny(metricName, excludedMetricsPrefixes) +
        ", include: " + StringUtils.startsWithAny(metricName, includedMetricsPrefixes));
    }
    if (StringUtils.startsWithAny(metricName, excludedMetricsPrefixes)) {
      if (!(StringUtils.startsWithAny(metricName, includedMetricsPrefixes) ||
        Arrays.stream(includedMetricsRegex).anyMatch(metricName::matches))) {
        excludedMetrics.add(metricName);
        return true;
      }
    }
    return false;
  }

  private void collectMetrics() {
    if (!initialized || metricsCache == null || KAFKA_METRICS_PATTERN == null) {
      return;
    }
    Set<ObjectName> objectNames = mBeanServer.queryNames(KAFKA_METRICS_PATTERN, null);
    if (objectNames.isEmpty()) {
      return;
    }
    long currentTimeMillis = System.currentTimeMillis();
    List<TimelineMetric> metricsList = new ArrayList<>();
    for (ObjectName objectName : objectNames) {
      String baseMetricName = buildMetricBaseName(objectName);
      if (baseMetricName == null) {
        continue;
      }
      AttributeList attributes = getAttributes(objectName);
      if (attributes == null) {
        continue;
      }
      for (Object attributeObj : attributes) {
        if (!(attributeObj instanceof Attribute)) {
          continue;
        }
        Attribute attribute = (Attribute) attributeObj;
        Object value = attribute.getValue();
        if (!(value instanceof Number)) {
          continue;
        }
        String suffix = ATTRIBUTE_SUFFIXES.get(attribute.getName());
        if (suffix == null) {
          continue;
        }
        String metricName = suffix.isEmpty() ? baseMetricName : baseMetricName + suffix;
        MetricType metricType = "Count".equals(attribute.getName()) ? MetricType.COUNTER : MetricType.GAUGE;
        cacheTimelineMetric(currentTimeMillis, metricName, (Number) value, metricType, metricsList);
      }
    }
    if (!metricsList.isEmpty()) {
      TimelineMetrics timelineMetrics = new TimelineMetrics();
      timelineMetrics.setMetrics(metricsList);
      try {
        emitMetrics(timelineMetrics);
      } catch (Throwable t) {
        LOG.error("Exception emitting metrics", t);
      }
    }
  }

  private AttributeList getAttributes(ObjectName objectName) {
    try {
      MBeanInfo mBeanInfo = mBeanServer.getMBeanInfo(objectName);
      if (mBeanInfo == null) {
        return null;
      }
      MBeanAttributeInfo[] attributeInfos = mBeanInfo.getAttributes();
      if (attributeInfos == null || attributeInfos.length == 0) {
        return null;
      }
      List<String> attributeNames = new ArrayList<>();
      for (MBeanAttributeInfo attributeInfo : attributeInfos) {
        if (attributeInfo.isReadable() && ATTRIBUTE_SUFFIXES.containsKey(attributeInfo.getName())) {
          attributeNames.add(attributeInfo.getName());
        }
      }
      if (attributeNames.isEmpty()) {
        return null;
      }
      return mBeanServer.getAttributes(objectName, attributeNames.toArray(new String[0]));
    } catch (Exception e) {
      LOG.debug("Unable to read JMX attributes for " + objectName, e);
      return null;
    }
  }

  private void cacheTimelineMetric(long currentTimeMillis, String metricName, Number metricValue,
                                   MetricType metricType, List<TimelineMetric> metricsList) {
    if (isExcludedMetric(metricName)) {
      return;
    }
    TimelineMetric metric = createTimelineMetric(currentTimeMillis, APP_ID, metricName, metricValue);
    metricsCache.putTimelineMetric(metric);
    TimelineMetric cachedMetric = metricsCache.getTimelineMetric(metricName);
    if (cachedMetric != null) {
      cachedMetric.setType(metricType.name());
      metricsList.add(cachedMetric);
    }
  }

  private TimelineMetric createTimelineMetric(long currentTimeMillis, String component, String attributeName,
                                              Number attributeValue) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Creating timeline metric: " + attributeName + " = " + attributeValue + " time = "
        + currentTimeMillis + " app_id = " + component);
    }
    TimelineMetric timelineMetric = new TimelineMetric();
    timelineMetric.setMetricName(attributeName);
    timelineMetric.setHostName(hostname);
    if (setInstanceId) {
      timelineMetric.setInstanceId(instanceId);
    }
    timelineMetric.setAppId(component);
    timelineMetric.setStartTime(currentTimeMillis);
    timelineMetric.setType(ClassUtils.getShortCanonicalName(attributeValue, "Number"));
    timelineMetric.getMetricValues().put(currentTimeMillis, attributeValue.doubleValue());
    return timelineMetric;
  }

  private String buildMetricBaseName(ObjectName objectName) {
    String domain = objectName.getDomain();
    String type = objectName.getKeyProperty("type");
    String name = objectName.getKeyProperty("name");
    if (domain == null || type == null || name == null) {
      return null;
    }
    StringBuilder rawName = new StringBuilder(domain)
      .append(".").append(type)
      .append(".").append(name);

    Map<String, String> keyPropertyList = new TreeMap<>(objectName.getKeyPropertyList());
    keyPropertyList.remove("type");
    keyPropertyList.remove("name");
    for (Map.Entry<String, String> entry : keyPropertyList.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      rawName.append(".").append(entry.getKey()).append(".").append(entry.getValue());
    }
    return sanitizeName(rawName.toString());
  }

  private String sanitizeName(String metricName) {
    if (metricName == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < metricName.length(); i++) {
      char p = metricName.charAt(i);
      if (!(p >= 'A' && p <= 'Z') && !(p >= 'a' && p <= 'z') && !(p >= '0' && p <= '9') &&
        (p != '_') && (p != '-') && (p != '.') && (p != '\0')) {
        sb.append('_');
      } else {
        sb.append(p);
      }
    }
    return sb.toString();
  }

  private int getIntProperty(Map<String, ?> configs, String name, int defaultValue) {
    String value = getStringProperty(configs, name, null);
    if (StringUtils.isEmpty(value)) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOG.debug("Unable to parse int property " + name + " with value " + value, e);
      return defaultValue;
    }
  }

  private boolean getBooleanProperty(Map<String, ?> configs, String name, boolean defaultValue) {
    String value = getStringProperty(configs, name, null);
    if (StringUtils.isEmpty(value)) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private String getStringProperty(Map<String, ?> configs, String name, String defaultValue) {
    if (configs == null || name == null) {
      return defaultValue;
    }
    Object value = configs.get(name);
    return value != null ? String.valueOf(value) : defaultValue;
  }

  private String resolveHostname() {
    try {
      String localHostname = InetAddress.getLocalHost().getHostName();
      if ((localHostname == null) || (!localHostname.contains("."))) {
        localHostname = InetAddress.getLocalHost().getCanonicalHostName();
      }
      return localHostname;
    } catch (UnknownHostException e) {
      LOG.error("Could not identify hostname.");
      throw new RuntimeException("Could not identify hostname.", e);
    }
  }

  private class JmxMetricsCollector implements Runnable {
    @Override
    public void run() {
      try {
        collectMetrics();
      } catch (Throwable t) {
        LOG.error("Exception processing Kafka JMX metrics", t);
      }
    }
  }

  private static class ReporterThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "kafka-timeline-metrics-reporter");
      thread.setDaemon(true);
      return thread;
    }
  }
}
