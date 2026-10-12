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

import org.apache.ambari.view.AmbariStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.service.CommandLogService;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The keytab endpoint is called by the view's own keytab webhook, with the Ambari administrator account the view
 * created for it. Once the role lists are set that account is in neither of them, yet it must keep getting keytabs;
 * read-only users must not.
 */
class CommandResourceKeytabRoleTest {

  private static final String INSTANCE = "keytab-role-test";

  private final CommandService commands = mock(CommandService.class);
  @TempDir
  Path workDir;

  @BeforeEach
  void registerCommandService() throws Exception {
    registry(CommandService.class).put(INSTANCE, commands);
    when(commands.submitKeytabRequest(any(), any(), any())).thenReturn("cmd-1");
  }

  @AfterEach
  void unregister() throws Exception {
    registry(CommandService.class).remove(INSTANCE);
    registry(CommandLogService.class).remove(INSTANCE);
  }

  @Test
  void theWebhookAccountGetsItsKeytab() {
    Response accepted = as("webhook-user").requestKeytab(headers(), uriInfo(), keytabRequest());

    assertEquals(202, accepted.getStatus());
    verify(commands).submitKeytabRequest(any(), any(), any());
  }

  @Test
  void anOperatorGetsAKeytab() {
    assertEquals(202, as("ops").requestKeytab(headers(), uriInfo(), keytabRequest()).getStatus());
  }

  @Test
  void aReadOnlyUserIsRefused() {
    ForbiddenException refused = assertThrows(ForbiddenException.class,
        () -> as("audit").requestKeytab(headers(), uriInfo(), keytabRequest()));

    assertEquals(403, refused.getResponse().getStatus());
    verifyNoInteractions(commands);
  }

  @Test
  void anAmbariAdministratorOutsideTheListsGetsNoKeytab() {
    // With the lists set, only the webhook's own account is let through on its Ambari role.
    assertThrows(ForbiddenException.class, () -> as("admin2").requestKeytab(headers(), uriInfo(), keytabRequest()));
    verifyNoInteractions(commands);
  }

  // ------------------------------------------------------------------------------------------------------------

  /** The resource as served to {@code user}; webhook-user and admin2 are Ambari administrators, nobody else is. */
  private CommandResource as(String user) {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users", "lead",
        "view.operator.users", "ops",
        "k8s.view.working.dir", workDir.toString()));
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    when(ctx.getViewName()).thenReturn("K8S-VIEW");
    ViewDefinition definition = mock(ViewDefinition.class);
    when(definition.getVersion()).thenReturn("1.0.0");
    when(ctx.getViewDefinition()).thenReturn(definition);
    when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("ambari.test");
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    try {
      when(ambari.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap())).thenAnswer(call -> {
        String path = call.getArgument(0);
        boolean admin = path.startsWith("/api/v1/users/webhook-user/") || path.startsWith("/api/v1/users/admin2/");
        return new ByteArrayInputStream((admin
            ? "{\"items\":[{\"PrivilegeInfo\":{\"permission_name\":\"AMBARI.ADMINISTRATOR\",\"principal_name\":\""
                + user + "\",\"principal_type\":\"USER\",\"type\":\"AMBARI\"}}]}"
            : "{\"items\":[]}").getBytes(StandardCharsets.UTF_8));
      });
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return new CommandResource(ctx, null, null);
  }

  private static HttpHeaders headers() {
    HttpHeaders headers = mock(HttpHeaders.class);
    when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
    return headers;
  }

  private static UriInfo uriInfo() {
    UriInfo ui = mock(UriInfo.class);
    when(ui.getBaseUri()).thenReturn(URI.create("http://ambari.test:8080/api/v1/"));
    return ui;
  }

  private static KeytabRequest keytabRequest() {
    KeytabRequest req = new KeytabRequest();
    req.setPrincipal("trino/trino-0.analytics@EXAMPLE.COM");
    req.setNamespace("analytics");
    return req;
  }

  @SuppressWarnings("unchecked")
  private static <T> ConcurrentMap<String, T> registry(Class<T> type) throws Exception {
    Field f = type.getDeclaredField("INSTANCES");
    f.setAccessible(true);
    return (ConcurrentMap<String, T>) f.get(null);
  }
}
