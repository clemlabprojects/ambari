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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.security.MasterKeyUnavailableException;
import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ViewConfigurationServiceTest {

  private static final EncryptionService MASTER_KEY = TestMasterKeys.encryptionWith("a-master-key");

  private static ViewConfigurationService service(ViewContext ctx, EncryptionService encryption) {
    return new ViewConfigurationService(ctx, new InClusterServiceAccount(Path.of("/nonexistent-sa"), name -> null),
        encryption);
  }

  @Test
  void roundTripKubeconfig() throws Exception {
    ViewContext ctx = mock(ViewContext.class);

    // ---- stub des propriétés ----
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    Map <String, String> properties = Map.of(
      "k8s.view.working.dir", tmp,
      "kubeconfig.path", tmp + "/config.yaml.enc",
      "kubeconfig.uploaded", "true"
    );
    when(ctx.getProperties()).thenReturn(properties);

    // ---- the view instance's settings live in its DataStore ----
    when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());

    ViewConfigurationService svc = service(ctx, MASTER_KEY);

    String yaml = "apiVersion: v1\nclusters: []\ncontexts: []\nusers: []\n";
    svc.saveKubeconfigFile(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
        "config.yaml"
    );

    assertEquals(yaml, svc.getRawKubeconfigContents()); // OK
    // clients get the kubeconfig as KDPS writes it again, never the saved text
    assertEquals(org.apache.ambari.view.k8s.utils.KubeconfigGuard.canonical(yaml), svc.getKubeconfigContents());
    assertNotNull(svc.getKubeconfigPath());          // OK
  }

  @TempDir
  Path workDir;

  private InMemoryDataStore store;

  private ViewConfigurationService serviceIn(Path dir) {
    return serviceIn(dir, MASTER_KEY);
  }

  private ViewConfigurationService serviceIn(Path dir, EncryptionService encryption) {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", dir.toString()));
    store = new InMemoryDataStore();
    when(ctx.getDataStore()).thenReturn(store);
    return service(ctx, encryption);
  }

  private InstanceSettings settings() {
    return new InstanceSettings(store, MASTER_KEY);
  }

  private static final String KUBECONFIG = "apiVersion: v1\nkind: Config\nclusters: []\n";

  @Test
  void withoutAMasterKeyAnUploadedKubeconfigIsNotStored() throws Exception {
    Path work = workDir.resolve("instance");
    ViewConfigurationService svc = serviceIn(work, TestMasterKeys.encryptionWithoutKey());

    assertFalse(svc.isMasterKeyAvailable());
    assertThrows(MasterKeyUnavailableException.class, () -> svc.saveKubeconfigFile(
        new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml"));

    assertFalse(Files.exists(work), "nothing written");
    assertFalse(svc.isConfigured());
    assertNull(svc.getKubeconfigPath());
  }

  @Test
  void withoutAMasterKeyNoPasswordIsStored() {
    ViewConfigurationService svc = serviceIn(workDir, TestMasterKeys.encryptionWithoutKey());

    assertThrows(MasterKeyUnavailableException.class,
        () -> svc.saveOpenShiftLogin("https://api.example.com:6443", "admin", "pw", null, false));
    assertThrows(MasterKeyUnavailableException.class,
        () -> svc.saveProxySettings(true, "http://proxy:3128", "u", "pw", ""));

    assertFalse(svc.isConfigured());
    assertNull(settings().get("openshift.username"));
    assertNull(settings().get("proxy.url"));
  }

  @Test
  void theKubeconfigIsEncryptedInAnOwnerOnlyFileAndDirectory() throws Exception {
    Path work = workDir.resolve("instance");
    ViewConfigurationService svc = serviceIn(work);

    java.io.File saved = svc.saveKubeconfigFile(
        new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved.toPath())));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(work)));
    assertFalse(Files.readString(saved.toPath()).contains("clusters"), "encrypted on disk");
    assertEquals(KUBECONFIG, svc.getRawKubeconfigContents());
  }

  @Test
  void aSavedKubeconfigWithACredentialCommandIsHandedOutWithoutIt() throws Exception {
    // A file saved before uploads were checked: it is on disk, so what clients get must be safe all the same.
    String withExec = "apiVersion: v1\nkind: Config\ncurrent-context: a\nclusters:\n- name: a\n  cluster: {server: 'https://a:6443'}\n"
        + "contexts:\n- name: a\n  context: {cluster: a, user: u}\n"
        + "users:\n- name: u\n  user:\n    token: t\n    exec: {command: /bin/sh, args: [-c, id]}\n";
    ViewConfigurationService svc = serviceIn(workDir);
    svc.saveKubeconfigFile(new ByteArrayInputStream(withExec.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");

    String handedOut = svc.getKubeconfigContents();
    assertFalse(handedOut.contains("exec"), handedOut);
    assertFalse(handedOut.contains("/bin/sh"), handedOut);
    assertTrue(handedOut.contains("token: \"t\"") || handedOut.contains("token: t"), "the token stays: " + handedOut);
    assertEquals(withExec, svc.getRawKubeconfigContents(), "the file as saved is what the context list reads");
  }

  @Test
  void aSavedTabIndentedJsonKubeconfigIsHandedOutAndAnUnreadableOneSaysWhy() throws Exception {
    // Saved by an earlier version, which accepted what the clients read: JSON indented with tabs (jq --tab).
    String json = "{\n\t\"kind\": \"Config\",\n\t\"current-context\": \"a\",\n"
        + "\t\"clusters\": [{\"name\": \"a\", \"cluster\": {\"server\": \"https://a:6443\"}}],\n"
        + "\t\"contexts\": [{\"name\": \"a\", \"context\": {\"cluster\": \"a\", \"user\": \"u\"}}],\n"
        + "\t\"users\": [{\"name\": \"u\", \"user\": {\"token\": \"t\"}}]\n}\n";
    ViewConfigurationService svc = serviceIn(workDir);
    svc.saveKubeconfigFile(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");

    io.fabric8.kubernetes.client.Config connected = io.fabric8.kubernetes.client.Config.fromKubeconfig(svc.getKubeconfigContents());
    assertEquals("https://a:6443/", connected.getMasterUrl());

    svc.saveKubeconfigFile(new ByteArrayInputStream("users: [unclosed".getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, svc::getKubeconfigContents,
        "the saved text is never handed out unread");
    assertTrue(e.getMessage().startsWith("The kubeconfig cannot be read"), e.getMessage());
  }

  @Test
  void aKubeconfigSavedInAnotherCharsetIsStoredAsTheTextItWasCheckedAsAndReadBack() throws Exception {
    // Windows-1252, with an accented name: not UTF-8.
    byte[] upload = (KUBECONFIG + "# Soci\u00e9t\u00e9\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));
    ViewConfigurationService svc = serviceIn(workDir);

    java.io.File saved = svc.saveKubeconfigFile(new ByteArrayInputStream(upload), "kubeconfig.yaml");

    // What the upload was checked as: the bytes read as UTF-8.
    String checked = new String(upload, StandardCharsets.UTF_8);
    assertEquals(checked, svc.getRawKubeconfigContents());
    EncryptionService.Decrypted stored = MASTER_KEY.decryptStored(Files.readAllBytes(saved.toPath()));
    assertArrayEquals(checked.getBytes(StandardCharsets.UTF_8), stored.plain());
    assertFalse(stored.reencrypt());
  }

  @Test
  void theOpenShiftCaIsWrittenOwnerOnly() throws Exception {
    ViewConfigurationService svc = serviceIn(workDir);

    svc.saveOpenShiftLogin("https://api.example.com:6443", "admin", "pw",
        "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n", false);

    Path ca = Path.of(settings().get("openshift.caPath"));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(ca)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workDir)));
    assertEquals("pw", settings().getSecret("openshift.password.enc"));
  }

  @Test
  void aKubeconfigEncryptedWithTheBuiltInPassphraseOfEarlierVersionsIsEncryptedAgainWithTheMasterKey() throws Exception {
    Path legacy = workDir.resolve("kubeconfig.yaml.enc");
    Files.write(legacy, TestMasterKeys.legacyCiphertext(KUBECONFIG));
    Files.setPosixFilePermissions(legacy, PosixFilePermissions.fromString("rw-r--r--"));
    ViewConfigurationService svc = serviceIn(workDir);
    svc.saveKubeconfigPath(legacy.toString());

    assertEquals(KUBECONFIG, svc.getRawKubeconfigContents());

    EncryptionService.Decrypted now = MASTER_KEY.decryptStored(Files.readAllBytes(legacy));
    assertEquals(KUBECONFIG, new String(now.plain(), StandardCharsets.UTF_8));
    assertFalse(now.reencrypt(), "encrypted with the master key now");
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(legacy)));
    assertEquals(KUBECONFIG, svc.getRawKubeconfigContents());
  }

  @Test
  void anEarlierKubeconfigFileIsMadeOwnerOnlyWhenTheViewStarts() throws Exception {
    Path enc = workDir.resolve("kubeconfig.yaml.enc");
    Files.write(enc, MASTER_KEY.encrypt(KUBECONFIG.getBytes(StandardCharsets.UTF_8)));
    Files.setPosixFilePermissions(enc, PosixFilePermissions.fromString("rw-r--r--"));
    Files.setPosixFilePermissions(workDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    serviceIn(workDir).saveKubeconfigPath(enc.toString());

    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
    when(ctx.getDataStore()).thenReturn(store);
    service(ctx, MASTER_KEY);

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(enc)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workDir)));
  }

  @Test
  void aPasswordEncryptedWithTheBuiltInPassphraseOfEarlierVersionsIsEncryptedAgainWithTheMasterKey() {
    ViewConfigurationService svc = serviceIn(workDir);
    String legacy = Base64.getEncoder().encodeToString(TestMasterKeys.legacyCiphertext("proxy-pw"));
    settings().put("proxy.password.enc", legacy);
    settings().put("proxy.enabled", "true");
    settings().put("proxy.url", "http://proxy:3128");
    settings().put("proxy.username", "u");

    // The proxy password is read through the instance settings, as ViewConfigurationService.outboundProxy reads it.
    assertEquals("proxy-pw", settings().getSecret("proxy.password.enc"));

    String stored = settings().get("proxy.password.enc");
    assertNotEquals(legacy, stored);
    // Saved again in the encrypted form itself (salt:iv:ciphertext), without an outer Base64.
    assertFalse(MASTER_KEY.decryptStored(stored.getBytes(StandardCharsets.UTF_8)).reencrypt());
    assertEquals("proxy-pw", settings().getSecret("proxy.password.enc"));
  }

  @Test
  void cachedValuesSealedWithTheBuiltInPassphraseOfEarlierVersionsAreSealedAgain() throws Exception {
    // Sealed values go through the master key of this server: AMBARI_SECURITY_MASTER_KEY of the test run.
    ViewConfigurationService svc = serviceIn(workDir);
    Path cache = Files.createDirectories(workDir.resolve("cache"));
    String legacyEnvelope = "{\"" + EncryptedEnvelope.FORMAT_KEY + "\":\"" + EncryptedEnvelope.FORMAT + "\",\""
        + EncryptedEnvelope.PAYLOAD_KEY + "\":\"" + Base64.getEncoder().encodeToString(
            TestMasterKeys.legacyCiphertext("{\"replicaCount\":3}")) + "\"}";
    Path file = Files.writeString(cache.resolve("values-op-2.json"), legacyEnvelope);

    assertEquals("{\"replicaCount\":3}", svc.readCacheFile(file.toString()));

    String now = Files.readString(file);
    assertNotEquals(legacyEnvelope, now);
    EncryptedEnvelope.Opened reread = EncryptedEnvelope.openStored(now);
    assertEquals("{\"replicaCount\":3}", reread.text());
    assertFalse(reread.reseal());
  }

  @Test
  void cachedValuesAreEncryptedInAnOwnerOnlyFile() throws Exception {
    ViewConfigurationService svc = serviceIn(workDir);
    String values = "{\"superset\":{\"secretKey\":\"Flask-Secret-751\"}}";

    String path = svc.writeCacheFile("values-op-1", values);

    Path file = Path.of(path);
    assertEquals(workDir.resolve("cache"), file.getParent());
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    assertFalse(Files.readString(file).contains("Flask-Secret-751"), "encrypted on disk");
    assertEquals(values, svc.readCacheFile(path));
  }

  @Test
  void valuesCachedInClearByAnEarlierVersionAreStillRead() throws Exception {
    ViewConfigurationService svc = serviceIn(workDir);
    Path cache = Files.createDirectories(workDir.resolve("cache"));
    Path legacy = Files.writeString(cache.resolve("values-op-0.json"), "{\"replicaCount\":2}");

    assertEquals("{\"replicaCount\":2}", svc.readCacheFile(legacy.toString()));
  }
}
