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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmRepoRequest;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.Response;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A repository the view cannot keep is refused with 400 and the reason, never a server error: a registry token too
 * long to be saved, or a missing field.
 */
class HelmRepoResourceSaveTest {

  @TempDir
  Path workDir;

  private final InMemoryDataStore store = new InMemoryDataStore();

  private HelmRepoResource resource() {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("view.admin.users", "admin",
        "k8s.view.working.dir", workDir.toString()));
    when(ctx.getUsername()).thenReturn("admin");
    when(ctx.getLoggedinUser()).thenReturn("admin");
    when(ctx.getDataStore()).thenReturn(store);
    return new HelmRepoResource(ctx, null);
  }

  private static HelmRepoRequest ociRepo(String token) {
    HelmRepoRequest r = new HelmRepoRequest();
    r.id = "ecr";
    r.type = "OCI";
    r.name = "ecr";
    r.url = "oci://123456789012.dkr.ecr.eu-west-1.amazonaws.com/charts";
    r.authMode = "token";
    r.username = "AWS";
    r.plainSecret = token;
    return r;
  }

  @Test
  void aTokenTooLongToBeSavedIsRefusedWith400AndTheReasonAndNothingIsSaved() {
    String tooLong = "t".repeat(InstanceSettings.MAX_SECRET_LENGTH + 1);

    Response response = resource().save(ociRepo(tooLong));

    assertEquals(400, response.getStatus());
    String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
    assertTrue(error.contains("too long"), error);
    assertFalse(error.contains(tooLong), "the token is never echoed");
    assertNull(store.find(HelmRepoEntity.class, "ecr"));
    assertTrue(store.findAll(KdpsSettingEntity.class, null).isEmpty());
  }

  @Test
  void aRepositoryWithoutAUrlIsRefusedWith400() {
    HelmRepoRequest request = ociRepo(null);
    request.url = null;

    Response response = resource().save(request);

    assertEquals(400, response.getStatus());
    assertEquals("url is required", ((Map<?, ?>) response.getEntity()).get("error"));
  }
}
