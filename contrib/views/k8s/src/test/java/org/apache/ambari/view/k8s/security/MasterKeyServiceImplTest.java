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

package org.apache.ambari.view.k8s.security;

import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The master key is found where Ambari finds it. */
class MasterKeyServiceImplTest {

  @TempDir
  Path dir;

  private static Function<String, String> props(Map<String, String> values) {
    return values::get;
  }

  private Path persistKey(Path keysDir, String key) throws Exception {
    Files.createDirectories(keysDir);
    return Files.writeString(keysDir.resolve("master"), TestMasterKeys.ambariMasterKeyFile(key));
  }

  @Test
  void theEnvironmentVariableComesFirst() throws Exception {
    persistKey(dir, "persisted-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of("AMBARI_SECURITY_MASTER_KEY", "env-key"),
        props(Map.of("security.server.keys_dir", dir.toString())));

    assertTrue(key.isMasterKeyInitialized());
    assertArrayEquals("env-key".toCharArray(), key.getMasterSecret());
  }

  @Test
  void aKeysDirMovedInAmbariPropertiesIsHonoured() throws Exception {
    Path keysDir = dir.resolve("hardened/keys");
    persistKey(keysDir, "persisted-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of(),
        props(Map.of("security.server.keys_dir", keysDir.toString())));

    assertTrue(key.isMasterKeyInitialized());
    assertArrayEquals("persisted-key".toCharArray(), key.getMasterSecret());
    assertEquals(keysDir.resolve("master").toString(), key.getSource());
  }

  @Test
  void theMasterKeyLocationComesBeforeTheKeysDir() throws Exception {
    persistKey(dir.resolve("keys"), "keys-dir-key");
    persistKey(dir.resolve("master-location"), "location-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of(), props(Map.of(
        "security.server.keys_dir", dir.resolve("keys").toString(),
        "security.master.key.location", dir.resolve("master-location").toString())));

    assertArrayEquals("location-key".toCharArray(), key.getMasterSecret());
  }

  @Test
  void aMasterKeyLocationNamingTheFileItselfIsAccepted() throws Exception {
    Path file = persistKey(dir, "file-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of(),
        props(Map.of("security.master.key.location", file.toString())));

    assertArrayEquals("file-key".toCharArray(), key.getMasterSecret());
  }

  @Test
  void theViewInstanceGivesTheAmbariProperties() throws Exception {
    persistKey(dir, "persisted-key");
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getAmbariProperty("security.server.keys_dir")).thenReturn(dir.toString());

    assertEquals(dir.resolve("master").toFile(),
        MasterKeyServiceImpl.keyFile(ctx::getAmbariProperty));
  }

  @Test
  void withoutAnyPropertyTheDefaultKeyFileIsUsed() {
    assertEquals(MasterKeyServiceImpl.MASTER_KEY_DEFAULT_LOCATION,
        MasterKeyServiceImpl.keyFile(props(Map.of())).getPath());
  }

  @Test
  void noKeyAnywhereLeavesTheServiceWithoutAKeyAndDoesNotThrow() {
    MasterKeyServiceImpl key = assertDoesNotThrow(() -> new MasterKeyServiceImpl(Map.of(),
        props(Map.of("security.server.keys_dir", dir.toString()))));

    assertFalse(key.isMasterKeyInitialized());
    assertNull(key.getMasterSecret());
  }

  @Test
  void anEnvironmentVariableNamedLikeTheAmbariPropertyIsNotALocation() throws Exception {
    // Ambari reads security.master.key.location from ambari.properties, never from the environment.
    persistKey(dir.resolve("from-env"), "env-located-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(
        Map.of("security.master.key.location", dir.resolve("from-env").toString()),
        props(Map.of("security.server.keys_dir", dir.resolve("empty").toString())));

    assertFalse(key.isMasterKeyInitialized());
  }

  @Test
  void aFileThatIsNotAMasterKeyFileGivesNoKey() throws Exception {
    Files.writeString(dir.resolve("master"), "not a master key\n");

    assertFalse(new MasterKeyServiceImpl(Map.of(), props(Map.of("security.server.keys_dir", dir.toString())))
        .isMasterKeyInitialized());
  }

  @Test
  void withoutAViewInstanceTheAmbariPropertiesFileIsRead() throws Exception {
    Path conf = Files.createDirectories(dir.resolve("conf"));
    Files.writeString(conf.resolve("ambari.properties"), "security.server.keys_dir=" + dir.resolve("keys") + "\n");
    persistKey(dir.resolve("keys"), "persisted-key");

    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of(),
        MasterKeyServiceImpl.ambariPropertiesFile(Map.of("AMBARI_CONF_DIR", conf.toString())));

    assertArrayEquals("persisted-key".toCharArray(), key.getMasterSecret());
  }

  @Test
  void theCallerMayWipeTheKeyItIsGiven() {
    MasterKeyServiceImpl key = new MasterKeyServiceImpl(Map.of("AMBARI_SECURITY_MASTER_KEY", "env-key"), props(Map.of()));

    java.util.Arrays.fill(key.getMasterSecret(), '\0');

    assertArrayEquals("env-key".toCharArray(), key.getMasterSecret());
  }
}
