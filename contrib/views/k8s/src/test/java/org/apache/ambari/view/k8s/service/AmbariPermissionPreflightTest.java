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

import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
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
 * A deploy whose steps replay the operator's Ambari session is refused up front, with the missing Ambari permission
 * named, instead of failing at the step Ambari rejects.
 */
class AmbariPermissionPreflightTest {

  private static final List<String> KERBERIZED_TRINO_PLAN = List.of(
      "HELM_REPO_LOGIN", "KEYTAB_ISSUE_PRINCIPAL", "KEYTAB_CREATE_SECRET", "HELM_DEPLOY", "RANGER_REPOSITORY_CREATION");

  @Test
  void keytabStepsNeedTheKerberosPermission() {
    assertEquals(List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS),
        AmbariPermissionPreflight.requiredFor(KERBERIZED_TRINO_PLAN, true));
  }

  @Test
  void eachPermissionIsListedOnceAndUnknownStepsAreIgnored() {
    List<AmbariPermissionPreflight.Requirement> required = AmbariPermissionPreflight.requiredFor(List.of(
        "KEYTAB_ISSUE_PRINCIPAL", "KEYTAB_ISSUE_PRINCIPAL", "NOT_A_STEP", "OM_RANGER_TAGSYNC_REGISTER",
        "ATLAS_USER_PROVISION_OM", "AMBARI_VIEW_PROVISION"), true);

    assertEquals(List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS, AmbariPermissionPreflight.MODIFY_CONFIGS,
        AmbariPermissionPreflight.RUN_CUSTOM_COMMAND, AmbariPermissionPreflight.MANAGE_VIEWS), required);
  }

  @Test
  void configurationStepsOfAnotherContextDoNotUseTheOperatorSession() {
    assertEquals(List.of(), AmbariPermissionPreflight.requiredFor(
        List.of("OM_RANGER_TAGSYNC_REGISTER", "ATLAS_SIMPLE_AUTHZ_GRANT_OM", "HELM_DEPLOY"), false));
  }

  @Test
  void onlyTheDefaultContextCountsAsTheManagedOne() {
    assertTrue(CommandService.usesManagedContext(Map.of()));
    assertTrue(CommandService.usesManagedContext(Map.of("_platformContextId", ContextService.DEFAULT_CONTEXT_ID)));
    assertFalse(CommandService.usesManagedContext(Map.of("_platformContextId", "cdp-prod")));
  }

  @Test
  void theRefusalNamesTheMissingPermissionAndWhatItIsFor() {
    String message = AmbariPermissionPreflight.missingMessage("ops", "dev21",
        List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS), Set.of("CLUSTER.VIEW_STATUS_INFO"));

    assertNotNull(message);
    assertTrue(message.contains("'ops'"), message);
    assertTrue(message.contains("'dev21'"), message);
    assertTrue(message.contains("CLUSTER.TOGGLE_KERBEROS"), message);
    assertTrue(message.contains("Kerberos keytabs"), message);
    assertTrue(message.contains("did not start"), message);
  }

  @Test
  void nothingIsMissingWhenEveryPermissionIsGranted() {
    assertNull(AmbariPermissionPreflight.missingMessage("ops", "dev21",
        List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS), Set.of("CLUSTER.TOGGLE_KERBEROS")));
  }

  @Test
  void checkReadsTheOperatorsAuthorizationsOnTheCluster() throws Exception {
    AmbariActionClient ambari = mock(AmbariActionClient.class);
    when(ambari.listUserAuthorizations("ops", "dev21")).thenReturn(Set.of("CLUSTER.VIEW_CONFIGS"));

    String message = new AmbariPermissionPreflight(ambari, "ops", "dev21")
        .check(AmbariPermissionPreflight.requiredFor(KERBERIZED_TRINO_PLAN, true));

    assertNotNull(message);
    assertTrue(message.contains("CLUSTER.TOGGLE_KERBEROS"));
  }

  @Test
  void aPlanWithoutAmbariStepsDoesNotAskAmbari() throws Exception {
    AmbariActionClient ambari = mock(AmbariActionClient.class);

    assertNull(new AmbariPermissionPreflight(ambari, "ops", "dev21")
        .check(AmbariPermissionPreflight.requiredFor(List.of("HELM_DEPLOY"), true)));
    verifyNoInteractions(ambari);
  }

  @Test
  void unreadablePermissionsDoNotBlockTheDeploy() throws Exception {
    // Ambari still enforces the permissions when the steps run; the preflight only reports early. The view's stream
    // provider does not throw on an HTTP error: it hands back Ambari's error body, which must not read as "no
    // permission".
    List<AmbariPermissionPreflight.Requirement> required = AmbariPermissionPreflight.requiredFor(KERBERIZED_TRINO_PLAN, true);
    for (String error : List.of(
        "{\"status\":403,\"message\":\"The authenticated user is not authorized to perform the requested operation\"}",
        "{\"status\":404,\"message\":\"The requested resource doesn't exist: User not found\"}",
        "{\"status\":500,\"message\":\"Server Error\"}")) {
      AmbariActionClient ambari = clientAnswering(error);

      assertNull(new AmbariPermissionPreflight(ambari, "ops", "dev21").check(required), error);
    }
    assertNull(new AmbariPermissionPreflight(null, "ops", "dev21").check(required));
  }

  @Test
  void aUserNameWithASpaceIsReadAsOnePathSegment() throws Exception {
    // A directory-synced "John Smith": written "John+Smith" in a path, Ambari would look up another user.
    URLStreamProvider stream = mock(URLStreamProvider.class);
    when(stream.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap()))
        .thenAnswer(call -> json("{\"items\":[]}"));
    AmbariActionClient client = client(stream);

    client.listUserAuthorizations("John Smith", "dev21");

    verify(stream).readFrom(startsWith("https://ambari:8443/api/v1/users/John%20Smith/authorizations?"), eq("GET"),
        (String) isNull(), anyMap());
  }

  // ----- reading the cluster ----------------------------------------------------------------------------------------

  @Test
  void aDeployOnTheManagedClusterNeedsToReadIt() {
    Map<String, Object> managed = Map.of();

    assertTrue(CommandService.readsManagedCluster(managed, "dev21", false, false), "a bound or discovered cluster");
    assertTrue(CommandService.readsManagedCluster(managed, null, false, true),
        "a cluster Ambari would not list for the operator");
    assertFalse(CommandService.readsManagedCluster(managed, null, true, true), "Ambari manages no cluster");
    assertFalse(CommandService.readsManagedCluster(managed, null, false, false),
        "a view bound to no cluster, with discovery off, reads none");
    assertFalse(CommandService.readsManagedCluster(Map.of("_platformContextId", "cdp-prod"), "dev21", false, true),
        "another context reads its own endpoints with its stored credentials");
  }

  @Test
  void anAccountThatCannotReadTheClusterIsToldWhichPermissionItNeeds() throws Exception {
    // A user granted the view and listed as an operator, with no role on the cluster: Ambari lists only the view.
    AmbariActionClient ambari = clientAnswering("{\"items\":[{\"AuthorizationInfo\":{\"authorization_id\":\"VIEW.USE\","
        + "\"resource_type\":\"VIEW\",\"cluster_name\":null}}]}");

    String message = new AmbariPermissionPreflight(ambari, "ops", "dev21")
        .check(List.of(AmbariPermissionPreflight.READ_CLUSTER));

    assertNotNull(message);
    assertTrue(message.contains("'View configuration' (CLUSTER.VIEW_CONFIGS)"), message);
    assertTrue(message.contains("Kerberos state"), message);
  }

  /** A client on the local Ambari whose every GET gets {@code body}, as the view's stream provider returns it. */
  private static AmbariActionClient clientAnswering(String body) throws IOException {
    URLStreamProvider stream = mock(URLStreamProvider.class);
    when(stream.readFrom(anyString(), eq("GET"), (String) isNull(), anyMap())).thenAnswer(call -> json(body));
    return client(stream);
  }

  private static AmbariActionClient client(URLStreamProvider stream) {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getURLStreamProvider()).thenReturn(stream);
    return new AmbariActionClient(ctx, "https://ambari:8443/api/v1", "dev21", Map.of("Cookie", "AMBARISESSIONID=abc"));
  }

  private static ByteArrayInputStream json(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aDeployReadsTheOperatorsPermissionsOnce() throws Exception {
    // The deploy checks what it knows before writing anything, then the steps it plans later: one Ambari read.
    AmbariActionClient ambari = mock(AmbariActionClient.class);
    when(ambari.listUserAuthorizations("ops", "dev21")).thenReturn(Set.of("CLUSTER.TOGGLE_KERBEROS"));
    AmbariPermissionPreflight preflight = new AmbariPermissionPreflight(ambari, "ops", "dev21");

    assertNull(preflight.check(List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS)));
    String later = preflight.check(AmbariPermissionPreflight.requiredFor(List.of("OM_RANGER_TAGSYNC_REGISTER"), true));

    assertNotNull(later, "the configuration steps planned later need more than the Kerberos permission");
    assertTrue(later.contains("SERVICE.MODIFY_CONFIGS"), later);
    verify(ambari, times(1)).listUserAuthorizations("ops", "dev21");
  }

  // ----- what a deploy knows it needs before it writes anything ------------------------------------------------

  private static CommandService.PlannedKeytab keytab(String principal, String secret) {
    return new CommandService.PlannedKeytab("trino", principal, secret, "service.keytab", "/etc/security/keytabs", "");
  }

  @Test
  void aKeytabToIssueNeedsTheKerberosPermission() {
    List<String> steps = CommandService.ambariStepsKnownUpFront(
        List.of(keytab("trino-ns@REALM", "trino-keytab")), k -> false, false, false);

    assertEquals(List.of(AmbariPermissionPreflight.TOGGLE_KERBEROS), AmbariPermissionPreflight.requiredFor(steps, true));
  }

  @Test
  void aKeytabAlreadyStoredNeedsNoAmbariPermission() {
    // Redeploying, upgrading or reapplying the configuration of a Kerberized release reuses its keytab without asking
    // Ambari, so an operator who is not a cluster administrator keeps doing it.
    List<String> steps = CommandService.ambariStepsKnownUpFront(
        List.of(keytab("trino-ns@REALM", "trino-keytab")), k -> true, false, false);

    assertEquals(List.of(), AmbariPermissionPreflight.requiredFor(steps, true));
  }

  @Test
  void oneKeytabStillToIssueIsEnough() {
    CommandService.PlannedKeytab stored = keytab("trino-ns@REALM", "trino-keytab");
    CommandService.PlannedKeytab missing = keytab("hive-ns@REALM", "hive-keytab");

    List<String> steps = CommandService.ambariStepsKnownUpFront(List.of(stored, missing), k -> k == stored, false, false);

    assertEquals(List.of("KEYTAB_ISSUE_PRINCIPAL"), steps);
  }

  @Test
  void aViewInstanceNeedsTheManageViewsPermission() {
    List<String> steps = CommandService.ambariStepsKnownUpFront(List.of(), k -> false, true, false);

    assertEquals(List.of(AmbariPermissionPreflight.MANAGE_VIEWS), AmbariPermissionPreflight.requiredFor(steps, false));
  }

  // ----- the Iceberg catalog's object store ----------------------------------------------------------------------

  private static Map<String, Object> icebergForm(Map<String, Object> icebergCatalog) {
    return Map.of("icebergCatalog", icebergCatalog);
  }

  @Test
  void anIcebergCatalogOnTheManagedClusterNeedsTheServiceCommandPermission() {
    // The Polaris step runs PROVISION_OZONE_BUCKET on the cluster with the operator's session, which a Cluster User
    // may not do.
    List<String> steps = CommandService.ambariStepsKnownUpFront(List.of(), k -> false, false,
        CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of("enabled", true))));

    assertEquals(List.of("POLARIS_PROVISION_CATALOG"), steps);
    assertEquals(List.of(AmbariPermissionPreflight.PROVISION_OBJECT_STORE),
        AmbariPermissionPreflight.requiredFor(steps, true));
    assertEquals("SERVICE.RUN_CUSTOM_COMMAND", AmbariPermissionPreflight.PROVISION_OBJECT_STORE.authorizationId());
  }

  @Test
  void anIcebergCatalogOnAnotherContextNeedsNoAmbariPermission() {
    assertEquals(List.of(), AmbariPermissionPreflight.requiredFor(List.of("POLARIS_PROVISION_CATALOG"), false));
  }

  @Test
  void theCatalogBucketIsOnlyAskedOfAmbariWhenTheStepIsPlannedWithoutAnS3Key() {
    assertTrue(CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of("enabled", "true", "provision", true))));
    // An S3 key in the form is used as it is: the step does not ask the cluster for a bucket.
    assertFalse(CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of("enabled", true, "s3AccessKey", "AKIA1"))));
    assertFalse(CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of("enabled", true, "provision", false))));
    assertFalse(CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of("enabled", false))));
    // An untouched form provisions nothing.
    assertFalse(CommandService.polarisProvisionUsesAmbari(icebergForm(Map.of())));
    assertFalse(CommandService.polarisProvisionUsesAmbari(Map.of()));
    assertFalse(CommandService.polarisProvisionUsesAmbari(null));
  }

  @Test
  void aPermissionTwoStepsNeedIsNamedOnceWithBothPurposes() {
    String message = AmbariPermissionPreflight.missingMessage("ops", "dev21", AmbariPermissionPreflight.requiredFor(
        List.of("OM_RANGER_TAGSYNC_REGISTER", "POLARIS_PROVISION_CATALOG"), true), Set.of("SERVICE.MODIFY_CONFIGS"));

    assertNotNull(message);
    assertEquals(message.indexOf("SERVICE.RUN_CUSTOM_COMMAND"), message.lastIndexOf("SERVICE.RUN_CUSTOM_COMMAND"),
        message);
    assertTrue(message.contains("restart those Ambari services"), message);
    assertTrue(message.contains("Ozone bucket"), message);
    assertFalse(message.contains("SERVICE.MODIFY_CONFIGS"), message);
  }

  @Test
  void theAuthorizationsOfOtherClustersDoNotCount() throws Exception {
    URLStreamProvider stream = mock(URLStreamProvider.class);
    String body = "{\"items\":["
        + "{\"AuthorizationInfo\":{\"authorization_id\":\"CLUSTER.TOGGLE_KERBEROS\",\"resource_type\":\"CLUSTER\",\"cluster_name\":\"other\"}},"
        + "{\"AuthorizationInfo\":{\"authorization_id\":\"CLUSTER.VIEW_CONFIGS\",\"resource_type\":\"CLUSTER\",\"cluster_name\":\"dev21\"}},"
        + "{\"AuthorizationInfo\":{\"authorization_id\":\"AMBARI.MANAGE_VIEWS\",\"resource_type\":\"AMBARI\",\"cluster_name\":null}}"
        + "]}";
    when(stream.readFrom(startsWith("https://ambari:8443/api/v1/users/ops/authorizations?fields="), eq("GET"),
        (String) isNull(), anyMap()))
        .thenReturn(json(body));

    AmbariActionClient client = client(stream);

    assertEquals(Set.of("CLUSTER.VIEW_CONFIGS", "AMBARI.MANAGE_VIEWS"), client.listUserAuthorizations("ops", "dev21"));
    verify(stream).readFrom(anyString(), eq("GET"), (String) isNull(),
        argThat(h -> "AMBARISESSIONID=abc".equals(h.get("Cookie"))));
  }
}
