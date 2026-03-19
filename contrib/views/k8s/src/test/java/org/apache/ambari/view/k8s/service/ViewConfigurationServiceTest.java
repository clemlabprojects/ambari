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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ViewConfigurationServiceTest {

  @Test
  void roundTripKubeconfig() throws Exception {
    ViewContext ctx = mock(ViewContext.class);

    // ---- stub des propriétés ----
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    Map <String, String> properties = Map.of(
      "k8s.view.working.dir", tmp,
      "kubeconfig.path", tmp + "/config.yaml.enc",
      "kubeconfig.uploaded", "true"
    );
    when(ctx.getProperties()).thenReturn(properties);

    // ---- stub InstanceData (clé/valeur) ----
    Map<String,String> instanceData = new java.util.HashMap<>();
    when(ctx.getInstanceData(anyString()))
        .thenAnswer(inv -> instanceData.get(inv.getArgument(0)));
    doAnswer(inv -> { instanceData.put(inv.getArgument(0), inv.getArgument(1)); return null; })
        .when(ctx).putInstanceData(anyString(), anyString());

    ViewConfigurationService svc = new ViewConfigurationService(ctx);

    String yaml = "apiVersion: v1\nclusters: []\ncontexts: []\nusers: []\n";
    svc.saveKubeconfigFile(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
        "config.yaml"
    );

    assertEquals(yaml, svc.getKubeconfigContents()); // OK
    assertNotNull(svc.getKubeconfigPath());          // OK
  }
}
