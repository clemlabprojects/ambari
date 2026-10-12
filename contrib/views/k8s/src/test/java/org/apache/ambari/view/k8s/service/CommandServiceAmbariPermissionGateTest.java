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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.URLStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.cluster.Cluster;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;

import javax.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A deploy refused for a missing Ambari permission is refused before it writes anything: no Secret, ConfigMap or
 * release record, no command. That includes an operator who may not even read the cluster, whose deploy would
 * otherwise go on as if the cluster were not Kerberized. An operator who is not a cluster administrator still
 * redeploys a Kerberized release whose keytab is already stored, since its keytab step does not call Ambari. The same
 * goes for an Iceberg catalog whose bucket the cluster would create, unless the operator gave an S3 key. A deploy
 * refused only once its plan is stored, for a step such as OpenMetadata's Ranger TagSync registration, cannot be
 * resumed afterwards.
 */
class CommandServiceAmbariPermissionGateTest {

  private static final String INSTANCE = "ambari-permission-gate-test";
  private static final String PRINCIPAL = "trino-analytics@EXAMPLE.COM";

  /** The authorizations Ambari lists for ops on dev21: a Cluster User by default, who may read but not change it. */
  private static final List<String> CLUSTER_USER = List.of("CLUSTER.VIEW_STATUS_INFO", "CLUSTER.VIEW_CONFIGS");
  /** Granted the view and listed as a KDPS operator, with no role on the cluster. */
  private static final List<String> NO_CLUSTER_ROLE = List.of("VIEW.USE");

  private final KubernetesService k8s = mock(KubernetesService.class);
  private final DataStore store = mock(DataStore.class);
  private final List<String> ambariCalls = new ArrayList<>();
  private List<String> opsAuthorizations = CLUSTER_USER;
  private ViewContext ctx;
  private Path workDir;

  @BeforeEach
  void setUp(@TempDir Path workDir) throws Exception {
    this.workDir = workDir;
    instances().put(INSTANCE, k8s);
    ctx = mock(ViewContext.class);
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    when(ctx.getDataStore()).thenReturn(store);
    when(ctx.getUsername()).thenReturn("ops");
    when(ctx.getLoggedinUser()).thenReturn("ops");
    Cluster cluster = mock(Cluster.class);
    when(cluster.getName()).thenReturn("dev21");
    when(ctx.getCluster()).thenReturn(cluster);
    URLStreamProvider ambari = mock(URLStreamProvider.class);
    when(ctx.getURLStreamProvider()).thenReturn(ambari);
    when(ambari.readFrom(anyString(), anyString(), (String) isNull(), anyMap()))
        .thenAnswer(call -> kerberizedClusterWhereOpsIsNotClusterAdministrator(call.getArgument(0)));
  }

  @AfterEach
  void tearDown() throws Exception {
    instances().remove(INSTANCE);
  }

