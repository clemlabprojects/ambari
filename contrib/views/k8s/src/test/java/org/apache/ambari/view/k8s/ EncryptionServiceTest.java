package org.apache.ambari.view.k8s.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

  private final EncryptionService svc = new EncryptionService();

  @Test
  void encryptThenDecryptReturnsOriginal() {
    String secret = "hunter2!";
    byte[] enc = svc.encrypt(secret.getBytes(StandardCharsets.UTF_8));

    // ciphertext must differ from plaintext
    assertNotEquals(secret, new String(enc, StandardCharsets.UTF_8));

    byte[] dec = svc.decrypt(enc);
    assertEquals(secret, new String(dec, StandardCharsets.UTF_8));
  }

  @Test
  void decryptOfNonBase64Throws() {
    assertThrows(IllegalArgumentException.class,
        () -> svc.decrypt("$$not‑base64$$".getBytes(StandardCharsets.UTF_8)));
  }
}
