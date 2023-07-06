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
package org.apache.ambari.logfeeder.output.cloud;

import org.apache.ambari.logfeeder.conf.CloudStorageDestination;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class CloudStorageUploaderTest {

  private static final String CLUSTER_NAME = "cl";
  private static final String HOSTNAME = "hostname";

  private CloudStorageUploader underTest;

  @Before
  public void setUp() {
    LogFeederProps logFeederProps = new LogFeederProps();
    logFeederProps.setCloudStorageDestination(CloudStorageDestination.DEFAULT_FS);
    underTest = new CloudStorageUploader("name", null, logFeederProps);
  }

  @Test
  public void testGenerateOutputPath() {
    // GIVEN
    String basePath = "example";
    // WHEN
    String output = underTest.generateOutputPath(basePath, CLUSTER_NAME, HOSTNAME, new File("/my/path"));
    // THEN
    Assert.assertEquals("example/cl/hostname/my/path", output);
  }

  @Test
  public void testGenerateOutputPathWithEmptyBasePath() {
    // GIVEN
    String basePath = "";
    // WHEN
    String output = underTest.generateOutputPath(basePath, CLUSTER_NAME, HOSTNAME, new File("/my/path"));
    // THEN
    Assert.assertEquals("cl/hostname/my/path", output);
  }

  @Test
  public void testGenerateOutputPathWithSlashEndAndStart() {
    // GIVEN
    String basePath = "example/";
    // WHEN
    String output = underTest.generateOutputPath(basePath, CLUSTER_NAME, HOSTNAME, new File("/my/path"));
    // THEN
    Assert.assertEquals("example/cl/hostname/my/path", output);
  }

  @Test
  public void testGenerateOutputPathWithScheme() {
    // GIVEN
    String basePath = "s3a://bucket/example";
    // WHEN
    String output = underTest.generateOutputPath(basePath, CLUSTER_NAME, HOSTNAME, new File("/my/path"));
    // THEN
    Assert.assertEquals("s3a://bucket/example/cl/hostname/my/path", output);
  }
}
