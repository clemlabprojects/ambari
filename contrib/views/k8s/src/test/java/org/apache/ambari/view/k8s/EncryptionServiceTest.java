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

import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

  private final EncryptionService svc = TestMasterKeys.encryptionWith("a-master-key");

  @Test
  void encryptThenDecryptReturnsOriginal() {
    String secret = "hunter2!";
    byte[] enc = svc.encrypt(secret.getBytes(StandardCharsets.UTF_8));

    // ciphertext must differ from plaintext
    assertNotEquals(secret, new String(enc, StandardCharsets.UTF_8));

    byte[] dec = svc.decrypt(enc);
    assertEquals(secret, new String(dec, StandardCharsets.UTF_8));
    assertFalse(svc.decryptStored(enc).reencrypt(), "encrypted with the master key: nothing to do");
  }

  @Test
  void decryptOfNonBase64Throws() {
    assertThrows(IllegalArgumentException.class,
        () -> svc.decrypt("$$not‑base64$$".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void withoutAMasterKeyNothingIsEncryptedAndTheServiceStillBuilds() {
    EncryptionService noKey = assertDoesNotThrow(TestMasterKeys::encryptionWithoutKey);

    assertFalse(noKey.isMasterKeyAvailable());
    MasterKeyUnavailableException e = assertThrows(MasterKeyUnavailableException.class,
        () -> noKey.encrypt("hunter2!".getBytes(StandardCharsets.UTF_8)));
    assertTrue(e.getMessage().contains("ambari-server setup-security"), e.getMessage());
    assertTrue(e.getMessage().contains("AMBARI_SECURITY_MASTER_KEY"), e.getMessage());
    assertThrows(MasterKeyUnavailableException.class, noKey::requireMasterKey);
    // Not even data written by an earlier version: there is no key to encrypt it again with.
    assertThrows(MasterKeyUnavailableException.class, () -> noKey.decrypt(TestMasterKeys.legacyCiphertext("x")));
  }

  @Test
  void aMissingMasterKeyIsAnswered412WithWhatToDo() {
    Response r = new MasterKeyUnavailableException().getResponse();

    assertEquals(412, r.getStatus());
    String body = String.valueOf(r.getEntity());
    assertTrue(body.contains("\"code\":\"" + MasterKeyUnavailableException.CODE + "\""), body);
    assertTrue(body.contains("ambari-server setup-security"), body);
  }

  @Test
  void dataEncryptedWithTheBuiltInPassphraseOfEarlierVersionsIsReadAndFlaggedForReencryption() {
    byte[] legacy = TestMasterKeys.legacyCiphertext("kubeconfig: legacy");

    EncryptionService.Decrypted read = svc.decryptStored(legacy);

    assertEquals("kubeconfig: legacy", new String(read.plain(), StandardCharsets.UTF_8));
    assertTrue(read.reencrypt());
    byte[] again = svc.encrypt(read.plain());
    EncryptionService.Decrypted reread = svc.decryptStored(again);
    assertEquals("kubeconfig: legacy", new String(reread.plain(), StandardCharsets.UTF_8));
    assertFalse(reread.reencrypt());
    assertThrows(IllegalStateException.class,
        () -> TestMasterKeys.encryptionWith("another-master-key").decrypt(again),
        "encrypted with the master key, not with the built-in passphrase any more");
  }

  @Test
  void base64OnlyDataOfEarlyVersionsIsReadAndFlaggedForReencryption() {
    byte[] early = Base64.getEncoder().encode("token-123".getBytes(StandardCharsets.UTF_8));

    EncryptionService.Decrypted read = svc.decryptStored(early);

    assertEquals("token-123", new String(read.plain(), StandardCharsets.UTF_8));
    assertTrue(read.reencrypt());
  }

  @Test
  void dataEncryptedWithAnotherMasterKeyIsRefusedClearly() {
    byte[] other = TestMasterKeys.encryptionWith("another-master-key").encrypt("s3cr3t".getBytes(StandardCharsets.UTF_8));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.decrypt(other));
    assertTrue(e.getMessage().contains("another key"), e.getMessage());
  }

  @Test
  void equalValuesEncryptedByTheSharedServiceGiveDifferentCiphertexts() {
    byte[] first = svc.encrypt("secret-one".getBytes(StandardCharsets.UTF_8));
    byte[] second = svc.encrypt("secret-one".getBytes(StandardCharsets.UTF_8));

    // Whoever reads the database must not see which credentials are the same.
    assertNotEquals(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
    String[] a = new String(first, StandardCharsets.UTF_8).split(":");
    String[] b = new String(second, StandardCharsets.UTF_8).split(":");
    assertNotEquals(a[1], b[1], "a new IV each time");
    assertEquals("secret-one", new String(svc.decrypt(first), StandardCharsets.UTF_8));
    assertEquals("secret-one", new String(svc.decrypt(second), StandardCharsets.UTF_8));
  }

  @Test
  void readingAValueWithAnotherSaltDoesNotChangeTheKeyNewValuesAreEncryptedWith() {
    svc.decryptStored(TestMasterKeys.legacyCiphertext("legacy"));
    svc.decrypt(TestMasterKeys.encryptionWith("a-master-key").encrypt("other salt".getBytes(StandardCharsets.UTF_8)));

    byte[] enc = svc.encrypt("after".getBytes(StandardCharsets.UTF_8));

    assertEquals("after", new String(TestMasterKeys.encryptionWith("a-master-key").decrypt(enc), StandardCharsets.UTF_8));
  }

  @Test
  void aLongTextInASingleByteCharsetIsDecrypted() {
    // A kubeconfig saved as Windows-1252, as an earlier version stored it: not UTF-8, but text.
    byte[] windows1252 = ("apiVersion: v1\nkind: Config\n# Soci\u00e9t\u00e9 project\n"
        + "clusters:\n- name: openshift\n  cluster:\n    server: https://api.example.com:6443\n"
        + "contexts: []\nusers: []\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));
    assertTrue(windows1252.length >= 128, "as long as a kubeconfig");

    EncryptionService.Decrypted legacy = svc.decryptStored(TestMasterKeys.legacyCiphertext(windows1252));
    assertArrayEquals(windows1252, legacy.plain());
    assertTrue(legacy.reencrypt());
    EncryptionService.Decrypted again = svc.decryptStored(svc.encrypt(legacy.plain()));
    assertArrayEquals(windows1252, again.plain());
    assertFalse(again.reencrypt());
  }

  @Test
  void bytesWithControlCharactersAreNotText() {
    byte[] garbage = new byte[256];
    java.util.Arrays.fill(garbage, (byte) 'a');
    garbage[50] = (byte) 0xe9;
    assertTrue(EncryptionService.isText(garbage), "long, not UTF-8, but text");
    garbage[100] = 0x03;

    assertFalse(EncryptionService.isText(garbage));
    assertFalse(EncryptionService.isText(new byte[] {(byte) 0xe9, 'a'}), "short and not UTF-8");
    assertTrue(EncryptionService.isText("Soci\u00e9t\u00e9".getBytes(StandardCharsets.UTF_8)));
  }
}
