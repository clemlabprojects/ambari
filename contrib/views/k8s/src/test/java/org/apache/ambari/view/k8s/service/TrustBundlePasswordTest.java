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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** A redeploy reuses the truststore password of the release's existing Secret (a new one restarts every pod). */
class TrustBundlePasswordTest {

  private static Secret secret(String password) {
    return new SecretBuilder().withNewMetadata().withName("trino-truststore").endMetadata()
        .addToData("truststore.password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)))
        .build();
  }

  @Test
  void theStoredPasswordIsReused() {
    assertArrayEquals("1cda3a50efa5423f".toCharArray(), TrustBundleService.passwordFromSecret(secret("1cda3a50efa5423f")));
  }

  @Test
  void noUsablePasswordMeansAFreshOne() {
    assertNull(TrustBundleService.passwordFromSecret(null));
    assertNull(TrustBundleService.passwordFromSecret(new SecretBuilder().withNewMetadata().withName("x").endMetadata().build()));
    assertNull(TrustBundleService.passwordFromSecret(secret("   ")));
    assertNull(TrustBundleService.passwordFromSecret(new SecretBuilder().addToData("truststore.password", "%%not-base64%%").build()));
  }
}
