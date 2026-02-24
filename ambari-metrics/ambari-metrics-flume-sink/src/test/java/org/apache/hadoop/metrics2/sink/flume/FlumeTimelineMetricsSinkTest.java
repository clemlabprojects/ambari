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

package org.apache.hadoop.metrics2.sink.flume;

import org.apache.flume.Context;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FlumeTimelineMetricsSinkTest {

  @Test
  public void testNonNumericMetricExclusion() throws Exception {
    TestFlumeTimelineMetricsSink sink = new TestFlumeTimelineMetricsSink();
    sink.configure(new Context());
    sink.setAllMBeans(singleMetricMap("value1"));
    sink.setTimes(1_000L);

    sink.new TimelineMetricsCollector().run();

    Assert.assertTrue(sink.getEmittedMetrics().isEmpty());
  }

  @Test
  public void testNumericMetricSubmission() throws Exception {
    TestFlumeTimelineMetricsSink sink = new TestFlumeTimelineMetricsSink();
    sink.configure(new Context());
    sink.setAllMBeans(singleMetricMap("42"));
    sink.setTimes(1_000L, 2_000L);

    sink.new TimelineMetricsCollector().run();
    sink.new TimelineMetricsCollector().run();

    Assert.assertEquals(1, sink.getEmittedMetrics().size());
    TimelineMetric metric = sink.getEmittedMetrics().get(0).getMetrics().get(0);
    Assert.assertEquals("key1", metric.getMetricName());
    Assert.assertEquals(2, metric.getMetricValues().size());
    Assert.assertTrue(metric.getMetricValues().containsKey(1_000L));
    Assert.assertTrue(metric.getMetricValues().containsKey(2_000L));
  }

  @Test
  public void testMonitorRestart() throws Exception {
    TestFlumeTimelineMetricsSink sink = new TestFlumeTimelineMetricsSink();
    sink.configure(new Context());
    sink.setPollFrequency(1);

    sink.start();
    sink.stop();

    RecordingScheduledExecutorService executor = sink.getExecutorService();
    Assert.assertTrue(executor.isScheduled());
    Assert.assertTrue(executor.isShutdown());
    Assert.assertEquals(1L, executor.getDelay());
    Assert.assertEquals(TimeUnit.MILLISECONDS, executor.getUnit());
  }

  @Test
  public void testMetricsRetrievalExceptionTolerance() throws Exception {
    TestFlumeTimelineMetricsSink sink = new TestFlumeTimelineMetricsSink();
    sink.configure(new Context());
    sink.setAllMBeansException(new RuntimeException("Failed to retrieve Flume Properties"));
    sink.setTimes(1_000L);

    sink.new TimelineMetricsCollector().run();

    Assert.assertTrue(sink.getEmittedMetrics().isEmpty());
  }

  @Test
  public void testGettingFqdn() throws Exception {
    TestFlumeTimelineMetricsSink fqdnSink = new TestFlumeTimelineMetricsSink();
    fqdnSink.setHostNames("hostname.domain", "hostname.domain");
    fqdnSink.configure(new Context());
    Assert.assertEquals("hostname.domain", getHostname(fqdnSink));

    TestFlumeTimelineMetricsSink shortNameSink = new TestFlumeTimelineMetricsSink();
    shortNameSink.setHostNames("hostname", "hostname.domain");
    shortNameSink.configure(new Context());
    Assert.assertEquals("hostname.domain", getHostname(shortNameSink));
  }

  private static Map<String, Map<String, String>> singleMetricMap(String value) {
    Map<String, String> attributes = new HashMap<String, String>();
    attributes.put("key1", value);
    Map<String, Map<String, String>> metrics = new HashMap<String, Map<String, String>>();
    metrics.put("component1", attributes);
    return metrics;
  }

  private static String getHostname(FlumeTimelineMetricsSink sink) throws Exception {
    Field field = FlumeTimelineMetricsSink.class.getDeclaredField("hostname");
    field.setAccessible(true);
    return (String) field.get(sink);
  }

  private static final class TestFlumeTimelineMetricsSink extends FlumeTimelineMetricsSink {
    private Map<String, Map<String, String>> allMBeans = Collections.emptyMap();
    private RuntimeException allMBeansException;
    private final List<TimelineMetrics> emittedMetrics = new ArrayList<TimelineMetrics>();
    private final RecordingScheduledExecutorService executorService = new RecordingScheduledExecutorService();
    private final List<Long> times = new ArrayList<Long>();
    private int timeIndex = 0;
    private String hostName = "host.example.com";
    private String canonicalHostName = "host.example.com";

    private void setAllMBeans(Map<String, Map<String, String>> allMBeans) {
      this.allMBeans = allMBeans;
      this.allMBeansException = null;
    }

    private void setAllMBeansException(RuntimeException exception) {
      this.allMBeansException = exception;
    }

    private void setTimes(Long... values) {
      times.clear();
      if (values != null) {
        Collections.addAll(times, values);
      }
      timeIndex = 0;
    }

    private void setHostNames(String hostName, String canonicalHostName) {
      this.hostName = hostName;
      this.canonicalHostName = canonicalHostName;
    }

    private List<TimelineMetrics> getEmittedMetrics() {
      return emittedMetrics;
    }

    private RecordingScheduledExecutorService getExecutorService() {
      return executorService;
    }

    @Override
    protected Map<String, Map<String, String>> getAllMBeans() {
      if (allMBeansException != null) {
        throw allMBeansException;
      }
      return allMBeans;
    }

    @Override
    protected boolean emitMetrics(TimelineMetrics metrics) {
      emittedMetrics.add(metrics);
      return true;
    }

    @Override
    protected ScheduledExecutorService createScheduledExecutorService() {
      return executorService;
    }

    @Override
    protected long getCurrentTimeMillis() {
      if (times.isEmpty()) {
        return super.getCurrentTimeMillis();
      }
      int index = Math.min(timeIndex, times.size() - 1);
      long value = times.get(index);
      timeIndex++;
      return value;
    }

    @Override
    protected InetAddress getLocalHost() throws UnknownHostException {
      return InetAddress.getLoopbackAddress();
    }

    @Override
    protected String getHostName(InetAddress address) {
      return hostName;
    }

    @Override
    protected String getCanonicalHostName(InetAddress address) {
      return canonicalHostName;
    }

    @Override
    protected Collection<String> findLiveCollectorHostsFromKnownCollector(String host, String port) {
      return Collections.singletonList(host);
    }
  }

  private static final class RecordingScheduledExecutorService implements ScheduledExecutorService {
    private boolean shutdown;
    private boolean scheduled;
    private long delay;
    private TimeUnit unit;

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
      this.scheduled = true;
      this.delay = delay;
      this.unit = unit;
      return new CompletedScheduledFuture();
    }

    @Override
    public void shutdown() {
      this.shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      this.shutdown = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    private boolean isScheduled() {
      return scheduled;
    }

    private long getDelay() {
      return delay;
    }

    private TimeUnit getUnit() {
      return unit;
    }
  }

  private static final class CompletedScheduledFuture implements ScheduledFuture<Object> {
    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      return null;
    }
  }
}
