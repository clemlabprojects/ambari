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

package org.apache.ambari.view.k8s.security;

import org.apache.ambari.view.AmbariHttpException;
import org.apache.ambari.view.AmbariStreamProvider;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.junit.jupiter.api.Test;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The KDPS roles when the role lists are empty or partly filled, and how a refusal reaches the caller. With no
 * admin list, only Ambari administrators are view admins; anything that cannot be confirmed means read-only.
 */
class AuthHelperRolesTest {

  private static ViewContext ctx(String user, String admins, String operators) {
    ViewContext ctx = mock(ViewContext.class);
    Map<String, String> props = new HashMap<>();
    if (admins != null) props.put("view.admin.users", admins);
    if (operators != null) props.put("view.operator.users", operators);
    when(ctx.getProperties()).thenReturn(props);
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);
    return ctx;
  }

  private static void assertRole(String role, boolean configure, boolean write, AuthHelper auth) {
    UserPermissions p = auth.getPermissions();
    assertEquals(role, p.getRole());
    assertEquals(configure, p.isCanConfigure(), "canConfigure");
    assertEquals(write, p.isCanWrite(), "canWrite");
  }

  @Test
  void withNoRoleListsOnlyAmbariAdministratorsAreAdmins() {
    Predicate<String> ambariAdmins = "admin"::equals;

    assertRole("ADMIN", true, true, new AuthHelper(ctx("admin", null, null), ambariAdmins));
    assertRole("VIEWER", false, false, new AuthHelper(ctx("audit", "", " "), ambariAdmins));
  }

  @Test
  void withNoRoleListsAnOrdinaryUserCannotWriteOrConfigure() {
    AuthHelper auth = new AuthHelper(ctx("audit", null, null), user -> false);

    assertThrows(ForbiddenException.class, auth::checkWritePermission);
    assertThrows(ForbiddenException.class, auth::checkConfigurationPermission);
  }

  @Test
  void withOnlyOperatorsListedAmbariAdministratorsAreTheAdmins() {
    Predicate<String> ambariAdmins = "admin"::equals;

    assertRole("ADMIN", true, true, new AuthHelper(ctx("admin", null, "ops"), ambariAdmins));
    assertRole("OPERATOR", false, true, new AuthHelper(ctx("ops", null, "ops"), ambariAdmins));
    assertRole("VIEWER", false, false, new AuthHelper(ctx("audit", null, "ops"), ambariAdmins));
  }

  @Test
  void anAdminListReplacesTheAmbariAdministrators() {
    AtomicInteger lookups = new AtomicInteger();
    AuthHelper auth = new AuthHelper(ctx("admin", "lead", "ops"), user -> {
      lookups.incrementAndGet();
      return true;
    });

    assertRole("VIEWER", false, false, auth);
    assertEquals(0, lookups.get(), "Ambari is not asked when the admin list is set");
  }

  @Test
  void listedUsersKeepTheirRoleWhateverTheSpacing() {
    assertRole("ADMIN", true, true, new AuthHelper(ctx("lead", " lead , other", "ops"), user -> false));
    assertRole("OPERATOR", false, true, new AuthHelper(ctx("ops", "lead", "x,  ops ,"), user -> false));
  }

  @Test
  void aFailingLookupMeansReadOnly() {
    AuthHelper auth = new AuthHelper(ctx("admin", null, null), user -> {
      throw new IllegalStateException("Ambari down");
    });

    assertRole("VIEWER", false, false, auth);
  }

  @Test
  void ambariIsAskedAtMostOncePerHelper() {
    AtomicInteger lookups = new AtomicInteger();
    AuthHelper auth = new AuthHelper(ctx("admin", null, null), user -> {
      lookups.incrementAndGet();
      return true;
    });

    auth.checkWritePermission();
    auth.checkConfigurationPermission();
    auth.getPermissions();
    assertEquals(1, lookups.get());
  }

  @Test
  void refusalsAre403WithAnEnglishReasonInTheBody() {
    AuthHelper auth = new AuthHelper(ctx("audit", "lead", "ops"), user -> false);

    ForbiddenException write = assertThrows(ForbiddenException.class, auth::checkWritePermission);
    assertEquals(403, write.getResponse().getStatus());
    assertEquals(AuthHelper.WRITE_DENIED, ((Map<?, ?>) write.getResponse().getEntity()).get("error"));
    assertTrue(AuthHelper.WRITE_DENIED.contains("read-only access"));

    ForbiddenException configure = assertThrows(ForbiddenException.class, auth::checkConfigurationPermission);
    assertEquals(403, configure.getResponse().getStatus());
    assertEquals(AuthHelper.CONFIGURE_DENIED, ((Map<?, ?>) configure.getResponse().getEntity()).get("error"));
  }

  @Test
  void anOperatorMayWriteButNotConfigure() {
    AuthHelper auth = new AuthHelper(ctx("ops", "lead", "ops"), user -> false);

    assertDoesNotThrow(auth::checkWritePermission);
    assertThrows(ForbiddenException.class, auth::checkConfigurationPermission);
  }

  // ----- the keytab webhook's own account -----------------------------------------------------------------------

  private static final String WEBHOOK_USER_SETTING = "k8s.view.webhooks.keytab-webhook.backend.webhookUsername";

  @Test
  void theKeytabWebhookAccountMayStillAskForKeytabsOnceTheListsAreSet() {
    // In the WEBHOOK injection mode the webhook asks for each pod's keytab with the Ambari administrator account the
    // view created for it; that account is in neither list.
    Predicate<String> ambariAdmins = "webhook-user"::equals;
    AuthHelper webhook = new AuthHelper(ctx("webhook-user", "admin", "ops"), ambariAdmins);
    AuthHelper audit = new AuthHelper(ctx("audit", "admin", "ops"), ambariAdmins);
    AuthHelper ops = new AuthHelper(ctx("ops", "admin", "ops"), ambariAdmins);

    assertDoesNotThrow(webhook::checkKeytabRequestPermission);
    assertDoesNotThrow(ops::checkKeytabRequestPermission);
    ForbiddenException refused = assertThrows(ForbiddenException.class, audit::checkKeytabRequestPermission);
    assertEquals(403, refused.getResponse().getStatus());
    assertEquals(AuthHelper.WRITE_DENIED, ((Map<?, ?>) refused.getResponse().getEntity()).get("error"));
  }

  @Test
  void theKeytabWebhookAccountGetsNothingElse() {
    AuthHelper webhook = new AuthHelper(ctx("webhook-user", "admin", "ops"), "webhook-user"::equals);

    assertRole("VIEWER", false, false, webhook);
    assertThrows(ForbiddenException.class, webhook::checkWritePermission);
    assertThrows(ForbiddenException.class, webhook::checkConfigurationPermission);
  }

  @Test
  void theWebhookAccountNameAloneIsNotEnough() {
    // An ordinary account that happens to have the webhook's name, synced from a directory say, gains nothing.
    AuthHelper namesake = new AuthHelper(ctx("webhook-user", "admin", "ops"), user -> false);

    assertThrows(ForbiddenException.class, namesake::checkKeytabRequestPermission);
  }

  @Test
  void aRenamedWebhookAccountIsTheOneRecognised() {
    ViewContext renamed = ctx("kdps-webhook", "admin", "ops");
    when(renamed.getAmbariProperty(WEBHOOK_USER_SETTING)).thenReturn("kdps-webhook");
    ViewContext oldName = ctx("webhook-user", "admin", "ops");
    when(oldName.getAmbariProperty(WEBHOOK_USER_SETTING)).thenReturn("kdps-webhook");

    assertDoesNotThrow(new AuthHelper(renamed, user -> true)::checkKeytabRequestPermission);
    assertThrows(ForbiddenException.class, new AuthHelper(oldName, user -> true)::checkKeytabRequestPermission);
  }

  @Test
  void anOperatorAsksForKeytabsWithoutAnyAmbariLookup() {
    AtomicInteger lookups = new AtomicInteger();
    AuthHelper ops = new AuthHelper(ctx("ops", "admin", "ops"), user -> {
      lookups.incrementAndGet();
      return false;
    });

    assertDoesNotThrow(ops::checkKeytabRequestPermission);
    assertEquals(0, lookups.get());
  }

  @Test
  void theAmbariLookupReadsThePrivilegesWithTheCallerSession() throws Exception {
    ViewContext ctx = ctx("admin", null, null);
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(eq(SESSION_PATH), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json(privileges("USER", "admin", "AMBARI", "AMBARI.ADMINISTRATOR")));

    assertRole("ADMIN", true, true, new AuthHelper(ctx));
  }

  @Test
  void anAdministratorThroughAGroupIsAnAdmin() throws Exception {
    // The Users/admin flag only reflects privileges granted to the user by name; LDAP sites grant the role to a group.
    ViewContext ctx = ctx("alice", null, null);
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(eq("/api/v1/users/alice" + PRIVILEGES), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json("{\"items\":["
            + privilege("GROUP", "hadoop-admins", "AMBARI", "AMBARI.ADMINISTRATOR") + ","
            + privilege("USER", "alice", "VIEW", "VIEW.USER") + "]}"));

    assertRole("ADMIN", true, true, new AuthHelper(ctx));
  }

  @Test
  void aClusterAdministratorIsNotAnAmbariAdministrator() throws Exception {
    ViewContext ctx = ctx("ops", null, null);
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json(privileges("USER", "ops", "CLUSTER", "CLUSTER.ADMINISTRATOR")));

    assertRole("VIEWER", false, false, new AuthHelper(ctx));
  }

  @Test
  void aNonAdministratorAccountStaysReadOnly() throws Exception {
    ViewContext ctx = ctx("audit", null, null);
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json("{\"items\":[]}"));

    assertRole("VIEWER", false, false, new AuthHelper(ctx));
  }

  @Test
  void theCallerCredentialsAreForwardedWhenTheSessionCannotAnswer() throws Exception {
    // An API client authenticating with a user name and password has no Ambari session yet: asked through the
    // session, Ambari refuses, and an administrator would lose every right.
    ViewContext ctx = ctx("admin", null, null);
    AmbariStreamProvider session = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(session);
    when(session.readFrom(anyString(), anyString(), (String) isNull(), anyMap()))
        .thenThrow(new AmbariHttpException("Forbidden", 403));
    URLStreamProvider loopback = loopback(ctx);
    when(loopback.readFrom(eq(LOOPBACK_URL), eq("GET"), (String) isNull(),
        argThat(h -> BASIC.equals(h.get("Authorization")))))
        .thenReturn(json(privileges("USER", "admin", "AMBARI", "AMBARI.ADMINISTRATOR")));

    assertRole("ADMIN", true, true, new AuthHelper(ctx, basicAuth()));
    verifyNoInteractions(session);
  }

  @Test
  void aFailedForwardedLookupFallsBackToTheSession() throws Exception {
    ViewContext ctx = ctx("admin", null, null);
    URLStreamProvider loopback = loopback(ctx);
    when(loopback.readFrom(anyString(), anyString(), (String) isNull(), anyMap()))
        .thenThrow(new IOException("PKIX path building failed"));
    AmbariStreamProvider session = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(session);
    when(session.readFrom(eq(SESSION_PATH), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json(privileges("USER", "admin", "AMBARI", "AMBARI.ADMINISTRATOR")));

    assertRole("ADMIN", true, true, new AuthHelper(ctx, basicAuth()));
  }

  @Test
  void aRefusedForwardedLookupFallsBackToTheSession() throws Exception {
    // The view's stream provider does not throw on an HTTP error: it hands back Ambari's error body, e.g. when Ambari
    // refuses forwarded credentials. That is a failed lookup, not an answer of "no".
    ViewContext ctx = ctx("admin", null, null);
    URLStreamProvider loopback = loopback(ctx);
    when(loopback.readFrom(anyString(), anyString(), (String) isNull(), anyMap()))
        .thenReturn(json("{\"status\":403,\"message\":\"Authentication required\"}"));
    AmbariStreamProvider session = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(session);
    when(session.readFrom(eq(SESSION_PATH), eq("GET"), (String) isNull(), anyMap()))
        .thenReturn(json(privileges("USER", "admin", "AMBARI", "AMBARI.ADMINISTRATOR")));

    assertRole("ADMIN", true, true, new AuthHelper(ctx, basicAuth()));
  }

  @Test
  void aUserNameWithASpaceIsLookedUp() throws Exception {
    // Directory-synced names such as "John Smith" are valid Ambari user names.
    ViewContext ctx = ctx("John Smith", null, null);
    URLStreamProvider loopback = loopback(ctx);
    when(loopback.readFrom(eq("http://ambari.test:8080/api/v1/users/John%20Smith" + PRIVILEGES), eq("GET"),
        (String) isNull(), anyMap()))
        .thenReturn(json(privileges("GROUP", "hadoop-admins", "AMBARI", "AMBARI.ADMINISTRATOR")));

    assertRole("ADMIN", true, true, new AuthHelper(ctx, basicAuth()));
  }

  @Test
  void aForwardedAnswerOfNoIsFinal() throws Exception {
    ViewContext ctx = ctx("audit", null, null);
    URLStreamProvider loopback = loopback(ctx);
    when(loopback.readFrom(anyString(), anyString(), (String) isNull(), anyMap()))
        .thenReturn(json("{\"items\":[]}"));
    AmbariStreamProvider session = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(session);

    assertRole("VIEWER", false, false, new AuthHelper(ctx, basicAuth()));
    verifyNoInteractions(session);
  }

  @Test
  void anUnreachableAmbariMeansReadOnly() throws Exception {
    ViewContext ctx = ctx("admin", null, null);
    AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
    when(ctx.getAmbariStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(anyString(), anyString(), (String) isNull(), anyMap())).thenThrow(new IOException("refused"));

    assertRole("VIEWER", false, false, new AuthHelper(ctx));
  }

  @Test
  void aUserNameThatWouldChangeThePathIsNotLookedUp() throws Exception {
    for (String name : new String[] {"x/../clusters", "admin?fields=x", " admin", "admin  x", "a%2Fb"}) {
      ViewContext ctx = ctx(name, null, null);
      AmbariStreamProvider ambari = mock(AmbariStreamProvider.class);
      when(ctx.getAmbariStreamProvider()).thenReturn(ambari);

      assertRole("VIEWER", false, false, new AuthHelper(ctx));
      verifyNoInteractions(ambari);
    }
  }

  private static final String PRIVILEGES = "/privileges?fields=PrivilegeInfo/permission_name,PrivilegeInfo/type";
  private static final String SESSION_PATH = "/api/v1/users/admin" + PRIVILEGES;
  private static final String LOOPBACK_URL = "http://ambari.test:8080/api/v1/users/admin" + PRIVILEGES;
  private static final String BASIC = "Basic YWRtaW46c2VjcmV0";

  /** The view's own stream provider, used for calls to the local Ambari at its loopback address. */
  private static URLStreamProvider loopback(ViewContext ctx) {
    when(ctx.getAmbariProperty(AmbariLoopbackUrlResolver.LOOPBACK_HOST_PROPERTY)).thenReturn("ambari.test");
    URLStreamProvider stream = mock(URLStreamProvider.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);
    return stream;
  }

  /** Headers of a request authenticated with a user name and password, without any Ambari session cookie. */
  private static HttpHeaders basicAuth() {
    MultivaluedHashMap<String, String> map = new MultivaluedHashMap<>();
    map.putSingle("Authorization", BASIC);
    HttpHeaders headers = mock(HttpHeaders.class);
    when(headers.getRequestHeaders()).thenReturn(map);
    return headers;
  }

  private static String privileges(String principalType, String principalName, String type, String permission) {
    return "{\"items\":[" + privilege(principalType, principalName, type, permission) + "]}";
  }

  private static String privilege(String principalType, String principalName, String type, String permission) {
    return "{\"PrivilegeInfo\":{\"permission_name\":\"" + permission + "\",\"principal_name\":\"" + principalName
        + "\",\"principal_type\":\"" + principalType + "\",\"type\":\"" + type + "\"}}";
  }

  private static ByteArrayInputStream json(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
}