  @Test
  void aRefusedDeployWritesNothing() throws Exception {
    when(k8s.readOpaqueSecretKeyAsBytes(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> deploy(kerberizedTrino()));

    assertTrue(refused.getMessage().contains("CLUSTER.TOGGLE_KERBEROS"), refused.getMessage());
    verify(store, never()).store(any());
    assertEquals(List.of(), kubernetesWrites(), "nothing may be written to Kubernetes before the refusal");
    assertTrue(ambariCalls.stream().anyMatch(u -> u.contains("/users/ops/authorizations")), ambariCalls::toString);
  }

  @Test
  void aStoredKeytabNeedsNoAmbariPermission() {
    when(k8s.readOpaqueSecretKeyAsBytes(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(keytab("trino-analytics", "EXAMPLE.COM")));
    HelmDeployRequest request = kerberizedTrino();
    // An incomplete cert-manager request stops the deploy right after the permission check, before any write.
    request.setIngressTlsCertManager(Map.of("issuerName", ""));

    IllegalArgumentException stopped = assertThrows(IllegalArgumentException.class, () -> deploy(request));

    // Ops, a Cluster User, may read the cluster, which is all this deploy needs from Ambari.
    assertTrue(stopped.getMessage().startsWith("INGRESS_TLS_CERTMANAGER"), stopped.getMessage());
  }

  @Test
  void anOperatorWhoCannotReadTheClusterIsRefusedBeforeAnythingIsWritten() throws Exception {
    // Ambari refuses the cluster's Kerberos state to ops: without the check, no keytab would be planned and the
    // release would come up without its Kerberos identity.
    opsAuthorizations = NO_CLUSTER_ROLE;

    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> deploy(kerberizedTrino()));

    assertTrue(refused.getMessage().contains("CLUSTER.VIEW_CONFIGS"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Kerberos state"), refused.getMessage());
    assertTrue(refused.getMessage().contains("'ops'"), refused.getMessage());
    verify(store, never()).store(any());
    assertEquals(List.of(), kubernetesWrites(), "nothing may be written to Kubernetes before the refusal");
  }

  @Test
  void anOperatorWhomAmbariWillNotListTheClustersIsRefusedToo() throws Exception {
    // A view bound to no cluster discovers it. Ambari refusing the list is not "Ambari manages no cluster", which
    // would turn Kerberos off.
    opsAuthorizations = NO_CLUSTER_ROLE;
    when(ctx.getCluster()).thenReturn(null);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString(),
        "ambari.cluster.discovery.enabled", "true"));

    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> deploy(kerberizedTrino()));

    assertTrue(refused.getMessage().contains("CLUSTER.VIEW_CONFIGS"), refused.getMessage());
    assertTrue(ambariCalls.stream().anyMatch(u -> u.contains("/clusters?fields=Clusters/cluster_name")),
        ambariCalls::toString);
    verify(store, never()).store(any());
    assertEquals(List.of(), kubernetesWrites(), "nothing may be written to Kubernetes before the refusal");
  }

  @Test
  void aDeployOnAnotherContextDoesNotNeedToReadTheCluster() {
    // The local cluster's Kerberos state is ignored for an external context, so being unable to read it is no reason
    // to refuse.
    opsAuthorizations = NO_CLUSTER_ROLE;
    HelmDeployRequest request = new HelmDeployRequest();
    request.setReleaseName("trino");
    request.setNamespace("analytics");
    request.setChart("trino");
    request.setRepoId("repo-1");
    request.setFormValues(Map.of("platformContextId", "cdp-prod"));
    request.setIngressTlsCertManager(Map.of("issuerName", ""));

    IllegalArgumentException stopped = assertThrows(IllegalArgumentException.class, () -> deploy(request));

    assertTrue(stopped.getMessage().startsWith("INGRESS_TLS_CERTMANAGER"), stopped.getMessage());
    assertTrue(ambariCalls.stream().noneMatch(u -> u.contains("/authorizations")),
        "nothing needs an Ambari permission, so Ambari is not asked: " + ambariCalls);
  }

  @Test
  void aKeytabStoredForAnotherPrincipalIsIssuedAgain() {
    when(k8s.readOpaqueSecretKeyAsBytes(anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(keytab("trino-coordinator-analytics", "EXAMPLE.COM")));

    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> deploy(kerberizedTrino()));

    assertTrue(refused.getMessage().contains("CLUSTER.TOGGLE_KERBEROS"), refused.getMessage());
  }

