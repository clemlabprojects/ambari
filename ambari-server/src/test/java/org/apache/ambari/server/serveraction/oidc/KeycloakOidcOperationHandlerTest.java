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
package org.apache.ambari.server.serveraction.oidc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.Before;
import org.junit.Test;

import junit.framework.Assert;

public class KeycloakOidcOperationHandlerTest {

  private static final String BASE_URL = "https://keycloak.example.com";
  private static final String REALM = "myrealm";
  private static final String CLIENT_ID = "internal-uuid";
  private static final String TOKEN = "test-access-token";

  private KeycloakOidcOperationHandler handler;
  private Client mockClient;
  private WebTarget mockTarget;
  private Invocation.Builder mockBuilder;

  @Before
  public void setUp() throws Exception {
    handler = new KeycloakOidcOperationHandler();

    mockClient = mock(Client.class);
    mockTarget = mock(WebTarget.class);
    mockBuilder = mock(Invocation.Builder.class);

    when(mockClient.target(anyString())).thenReturn(mockTarget);
    when(mockTarget.request(any(MediaType.class))).thenReturn(mockBuilder);
    when(mockBuilder.header(anyString(), any())).thenReturn(mockBuilder);

    OidcProviderConfiguration config = new OidcProviderConfiguration(
      "keycloak", BASE_URL, REALM, "master", "admin-cli", null, false);

    setField(handler, "client", mockClient);
    setField(handler, "configuration", config);
  }

  @Test
  public void testEnsureProtocolMappersCreatesAllWhenNoneExist() throws Exception {
    Response listResp = buildResponse(200, "[]");
    when(mockBuilder.get()).thenReturn(listResp);

    Response createResp = buildResponse(201, "");
    when(mockBuilder.post(any(Entity.class))).thenReturn(createResp);

    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());

    // One GET to list, then one POST per mapper
    verify(mockBuilder, times(1)).get();
    verify(mockBuilder, times(3)).post(any(Entity.class));
  }

  @Test
  public void testEnsureProtocolMappersSkipsExistingMapper() throws Exception {
    String existingJson = "[{\"name\":\"polaris-principal-name\"}]";
    Response listResp = buildResponse(200, existingJson);
    when(mockBuilder.get()).thenReturn(listResp);

    Response createResp = buildResponse(201, "");
    when(mockBuilder.post(any(Entity.class))).thenReturn(createResp);

    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());

    // One GET to list, then POST only for the two missing mappers
    verify(mockBuilder, times(1)).get();
    verify(mockBuilder, times(2)).post(any(Entity.class));
  }

  @Test
  public void testEnsureProtocolMappersIsNoOpWhenAllExist() throws Exception {
    String existingJson =
      "[{\"name\":\"polaris-principal-name\"}," +
      "{\"name\":\"polaris-principal-id\"}," +
      "{\"name\":\"polaris-roles\"}]";
    Response listResp = buildResponse(200, existingJson);
    when(mockBuilder.get()).thenReturn(listResp);

    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());

    verify(mockBuilder, times(1)).get();
    verify(mockBuilder, never()).post(any(Entity.class));
  }

  @Test
  public void testEnsureProtocolMappersIsNoOpForNullList() throws Exception {
    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, null);

    verify(mockClient, never()).target(anyString());
  }

  @Test
  public void testEnsureProtocolMappersIsNoOpForEmptyList() throws Exception {
    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, new ArrayList<>());

    verify(mockClient, never()).target(anyString());
  }

  @Test
  public void testEnsureProtocolMappersThrowsOnListFailure() throws Exception {
    Response listResp = buildResponse(403, "Forbidden");
    when(mockBuilder.get()).thenReturn(listResp);

    try {
      handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());
      Assert.fail("Expected OidcOperationException");
    } catch (OidcOperationException e) {
      Assert.assertTrue(e.getMessage().contains("Failed to list protocol mappers"));
      Assert.assertTrue(e.getMessage().contains("403"));
    }
  }

  @Test
  public void testEnsureProtocolMappersThrowsOnCreateFailure() throws Exception {
    Response listResp = buildResponse(200, "[]");
    when(mockBuilder.get()).thenReturn(listResp);

    Response createResp = buildResponse(500, "Internal Server Error");
    when(mockBuilder.post(any(Entity.class))).thenReturn(createResp);

    try {
      handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());
      Assert.fail("Expected OidcOperationException");
    } catch (OidcOperationException e) {
      Assert.assertTrue(e.getMessage().contains("Failed to create protocol mapper"));
      Assert.assertTrue(e.getMessage().contains("500"));
    }
  }

  @Test
  public void testEnsureProtocolMappersAccepts409OnCreate() throws Exception {
    Response listResp = buildResponse(200, "[]");
    when(mockBuilder.get()).thenReturn(listResp);

    // 409 Conflict means the mapper already exists — must not throw
    Response conflictResp = buildResponse(409, "Conflict");
    when(mockBuilder.post(any(Entity.class))).thenReturn(conflictResp);

    handler.ensureProtocolMappers(TOKEN, REALM, CLIENT_ID, buildThreeMappers());
    // No exception expected; all three POSTs are attempted
    verify(mockBuilder, times(3)).post(any(Entity.class));
  }

  // --- helpers ---

  private static Response buildResponse(int status, String body) {
    Response r = mock(Response.class);
    when(r.getStatus()).thenReturn(status);
    when(r.readEntity(String.class)).thenReturn(body);
    return r;
  }

  private static List<Map<String, Object>> buildThreeMappers() {
    List<Map<String, Object>> mappers = new ArrayList<>();

    mappers.add(buildMapper("polaris-principal-name", "oidc-usermodel-property-mapper",
      "username", "polaris/principal_name", "String"));

    mappers.add(buildMapper("polaris-principal-id", "oidc-usermodel-attribute-mapper",
      "polaris_principal_id", "polaris/principal_id", "long"));

    Map<String, Object> roles = new LinkedHashMap<>();
    roles.put("name", "polaris-roles");
    roles.put("protocol", "openid-connect");
    roles.put("protocolMapper", "oidc-usermodel-realm-role-mapper");
    roles.put("consentRequired", "false");
    Map<String, String> rolesConfig = new LinkedHashMap<>();
    rolesConfig.put("claim.name", "polaris/roles");
    rolesConfig.put("multivalued", "true");
    roles.put("config", rolesConfig);
    mappers.add(roles);

    return mappers;
  }

  private static Map<String, Object> buildMapper(String name, String protocolMapper,
                                                  String userAttr, String claimName,
                                                  String jsonType) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("protocol", "openid-connect");
    m.put("protocolMapper", protocolMapper);
    m.put("consentRequired", "false");
    Map<String, String> cfg = new LinkedHashMap<>();
    cfg.put("user.attribute", userAttr);
    cfg.put("claim.name", claimName);
    cfg.put("jsonType.label", jsonType);
    cfg.put("id.token.claim", "true");
    cfg.put("access.token.claim", "true");
    m.put("config", cfg);
    return m;
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = findField(target.getClass(), fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot set field " + fieldName, e);
    }
  }

  private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
    while (type != null) {
      try {
        return type.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }
}
