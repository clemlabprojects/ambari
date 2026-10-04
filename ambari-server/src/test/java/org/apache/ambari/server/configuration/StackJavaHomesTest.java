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
package org.apache.ambari.server.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.AmbariManagementControllerImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StackJavaHomesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private File file;
  private Configuration config;

  @Before
  public void setup() throws Exception {
    file = temporary.newFile("ambari.properties");
    Files.write(file.toPath(), ("java.home=/jdk8\nstack.java.home=/jdk8\n"
        + "ambari.java.home=/server17\nserver.jdbc.user.passwd=unchanged\n").getBytes(StandardCharsets.UTF_8));
    Properties properties = read();
    properties.setProperty("security.server.keys_dir", temporary.getRoot().getAbsolutePath());
    config = spy(new Configuration(properties));
    doReturn(file).when(config).getConfigFile();
  }

  private Properties read() throws Exception {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  private Map<String, String> selected(String primary, String secondary) {
    Map<String, String> homes = new HashMap<>();
    homes.put("java.home", primary);
    homes.put("stack.java.home", primary);
    homes.put("secondary.java.home", secondary);
    return homes;
  }

  @Test
  public void persistsAndUpdatesLiveConfigurationWithoutChangingServerRuntime() throws Exception {
    config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
    assertEquals("/jdk17", read().getProperty("java.home"));
    assertEquals("/jdk21", read().getProperty("secondary.java.home"));
    assertEquals("/jdk17", config.getJavaHome());
    assertEquals("/jdk21", config.getSecondaryJavaHome());
    assertEquals("/server17", read().getProperty("ambari.java.home"));
    assertEquals("unchanged", read().getProperty("server.jdbc.user.passwd"));
  }

  @Test
  public void controllerGetterTracksExplicitChangesButDoesNotSwitchAnythingItself() throws Exception {
    AmbariManagementControllerImpl controller = mock(AmbariManagementControllerImpl.class, CALLS_REAL_METHODS);
    Field configuration = AmbariManagementControllerImpl.class.getDeclaredField("configs");
    configuration.setAccessible(true);
    configuration.set(controller, config);
    assertEquals("/jdk8", controller.getJavaHome());
    assertEquals("/jdk8", controller.getJavaHome());
    config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
    assertEquals("/jdk17", controller.getJavaHome());
    config.updateStackJavaHomes(selected("/jdk8", null));
    assertEquals("/jdk8", controller.getJavaHome());
  }

  @Test
  public void controllerGetterPreservesNullConfigurationBehavior() {
    AmbariManagementControllerImpl controller = mock(AmbariManagementControllerImpl.class, CALLS_REAL_METHODS);
    assertNull(controller.getJavaHome());
  }

  @Test
  public void retryIsIdempotentAndDowngradeCanRemoveSecondary() throws Exception {
    config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
    config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
    config.updateStackJavaHomes(selected("/jdk8", null));
    assertEquals("/jdk8", config.getJavaHome());
    assertNull(read().getProperty("secondary.java.home"));
    assertNull(config.getSecondaryJavaHome());
  }

  @Test
  public void filePermissionsArePreserved() throws Exception {
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
    config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())));
  }

  @Test
  public void failedWriteDoesNotChangeLiveProperties() throws Exception {
    doReturn(new File(temporary.getRoot(), "missing/ambari.properties")).when(config).getConfigFile();
    try {
      config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
      fail("Expected a persistence failure");
    } catch (AmbariException expected) {
      assertEquals("/jdk8", config.getJavaHome());
      assertEquals("/jdk8", read().getProperty("java.home"));
    }
  }

  @Test(expected = AmbariException.class)
  public void cannotWriteUnrelatedServerProperties() throws Exception {
    Map<String, String> homes = selected("/jdk17", "/jdk21");
    homes.put("ambari.java.home", "/jdk21");
    config.updateStackJavaHomes(homes);
  }

  @Test
  public void manualDiskEditIsNotSilentlyOverwritten() throws Exception {
    Files.write(file.toPath(), "java.home=/manually-selected\n".getBytes(StandardCharsets.UTF_8));
    try {
      config.updateStackJavaHomes(selected("/jdk17", "/jdk21"));
      fail("Expected concurrent Java configuration change to be rejected");
    } catch (AmbariException expected) {
      assertEquals("/manually-selected", read().getProperty("java.home"));
      assertEquals("/jdk8", config.getJavaHome());
    }
  }
}
