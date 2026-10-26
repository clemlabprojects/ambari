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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Company CAs the view keeps for every cluster: the private key encrypted in its settings, those of earlier versions
 * imported once from the default cluster, a ClusterIssuer written on the cluster an administrator names.
 */
@EnableKubernetesMockClient(crud = true)
class CaRegistryServiceTest {

  KubernetesClient client;
  KubernetesMockServer server;

  @TempDir
  Path workDir;

  PerUserViewContext view;
  KubernetesService defaultCluster;
  String caCertPem;
  String caKeyPem;

  /**
   * An issuing CA: a self-signed certificate with basicConstraints CA=true, and its key, in PEM. An EC key in PKCS#8,
   * the form KDPS signs leaf certificates with.
   */
  static String[] issuingCa(String cn) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    KeyPair keys = generator.generateKeyPair();
    X500Name name = new X500Name("CN=" + cn);
    long now = System.currentTimeMillis();
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
        new Date(now - 60_000L), new Date(now + 400L * 86_400_000L), name, keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
        builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
    return new String[] {pem(cert), pem(new org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(keys.getPrivate(), null))};
  }

  private static String pem(Object o) throws Exception {
    StringWriter out = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
      writer.writeObject(o);
    }
    return out.toString();
  }

  @BeforeEach
  void setUp() throws Exception {
    view = new PerUserViewContext("ca-registry", workDir).as("lead");
    defaultCluster = new KubernetesService(view.context, client, mock(HelmClient.class), true);
    String[] ca = issuingCa("Corp Issuing CA");
    caCertPem = ca[0];
    caKeyPem = ca[1];
  }

  private CaRegistryService registry(KubernetesService cluster) {
    return new CaRegistryService(view.context, cluster);
  }

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aCaIsKeptByTheViewItsKeyEncryptedAndFoundByItsNameOrItsIdentifier() {
    CaRegistryService.CaEntry uploaded = registry(defaultCluster).upload("corp", caCertPem, caKeyPem, "Corp root");

    assertEquals("ca-registry-corp", uploaded.secretName());
    assertEquals(List.of("corp"), registry(defaultCluster).list().stream().map(CaRegistryService.CaEntry::caName).toList());
    assertEquals("CN=Corp Issuing CA", registry(defaultCluster).get("corp").subject());
    assertEquals("corp", registry(defaultCluster).get("ca-registry-corp").caName(), "the wizard picker's value");
    assertEquals(caKeyPem, registry(defaultCluster).material("ca-registry-corp").caPrivateKeyPem());
    String keyBody = caKeyPem.split("\n")[1];
    assertTrue(view.dataStore.findAll(KdpsSettingEntity.class, null).stream()
        .noneMatch(e -> e.getValue() != null && e.getValue().contains(keyBody)), "the key is never kept in clear");
    assertTrue(client.secrets().inAnyNamespace().list().getItems().isEmpty(), "nothing written to a cluster");
  }

  @Test
  void theCasOfAnEarlierVersionAreImportedOnceAndTheirSecretsLeftInPlace() {
    client.secrets().inNamespace("ambari-pki").resource(new SecretBuilder()
        .withNewMetadata().withName("ca-registry-legacy").withNamespace("ambari-pki")
        .addToLabels(CaRegistryService.LABEL_RESOURCE_TYPE, CaRegistryService.RESOURCE_TYPE_ISSUING_CA)
        .addToLabels(CaRegistryService.LABEL_VIEW_INSTANCE, "ca-registry")
        .addToAnnotations("ambari.clemlab.com/ca-name", "legacy")
        .addToAnnotations("ambari.clemlab.com/uploaded-by", "admin")
        .endMetadata()
        .withType("kubernetes.io/tls")
        .addToData("tls.crt", b64(caCertPem)).addToData("tls.key", b64(caKeyPem))
        .build()).create();

    assertEquals(List.of("legacy"), registry(defaultCluster).list().stream().map(CaRegistryService.CaEntry::caName).toList());
    assertEquals(1, registry(defaultCluster).list().size(), "imported once");
    assertEquals(caKeyPem, registry(defaultCluster).material("legacy").caPrivateKeyPem());
    assertNotNull(client.secrets().inNamespace("ambari-pki").withName("ca-registry-legacy").get(), "left in place");

    registry(defaultCluster).delete("legacy");

    assertTrue(registry(defaultCluster).list().isEmpty());
    assertNull(client.secrets().inNamespace("ambari-pki").withName("ca-registry-legacy").get(),
        "deleting the CA deletes the Secret an earlier version kept it in");
  }

  @Test
  void theCasDoNotDependOnWhatTheKubernetesAccountMayRead() {
    KubernetesService refused = mock(KubernetesService.class);
    when(refused.getClient()).thenThrow(new KubernetesClientException("secrets is forbidden", 403, null));
    registry(refused).upload("corp", caCertPem, caKeyPem, "");

    assertEquals(List.of("corp"), registry(refused).list().stream().map(CaRegistryService.CaEntry::caName).toList());
    assertNotEquals("true", new org.apache.ambari.view.k8s.store.InstanceSettings(view.context)
        .get(CaRegistryService.IMPORTED_KEY), "the import is tried again later");
    assertThrows(IllegalArgumentException.class, () -> registry(refused).get("unknown"));
  }

  @Test
  void promotingACaWritesItOnTheClusterNamedOnly() {
    registry(defaultCluster).upload("corp", caCertPem, caKeyPem, "");
    KubernetesService prod = mock(KubernetesService.class);
    when(prod.cluster()).thenReturn(org.apache.ambari.view.k8s.service.cluster.ClusterRef.of("prod"));

    String issuer = registry(defaultCluster).promote("corp", prod);

    assertEquals("clemlab-corp", issuer);
    org.mockito.Mockito.verify(prod).applyCertManagerClusterIssuerFromCa("clemlab-corp", "ca-registry-corp", caCertPem,
        caKeyPem);
    assertNull(client.secrets().inNamespace("cert-manager").withName("ca-registry-corp").get(),
        "nothing written on the default cluster");
  }

  @Test
  void theClusterIssuersCaIsWrittenInCertManagersNamespace() {
    defaultCluster.applyCertManagerClusterIssuerFromCa("clemlab-corp", "ca-registry-corp", caCertPem, caKeyPem);

    Secret written = client.secrets().inNamespace("cert-manager").withName("ca-registry-corp").get();
    assertEquals("kubernetes.io/tls", written.getType());
    assertEquals(caKeyPem, new String(Base64.getDecoder().decode(written.getData().get("tls.key")), StandardCharsets.UTF_8));
  }
}
