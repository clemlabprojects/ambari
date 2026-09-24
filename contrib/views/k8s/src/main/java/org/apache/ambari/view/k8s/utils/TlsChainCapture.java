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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Certificates for a platform whose CA the hosting Ambari does not know: parse a PEM the operator
 * supplied, or capture the chain a TLS server presents, and merge either into a copy of the Ambari
 * truststore so a deployed plugin trusts both clusters.
 */
public final class TlsChainCapture {

    private TlsChainCapture() {}

    /** Every certificate in a PEM bundle. */
    public static List<X509Certificate> parsePem(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        List<X509Certificate> out = new ArrayList<>();
        for (Certificate c : certs) out.add((X509Certificate) c);
        return out;
    }

    /**
     * The chain an HTTPS endpoint presents, captured without validating it. Only ever used to build a
     * truststore the caller has decided to trust; the decision is the caller's to log.
     */
    public static List<X509Certificate> fetchChain(String httpsUrl) throws Exception {
        URI u = URI.create(httpsUrl);
        int port = u.getPort() > 0 ? u.getPort() : 443;
        final List<X509Certificate> captured = new ArrayList<>();
        TrustManager capture = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                captured.addAll(java.util.Arrays.asList(chain));
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{capture}, null);
        try (SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket()) {
            s.connect(new java.net.InetSocketAddress(u.getHost(), port), 10000);
            s.setSoTimeout(10000);
            s.startHandshake();
        }
        return captured;
    }

    /** Copy of the local truststore with the given certificates added, as keystore bytes. */
    public static byte[] mergeIntoTruststore(String truststorePath, String type, char[] password,
                                             List<X509Certificate> extra) throws Exception {
        KeyStore ks = KeyStore.getInstance(type == null || type.isBlank() ? "JKS" : type);
        if (truststorePath != null && !truststorePath.isBlank()) {
            try (InputStream in = new FileInputStream(truststorePath)) { ks.load(in, password); }
        } else {
            ks.load(null, password);
        }
        int i = 0;
        for (X509Certificate c : extra) {
            String alias = "kdps-external-" + (i++) + "-" + c.getSerialNumber().toString(16);
            if (!ks.containsAlias(alias)) ks.setCertificateEntry(alias, c);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ks.store(bos, password);
        return bos.toByteArray();
    }

    public static String toPem(List<X509Certificate> certs) throws Exception {
        StringBuilder sb = new StringBuilder();
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8));
        for (X509Certificate c : certs) {
            sb.append("-----BEGIN CERTIFICATE-----\n").append(enc.encodeToString(c.getEncoded()))
              .append("\n-----END CERTIFICATE-----\n");
        }
        return sb.toString();
    }
}