  @Test
  void anIcebergCatalogDeployIsRefusedBeforeItWritesAnything() throws Exception {
    // No keytab to issue: the Polaris step alone would run a Polaris service command with ops's session.
    HelmDeployRequest request = trinoWithIcebergCatalog(Map.of("enabled", true));

    IllegalStateException refused = assertThrows(IllegalStateException.class, () -> deploy(request));

    assertTrue(refused.getMessage().contains("SERVICE.RUN_CUSTOM_COMMAND"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Ozone bucket"), refused.getMessage());
    verify(store, never()).store(any());
    assertEquals(List.of(), kubernetesWrites(), "nothing may be written to Kubernetes before the refusal");
  }

  @Test
  void anIcebergCatalogWithAnS3KeyNeedsNoAmbariPermission() {
    HelmDeployRequest request = trinoWithIcebergCatalog(Map.of("enabled", true, "s3AccessKey", "AKIA1",
        "s3SecretKey", "secret"));
    request.setIngressTlsCertManager(Map.of("issuerName", ""));

    IllegalArgumentException stopped = assertThrows(IllegalArgumentException.class, () -> deploy(request));

    // Reading the cluster is all this deploy needs from Ambari, which a Cluster User may do.
    assertTrue(stopped.getMessage().startsWith("INGRESS_TLS_CERTMANAGER"), stopped.getMessage());
  }

  @Test
  void aDeployRefusedOnceItsPlanIsStoredCannotBeResumed() throws Exception {
    Map<String, Object> saved = keepWhatIsStored();
    HelmRepoEntity repo = new HelmRepoEntity();
    repo.setId("repo-1");
    repo.setName("kdps");
    saved.put("repo-1", repo);
    MultivaluedHashMap<String, String> adminHeaders = new MultivaluedHashMap<>();
    adminHeaders.putSingle("Cookie", "AMBARISESSIONID=admin-session");
    CommandService service = withChartValues(new CommandService(ctx), Map.of("openmetadata", Map.of()));

    // The Ranger TagSync registration is only planned after the plan is stored; ops may not modify configurations.
    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> deploy(service, openMetadataWithRangerTagSync()));

    assertTrue(refused.getMessage().contains("SERVICE.MODIFY_CONFIGS"), refused.getMessage());
    CommandEntity root = saved.values().stream()
        .filter(CommandEntity.class::isInstance).map(CommandEntity.class::cast)
        .filter(c -> CommandType.K8S_MANAGER_RELEASE_DEPLOY.name().equals(c.getType()))
        .findFirst().orElseThrow(() -> new AssertionError("the refused deploy keeps its operation: " + saved.keySet()));
    CommandStatusEntity rootStatus = (CommandStatusEntity) saved.get(root.getCommandStatusId());
    assertEquals(CommandState.FAILED.name(), rootStatus.getState());
    assertEquals(refused.getMessage(), rootStatus.getMessage(), "the operation says why nothing ran");
    assertTrue(stepTypes(saved).contains(CommandType.OM_RANGER_TAGSYNC_REGISTER.name()), stepTypes(saved)::toString);
    assertTrue(org.apache.ambari.view.k8s.utils.CommandUtils.resolveParamsPayload(root.getParamsJson())
            .contains(CommandService.RELEASE_METADATA_PARAM),
        "the stored plan carries the release record its Helm step writes once Helm has applied the deploy");
    assertEquals(List.of(), stepsLeftToRun(saved), "no step of a refused deploy may be left to run");

    // Resuming, even by someone holding every Ambari permission, must not run the refused steps.
    IllegalStateException notResumed = assertThrows(IllegalStateException.class,
        () -> service.resume(root.getId(), adminHeaders));

    assertTrue(notResumed.getMessage().contains("refused before it started"), notResumed.getMessage());
    assertTrue(notResumed.getMessage().contains("SERVICE.MODIFY_CONFIGS"), notResumed.getMessage());
    assertEquals(CommandState.FAILED.name(), rootStatus.getState());
    assertEquals(List.of(), stepsLeftToRun(saved), "a refused resume leaves every step as it was");
    assertTrue(saved.values().stream().noneMatch(K8sReleaseEntity.class::isInstance),
        "a refused deploy leaves no release record");
  }

  // ------------------------------------------------------------------------------------------------------------

  /** An OpenMetadata-like release whose definition registers a Ranger TagSync source after the install. */
  private static HelmDeployRequest openMetadataWithRangerTagSync() {
    HelmDeployRequest r = new HelmDeployRequest();
    r.setReleaseName("openmetadata");
    r.setNamespace("analytics");
    r.setChart("openmetadata");
    r.setRepoId("repo-1");
    r.setServiceKey("ZZTESTTAGSYNC");
    Map<String, Map<String, Object>> ranger = new LinkedHashMap<>();
    ranger.put("ranger-tagsync-settings", Map.of("endpointHelmPath", "openmetadata.config.server.url"));
    ranger.put("openmetadatarest", Map.of("type", "ranger-tagsync-source",
        "source_class", "org.apache.ranger.tagsync.source.openmetadatarest.OpenmetadataRESTTagSource"));
    r.setRanger(ranger);
    return r;
  }

  /** Serves the chart's default values without asking a Helm repository. */
  private static CommandService withChartValues(CommandService service, Map<String, Object> values) throws Exception {
    HelmRepositoryService repositories = mock(HelmRepositoryService.class);
    when(repositories.showValuesAsMap(anyString(), anyString(), any())).thenReturn(values);
    HelmService helm = mock(HelmService.class);
    when(helm.getRepositoryService()).thenReturn(repositories);
    Field f = CommandService.class.getDeclaredField("helmService");
    f.setAccessible(true);
    f.set(service, helm);
    return service;
  }

  /** Makes the DataStore keep what it is given, by id, as Ambari's does. */
  private Map<String, Object> keepWhatIsStored() throws Exception {
    Map<String, Object> saved = new ConcurrentHashMap<>();
    doAnswer(call -> {
      Object entity = call.getArgument(0);
      saved.put(String.valueOf(entity.getClass().getMethod("getId").invoke(entity)), entity);
      return null;
    }).when(store).store(any());
    when(store.find(any(), any())).thenAnswer(call -> {
      Object entity = saved.get(String.valueOf((Object) call.getArgument(1)));
      return ((Class<?>) call.getArgument(0)).isInstance(entity) ? entity : null;
    });
    when(store.findAll(any(), any())).thenAnswer(call -> saved.values().stream()
        .filter(((Class<?>) call.getArgument(0))::isInstance).collect(Collectors.toList()));
    return saved;
  }

  private static List<String> stepTypes(Map<String, Object> saved) {
    return saved.values().stream().filter(CommandEntity.class::isInstance).map(CommandEntity.class::cast)
        .filter(c -> !CommandType.K8S_MANAGER_RELEASE_DEPLOY.name().equals(c.getType()))
        .map(CommandEntity::getType).collect(Collectors.toList());
  }

  /** The steps a run or a resume would still pick up. */
  private static List<String> stepsLeftToRun(Map<String, Object> saved) {
    return saved.values().stream().filter(CommandStatusEntity.class::isInstance).map(CommandStatusEntity.class::cast)
        .filter(st -> !CommandState.FAILED.name().equals(st.getState())
            && !CommandState.CANCELED.name().equals(st.getState()))
        .map(CommandStatusEntity::getId).collect(Collectors.toList());
  }

  private static HelmDeployRequest trinoWithIcebergCatalog(Map<String, Object> icebergCatalog) {
    HelmDeployRequest r = new HelmDeployRequest();
    r.setReleaseName("trino");
    r.setNamespace("analytics");
    r.setChart("trino");
    r.setRepoId("repo-1");
    r.setFormValues(Map.of("icebergCatalog", icebergCatalog));
    return r;
  }

  private void deploy(HelmDeployRequest request) {
    deploy(new CommandService(ctx), request);
  }

  private static void deploy(CommandService service, HelmDeployRequest request) {
    MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
    headers.putSingle("Cookie", "AMBARISESSIONID=ops-session");
    service.directHelmDeploy(request, "repo-1", "1.0.0", null, "http://ambari.test/commands",
        headers, URI.create("http://ambari.test:8080/api/v1"), null);
  }

  private static HelmDeployRequest kerberizedTrino() {
    HelmDeployRequest r = new HelmDeployRequest();
    r.setReleaseName("trino");
    r.setNamespace("analytics");
    r.setChart("trino");
    r.setRepoId("repo-1");
    r.setKerberos(Map.of("trino", Map.of("enabled", true)));
    return r;
  }

  /**
   * A Kerberized cluster dev21 on which ops holds {@link #opsAuthorizations}. Like Ambari, it refuses the cluster to an
   * account without a cluster role, and its configurations to one that may not view them; like the view's stream
   * provider, it hands the refusal back as an error body rather than throwing.
   */
  private InputStream kerberizedClusterWhereOpsIsNotClusterAdministrator(String url) throws IOException {
    ambariCalls.add(url);
    if (url.contains("/users/ops/authorizations")) {
      return json("{\"items\":[" + opsAuthorizations.stream()
          .map(id -> id.startsWith("CLUSTER.")
              ? "{\"AuthorizationInfo\":{\"authorization_id\":\"" + id + "\",\"resource_type\":\"CLUSTER\",\"cluster_name\":\"dev21\"}}"
              : "{\"AuthorizationInfo\":{\"authorization_id\":\"" + id + "\",\"resource_type\":\"VIEW\",\"cluster_name\":null}}")
          .collect(Collectors.joining(",")) + "]}");
    }
    boolean anyClusterRole = opsAuthorizations.stream().anyMatch(id -> id.startsWith("CLUSTER."));
    if (url.contains("/clusters") && !anyClusterRole
        || url.contains("/configurations") && !opsAuthorizations.contains("CLUSTER.VIEW_CONFIGS")) {
      return json("{\"status\":403,\"message\":\"The authenticated user is not authorized to perform the requested "
          + "operation\"}");
    }
    if (url.contains("/clusters?fields=Clusters/cluster_name")) {
      return json("{\"items\":[{\"Clusters\":{\"cluster_name\":\"dev21\"}}]}");
    }
    Matcher desired = Pattern.compile("desired_configs%2F([a-z-]+)").matcher(url);
    if (desired.find()) {
      return json("{\"Clusters\":{\"desired_configs\":{\"" + desired.group(1) + "\":{\"tag\":\"version1\"}}}}");
    }
    if (url.contains("/configurations?type=cluster-env")) {
      return json("{\"items\":[{\"properties\":{\"security_enabled\":\"true\"}}]}");
    }
    if (url.contains("/configurations?type=kerberos-env")) {
      return json("{\"items\":[{\"properties\":{\"realm\":\"EXAMPLE.COM\"}}]}");
    }
    throw new IOException("not served by this test: " + url);
  }

  /** The KubernetesService calls that change something. */
  private List<String> kubernetesWrites() {
    return mockingDetails(k8s).getInvocations().stream()
        .map(Invocation::getMethod)
        .map(m -> m.getName())
        .filter(name -> name.matches("(create|ensure|delete|apply|upsert|patch|update|grant|label|write|scale|restart).*"))
        .collect(Collectors.toList());
  }

  /** A keytab (MIT format 0x0502) with one entry for primary@realm. */
  private static byte[] keytab(String primary, String realm) {
    try {
      ByteArrayOutputStream entry = new ByteArrayOutputStream();
      DataOutputStream e = new DataOutputStream(entry);
      e.writeShort(1);
      e.writeShort(realm.length());
      e.write(realm.getBytes(StandardCharsets.UTF_8));
      e.writeShort(primary.length());
      e.write(primary.getBytes(StandardCharsets.UTF_8));
      e.writeInt(1);          // name type
      e.writeInt(0);          // timestamp
      e.writeByte(1);         // key version
      e.writeShort(18);       // key type
      e.writeShort(4);
      e.write(new byte[4]);   // key
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(out);
      o.writeByte(0x05);
      o.writeByte(0x02);
      o.writeInt(entry.size());
      o.write(entry.toByteArray());
      return out.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static InputStream json(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, KubernetesService> instances() throws Exception {
    Field f = KubernetesService.class.getDeclaredField("INSTANCES");
    f.setAccessible(true);
    return (ConcurrentMap<String, KubernetesService>) f.get(null);
  }
}
