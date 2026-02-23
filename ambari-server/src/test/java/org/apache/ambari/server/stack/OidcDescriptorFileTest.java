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
package org.apache.ambari.server.stack;

import java.io.File;
import java.net.URL;
import java.util.regex.Pattern;

import org.apache.ambari.server.state.oidc.OidcDescriptorFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;

public class OidcDescriptorFileTest {
  private static final Logger LOG = LoggerFactory.getLogger(OidcDescriptorFileTest.class);
  private static final Pattern PATTERN_OIDC_DESCRIPTOR_FILENAME = Pattern.compile("^oidc\\.json$");

  private static final OidcDescriptorFactory OIDC_DESCRIPTOR_FACTORY = new OidcDescriptorFactory();

  private static File resourcesDirectory;
  private static File stacksDirectory;
  private static File commonServicesDirectory;

  @BeforeClass
  public static void beforeClass() {
    URL rootDirectoryURL = OidcDescriptorFileTest.class.getResource("/");
    Assert.assertNotNull(rootDirectoryURL);

    resourcesDirectory = new File(new File(rootDirectoryURL.getFile()).getParentFile().getParentFile(), "src/main/resources");
    Assert.assertNotNull(resourcesDirectory);
    Assert.assertTrue(resourcesDirectory.canRead());

    stacksDirectory = new File(resourcesDirectory, "stacks");
    Assert.assertNotNull(stacksDirectory);
    Assert.assertTrue(stacksDirectory.canRead());

    commonServicesDirectory = new File(resourcesDirectory, "common-services");
    Assert.assertNotNull(commonServicesDirectory);
    Assert.assertTrue(commonServicesDirectory.canRead());
  }

  @Test
  public void testCommonServiceDescriptors() throws Exception {
    Assert.assertTrue(visitFile(commonServicesDirectory, true));
  }

  @Test
  public void testCommonDescriptor() throws Exception {
    File commonOidcDescriptor = new File(resourcesDirectory, "oidc.json");
    Assert.assertTrue(commonOidcDescriptor.isFile());
    OIDC_DESCRIPTOR_FACTORY.createInstance(commonOidcDescriptor);
  }

  @Test
  public void testStackDescriptors() throws Exception {
    Assert.assertTrue(visitFile(stacksDirectory, true));
  }

  private boolean visitFile(File file, boolean previousResult) throws Exception {
    if (file.isDirectory()) {
      boolean currentResult = true;
      File[] files = file.listFiles();
      if (files != null) {
        for (File currentFile : files) {
          currentResult = visitFile(currentFile, previousResult) && currentResult;
        }
      }
      return previousResult && currentResult;
    } else if (file.isFile()) {
      if (PATTERN_OIDC_DESCRIPTOR_FILENAME.matcher(file.getName()).matches()) {
        LOG.info("Validating {}", file.getAbsolutePath());
        try {
          OIDC_DESCRIPTOR_FACTORY.createInstance(file);
        } catch (Exception e) {
          LOG.error("Failed parsing OIDC descriptor {}", file.getAbsolutePath(), e);
          return false;
        }
      }
      return true;
    }
    return previousResult;
  }
}
