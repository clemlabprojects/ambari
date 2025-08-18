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
