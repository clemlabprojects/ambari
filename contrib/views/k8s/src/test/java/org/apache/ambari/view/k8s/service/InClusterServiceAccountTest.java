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

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ServiceAccount of the Ambari pod: when it can be used, who it is, and the kubeconfig KDPS writes from it, always
 * with the token the kubelet keeps renewed in the pod.
 */
class InClusterServiceAccountTest {

  static final String CA_PEM = "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----\n";

  @TempDir
  Path mount;

  final Map<String, String> env = new HashMap<>(Map.of(
      "KUBERNETES_SERVICE_HOST", "172.30.0.1",
      "KUBERNETES_SERVICE_PORT", "443"));

  /** A ServiceAccount token as Kubernetes signs it: a JWT with the given claims (the signature is not checked). */
  static String jwt(String claimsJson) {
    Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
    return url.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + url.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
  }

  /** A bound (projected) token of ServiceAccount kdps/ambari. */
  static String boundToken(String marker) {
    return jwt("{\"aud\":[\"https://kubernetes.default.svc\"],\"exp\":1999999999,\"iss\":\"https://kubernetes.default.svc\","
        + "\"kubernetes.io\":{\"namespace\":\"kdps\",\"pod\":{\"name\":\"ambari-0\",\"uid\":\"" + marker + "\"},"
        + "\"serviceaccount\":{\"name\":\"ambari\",\"uid\":\"u-1\"}},\"sub\":\"system:serviceaccount:kdps:ambari\"}");
  }

  /** A pod with its ServiceAccount mounted: token, CA and namespace. */
  InClusterServiceAccount pod(String token) throws Exception {
    Files.writeString(mount.resolve("token"), token + "\n");
    Files.writeString(mount.resolve("ca.crt"), CA_PEM);
    Files.writeString(mount.resolve("namespace"), "kdps");
    return new InClusterServiceAccount(mount, env::get);
  }

  @Test
  void aPodWithItsServiceAccountMountedCanConnect() throws Exception {
    InClusterServiceAccount sa = pod(boundToken("a"));

    assertNull(sa.unavailableReason());
    assertTrue(sa.isAvailable());
    assertEquals("https://172.30.0.1:443", sa.apiServerUrl());
    assertEquals("kdps", sa.namespace());
  }

  @Test
  void outsideAPodItSaysSo() throws Exception {
    InClusterServiceAccount sa = pod(boundToken("a"));
    env.clear();

    String reason = sa.unavailableReason();
    assertNotNull(reason);
    assertTrue(reason.contains("not running in a Kubernetes or OpenShift pod"), reason);
    assertFalse(sa.isAvailable());
    IllegalStateException e = assertThrows(IllegalStateException.class, sa::kubeconfig);
    assertEquals(reason, e.getMessage());
  }

  @Test
  void aPodWithoutTheTokenPointsAtTheChartValue() {
    InClusterServiceAccount sa = new InClusterServiceAccount(mount, env::get);

    String reason = sa.unavailableReason();
    assertTrue(reason.contains("no ServiceAccount token"), reason);
    assertTrue(reason.contains("inClusterServiceAccount.enabled=true"), reason);
    assertNull(sa.getToken());
  }

  @Test
  void anEmptyTokenOrAMissingCaIsReported() throws Exception {
    InClusterServiceAccount sa = pod("  ");
    assertTrue(sa.unavailableReason().contains("cannot be read or is empty"), sa.unavailableReason());

    Files.writeString(mount.resolve("token"), boundToken("a"));
    Files.delete(mount.resolve("ca.crt"));
    assertTrue(sa.unavailableReason().contains("no cluster CA certificate"), sa.unavailableReason());
  }

  @Test
  void theTokenIsReadAgainOnEveryUseSoARenewedOneIsUsedAtOnce() throws Exception {
    InClusterServiceAccount sa = pod(boundToken("first"));
    assertEquals(boundToken("first"), sa.getToken());

    // The kubelet writes the renewed token in place of the old one.
    Files.writeString(mount.resolve("token"), boundToken("renewed"));
    assertEquals(boundToken("renewed"), sa.getToken());

    // While the file cannot be read for a moment, the last token read is answered rather than none.
    Files.delete(mount.resolve("token"));
    assertEquals(boundToken("renewed"), sa.getToken());
  }

  @Test
  void theIdentityIsTheServiceAccountUserName() throws Exception {
    InClusterServiceAccount.Identity id = pod(boundToken("a")).identity();

    assertEquals("system:serviceaccount:kdps:ambari", id.username());
    assertEquals("kdps", id.namespace());
    assertEquals("ambari", id.name());
  }

  @Test
  void identityOfTokensWithoutASubjectOrOfTheLegacyKind() throws Exception {
    InClusterServiceAccount bound = pod(jwt(
        "{\"kubernetes.io\":{\"namespace\":\"team-a\",\"serviceaccount\":{\"name\":\"kdps\"}}}"));
    assertEquals("system:serviceaccount:team-a:kdps", bound.identity().username());

    InClusterServiceAccount legacy = pod(jwt("{\"iss\":\"kubernetes/serviceaccount\","
        + "\"kubernetes.io/serviceaccount/namespace\":\"team-b\","
        + "\"kubernetes.io/serviceaccount/service-account.name\":\"deployer\"}"));
    assertEquals(new InClusterServiceAccount.Identity("system:serviceaccount:team-b:deployer", "team-b", "deployer"),
        legacy.identity());
  }

  @Test
  void aTokenThatIsNotAJwtHasNoNameButThePodNamespace() throws Exception {
    InClusterServiceAccount.Identity id = pod("opaque-token").identity();

    assertNull(id.username());
    assertNull(id.name());
    assertEquals("kdps", id.namespace());
  }

  @Test
  void ipv6ApiServerAddressesAreBracketed() throws Exception {
    env.put("KUBERNETES_SERVICE_HOST", "fd00:10:96::1");
    assertEquals("https://[fd00:10:96::1]:443", pod(boundToken("a")).apiServerUrl());
  }

  @Test
  void theKubeconfigConnectsWithTheServiceAccountAndItsCurrentToken() throws Exception {
    InClusterServiceAccount sa = pod(boundToken("first"));

    String kubeconfig = sa.kubeconfig();
    io.fabric8.kubernetes.api.model.Config model =
        Serialization.unmarshal(kubeconfig, io.fabric8.kubernetes.api.model.Config.class);
    assertEquals("Config", model.getKind());
    assertEquals("in-cluster", model.getCurrentContext());
    assertEquals(1, model.getContexts().size());
    assertEquals("kdps", model.getContexts().get(0).getContext().getNamespace());
    assertEquals("https://172.30.0.1:443", model.getClusters().get(0).getCluster().getServer());
    assertEquals(CA_PEM, new String(Base64.getDecoder().decode(
        model.getClusters().get(0).getCluster().getCertificateAuthorityData()), StandardCharsets.UTF_8));
    assertEquals(boundToken("first"), model.getUsers().get(0).getUser().getToken());

    Config client = Config.fromKubeconfig(kubeconfig);
    assertEquals("https://172.30.0.1:443/", client.getMasterUrl());
    assertEquals("kdps", client.getNamespace());
    assertEquals(boundToken("first"), client.getAutoOAuthToken());

    // Helm gets a kubeconfig written for each use: a renewed token is in the next one.
    Files.writeString(mount.resolve("token"), boundToken("renewed"));
    assertEquals(boundToken("renewed"), Config.fromKubeconfig(sa.kubeconfig()).getAutoOAuthToken());
  }
}
