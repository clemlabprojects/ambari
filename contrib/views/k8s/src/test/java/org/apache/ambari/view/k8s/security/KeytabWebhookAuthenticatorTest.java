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

import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.apache.ambari.view.k8s.security.KeytabWebhookProofs.issue;
import static org.apache.ambari.view.k8s.security.KeytabWebhookProofs.proof;
import static org.apache.ambari.view.k8s.security.KeytabWebhookProofs.trinoPodRequest;
import static org.apache.ambari.view.k8s.security.KeytabWebhookProofs.viewWorkingIn;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The keytab webhook proves each request with the client certificate the view issued to it: the view accepts only a
 * certificate of its own CA given to the webhook, a recent signature, and a signature over the request as received.
 */
class KeytabWebhookAuthenticatorTest {

  /** The certificates are issued for 30 days from the real clock. */
  private static final Instant NOW = Instant.now();

  @TempDir
  Path viewDir;
  @TempDir
  Path otherViewDir;

  private WebHookConfigurationService.ClientCertificateMaterial webhook;
  private KeytabWebhookAuthenticator authenticator;

  @BeforeEach
  void issueTheWebhookCertificate() {
    webhook = issue(viewDir);
    authenticator = new KeytabWebhookAuthenticator(WebHookConfigurationService.certificateAuthorityPem(
        viewWorkingIn(viewDir)), WebHookBootstrap.CLIENT_CERTIFICATE_CN);
  }

  @Test
  void aRequestSignedWithTheWebhookCertificateIsAccepted() throws Exception {
    KeytabRequest req = trinoPodRequest();

    assertDoesNotThrow(() -> authenticator.authenticate(proof(webhook, req, NOW), req, NOW.plusSeconds(3)));
  }

  @Test
  void aRequestWithoutProofIsRefused() {
    MultivaluedMap<String, String> none = new MultivaluedHashMap<>();

    assertFalse(KeytabWebhookAuthenticator.claimsWebhook(none));
    assertThrows(KeytabWebhookAuthenticator.RefusedException.class,
        () -> authenticator.authenticate(none, trinoPodRequest(), NOW));
  }

  @Test
  void aCertificateOfAnotherCaIsRefused() throws Exception {
    KeytabRequest req = trinoPodRequest();
    MultivaluedMap<String, String> forged = proof(issue(otherViewDir), req, NOW);

    KeytabWebhookAuthenticator.RefusedException refused = assertThrows(KeytabWebhookAuthenticator.RefusedException.class,
        () -> authenticator.authenticate(forged, req, NOW));
    assertTrue(refused.getMessage().contains("internal CA"), refused.getMessage());
  }

  @Test
  void anotherCertificateOfTheSameCaIsRefused() throws Exception {
    // The CA also signs other client certificates; only the webhook's may ask for keytabs.
    KeytabRequest req = trinoPodRequest();
    MultivaluedMap<String, String> other = proof(issue(viewDir, "some-other-client"), req, NOW);

    assertThrows(KeytabWebhookAuthenticator.RefusedException.class, () -> authenticator.authenticate(other, req, NOW));
  }

  @Test
  void aRequestChangedAfterSigningIsRefused() throws Exception {
    MultivaluedMap<String, String> signed = proof(webhook, trinoPodRequest(), NOW);
    KeytabRequest changed = trinoPodRequest();
    changed.setSecretName("trino-ingress-tls");

    KeytabWebhookAuthenticator.RefusedException refused = assertThrows(KeytabWebhookAuthenticator.RefusedException.class,
        () -> authenticator.authenticate(signed, changed, NOW));
    assertTrue(refused.getMessage().contains("signature"), refused.getMessage());
  }

  @Test
  void anOldSignatureIsRefused() throws Exception {
    KeytabRequest req = trinoPodRequest();
    MultivaluedMap<String, String> old = proof(webhook, req, NOW.minus(Duration.ofMinutes(10)));

    assertThrows(KeytabWebhookAuthenticator.RefusedException.class, () -> authenticator.authenticate(old, req, NOW));
  }

  @Test
  void anExpiredCertificateIsRefused() throws Exception {
    KeytabRequest req = trinoPodRequest();
    Instant later = NOW.plus(Duration.ofDays(400));

    assertThrows(KeytabWebhookAuthenticator.RefusedException.class,
        () -> authenticator.authenticate(proof(webhook, req, later), req, later));
  }

  @Test
  void aViewWithoutCaAcceptsNoWebhook() throws Exception {
    KeytabRequest req = trinoPodRequest();

    assertThrows(KeytabWebhookAuthenticator.RefusedException.class,
        () -> new KeytabWebhookAuthenticator(null, WebHookBootstrap.CLIENT_CERTIFICATE_CN)
            .authenticate(proof(webhook, req, NOW), req, NOW));
  }

  @Test
  void aFieldWithALineBreakCannotBeSigned() {
    KeytabRequest req = trinoPodRequest();
    req.setPodName("trino-0\nkeytab-other");

    assertNull(KeytabWebhookAuthenticator.signingInput("1700000000", req));
  }

  // The webhook (main_test.go, TestSigningInputAndAccountPasswordMatchTheView) asserts the same values: the two sides
  // must sign the same text and derive the same password.

  @Test
  void theSignedTextIsTheOneTheWebhookSigns() {
    assertEquals("kdps-keytab-request\n1700000000\ntrino-analytics@EXAMPLE.COM\nanalytics\ntrino-0\nkeytab-trino-0\n"
            + "service.keytab\ntrino",
        new String(KeytabWebhookAuthenticator.signingInput("1700000000", trinoPodRequest()), StandardCharsets.UTF_8));
  }

  @Test
  void theAccountPasswordIsTheOneTheWebhookDerives() {
    String keyPem = "-----BEGIN PRIVATE KEY-----\n"
        + java.util.Base64.getEncoder().encodeToString("key-der-bytes".getBytes(StandardCharsets.UTF_8))
        + "\n-----END PRIVATE KEY-----\n";

    assertEquals("sfW3tc4mdQP4scL0FaKsgGbBmG5Jk-XjPfUxZx9zd0Y", KeytabWebhookAuthenticator.accountPassword(keyPem));
    assertNotEquals(KeytabWebhookAuthenticator.accountPassword(webhook.clientPrivateKeyPem()),
        KeytabWebhookAuthenticator.accountPassword(issue(otherViewDir).clientPrivateKeyPem()));
  }
}
