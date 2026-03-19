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

import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

class CompositeTrustManagerTest {

  @Test
  void acceptsWhenAtLeastOneDelegateAccepts() throws Exception {
    X509TrustManager reject = new DummyTM(false);
    X509TrustManager accept = new DummyTM(true);

    CompositeTrustManager ctm = new CompositeTrustManager(reject, accept);
    // should NOT throw
    ctm.checkServerTrusted(new X509Certificate[0], "RSA");
  }

  @Test
  void rejectsWhenAllDelegatesReject() {
    CompositeTrustManager ctm = new CompositeTrustManager(
        new DummyTM(false), new DummyTM(false));

    assertThrows(CertificateException.class,
        () -> ctm.checkServerTrusted(new X509Certificate[0], "RSA"));
  }

  // ─── helper ───────────────────────────────────────────────────────────────────
  static final class DummyTM implements X509TrustManager {
    private final boolean accept;
    DummyTM(boolean accept) { this.accept = accept; }
    public void checkClientTrusted(X509Certificate[] c, String a) {}
    public void checkServerTrusted(X509Certificate[] c, String a)
        throws CertificateException {
      if (!accept) throw new CertificateException("Rejected");
    }
    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
  }
}
