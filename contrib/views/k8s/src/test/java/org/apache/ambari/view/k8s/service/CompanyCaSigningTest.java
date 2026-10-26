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

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An ingress certificate signed by a Company CA: whatever the kind and form of the CA's key, and whatever the parts of
 * its name, the leaf chains to the CA.
 */
@EnableKubernetesMockClient(crud = true)
class CompanyCaSigningTest {

  static KubernetesClient client;
  private static final String NS = "analytics";

  private WebHookConfigurationService webhooks;

  @BeforeEach
  void setUp() throws Exception {
    if (client.namespaces().withName(NS).get() == null) {
      client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NS).endMetadata().build()).create();
    }
    Path workDir = Files.createTempDirectory("kdps-company-ca");
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getInstanceName()).thenReturn("company-ca");
    when(ctx.getUsername()).thenReturn("admin");
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    when(ctx.getAmbariProperty("k8s.view.webhook.ca.dir")).thenReturn(workDir.resolve("ca").toString());
    when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());
    webhooks = new WebHookConfigurationService(ctx, new KubernetesService(ctx, client, mock(HelmClient.class), true));
  }

  /** A CA of the given key kind and name, with its key written in the given form. */
  static String[] ca(String keyKind, String name, String keyForm) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(keyKind);
    generator.initialize("RSA".equals(keyKind) ? 2048 : 256);
    KeyPair keys = generator.generateKeyPair();
    X500Name dn = new X500Name(name);
    long now = System.currentTimeMillis();
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now),
        new Date(now - 60_000L), new Date(now + 400L * 86_400_000L), dn, keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(
        new JcaContentSignerBuilder("RSA".equals(keyKind) ? "SHA256withRSA" : "SHA256withECDSA").build(keys.getPrivate())));
    if ("pkcs8".equals(keyForm)) {
      return new String[] {pem(cert), pem(new JcaPKCS8Generator(keys.getPrivate(), null))};
    }
    if ("RSA".equals(keyKind)) {
      return new String[] {pem(cert), pem(keys.getPrivate())};
    }
    // As OpenSSL writes an EC key: its curve in an "EC PARAMETERS" block, then the SEC1 key with curve and public point.
    org.bouncycastle.asn1.ASN1ObjectIdentifier curve = org.bouncycastle.asn1.sec.SECObjectIdentifiers.secp256r1;
    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo publicInfo =
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded());
    org.bouncycastle.asn1.sec.ECPrivateKey sec1 = new org.bouncycastle.asn1.sec.ECPrivateKey(256,
        ((java.security.interfaces.ECPrivateKey) keys.getPrivate()).getS(), publicInfo.getPublicKeyData(),
        new org.bouncycastle.asn1.x9.X962Parameters(curve));
    return new String[] {pem(cert), pem(new org.bouncycastle.util.io.pem.PemObject("EC PARAMETERS", curve.getEncoded()))
        + pem(new org.bouncycastle.util.io.pem.PemObject("EC PRIVATE KEY", sec1.getEncoded()))};
  }

  private static String pem(Object o) throws Exception {
    StringWriter out = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
      writer.writeObject(o);
    }
    return out.toString();
  }

  private static X509Certificate firstCertificate(String pem) throws Exception {
    return (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificates(new ByteArrayInputStream(pem.getBytes())).iterator().next();
  }

  private void assertLeafChainsToTheCa(String keyKind, String keyForm, String pemHeader) throws Exception {
    String[] ca = ca(keyKind, "C=FR, O=Corp, OU=PKI, CN=Corp Issuing CA", keyForm);
    assertTrue(ca[1].contains(pemHeader), ca[1].lines().findFirst().orElse(""));
    String secret = "leaf-" + keyKind.toLowerCase() + "-" + keyForm;

    webhooks.issueIngressTlsSecret(NS, secret, "trino.apps.example.com", List.of(), 90,
        new WebHookConfigurationService.CertificateAuthorityMaterial(ca[0], ca[1], "test"), Map.of(), false, false);

    Secret written = client.secrets().inNamespace(NS).withName(secret).get();
    X509Certificate leaf = firstCertificate(new String(Base64.getDecoder().decode(written.getData().get("tls.crt"))));
    X509Certificate caCert = firstCertificate(ca[0]);
    assertEquals(caCert.getSubjectX500Principal(), leaf.getIssuerX500Principal(), "the leaf names its CA as encoded");
    leaf.verify(caCert.getPublicKey());
  }

  @Test
  void aCaWithAnRsaKeyInPkcs8SignsALeafThatChainsToIt() throws Exception {
    assertLeafChainsToTheCa("RSA", "pkcs8", "BEGIN PRIVATE KEY");
  }

  @Test
  void aCaWithAnRsaKeyInPkcs1SignsALeafThatChainsToIt() throws Exception {
    assertLeafChainsToTheCa("RSA", "traditional", "BEGIN RSA PRIVATE KEY");
  }

  @Test
  void aCaWithAnEcKeyInPkcs8SignsALeafThatChainsToIt() throws Exception {
    assertLeafChainsToTheCa("EC", "pkcs8", "BEGIN PRIVATE KEY");
  }

  @Test
  void aCaWithAnEcKeyInSec1SignsALeafThatChainsToIt() throws Exception {
    assertLeafChainsToTheCa("EC", "traditional", "BEGIN EC PRIVATE KEY");
  }

  @Test
  void aKeyThatIsNotTheCertificatesIsRefused() throws Exception {
    String[] ca = ca("RSA", "CN=Corp Issuing CA", "pkcs8");
    String[] other = ca("RSA", "CN=Other", "pkcs8");

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> WebHookConfigurationService.checkCaKeyMatches(ca[0], other[1]));
    assertTrue(refused.getMessage().contains("does not belong to the certificate"), refused.getMessage());
    WebHookConfigurationService.checkCaKeyMatches(ca[0], ca[1]);
  }

  @Test
  void anEncryptedKeyIsRefusedWithWhatToDo() throws Exception {
    String[] ca = ca("RSA", "CN=Corp Issuing CA", "pkcs8");
    java.security.PrivateKey key = WebHookConfigurationService.parsePrivateKeyFromPem(ca[1]);
    String encrypted = pem(new JcaPKCS8Generator(key,
        new org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(JcaPKCS8Generator.AES_256_CBC)
            .setPassword("secret".toCharArray()).build()));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> WebHookConfigurationService.checkCaKeyMatches(ca[0], encrypted));
    assertTrue(refused.getMessage().contains("without a passphrase"), refused.getMessage());
  }
}
