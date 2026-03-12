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
package org.apache.ambari.server.serveraction.upgrades;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FixAtlasSolrCloudZkUrlTest {

  @Test
  public void testNormalizeRepeatedChrootPerHost() {
    String broken = "master03:2181/infra-solr,master02:2181/infra-solr,master01:2181/infra-solr";
    String expected = "master03:2181,master02:2181,master01:2181/infra-solr";

    assertEquals(expected, FixAtlasSolrCloudZkUrl.normalizeSolrCloudZookeeperUrl(broken));
  }

  @Test
  public void testNormalizeAlreadyValidCloudConnectString() {
    String valid = "master03:2181,master02:2181,master01:2181/infra-solr";

    assertEquals(valid, FixAtlasSolrCloudZkUrl.normalizeSolrCloudZookeeperUrl(valid));
  }

  @Test
  public void testNormalizeWithoutChroot() {
    String valid = "master03:2181,master02:2181,master01:2181";

    assertEquals(valid, FixAtlasSolrCloudZkUrl.normalizeSolrCloudZookeeperUrl(valid));
  }

  @Test
  public void testNormalizeConflictingChrootsReturnsNull() {
    String conflicting = "master03:2181/infra-solr,master02:2181/other";

    assertNull(FixAtlasSolrCloudZkUrl.normalizeSolrCloudZookeeperUrl(conflicting));
  }
}
