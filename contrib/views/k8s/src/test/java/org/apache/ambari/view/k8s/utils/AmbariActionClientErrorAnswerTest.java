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

package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The view's stream provider does not throw on an HTTP error: it hands back Ambari's error body. Read as data, a
 * refusal would look like an empty answer, so the calls the role and permission checks rely on fail on it instead.
 */
class AmbariActionClientErrorAnswerTest {

  private static final String REFUSED =
      "{\"status\":403,\"message\":\"The authenticated user is not authorized to perform the requested operation\"}";

  private final URLStreamProvider stream = mock(URLStreamProvider.class);

  private AmbariActionClient answering(String body) throws IOException {
    when(stream.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap()))
        .thenAnswer(call -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);
    return new AmbariActionClient(ctx, "https://ambari:8443/api/v1", Map.of("Cookie", "AMBARISESSIONID=abc"));
  }

  @Test
  void aRefusedPermissionReadIsAFailureNotAnEmptySet() throws Exception {
    IOException refused = assertThrows(IOException.class,
        () -> answering(REFUSED).listUserAuthorizations("ops", "dev21"));

    assertTrue(refused.getMessage().contains("HTTP 403"), refused.getMessage());
  }

  @Test
  void aRefusedPrivilegeReadIsAFailureNotANo() throws Exception {
    assertThrows(IOException.class, () -> answering(REFUSED).isAmbariAdministrator("admin"));
    assertThrows(IOException.class, () -> answering("{\"status\":500,\"message\":\"Server Error\"}")
        .isAmbariAdministrator("admin"));
  }

  @Test
  void aRefusedClusterListIsAFailureNotAnAmbariWithoutCluster() throws Exception {
    // Read as "no cluster", the deploy would believe there is no Kerberos to inherit and turn it off.
    IOException refused = assertThrows(IOException.class, () -> answering(REFUSED).listClusters());

    assertTrue(refused.getMessage().contains("HTTP 403"), refused.getMessage());
  }

  @Test
  void anAmbariWithoutClusterStillAnswersAnEmptyList() throws Exception {
    assertEquals(List.of(), answering("{\"href\":\"https://ambari:8443/api/v1/clusters\",\"items\":[]}").listClusters());
    assertEquals(List.of("dev21"),
        answering("{\"items\":[{\"Clusters\":{\"cluster_name\":\"dev21\"}}]}").listClusters());
  }

  @Test
  void anAnswerWithoutItemsIsNotAnEmptyPermissionSet() throws Exception {
    assertThrows(IOException.class, () -> answering("{\"Users\":{\"user_name\":\"ops\"}}")
        .listUserAuthorizations("ops", null));
  }

  @Test
  void anAccountWithoutPermissionsHasAnEmptySet() throws Exception {
    assertEquals(Set.of(), answering("{\"items\":[]}").listUserAuthorizations("ops", "dev21"));
    assertFalse(answering("{\"items\":[]}").isAmbariAdministrator("ops"));
  }

  @Test
  void userNamesAreEncodedAsPathSegments() throws Exception {
    AmbariActionClient client = answering("{\"items\":[]}");

    client.isAmbariAdministrator("John Smith");
    client.listUserAuthorizations("ops+qa", null);

    verify(stream).readFrom(startsWith("https://ambari:8443/api/v1/users/John%20Smith/privileges?"), eq("GET"),
        (String) isNull(), anyMap());
    verify(stream).readFrom(startsWith("https://ambari:8443/api/v1/users/ops%2Bqa/authorizations?"), eq("GET"),
        (String) isNull(), anyMap());
  }
}
