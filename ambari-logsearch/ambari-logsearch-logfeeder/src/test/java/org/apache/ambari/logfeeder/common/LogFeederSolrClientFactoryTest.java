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
package org.apache.ambari.logfeeder.common;

import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogFeederSolrClientFactoryTest {

  private LogFeederSolrClientFactory underTest;

  @Before
  public void setUp() {
    underTest = new LogFeederSolrClientFactory();
  }

  @Test
  public void testCreateZKConnection() {
    // GIVEN
    String input = "localhost:2181/solr";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertEquals("/solr", Optional.ofNullable(result.getZkChroot()).get());
    assertEquals("localhost:2181", result.getZkHosts().get(0));
  }

  @Test
  public void testCreateZKConnectionWithoutChroot() {
    // GIVEN
    String input = "localhost:2181";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertFalse(Optional.ofNullable(result.getZkChroot()).isPresent());
    assertEquals(input, result.getZkHosts().get(0));
  }

  @Test
  public void testCreateZKConnectionWithMultipleHosts() {
    // GIVEN
    String input = "localhost1:2181,localhost2:2181";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertFalse(Optional.ofNullable(result.getZkChroot()).isPresent());
    assertEquals(2, result.getZkHosts().size());
    assertTrue(result.getZkHosts().contains("localhost1:2181"));
    assertTrue(result.getZkHosts().contains("localhost2:2181"));
  }

  @Test
  public void testCreateZKConnectionWithMultipleHostsAndChroot() {
    // GIVEN
    String input = "localhost1:2181,localhost2:2181/solr";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertEquals("/solr", result.getZkChroot());
    assertEquals(2, result.getZkHosts().size());
    assertTrue(result.getZkHosts().contains("localhost1:2181"));
    assertTrue(result.getZkHosts().contains("localhost2:2181"));
  }

  @Test
  public void testCreateZKConnectionInvalidSlashes() {
    // GIVEN
    String input = "localhost:2181//solr/";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertEquals("/solr", Optional.ofNullable(result.getZkChroot()).get());
    assertEquals("localhost:2181", result.getZkHosts().get(0));
  }

  @Test
  public void testCreateZKConnectionInvalidSlashesMultipleTimes() {
    // GIVEN
    String input = "localhost:2181//solr/my//root";
    // WHEN
    LogFeederSolrClientFactory.ZkConnection result = underTest.createZKConnection(input);
    // THEN
    assertEquals("/solr/my/root", Optional.ofNullable(result.getZkChroot()).get());
    assertEquals("localhost:2181", result.getZkHosts().get(0));
  }

  @Test
  public void testAppendTo() {
    // GIVEN
    String toAppend = "/mycollection";
    String[] appendees = new String[]{"http://solr1:8886", "http://solr2:8886"};
    // WHEN
    String[] result = underTest.appendTo(toAppend, appendees);
    // THEN
    assertEquals("http://solr1:8886/mycollection", result[0]);
    assertEquals("http://solr2:8886/mycollection", result[1]);
  }
}
