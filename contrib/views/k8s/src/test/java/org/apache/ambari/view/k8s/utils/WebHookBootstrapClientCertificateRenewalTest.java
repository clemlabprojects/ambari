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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.security.KeytabWebhookAuthenticator;
import org.apache.ambari.view.k8s.security.KeytabWebhookProofs;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The keytab webhook's client certificate is renewed before it expires, without waiting for a deploy: the view checks
 * it on every keytab request, so an expired one would make the webhook refuse every new Kerberized pod. The renewal
 * keeps the key, so the webhook's Ambari password, derived from it, does not change and no administrator is needed.
 */
@EnableKubernetesMockClient(crud = true)
class WebHookBootstrapClientCertificateRenewalTest {

  private static final String NS = "ambari-mutating-webhooks";
  private static final String SECRET = WebHookBootstrap.KEYTAB_WEBHOOK + "-client-tls";

  @TempDir
  Path workDir;

  KubernetesClient client;

  private ViewContext ctx;
  private WebHookConfigurationService service;

  @BeforeEach
  void setUp() {
    ctx = KeytabWebhookProofs.viewWorkingIn(workDir);
    KubernetesService kubernetes = mock(KubernetesService.class);
    when(kubernetes.getClient()).thenReturn(client);
    service = new WebHookConfigurationService(ctx, kubernetes);
  }

  /** Writes the client certificate Secret with a certificate valid for {@code validityDays}, as a deploy does. */
  private void issue(int validityDays) {
    service.ensureOrRotateClientMtlsSecret(NS, SECRET, WebHookBootstrap.CLIENT_CERTIFICATE_CN, List.of(), List.of(),
        validityDays, 0, Map.of());
  }

  private String data(String key) {
    Secret secret = client.secrets().inNamespace(NS).withName(SECRET).get();
    return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
  }

  private WebHookConfigurationService.ClientCertificateMaterial mounted() {
    return new WebHookConfigurationService.ClientCertificateMaterial(data("client.crt"), data("client.key"),
        data("ca.crt"));
  }

  /** Whether the view accepts a keytab request the webhook signs at {@code at} with what its Secret holds. */
  private boolean accepted(Instant at) throws Exception {
    KeytabRequest request = KeytabWebhookProofs.trinoPodRequest();
    try {
      new KeytabWebhookAuthenticator(WebHookConfigurationService.certificateAuthorityPem(ctx),
          WebHookBootstrap.CLIENT_CERTIFICATE_CN)
          .authenticate(KeytabWebhookProofs.proof(mounted(), request, at), request, at);
      return true;
    } catch (KeytabWebhookAuthenticator.RefusedException e) {
      return false;
    }
  }

  @Test
  void aCertificateNearExpiryIsRenewedForTheSameKey() throws Exception {
    issue(60);
    String key = data("client.key");
    String password = KeytabWebhookAuthenticator.accountPassword(key);
    Instant afterExpiry = Instant.now().plus(100, ChronoUnit.DAYS);
    assertFalse(accepted(afterExpiry), "the certificate issued for 60 days is still accepted after 100");

    assertTrue(WebHookBootstrap.renewClientCertificate(ctx, service));

    Instant notAfter = service.clientCertificateNotAfter(NS, SECRET);
    assertTrue(notAfter.isAfter(Instant.now().plus(300, ChronoUnit.DAYS)), "renewed until " + notAfter);
    // The key is kept byte for byte: the webhook's Ambari password does not change under it.
    assertEquals(key, data("client.key"));
    assertEquals(password, KeytabWebhookAuthenticator.accountPassword(data("client.key")));
    assertTrue(accepted(Instant.now()));
    assertTrue(accepted(afterExpiry), "the renewed certificate is refused after the earlier one's expiry");
  }

  @Test
  void aCertificateWithEnoughTimeLeftIsKept() {
    issue(WebHookBootstrap.CLIENT_CERTIFICATE_VALIDITY_DAYS);
    String certificate = data("client.crt");

    assertFalse(WebHookBootstrap.renewClientCertificate(ctx, service));

    assertEquals(certificate, data("client.crt"));
  }

  @Test
  void noSecretIsCreatedWhereTheWebhookWasNeverSetUp() {
    assertFalse(WebHookBootstrap.renewClientCertificate(ctx, service));

    assertNull(client.secrets().inNamespace(NS).withName(SECRET).get());
  }

  @Test
  void aKeyThatDoesNotMatchItsCertificateIsReplaced() throws Exception {
    issue(60);
    String unrelated = KeytabWebhookProofs.issue(workDir).clientPrivateKeyPem();
    Secret secret = client.secrets().inNamespace(NS).withName(SECRET).get();
    secret.getData().put("client.key",
        Base64.getEncoder().encodeToString(unrelated.getBytes(StandardCharsets.UTF_8)));
    client.secrets().inNamespace(NS).resource(secret).update();

    assertTrue(WebHookBootstrap.renewClientCertificate(ctx, service));

    assertNotEquals(unrelated, data("client.key"));
    assertTrue(accepted(Instant.now()));
  }

  @Test
  void aViewWithoutKubernetesAccessRenewsNothing() {
    assertFalse(WebHookBootstrap.renewClientCertificateIfDue(ctx, null));
  }
}
