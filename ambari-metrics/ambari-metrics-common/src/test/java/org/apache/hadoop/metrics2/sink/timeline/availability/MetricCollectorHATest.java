/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.metrics2.sink.timeline.availability;

import com.google.gson.Gson;
import junit.framework.Assert;
import org.apache.commons.io.IOUtils;
import org.apache.curator.CuratorZookeeperClient;
import org.apache.curator.RetryPolicy;
import org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Test;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

public class MetricCollectorHATest {

  @Test
  public void findCollectorUsingZKTest() throws Exception {
    InputStream is = createNiceMock(InputStream.class);
    HttpURLConnection connection = createNiceMock(HttpURLConnection.class);
    MetricCollectorHAHelper haHelper = createNiceMock(MetricCollectorHAHelper.class);

    expect(connection.getInputStream()).andReturn(is).anyTimes();
    expect(connection.getResponseCode()).andThrow(new IOException()).anyTimes();
    expect(haHelper.findLiveCollectorHostsFromZNode()).andReturn(
      new ArrayList<String>() {{
        add("h2");
        add("h3");
      }});

    replay(connection, is, haHelper);
    TestTimelineMetricsSink sink = new TestTimelineMetricsSink(haHelper, connection);
    sink.init();

    String host = sink.findPreferredCollectHost();

    verify(connection, is, haHelper);

    Assert.assertNotNull(host);
    Assert.assertEquals("h2", host);

  }


  @Test
  public void testEmbeddedModeCollectorZK() throws Exception {
    RetryPolicy retryPolicyMock = createNiceMock(RetryPolicy.class);
    CuratorZookeeperClient clientMock = createMock(CuratorZookeeperClient.class);
    ZooKeeper zkMock = createMock(ZooKeeper.class);

    clientMock.start();
    expectLastCall().once();

    clientMock.close();
    expectLastCall().once();

    expect(clientMock.getZooKeeper()).andReturn(zkMock).once();
    expect(zkMock.exists("/ambari-metrics-cluster", false)).andReturn(null).once();

    TestMetricCollectorHAHelper metricCollectorHAHelper = new TestMetricCollectorHAHelper("zkQ", 1, 1000);
    metricCollectorHAHelper.setRetryPolicy(retryPolicyMock);
    metricCollectorHAHelper.setClient(clientMock);

    replay(clientMock, zkMock, retryPolicyMock);
    Collection<String> liveInstances = metricCollectorHAHelper.findLiveCollectorHostsFromZNode();
    verify(clientMock, zkMock, retryPolicyMock);
    Assert.assertTrue(liveInstances.isEmpty());
  }

  @Test
  public void findCollectorUsingKnownCollectorTest() throws Exception {
    HttpURLConnection connection = createNiceMock(HttpURLConnection.class);
    MetricCollectorHAHelper haHelper = createNiceMock(MetricCollectorHAHelper.class);

    Gson gson = new Gson();
    ArrayList<String> output = new ArrayList<>();
    output.add("h1");
    output.add("h2");
    output.add("h3");
    InputStream is = IOUtils.toInputStream(gson.toJson(output));

    expect(connection.getInputStream()).andReturn(is).anyTimes();
    expect(connection.getResponseCode()).andReturn(200).anyTimes();

    replay(connection, haHelper);
    TestTimelineMetricsSink sink = new TestTimelineMetricsSink(haHelper, connection);
    sink.init();

    String host = sink.findPreferredCollectHost();
    Assert.assertNotNull(host);
    Assert.assertEquals("h3", host);

    verify(connection, haHelper);
  }

  private class TestMetricCollectorHAHelper extends MetricCollectorHAHelper {
    private RetryPolicy retryPolicy;
    private CuratorZookeeperClient client;

    TestMetricCollectorHAHelper(String zookeeperConnectionURL, int tryCount, int sleepMsBetweenRetries) {
      super(zookeeperConnectionURL, tryCount, sleepMsBetweenRetries);
    }

    void setRetryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
    }

    void setClient(CuratorZookeeperClient client) {
      this.client = client;
    }

    @Override
    protected RetryPolicy createRetryPolicy(int baseSleepMs, int maxSleepMs, int maxRetries) {
      return retryPolicy;
    }

    @Override
    protected CuratorZookeeperClient createCuratorZookeeperClient(String zkConnectionUrl,
      int sessionTimeoutMs, int connectionTimeoutMs, RetryPolicy retryPolicy) {
      return client;
    }
  }

  private class TestTimelineMetricsSink extends AbstractTimelineMetricsSink {
    MetricCollectorHAHelper testHelper;
    private final HttpURLConnection connection;

    TestTimelineMetricsSink(MetricCollectorHAHelper haHelper, HttpURLConnection connection) {
      testHelper = haHelper;
      this.connection = connection;
    }

    @Override
    protected void init() {
      super.init();
      this.collectorHAHelper = testHelper;
    }

    @Override
    protected synchronized String findPreferredCollectHost() {
      return super.findPreferredCollectHost();
    }

    @Override
    protected HttpURLConnection getConnection(String spec) {
      return connection;
    }

    @Override
    protected String getCollectorUri(String host) {
      return null;
    }

    @Override
    protected String getCollectorProtocol() {
      return "http";
    }

    @Override
    protected String getCollectorPort() {
      return "2181";
    }

    @Override
    protected int getTimeoutSeconds() {
      return 10;
    }

    @Override
    protected String getZookeeperQuorum() {
      return "localhost1:2181";
    }

    @Override
    protected Collection<String> getConfiguredCollectorHosts() {
      return Arrays.asList("localhost1",  "localhost2");
    }

    @Override
    protected String getHostname() {
      return "h1";
    }

    @Override
    protected boolean isHostInMemoryAggregationEnabled() {
      return true;
    }

    @Override
    protected int getHostInMemoryAggregationPort() {
      return 61888;
    }

    @Override
    protected String getHostInMemoryAggregationProtocol() {
      return "http";
    }
  }
}
