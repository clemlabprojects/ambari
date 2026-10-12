/**
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

package org.apache.ambari.view.k8s.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GET /contexts/{id}/resolved returns a resolved context to every user of the view, read-only ones included, so no
 * credential the context holds may leave it in clear.
 */
class ResolvedContextMaskTest {

  @Test
  void everyCredentialIsMasked() throws Exception {
    ResolvedContext rc = new ResolvedContext();
    List<Field> credentials = new ArrayList<>();
    for (Field f : ResolvedContext.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
      String name = f.getName().toLowerCase(Locale.ROOT);
      if (name.contains("password") || name.contains("secret") || name.contains("token")) {
        f.setAccessible(true);
        f.set(rc, "plain-" + f.getName());
        credentials.add(f);
      }
    }
    assertFalse(credentials.isEmpty(), "the context holds credentials, so the test must find some");

    rc.maskSecrets();

    for (Field f : credentials) {
      assertEquals(ResolvedContext.MASKED_SECRET, f.get(rc), f.getName() + " must not leave the view in clear");
    }
  }

  @Test
  void anUnsetCredentialStaysUnset() {
    ResolvedContext rc = new ResolvedContext();
    rc.setRangerAdminUsername("admin");

    rc.maskSecrets();

    assertNull(rc.getRangerAdminPassword());
    assertNull(rc.getOidcAdminClientSecret());
    assertNull(rc.getAtlasFederationPassword());
    assertEquals("admin", rc.getRangerAdminUsername(), "user names are not secrets");
  }
}
