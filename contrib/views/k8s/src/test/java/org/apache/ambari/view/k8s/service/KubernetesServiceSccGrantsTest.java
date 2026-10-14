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

package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.APIGroupListBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.utils.ResponseProvider;
import okhttp3.Headers;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OpenShift SCCs for the service accounts of a release (OpenMetadata): detected first, granted through one
 * RoleBinding per release only when the account may, never taken away by a failed or later deploy, and removed with
 * the release.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceSccGrantsTest {

  private static final String NS = "data";
  private static final ObjectMapper JSON = new ObjectMapper();

  KubernetesMockServer server;
  KubernetesClient client;
  KubernetesService svc;

  /** Service accounts that may use an SCC through RBAC (what a LocalSubjectAccessReview answers), by SCC. */
  private final Map<String, Set<String>> rbacGrants = new ConcurrentHashMap<>();
  /** Service accounts named in an SCC's own users field (seen only by a PodSecurityPolicySubjectReview), by SCC. */
  private final Map<String, Set<String>> sccFieldGrants = new ConcurrentHashMap<>();
  /** The KDPS account may not create LocalSubjectAccessReviews (OpenShift's edit role). */
  private volatile boolean localReviewsForbidden;
  /** The security contexts of the probe pods of the PodSecurityPolicySubjectReviews made, in order. */
  private final List<JsonNode> probes = new CopyOnWriteArrayList<>();
  /** What the KDPS account may do: "verb resource[/name]" entries. */
  private final Set<String> accountMay = ConcurrentHashMap.newKeySet();
  private final Map<String, String> bodies = new LinkedHashMap<>();
  /** What a deploy tells the operator although it goes on. */
  private final List<String> warnings = new ArrayList<>();

  /**
   * The SCCs of the mock cluster in OpenShift admission order (priority first, then the most restrictive), with what
   * each admits. restricted-v2, which every service account may use, is left out: no probe runs in a project UID range.
   */
  private static final Map<String, Predicate<JsonNode>> ADMISSION_ORDER = new LinkedHashMap<>();
  static {
    ADMISSION_ORDER.put("site-priority-20", sc -> true);                    // a site SCC ranked above anyuid
    ADMISSION_ORDER.put("anyuid", sc -> !sc.has("seccompProfile"));        // priority 10, allows no seccomp profile
    ADMISSION_ORDER.put("nonroot-v2", sc -> sc.path("runAsUser").asLong(0) > 0);
    ADMISSION_ORDER.put("privileged", sc -> true);
  }

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("scc-grants-test");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);

    server.expect().get().withPath("/apis").andReturn(200, new APIGroupListBuilder()
        .addNewGroup().withName("route.openshift.io").endGroup()
        .addNewGroup().withName("security.openshift.io").endGroup().build()).always();
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
        .andReply(201, req -> answer(req, review -> {
          JsonNode attrs = review.path("spec").path("resourceAttributes");
          String name = attrs.path("name").asText("");
          return accountMay.contains(attrs.path("verb").asText() + " " + attrs.path("resource").asText()
              + (name.isEmpty() ? "" : "/" + name));
        })).always();
    server.expect().post().withPath("/apis/authorization.k8s.io/v1/namespaces/" + NS + "/localsubjectaccessreviews")
        .andReply(new ResponseProvider<Object>() {
          private Headers headers = new Headers.Builder().build();

          @Override
          public int getStatusCode(RecordedRequest req) {
            return localReviewsForbidden ? 403 : 201;
          }

          @Override
          public Object getBody(RecordedRequest req) {
            if (localReviewsForbidden) {
              return "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
                  + "\"reason\":\"Forbidden\",\"message\":\"cannot create localsubjectaccessreviews\"}";
            }
            return answer(req, review -> {
              String user = review.path("spec").path("user").asText();
              String scc = review.path("spec").path("resourceAttributes").path("name").asText();
              String sa = user.substring(("system:serviceaccount:" + NS + ":").length());
              return rbacGrants.getOrDefault(scc, Set.of()).contains(sa);
            });
          }

          @Override
          public Headers getHeaders() {
            return headers;
          }

          @Override
          public void setHeaders(Headers headers) {
            this.headers = headers;
          }
        }).always();
    server.expect().post().withPath("/apis/security.openshift.io/v1/namespaces/" + NS + "/podsecuritypolicysubjectreviews")
        .andReply(201, req -> {
          try {
            ObjectNode review = (ObjectNode) JSON.readTree(req.getBody().clone().readUtf8());
            JsonNode podSpec = review.path("spec").path("template").path("spec");
            JsonNode securityContext = podSpec.path("containers").path(0).path("securityContext");
            probes.add(securityContext);
            ObjectNode status = review.putObject("status");
            String scc = admittingScc(podSpec.path("serviceAccountName").asText(), securityContext);
            if (scc != null) status.putObject("allowedBy").put("kind", "SecurityContextConstraints").put("name", scc);
            return JSON.writeValueAsString(review);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }).always();
  }

  /**
   * The SCC OpenShift admission picks for a probe pod: the first in admission order that the service account may use,
   * through RBAC or the SCC's users field, and that admits the pod.
   */
  private String admittingScc(String sa, JsonNode securityContext) {
    for (Map.Entry<String, Predicate<JsonNode>> scc : ADMISSION_ORDER.entrySet()) {
      boolean held = rbacGrants.getOrDefault(scc.getKey(), Set.of()).contains(sa)
          || sccFieldGrants.getOrDefault(scc.getKey(), Set.of()).contains(sa);
      if (held && scc.getValue().test(securityContext)) return scc.getKey();
    }
    return null;
  }

  /** The posted access review with its answer added. */
  private static String answer(RecordedRequest req, java.util.function.Predicate<JsonNode> allowed) {
    try {
      ObjectNode review = (ObjectNode) JSON.readTree(req.getBody().clone().readUtf8());
      review.putObject("status").put("allowed", allowed.test(review));
      return JSON.writeValueAsString(review);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The KDPS account may grant these SCCs: create RoleBindings and bind their ClusterRoles in the project. */
  private void accountMayGrant(String... sccs) {
    accountMay.add("create rolebindings");
    for (String scc : sccs) accountMay.add("bind clusterroles/system:openshift:scc:" + scc);
  }

  private void rbacGrants(String scc, String... sas) {
    rbacGrants.computeIfAbsent(scc, k -> ConcurrentHashMap.newKeySet()).addAll(List.of(sas));
  }

  private void sccFieldGrants(String scc, String... sas) {
    sccFieldGrants.computeIfAbsent(scc, k -> ConcurrentHashMap.newKeySet()).addAll(List.of(sas));
  }

  /** The requests made since the last call, as "METHOD path". */
  private List<String> requests() throws InterruptedException {
    List<String> out = new ArrayList<>();
    RecordedRequest r;
    while ((r = server.takeRequest(100, TimeUnit.MILLISECONDS)) != null) {
      out.add(r.getMethod() + " " + r.getPath());
      bodies.put(r.getMethod() + " " + r.getPath(), r.getBody().readUtf8());
    }
    return out;
  }

  private static List<String> roleBindingWrites(List<String> requests) {
    return requests.stream().filter(r -> r.contains("/rolebindings") && !r.startsWith("GET ")).collect(Collectors.toList());
  }

  private static List<Map<String, Object>> openMetadataScc() {
    return new StackDefinitionService().getServiceDefinition("OPENMETADATA").openshiftScc;
  }

  /**
   * The SCC needs of a second service sharing the namespace's 'default' account with OpenMetadata: nonroot-v2 for a
   * pinned non-root UID, and anyuid for a root sidecar. Declared here because no shipped service has this shape.
   */
  private static List<Map<String, Object>> twoSccService() {
    return List.of(
        Map.of("scc", "nonroot-v2", "serviceAccounts", List.of("hub", "default")),
        Map.of("scc", "anyuid", "serviceAccounts", List.of("default")));
  }

  private static List<String> openMetadataAccounts(String release) {
    return KubernetesService.sccNeeds(release, openMetadataScc()).get(0).serviceAccounts();
  }

  private RoleBinding binding(String name) {
    return client.rbac().roleBindings().inNamespace(NS).withName(name).get();
  }

  private static Set<String> subjects(RoleBinding rb) {
    return rb.getSubjects().stream().map(s -> s.getName()).collect(Collectors.toSet());
  }

  private void existingBinding(String name, Map<String, String> labels, String scc, String... sas) {
    RoleBindingBuilder b = new RoleBindingBuilder()
        .withNewMetadata().withName(name).withNamespace(NS).withLabels(labels).endMetadata()
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole")
        .withName("system:openshift:scc:" + scc).endRoleRef();
    for (String sa : sas) b.addNewSubject().withKind("ServiceAccount").withName(sa).withNamespace(NS).endSubject();
    client.rbac().roleBindings().inNamespace(NS).resource(b.build()).create();
  }

  private static Map<String, String> releaseLabels(String release, String scc) {
    return Map.of("app.kubernetes.io/managed-by", "ambari-kdps", "ambari.clemlab.com/release", release,
        "ambari.clemlab.com/scc", scc);
  }

  @Test
  void alreadyGrantedServiceAccountsNeedNoWrite() throws Exception {
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    accountMayGrant("anyuid");

    assertNull(svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings));

    List<String> calls = requests();
    assertEquals(List.of(), roleBindingWrites(calls), calls::toString);
    assertTrue(calls.stream().noneMatch(c -> c.contains("selfsubjectaccessreviews")),
        "nothing to grant, so the account's rights are not even asked: " + calls);
    assertEquals(List.of(), warnings);
  }

  @Test
  void grantableServiceAccountsGetOneBindingForTheRelease() throws Exception {
    accountMayGrant("anyuid");

    assertNull(svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings));

    List<String> writes = roleBindingWrites(requests());
    assertEquals(List.of("POST /apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings"), writes);
    RoleBinding rb = binding("kdps-scc-anyuid-om");
    assertNotNull(rb);
    assertEquals("system:openshift:scc:anyuid", rb.getRoleRef().getName());
    assertEquals(releaseLabels("om", "anyuid"), rb.getMetadata().getLabels());
    assertEquals(new HashSet<>(openMetadataAccounts("om")), subjects(rb));
    assertTrue(subjects(rb).contains("om-airflow-worker"), "release-templated service accounts are expanded");
    String bindCheck = bodies.entrySet().stream()
        .filter(e -> e.getKey().contains("selfsubjectaccessreviews") && e.getValue().contains("\"bind\""))
        .map(Map.Entry::getValue).findFirst().orElse("");
    assertTrue(bindCheck.contains("\"name\":\"system:openshift:scc:anyuid\""),
        "the bind right is asked for the SCC's ClusterRole by name: " + bindCheck);
    assertTrue(bindCheck.contains("\"namespace\":\"data\""), bindCheck);
  }

  @Test
  void theUseRightOnTheSccAlsoLetsTheAccountGrantIt() throws Exception {
    accountMay.add("create rolebindings");
    accountMay.add("use securitycontextconstraints/anyuid");

    assertNull(svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings));

    assertNotNull(binding("kdps-scc-anyuid-om"));
  }

  @Test
  void notGrantableKeepsTheExistingBindingAndPrintsBothRemedies() throws Exception {
    // The shared binding an earlier version made with a privileged account, before the account was replaced.
    existingBinding("kdps-scc-anyuid", Map.of(), "anyuid", "default");
    rbacGrants("anyuid", "default");
    accountMay.add("create rolebindings");
    requests();

    String problem = svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings);

    assertNotNull(problem);
    List<String> calls = requests();
    assertEquals(List.of(), roleBindingWrites(calls), "nothing deleted, nothing written: " + calls);
    assertEquals(Set.of("default"), subjects(binding("kdps-scc-anyuid")));
    assertTrue(problem.contains("oc adm policy add-scc-to-user anyuid -z default -z om-airflow-scheduler"),
        "'default' holds anyuid only through the shared binding of an earlier version, so it is asked for too: "
            + problem);
    assertTrue(problem.contains("-z om-airflow-create-user-job -n data"), problem);
    assertTrue(problem.contains("oc create clusterrole kdps-grant-scc --verb=bind --resource=clusterroles"
        + " --resource-name=system:openshift:scc:anyuid --resource-name=system:openshift:scc:nonroot-v2"), problem);
    assertTrue(problem.contains("oc -n data create rolebinding kdps-grant-scc --clusterrole=kdps-grant-scc"), problem);
  }

  @Test
  void aFailedGrantNeverRemovesTheReleasesExistingBinding() throws Exception {
    // A binding made for the release earlier; the account may still bind, but the API refuses this write.
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid", "default");
    rbacGrants("anyuid", "default");
    accountMayGrant("anyuid");
    server.expect().patch().withPath("/apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings/kdps-scc-anyuid-om")
        .andReturn(403, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
            + "\"reason\":\"Forbidden\",\"message\":\"attempt to grant extra privileges\"}").always();
    requests();

    String problem = svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings);

    assertNotNull(problem);
    List<String> calls = requests();
    assertTrue(calls.stream().noneMatch(c -> c.startsWith("DELETE ")), calls::toString);
    assertEquals(Set.of("default"), subjects(binding("kdps-scc-anyuid-om")));
  }

  @Test
  void anExistingBindingOfTheReleaseOnlyGainsServiceAccounts() throws Exception {
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid", "default", "retired-sa");
    rbacGrants("anyuid", "default", "retired-sa");
    accountMayGrant("anyuid");
    requests();

    assertNull(svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings));

    List<String> writes = roleBindingWrites(requests());
    assertEquals(1, writes.size(), writes::toString);
    assertTrue(writes.get(0).startsWith("PATCH ") || writes.get(0).startsWith("PUT "), writes::toString);
    Set<String> after = subjects(binding("kdps-scc-anyuid-om"));
    assertTrue(after.containsAll(openMetadataAccounts("om")), after::toString);
    assertTrue(after.contains("retired-sa"), "subjects are only ever added: " + after);
  }

  @Test
  void aGrantInTheSccUsersFieldIsDetected() throws Exception {
    sccFieldGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    accountMayGrant("anyuid");

    assertNull(svc.ensureSccGrants(NS, "om", openMetadataScc(), warnings));

    List<String> calls = requests();
    assertEquals(List.of(), roleBindingWrites(calls), calls::toString);
    assertTrue(calls.contains("POST /apis/security.openshift.io/v1/namespaces/data/podsecuritypolicysubjectreviews"), calls::toString);
    String probe = bodies.get("POST /apis/security.openshift.io/v1/namespaces/data/podsecuritypolicysubjectreviews");
    assertTrue(probe.contains("\"user\":\"system:serviceaccount:data:"), probe);
    assertTrue(probe.contains("\"system:serviceaccounts:data\""), probe);
  }

  @Test
  void anotherSccAdmittingTheProbeIsNotTheOneNeeded() throws Exception {
    sccFieldGrants("privileged", "hub", "default");

    String problem = svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings);

    assertNotNull(problem);
    assertTrue(problem.contains("add-scc-to-user nonroot-v2 -z hub -z default -n data"), problem);
    assertTrue(problem.contains("add-scc-to-user anyuid -z default -n data"), problem);
  }

  @Test
  void withoutLocalReviewsAServiceAccountHoldingAnyuidIsStillSeenToHoldNonrootV2() throws Exception {
    // An account with OpenShift's edit role: no LocalSubjectAccessReview, only the admission probe. 'default' holds
    // anyuid as well, which admission prefers for any probe it admits.
    localReviewsForbidden = true;
    rbacGrants("nonroot-v2", "hub", "default");
    rbacGrants("anyuid", "default");
    accountMayGrant("anyuid", "nonroot-v2");

    assertNull(svc.checkSccGrants(NS, "jhub", twoSccService(), warnings), "GitOps finds the platform team's grants");
    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings), "so does a direct deploy");

    assertEquals(List.of(), roleBindingWrites(requests()));
    assertTrue(probes.stream().anyMatch(sc -> "RuntimeDefault".equals(sc.path("seccompProfile").path("type").asText())
        && sc.path("runAsUser").asLong() > 0), "the nonroot-v2 probe declares a seccomp profile: " + probes);
    assertTrue(probes.stream().anyMatch(sc -> sc.path("runAsUser").asLong(-1) == 0 && !sc.has("seccompProfile")),
        "the anyuid probe runs as root: " + probes);
  }

  @Test
  void withoutLocalReviewsTheRetryAfterThePrintedCommandGoesThrough() throws Exception {
    localReviewsForbidden = true;
    rbacGrants("nonroot-v2", "hub");
    rbacGrants("anyuid", "default");

    String problem = svc.checkSccGrants(NS, "jhub", twoSccService(), warnings);

    assertNotNull(problem);
    assertTrue(problem.contains("oc adm policy add-scc-to-user nonroot-v2 -z default -n data"), problem);
    assertFalse(problem.contains("add-scc-to-user anyuid"), "'default' may already use anyuid: " + problem);
    assertFalse(problem.contains("LocalSubjectAccessReviews"), "the probe answered for every account: " + problem);

    // The platform team runs the printed command.
    rbacGrants("nonroot-v2", "default");
    assertNull(svc.checkSccGrants(NS, "jhub", twoSccService(), warnings));
  }

  @Test
  void withoutLocalReviewsAProbeAdmittedByAnotherSccAsksForTheReviewRight() {
    // A site SCC ranked above anyuid admits every probe of 'default', so the probe cannot tell what else it holds.
    localReviewsForbidden = true;
    sccFieldGrants("site-priority-20", "default");
    rbacGrants("nonroot-v2", "hub", "default");
    rbacGrants("anyuid", "default");

    String problem = svc.checkSccGrants(NS, "jhub", twoSccService(), warnings);

    assertNotNull(problem);
    assertTrue(problem.contains("could not check the existing grants")
        && problem.contains("create LocalSubjectAccessReviews"), "not only a grant the team may have made: " + problem);
    assertFalse(problem.contains("-z hub"), "the probe did answer for hub: " + problem);
  }

  @Test
  void twoReleasesKeepSeparateBindingsAndUninstallRemovesOnlyItsOwn() throws Exception {
    accountMayGrant("anyuid");
    assertNull(svc.ensureSccGrants(NS, "om1", openMetadataScc(), warnings));
    rbacGrants("anyuid", openMetadataAccounts("om1").toArray(new String[0]));

    assertNull(svc.ensureSccGrants(NS, "om2", openMetadataScc(), warnings));
    rbacGrants("anyuid", openMetadataAccounts("om2").toArray(new String[0]));

    assertEquals(new HashSet<>(openMetadataAccounts("om1")), subjects(binding("kdps-scc-anyuid-om1")),
        "deploying om2 left om1's grant as it was");
    assertEquals(new HashSet<>(openMetadataAccounts("om2")), subjects(binding("kdps-scc-anyuid-om2")));
    requests();

    // om1 is uninstalled; RBAC no longer grants what only its binding did.
    rbacGrants.get("anyuid").removeIf(sa -> sa.startsWith("om1-"));
    assertNull(svc.removeSccGrants(NS, "om1", openMetadataScc(), Map.of("om2", openMetadataScc())),
        "KDPS made every grant om1 had");

    List<String> writes = roleBindingWrites(requests());
    assertEquals(List.of("DELETE /apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings/kdps-scc-anyuid-om1"), writes);
    assertNull(binding("kdps-scc-anyuid-om1"));
    assertEquals(new HashSet<>(openMetadataAccounts("om2")), subjects(binding("kdps-scc-anyuid-om2")),
        "om2's Airflow task pods are still admitted");
  }

  @Test
  void aGrantOnlyThroughAnotherReleaseGetsTheReleasesOwnBinding() throws Exception {
    // OpenMetadata granted anyuid to 'default'; the second service needs it too and must not depend on OpenMetadata staying.
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid",
        openMetadataAccounts("om").toArray(new String[0]));
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    accountMayGrant("anyuid", "nonroot-v2");

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings));

    assertEquals(Set.of("default"), subjects(binding("kdps-scc-anyuid-jhub")));
    assertEquals(Set.of("hub", "default"), subjects(binding("kdps-scc-nonroot-v2-jhub")));
    assertEquals(new HashSet<>(openMetadataAccounts("om")), subjects(binding("kdps-scc-anyuid-om")));
  }

  @Test
  void aGrantOnlyThroughAnotherReleaseDoesNotBlockWhenTheAccountCannotGrantButIsReported() {
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid", "default");
    rbacGrants("anyuid", "default");
    rbacGrants("nonroot-v2", "hub", "default");

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings), "its pods are admitted today");
    assertNull(binding("kdps-scc-anyuid-jhub"));
    assertEquals(1, warnings.size(), warnings::toString);
    String warning = warnings.get(0);
    assertTrue(warning.contains("kdps-scc-anyuid-om (made by KDPS for release om)"), warning);
    assertTrue(warning.contains("oc adm policy add-scc-to-user anyuid -z default -n data"), warning);
  }

  @Test
  void aGrantOnlyThroughTheSharedBindingOfEarlierVersionsGetsTheReleasesOwnBinding() throws Exception {
    existingBinding("kdps-scc-anyuid", Map.of(), "anyuid", "default", "om-airflow-worker");
    rbacGrants("anyuid", "default", "om-airflow-worker");
    rbacGrants("nonroot-v2", "hub", "default");
    accountMayGrant("anyuid");
    requests();

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings));

    assertEquals(Set.of("default"), subjects(binding("kdps-scc-anyuid-jhub")));
    assertEquals(Set.of("default", "om-airflow-worker"), subjects(binding("kdps-scc-anyuid")), "left untouched");
    assertEquals(List.of("POST /apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings"),
        roleBindingWrites(requests()));
    assertEquals(List.of(), warnings);
  }

  @Test
  void aGrantOnlyThroughTheSharedBindingOfEarlierVersionsIsReportedWhenTheAccountCannotGrant() {
    existingBinding("kdps-scc-anyuid", Map.of(), "anyuid", "default");
    rbacGrants("anyuid", "default");
    rbacGrants("nonroot-v2", "hub", "default");

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings));

    assertEquals(1, warnings.size(), warnings::toString);
    assertTrue(warnings.get(0).contains("kdps-scc-anyuid (made by an earlier KDPS version for the whole project)"),
        warnings::toString);
  }

  @Test
  void anAdministratorsGrantNextToAnotherReleasesBindingIsNotBorrowed() throws Exception {
    // What `oc adm policy add-scc-to-user` writes, next to the binding KDPS made for OpenMetadata.
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid", "default");
    existingBinding("system:openshift:scc:anyuid", Map.of(), "anyuid", "default");
    rbacGrants("anyuid", "default");
    rbacGrants("nonroot-v2", "hub", "default");
    accountMayGrant("anyuid", "nonroot-v2");
    requests();

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings));

    assertEquals(List.of(), roleBindingWrites(requests()));
    assertEquals(List.of(), warnings);
  }

  @Test
  void aClusterWideGrantTheAccountMayReadIsNotBorrowedEither() throws Exception {
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid", "default");
    client.rbac().clusterRoleBindings().resource(new ClusterRoleBindingBuilder()
        .withNewMetadata().withName("system:openshift:scc:anyuid").endMetadata()
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole")
        .withName("system:openshift:scc:anyuid").endRoleRef()
        .addNewSubject().withKind("ServiceAccount").withName("default").withNamespace(NS).endSubject()
        .build()).create();
    rbacGrants("anyuid", "default");
    rbacGrants("nonroot-v2", "hub", "default");

    assertNull(svc.ensureSccGrants(NS, "jhub", twoSccService(), warnings));

    assertEquals(List.of(), warnings);
  }

  @Test
  void uninstallListsTheGrantsAnAdministratorMade() throws Exception {
    // The platform team ran the printed oc adm policy command; KDPS wrote nothing.
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    existingBinding("kdps-scc-anyuid-jhub", releaseLabels("jhub", "anyuid"), "anyuid", "default");
    requests();

    String note = svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of("jhub", twoSccService()));

    assertEquals(List.of(), roleBindingWrites(requests()), "nothing of KDPS's to remove");
    assertNotNull(note);
    assertTrue(note.contains("oc adm policy remove-scc-from-user anyuid -z om-airflow-scheduler"), note);
    assertTrue(note.contains("-z om-airflow-create-user-job -n data"), note);
    assertFalse(note.contains("-z default "), "another release's KDPS grant covers 'default': " + note);
    assertNotNull(binding("kdps-scc-anyuid-jhub"), "the other release keeps its grant");
  }

  @Test
  void aBindingTheAccountMayNotDeleteIsReportedAndNotMistakenForAnAdministratorsGrant() throws Exception {
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid",
        openMetadataAccounts("om").toArray(new String[0]));
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    server.expect().delete().withPath("/apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings/kdps-scc-anyuid-om")
        .andReturn(403, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
            + "\"reason\":\"Forbidden\",\"message\":\"cannot delete rolebindings\"}").always();

    String note = assertDoesNotThrow(() -> svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of()));

    assertNotNull(note);
    assertTrue(note.contains("oc -n data delete rolebinding kdps-scc-anyuid-om"), note);
    assertFalse(note.contains("remove-scc-from-user"), note);
    assertNotNull(binding("kdps-scc-anyuid-om"));
  }

  @Test
  void uninstallPointsAtTheSharedBindingOfEarlierVersions() throws Exception {
    existingBinding("kdps-scc-anyuid", Map.of(), "anyuid", "default", "om-airflow-worker");
    rbacGrants("anyuid", "default", "om-airflow-worker");
    requests();

    String note = svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of());

    assertEquals(List.of(), roleBindingWrites(requests()), "the shared binding is never deleted by KDPS");
    assertNotNull(note);
    assertTrue(note.contains("oc -n data delete rolebinding kdps-scc-anyuid"), note);
    assertFalse(note.contains("remove-scc-from-user"), note);
  }

  @Test
  void uninstallLeavesOutAnAdministratorsGrantAnotherReleaseStillUses() throws Exception {
    // The platform team granted anyuid for om1, then only what om2 lacked: 'default' was already granted.
    List<String> granted = new ArrayList<>(openMetadataAccounts("om1"));
    openMetadataAccounts("om2").stream().filter(sa -> !granted.contains(sa)).forEach(granted::add);
    existingBinding("system:openshift:scc:anyuid", Map.of(), "anyuid", granted.toArray(new String[0]));
    rbacGrants("anyuid", granted.toArray(new String[0]));
    requests();

    String note = svc.removeSccGrants(NS, "om2", openMetadataScc(), Map.of("om1", openMetadataScc()));

    assertEquals(List.of(), roleBindingWrites(requests()));
    assertNotNull(note);
    assertTrue(note.contains("oc adm policy remove-scc-from-user anyuid -z om2-airflow-scheduler"), note);
    assertFalse(note.contains("-z default "), "om1 still runs OpenSearch under 'default': " + note);
    assertFalse(note.contains("om1-"), note);
    assertTrue(note.contains("No other KDPS release of the project uses them"), note);
  }

  @Test
  void uninstallKeepsTheSharedBindingOfEarlierVersionsWhileAnotherReleaseDependsOnIt() throws Exception {
    existingBinding("kdps-scc-anyuid", Map.of(), "anyuid", openMetadataAccounts("om").toArray(new String[0]));
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    requests();

    // it needed only 'default', which OpenMetadata's Airflow grant in that binding also covers.
    assertNull(svc.removeSccGrants(NS, "jhub", twoSccService(), Map.of("om", openMetadataScc())),
        "the shared binding still holds OpenMetadata's grant");

    assertEquals(List.of(), roleBindingWrites(requests()));
    String afterLastRelease = svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of());
    assertNotNull(afterLastRelease);
    assertTrue(afterLastRelease.contains("oc -n data delete rolebinding kdps-scc-anyuid"), afterLastRelease);
  }

  @Test
  void uninstallHandsAGrantAnotherReleaseStillUsesOverToIt() throws Exception {
    // OpenMetadata's binding is all that lets the second service's 'default' use anyuid.
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid",
        openMetadataAccounts("om").toArray(new String[0]));
    existingBinding("kdps-scc-nonroot-v2-jhub", releaseLabels("jhub", "nonroot-v2"), "nonroot-v2",
        "hub", "user-scheduler", "default");
    rbacGrants("anyuid", "default");
    accountMayGrant("anyuid");
    requests();

    assertNull(svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of("jhub", twoSccService())));

    assertNull(binding("kdps-scc-anyuid-om"));
    RoleBinding handedOver = binding("kdps-scc-anyuid-jhub");
    assertNotNull(handedOver, "jhub got its own grant before om's went");
    assertEquals(Set.of("default"), subjects(handedOver));
    assertEquals(releaseLabels("jhub", "anyuid"), handedOver.getMetadata().getLabels());
    List<String> writes = roleBindingWrites(requests());
    assertEquals(List.of("POST /apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings",
        "DELETE /apis/rbac.authorization.k8s.io/v1/namespaces/data/rolebindings/kdps-scc-anyuid-om"), writes);
  }

  @Test
  void uninstallKeepsABindingAnotherReleaseStillUsesWhenTheAccountCannotGrant() throws Exception {
    existingBinding("kdps-scc-anyuid-om", releaseLabels("om", "anyuid"), "anyuid",
        openMetadataAccounts("om").toArray(new String[0]));
    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    requests();

    String note = svc.removeSccGrants(NS, "om", openMetadataScc(), Map.of("jhub", twoSccService()));

    assertEquals(List.of(), roleBindingWrites(requests()), "nothing deleted: jhub's pods would be refused");
    assertNotNull(binding("kdps-scc-anyuid-om"));
    assertNotNull(note);
    assertTrue(note.contains("RoleBinding kdps-scc-anyuid-om stays: it also grants SCC anyuid to default of release "
        + "jhub"), note);
    assertTrue(note.contains("oc adm policy add-scc-to-user anyuid -z default -n data\n"
        + "  oc -n data delete rolebinding kdps-scc-anyuid-om"), "grant first, then delete: " + note);
    assertFalse(note.contains("remove-scc-from-user"), "everything left is KDPS's: " + note);
  }

  @Test
  void gitOpsOnlyChecksAndNeverGrants() throws Exception {
    accountMayGrant("anyuid");

    String problem = svc.checkSccGrants(NS, "om", openMetadataScc(), warnings);

    assertNotNull(problem);
    assertEquals(List.of(), roleBindingWrites(requests()));
    assertTrue(problem.contains("oc adm policy add-scc-to-user anyuid -z default -z om-airflow-scheduler"), problem);
    assertFalse(problem.contains("kdps-grant-scc"), "letting the account grant does not help a GitOps deploy: " + problem);

    rbacGrants("anyuid", openMetadataAccounts("om").toArray(new String[0]));
    assertNull(svc.checkSccGrants(NS, "om", openMetadataScc(), warnings), "the platform team's grant is found");
  }

  @Test
  void nothingHappensOnPlainKubernetes() throws Exception {
    KubernetesService plain = spy(svc);
    doReturn(false).when(plain).isOpenShiftCluster();

    assertNull(plain.ensureSccGrants(NS, "om", openMetadataScc(), warnings));
    assertNull(plain.checkSccGrants(NS, "om", openMetadataScc(), warnings));
    assertNull(plain.removeSccGrants(NS, "om", openMetadataScc(), Map.of()));
    assertEquals(List.of(), requests());
  }

  @Test
  void bindingNamesStayWithinTheKubernetesLimitAndApartPerRelease() {
    assertEquals("kdps-scc-nonroot-v2-jhub", KubernetesService.sccBindingName("nonroot-v2", "jhub"));
    String longRelease = "openmetadata-analytics-platform-production-eu-west-1";
    String a = KubernetesService.sccBindingName("nonroot-v2", longRelease);
    String b = KubernetesService.sccBindingName("nonroot-v2", longRelease + "x");
    assertTrue(a.length() <= 63, a);
    assertTrue(b.length() <= 63, b);
    assertNotEquals(a, b);
    assertTrue(a.startsWith("kdps-scc-nonroot-v2-openmetadata"), a);
    assertEquals(a, KubernetesService.sccBindingName("nonroot-v2", longRelease), "stable across deploys");
  }

  @Test
  void declarationsAreMergedBySccAndNeverGrantTheWholeProject() {
    List<Map<String, Object>> grants = new ArrayList<>();
    grants.add(new HashMap<>(Map.of("scc", "anyuid", "serviceAccounts", List.of("default"))));
    grants.add(new HashMap<>(Map.of("scc", "anyuid", "serviceAccounts", List.of("{{releaseName}}-worker", "default"))));
    grants.add(new HashMap<>(Map.of("scc", "privileged")));

    List<KubernetesService.SccNeed> needs = KubernetesService.sccNeeds("om", grants);

    assertEquals(1, needs.size());
    assertEquals("anyuid", needs.get(0).scc());
    assertEquals(List.of("default", "om-worker"), needs.get(0).serviceAccounts());
  }
}
