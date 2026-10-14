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

import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Ranger plugins of the deployed services read their Ranger Admin truststore password from a jceks file that the
 * view writes with Hadoop's credential provider API. The view ships only the part of hadoop-common that API needs, so
 * these tests write real jceks files and read them back the way the plugins do.
 */
class RangerCredentialWriterTest {

  @TempDir
  Path tmp;

  @Test
  void writesATruststorePasswordThePluginCanReadBack() throws Exception {
    KubernetesService kube = mock(KubernetesService.class);
    RangerCredentialWriter writer = new RangerCredentialWriter(kube);

    Map<String, String> props = writer.ensureJceksSecretForTruststorePassword("trino", "ranger-cred", "/etc/ranger/cred.jceks",
        null, "s3cr3t-pass".toCharArray(), Map.of("app", "trino"), Map.of("purpose", "ranger-ssl-password"));

    assertEquals("jceks://file/etc/ranger/cred.jceks", props.get("xasecure.policymgr.clientssl.truststore.credential.file"));
    assertEquals(RangerCredentialWriter.DEFAULT_ALIAS, props.get("xasecure.policymgr.clientssl.truststore.credential.alias"));

    Stored stored = captureStoredSecret(kube, "trino", "ranger-cred");
    assertArrayEquals("s3cr3t-pass".toCharArray(), readAlias(stored.jceks, RangerCredentialWriter.DEFAULT_ALIAS));
    assertEquals("trino", stored.labels.get("app"));
    assertEquals("ranger-credential", stored.labels.get("component"));
    assertEquals("ambari-k8s-view", stored.labels.get("managed-by"));
    assertEquals("ranger-ssl-password", stored.annotations.get("purpose"));
    assertEquals("jceks", stored.annotations.get("clemlab.com/credential-kind"));
  }

  @Test
  void theFileIsAJavaJceksKeystore() throws Exception {
    KubernetesService kube = mock(KubernetesService.class);
    new RangerCredentialWriter(kube).ensureJceksSecretForTruststorePassword("ns", "cred", "/etc/ranger/cred.jceks",
        "sslTrustStore", "pw".toCharArray(), null, null);

    byte[] jceks = captureStoredSecret(kube, "ns", "cred").jceks;
    // Hadoop's default keystore password when HADOOP_CREDSTORE_PASSWORD is not set, as on the plugin side.
    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    keyStore.load(new ByteArrayInputStream(jceks), "none".toCharArray());
    assertTrue(keyStore.isKeyEntry("ssltruststore"), "Hadoop keeps aliases in lower case");
  }

  @Test
  void writesEveryAliasGivenAndSkipsTheBlankOnes() throws Exception {
    KubernetesService kube = mock(KubernetesService.class);
    Map<String, char[]> aliases = new LinkedHashMap<>();
    aliases.put("ssltruststore", "trust-pw".toCharArray());
    aliases.put("sslkeystore", "key-pw".toCharArray());
    aliases.put(" ", "ignored".toCharArray());
    aliases.put("nopassword", null);

    new RangerCredentialWriter(kube, "kdps").ensureJceksSecretWithAliases("ns", "multi", "/etc/ranger/cred.jceks",
        aliases, null, null);

    Stored stored = captureStoredSecret(kube, "ns", "multi");
    assertArrayEquals("trust-pw".toCharArray(), readAlias(stored.jceks, "ssltruststore"));
    assertArrayEquals("key-pw".toCharArray(), readAlias(stored.jceks, "sslkeystore"));
    assertEquals(List.of("sslkeystore", "ssltruststore"), aliasesOf(stored.jceks));
    assertEquals("kdps", stored.labels.get("managed-by"));
  }

  @Test
  void refusesAnEmptyAliasSetWithoutWritingASecret() {
    KubernetesService kube = mock(KubernetesService.class);
    RangerCredentialWriter writer = new RangerCredentialWriter(kube);

    assertThrows(IllegalArgumentException.class,
        () -> writer.ensureJceksSecretWithAliases("ns", "multi", "/etc/ranger/cred.jceks", Map.of(), null, null));
    verify(kube, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyString(), any(byte[].class),
        anyMap(), anyMap(), any());
  }

  // ----- helpers -----------------------------------------------------------

  private record Stored(byte[] jceks, Map<String, String> labels, Map<String, String> annotations) {
  }

  @SuppressWarnings("unchecked")
  private static Stored captureStoredSecret(KubernetesService kube, String namespace, String secretName) {
    ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<Map<String, String>> annotations = ArgumentCaptor.forClass(Map.class);
    verify(kube).createOrUpdateOpaqueSecret(eq(namespace), eq(secretName), eq(RangerCredentialWriter.DEFAULT_KEY_NAME),
        data.capture(), labels.capture(), annotations.capture(), eq(Boolean.TRUE));
    assertTrue(data.getValue().length > 0, "the jceks must not be empty");
    return new Stored(data.getValue(), labels.getValue(), annotations.getValue());
  }

  /** Reads an alias back through the Hadoop credential provider, as the Ranger plugin does from the mounted file. */
  private char[] readAlias(byte[] jceks, String alias) throws Exception {
    CredentialProvider.CredentialEntry entry = providerFor(jceks).getCredentialEntry(alias);
    assertNotNull(entry, "alias " + alias + " is missing");
    return entry.getCredential();
  }

  private List<String> aliasesOf(byte[] jceks) throws Exception {
    return providerFor(jceks).getAliases().stream().sorted().toList();
  }

  private CredentialProvider providerFor(byte[] jceks) throws Exception {
    Path file = Files.createTempFile(tmp, "cred-", ".jceks");
    Files.write(file, jceks);
    Configuration conf = new Configuration(false);
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, "jceks://file" + file.toAbsolutePath());
    List<CredentialProvider> providers = CredentialProviderFactory.getProviders(conf);
    assertEquals(1, providers.size());
    return providers.get(0);
  }
}
