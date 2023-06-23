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
package org.apache.ambari.infra.solr.domain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.zookeeper.CreateMode.PERSISTENT;

import java.util.Optional;

import org.apache.solr.common.cloud.SolrZkClient;

public class ZookeeperClient {
  private final SolrZkClient zkClient;

  public ZookeeperClient(SolrZkClient zkClient) {
    this.zkClient = zkClient;
  }

  public void putFileContent(String fileName, String content) throws Exception {
    if (zkClient.exists(fileName, true)) {
      zkClient.setData(fileName, content.getBytes(UTF_8), true);
    } else {
      zkClient.create(fileName, content.getBytes(UTF_8), PERSISTENT, true);
    }
  }

  public Optional<String> getFileContent(String fileName) throws Exception {
    if (!zkClient.exists(fileName, true))
      return Optional.empty();

    byte[] data = zkClient.getData(fileName, null, null, true);
    return Optional.of(new String(data, UTF_8));
  }
}
