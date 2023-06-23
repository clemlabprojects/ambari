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
package org.apache.ambari.infra.solr.commands;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.File;
import java.util.Optional;

import org.apache.ambari.infra.solr.AmbariSolrCloudClient;
import org.apache.ambari.infra.solr.domain.ZookeeperClient;
import org.apache.commons.io.FileUtils;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.SolrZooKeeper;

public class SetAutoScalingZkCommand extends AbstractZookeeperRetryCommand<String> {
  private static final String AUTO_SCALING_JSON = "/autoscaling.json";

  private final String autoScalingJsonLocation;

  public SetAutoScalingZkCommand(int maxRetries, int interval, String autoScalingJsonLocation) {
    super(maxRetries, interval);
    this.autoScalingJsonLocation = autoScalingJsonLocation;
  }

  @Override
  protected String executeZkCommand(AmbariSolrCloudClient client, SolrZkClient zkClient, SolrZooKeeper solrZooKeeper) throws Exception {
    if (isBlank(autoScalingJsonLocation))
      return "";

    File fileToUpload = new File(autoScalingJsonLocation);
    if (!fileToUpload.exists())
      return "";

    String contentToUpload = FileUtils.readFileToString(fileToUpload, UTF_8);
    if (isBlank(contentToUpload))
      return "";

    String zFilePath = client.getZnode() + AUTO_SCALING_JSON;
    ZookeeperClient zookeeperClient = new ZookeeperClient(zkClient);
    Optional<String> fileContent = zookeeperClient.getFileContent(zFilePath);
    if (!fileContent.isPresent() || !contentToUpload.equals(fileContent.get()))
      zookeeperClient.putFileContent(zFilePath, contentToUpload);

    return contentToUpload;
  }
}
