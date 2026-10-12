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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.k8s.model.ConnectionHealth;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KDPS connected with the Ambari pod's ServiceAccount, against an API server over TLS: the client trusts the pod's CA,
 * sends the pod's token, and sends the renewed one as soon as the kubelet writes it, without being rebuilt.
 */
class KubernetesServiceInClusterTest {

  @TempDir
  Path workDir;
  @TempDir
  Path mount;

  HttpsServer apiServer;
  final List<String> authorizations = new CopyOnWriteArrayList<>();
  SSLContext defaultSslContext;
  PerUserViewContext view;
  InClusterServiceAccount pod;

  @BeforeEach
  void setUp() throws Exception {
    // Connecting installs the cluster CA into the JVM's default SSL context; put the original back afterwards.
    defaultSslContext = SSLContext.getDefault();

    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    X509Certificate certificate = selfSigned(keys);

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry("api", keys.getPrivate(), "changeit".toCharArray(), new Certificate[] {certificate});
    KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, "changeit".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(keyManagers.getKeyManagers(), null, null);

    apiServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    apiServer.setHttpsConfigurator(new HttpsConfigurator(tls));
    apiServer.createContext("/", exchange -> {
      authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      byte[] body = "{\"apiVersion\":\"v1\",\"kind\":\"NamespaceList\",\"metadata\":{},\"items\":[]}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    apiServer.start();

    Files.writeString(mount.resolve("token"), InClusterServiceAccountTest.boundToken("first"));
    Files.writeString(mount.resolve("ca.crt"), pem(certificate));
    Files.writeString(mount.resolve("namespace"), "kdps");
    pod = new InClusterServiceAccount(mount, Map.of(
        "KUBERNETES_SERVICE_HOST", "127.0.0.1",
        "KUBERNETES_SERVICE_PORT", String.valueOf(apiServer.getAddress().getPort()))::get);

    view = new PerUserViewContext("in-cluster-client-test", workDir);
  }

  @AfterEach
  void tearDown() {
    if (apiServer != null) {
      apiServer.stop(0);
    }
    SSLContext.setDefault(defaultSslContext);
  }

  /** The service of the view instance, built before anyone configured it, connecting with {@code serviceAccount}. */
  private KubernetesService service(InClusterServiceAccount serviceAccount) {
    view.as("ops1");
    KubernetesService svc = new KubernetesService(view.context);
    svc.podServiceAccount = serviceAccount;
    svc.retiredClientGraceSeconds = 0;
    return svc;
  }

  private void adminChoosesTheInClusterMode() {
    view.as("alice");
    new ViewConfigurationService(view.context, pod).saveInClusterServiceAccount();
    view.as("ops1");
  }

  @Test
  void connectsWithThePodTokenAndUsesTheRenewedOneWithoutReconnecting() throws Exception {
    KubernetesService svc = service(pod);
    assertNull(svc.getClient(), "not configured yet");
    adminChoosesTheInClusterMode();

    assertTrue(svc.forceReloadClient());
    assertEquals(ConnectionHealth.State.CONNECTED, svc.pingCluster().getState());
    assertEquals("Bearer " + InClusterServiceAccountTest.boundToken("first"), authorizations.get(authorizations.size() - 1));
    KubernetesClient client = svc.getClient();
    assertEquals("https://127.0.0.1:" + apiServer.getAddress().getPort() + "/", client.getConfiguration().getMasterUrl());
    assertEquals("kdps", client.getNamespace());

    // The kubelet renews the projected token in the pod.
    Files.writeString(mount.resolve("token"), InClusterServiceAccountTest.boundToken("renewed"));

    assertEquals(ConnectionHealth.State.CONNECTED, svc.pingCluster().getState());
    assertEquals("Bearer " + InClusterServiceAccountTest.boundToken("renewed"),
        authorizations.get(authorizations.size() - 1));
    assertSame(client, svc.getClient(), "same client, no reconnection");
    assertTrue(svc.getConfigurationService().getKubeconfigContents()
        .contains(InClusterServiceAccountTest.boundToken("renewed")), "Helm gets the renewed token too");
  }

  @Test
  void anUploadedKubeconfigHoldingAServiceAccountTokenConnectsWithIt() throws Exception {
    // Written as the chart's OPERATIONS.md shows, from a kubernetes.io/service-account-token Secret.
    String token = InClusterServiceAccountTest.jwt("{\"sub\":\"system:serviceaccount:kdps-system:kdps-deployer\"}");
    String kubeconfig = String.join("\n",
        "apiVersion: v1",
        "kind: Config",
        "clusters:",
        "- name: cluster",
        "  cluster:",
        "    server: https://127.0.0.1:" + apiServer.getAddress().getPort(),
        "    certificate-authority-data: " + Base64.getEncoder().encodeToString(
            Files.readAllBytes(mount.resolve("ca.crt"))),
        "users:",
        "- name: kdps-deployer",
        "  user:",
        "    token: " + token,
        "contexts:",
        "- name: kdps-deployer",
        "  context:",
        "    cluster: cluster",
        "    user: kdps-deployer",
        "    namespace: data",
        "current-context: kdps-deployer",
        "");
    KubernetesService svc = service(pod);
    view.as("alice");
    new ViewConfigurationService(view.context, pod).saveKubeconfigFile(
        new java.io.ByteArrayInputStream(kubeconfig.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
    view.as("ops1");

    assertTrue(svc.forceReloadClient());
    assertEquals(ConnectionHealth.State.CONNECTED, svc.pingCluster().getState());
    assertEquals("Bearer " + token, authorizations.get(authorizations.size() - 1));
    assertEquals("data", svc.getClient().getNamespace());
    assertNull(svc.getClient().getConfiguration().getOauthTokenProvider(), "the uploaded token as it is");
  }

  @Test
  void aPodWithoutServiceAccountIsConfiguredButNotConnected() {
    KubernetesService svc = service(new InClusterServiceAccount(mount.resolve("absent"), name -> null));
    adminChoosesTheInClusterMode();

    assertFalse(svc.forceReloadClient());
    ConnectionHealth health = svc.pingCluster();
    assertEquals(ConnectionHealth.State.UNREACHABLE, health.getState(), "disconnected, not unconfigured");
    assertThrows(KubernetesService.NotConnectedException.class, svc::countNodes);
    assertTrue(authorizations.isEmpty(), "nothing was sent anywhere");
  }

  private static X509Certificate selfSigned(KeyPair keys) throws Exception {
    X500Name name = new X500Name("CN=kube-apiserver");
    long now = System.currentTimeMillis();
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
        new Date(now - 60_000L), new Date(now + 3_600_000L), name, keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
        new GeneralName(GeneralName.iPAddress, "127.0.0.1"), new GeneralName(GeneralName.dNSName, "localhost")}));
    return new JcaX509CertificateConverter().getCertificate(
        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
  }

  private static String pem(X509Certificate certificate) throws Exception {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }
}
