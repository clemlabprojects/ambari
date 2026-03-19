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
