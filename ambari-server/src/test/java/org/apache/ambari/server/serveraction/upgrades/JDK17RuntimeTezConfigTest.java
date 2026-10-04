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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class JDK17RuntimeTezConfigTest {

  @Test
  public void migratesTezOptsToEnv() {
    Map<String, String> tezSite = new HashMap<>();
    Map<String, String> tezEnv = new HashMap<>();

    tezSite.put("tez.am.launch.cmd-opts", "-Xmx1g -XX:+UseG1GC");
    tezSite.put("tez.task.launch.cmd-opts", "-Xmx512m -XX:+UseG1GC");

    boolean updated = new JDK17RuntimeTezConfig().migrateTezJvmOpts(tezSite, tezEnv, 17);

    assertTrue(updated);
    assertEquals("", tezSite.get("tez.am.launch.cmd-opts"));
    assertEquals("", tezSite.get("tez.task.launch.cmd-opts"));
    assertEquals("-Xmx1g -XX:+UseG1GC", tezEnv.get("tez_am_base_java_opts"));
    assertEquals("-Xmx512m -XX:+UseG1GC", tezEnv.get("tez_task_base_java_opts"));
    assertEquals("{{heap_dump_opts}}", tezEnv.get("tez_am_extra_java_opts"));
    assertEquals("{{heap_dump_opts}}", tezEnv.get("tez_task_extra_java_opts"));
  }

  @Test
  public void noUpdateWhenEmpty() {
    Map<String, String> tezSite = new HashMap<>();
    Map<String, String> tezEnv = new HashMap<>();

    boolean updated = new JDK17RuntimeTezConfig().migrateTezJvmOpts(tezSite, tezEnv, 17);

    assertEquals(false, updated);
    assertTrue(tezSite.isEmpty());
    assertTrue(tezEnv.isEmpty());
  }

  @Test
  public void removesRetiredFlagsWithoutReplacingOperatorOptions() {
    for (int major : new int[] {17, 21}) {
      Map<String, String> site = new HashMap<>();
      Map<String, String> env = new HashMap<>();
      site.put("tez.am.launch.cmd-opts", "-XX:+PrintGCTimeStamps -Xmx1g -verbose:gc");
      site.put("tez.task.launch.cmd-opts", "-XX:+PrintGCDateStamps -XX:+UseG1GC -Dcustom=true");
      JDK17RuntimeTezConfig migration = new JDK17RuntimeTezConfig();
      assertTrue(migration.migrateTezJvmOpts(site, env, major));
      assertEquals("-Xmx1g -verbose:gc", env.get("tez_am_base_java_opts"));
      assertEquals("-XX:+UseG1GC -Dcustom=true", env.get("tez_task_base_java_opts"));
      assertEquals(false, migration.migrateTezJvmOpts(site, env, major));
    }
  }

  @Test
  public void repairsAlreadyMigratedValuesButLeavesJava8Unchanged() {
    Map<String, String> site = new HashMap<>();
    Map<String, String> env = new HashMap<>();
    env.put("tez_am_base_java_opts", "-XX:+PrintGCTimeStamps -XX:+UseG1GC");
    env.put("tez_task_extra_java_opts", "-XX:+PrintGCDateStamps {{heap_dump_opts}}");
    JDK17RuntimeTezConfig migration = new JDK17RuntimeTezConfig();
    assertEquals(false, migration.migrateTezJvmOpts(site, env, 8));
    assertTrue(migration.migrateTezJvmOpts(site, env, 17));
    assertEquals("-XX:+UseG1GC", env.get("tez_am_base_java_opts"));
    assertEquals("{{heap_dump_opts}}", env.get("tez_task_extra_java_opts"));
  }
}
