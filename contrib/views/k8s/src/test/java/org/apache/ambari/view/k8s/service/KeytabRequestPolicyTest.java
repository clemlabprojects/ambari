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

import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Keytabs go only to the releases KDPS deployed, for principals named after their namespace. */
class KeytabRequestPolicyTest {

  private static final List<K8sReleaseEntity> ANALYTICS = List.of(release("trino"), release("superset"));

  @Test
  void theWebhookGetsTheKeytabOfAPodOfAKdpsRelease() {
    K8sReleaseEntity release = KeytabRequestPolicy.check(request("trino-analytics@EXAMPLE.COM"), ANALYTICS, true);

    assertEquals("trino", release.getReleaseName());
  }

  @Test
  void serviceAndSyntheticHostPrincipalsOfTheNamespaceAreAccepted() {
    assertDoesNotThrow(() -> KeytabRequestPolicy.check(request("trino/trino.analytics.svc.cluster.local@EXAMPLE.COM"),
        ANALYTICS, true));
    assertDoesNotThrow(() -> KeytabRequestPolicy.check(request("trino/trino-0.trino.analytics.k8s@EXAMPLE.COM"),
        ANALYTICS, true));
    assertDoesNotThrow(() -> KeytabRequestPolicy.check(request("trino/trino-analytics.k8s@EXAMPLE.COM"),
        ANALYTICS, true));
    // The webhook's headless template with the cluster name; Ambari decides whether it may be issued.
    assertDoesNotThrow(() -> KeytabRequestPolicy.check(request("trino-analytics-c1@EXAMPLE.COM"), ANALYTICS, true));
  }

  @Test
  void anUnknownPrincipalIsRefused() {
    // Cluster identities, other namespaces' principals and users are not KDPS principals of this namespace.
    for (String principal : List.of("hdfs@EXAMPLE.COM", "admin/admin@EXAMPLE.COM", "trino-finance@EXAMPLE.COM", "trino-analyticsx@EXAMPLE.COM",
        "alice/trino.finance.k8s@EXAMPLE.COM", "trino-analytics", "trino analytics@EXAMPLE.COM",
        "alice/analytics@EXAMPLE.COM")) {
      KeytabRequestPolicy.RefusedException refused = assertThrows(KeytabRequestPolicy.RefusedException.class,
          () -> KeytabRequestPolicy.check(request(principal), ANALYTICS, true), principal);
      assertTrue(refused.getMessage().contains(principal), refused.getMessage());
    }
  }

  @Test
  void aNamespaceWithoutKdpsReleaseGetsNoKeytab() {
    KeytabRequest req = request("trino-analytics@EXAMPLE.COM");

    KeytabRequestPolicy.RefusedException refused = assertThrows(KeytabRequestPolicy.RefusedException.class,
        () -> KeytabRequestPolicy.check(req, List.of(), false));
    assertTrue(refused.getMessage().contains("no release in namespace 'analytics'"), refused.getMessage());
  }

  @Test
  void anUnknownReleaseIsRefused() {
    KeytabRequest req = request("trino-analytics@EXAMPLE.COM");
    req.setReleaseName("rogue");

    assertThrows(KeytabRequestPolicy.RefusedException.class, () -> KeytabRequestPolicy.check(req, ANALYTICS, true));
    assertThrows(KeytabRequestPolicy.RefusedException.class, () -> KeytabRequestPolicy.check(req, ANALYTICS, false));
  }

  @Test
  void theWebhookMustNameTheReleaseOperatorsNeedNot() {
    KeytabRequest req = request("trino-analytics@EXAMPLE.COM");
    req.setReleaseName(null);

    assertThrows(KeytabRequestPolicy.RefusedException.class, () -> KeytabRequestPolicy.check(req, ANALYTICS, true));
    assertNull(KeytabRequestPolicy.check(req, ANALYTICS, false));
  }

  @Test
  void theWebhookOnlyWritesThePodKeytabSecrets() {
    KeytabRequest req = request("trino-analytics@EXAMPLE.COM");
    req.setSecretName("trino-ingress-tls");

    KeytabRequestPolicy.RefusedException refused = assertThrows(KeytabRequestPolicy.RefusedException.class,
        () -> KeytabRequestPolicy.check(req, ANALYTICS, true));
    assertTrue(refused.getMessage().contains("trino-ingress-tls"), refused.getMessage());
    // An operator names the Secret of a keytab as a deploy does.
    assertDoesNotThrow(() -> KeytabRequestPolicy.check(req, ANALYTICS, false));
  }

  @Test
  void malformedNamesAreRefused() {
    KeytabRequest badNamespace = request("trino-analytics@EXAMPLE.COM");
    badNamespace.setNamespace("Analytics/..");
    KeytabRequest badSecret = request("trino-analytics@EXAMPLE.COM");
    badSecret.setSecretName("keytab-../x");
    KeytabRequest badKey = request("trino-analytics@EXAMPLE.COM");
    badKey.setKeyNameInSecret("service keytab");

    for (KeytabRequest req : List.of(badNamespace, badSecret, badKey)) {
      assertThrows(KeytabRequestPolicy.RefusedException.class, () -> KeytabRequestPolicy.check(req, ANALYTICS, true));
    }
  }

  private static KeytabRequest request(String principal) {
    KeytabRequest req = new KeytabRequest();
    req.setPrincipal(principal);
    req.setNamespace("analytics");
    req.setPodName("trino-0");
    req.setSecretName("keytab-trino-0");
    req.setKeyNameInSecret("service.keytab");
    req.setReleaseName("trino");
    return req;
  }

  private static K8sReleaseEntity release(String name) {
    K8sReleaseEntity release = new K8sReleaseEntity();
    release.setId(K8sReleaseEntity.idOf("analytics", name));
    release.setNamespace("analytics");
    release.setReleaseName(name);
    return release;
  }
}
