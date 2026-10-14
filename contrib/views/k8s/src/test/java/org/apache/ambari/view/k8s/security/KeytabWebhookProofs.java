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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Signs keytab requests the way the keytab webhook does, with a client certificate a view's internal CA issued. */
public final class KeytabWebhookProofs {

  private KeytabWebhookProofs() {
  }

  /** A view instance whose working directory, and so internal CA, is {@code workDir}. */
  public static ViewContext viewWorkingIn(Path workDir) {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    return ctx;
  }

  /** A client certificate the internal CA of the view working in {@code workDir} issues, with that common name. */
  public static WebHookConfigurationService.ClientCertificateMaterial issue(Path workDir, String commonName) {
    return new WebHookConfigurationService(viewWorkingIn(workDir), mock(KubernetesService.class))
        .issueClientCertificate(commonName, List.of(), List.of(), 30);
  }

  /** The webhook's own client certificate, issued by the internal CA of the view working in {@code workDir}. */
  public static WebHookConfigurationService.ClientCertificateMaterial issue(Path workDir) {
    return issue(workDir, WebHookBootstrap.CLIENT_CERTIFICATE_CN);
  }

  /** The headers the webhook sends with {@code signed}, signed at {@code at}. */
  public static MultivaluedMap<String, String> proof(WebHookConfigurationService.ClientCertificateMaterial client,
                                                     KeytabRequest signed, Instant at) throws Exception {
    String timestamp = Long.toString(at.getEpochSecond());
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(privateKey(client.clientPrivateKeyPem()));
    signer.update(KeytabWebhookAuthenticator.signingInput(timestamp, signed));
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.putSingle(KeytabWebhookAuthenticator.CERTIFICATE_HEADER,
        Base64.getEncoder().encodeToString(der(client.clientCertificatePem())));
    headers.putSingle(KeytabWebhookAuthenticator.TIMESTAMP_HEADER, timestamp);
    headers.putSingle(KeytabWebhookAuthenticator.SIGNATURE_HEADER, Base64.getEncoder().encodeToString(signer.sign()));
    return headers;
  }

  /** A request of the webhook for the pod {@code trino-0} of release {@code trino} in namespace {@code analytics}. */
  public static KeytabRequest trinoPodRequest() {
    KeytabRequest req = new KeytabRequest();
    req.setPrincipal("trino-analytics@EXAMPLE.COM");
    req.setNamespace("analytics");
    req.setPodName("trino-0");
    req.setSecretName("keytab-trino-0");
    req.setKeyNameInSecret("service.keytab");
    req.setReleaseName("trino");
    return req;
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
  }

  private static byte[] der(String pem) {
    return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
  }
}
