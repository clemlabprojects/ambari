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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An HTTPS server on the loopback address with a self-signed certificate that no truststore knows, issued for the
 * names given: what a man in the middle, or a Ranger whose CA the operator never imported, presents. It answers each
 * path with the JSON set in {@link #answers} (404 otherwise) and records the Authorization header of every request,
 * so a test can tell whether credentials were sent.
 */
public final class SelfSignedHttpsServer implements AutoCloseable {

    /** JSON answer by request path, without the query. */
    public final Map<String, String> answers = new ConcurrentHashMap<>();
    /** "METHOD path Authorization" of every request that got past the handshake. */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpsServer server;
    private final X509Certificate certificate;

    /**
     * @param dnsNames the DNS names the certificate is issued for; with {@code withLoopbackIp}, 127.0.0.1 too
     */
    public SelfSignedHttpsServer(boolean withLoopbackIp, String... dnsNames) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        certificate = selfSigned(keys, withLoopbackIp, dnsNames);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", keys.getPrivate(), "changeit".toCharArray(), new Certificate[]{certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            String body = answers.get(path);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** The base URL, by the loopback IP. */
    public String ipUrl() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The base URL, by the name {@code localhost}. */
    public String localhostUrl() {
        return "https://localhost:" + server.getAddress().getPort();
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** The certificate in PEM, as an operator would paste it on a platform context. */
    public String certificatePem() throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static X509Certificate selfSigned(KeyPair keys, boolean withLoopbackIp, String... dnsNames) throws Exception {
        X500Name name = new X500Name("CN=" + (dnsNames.length > 0 ? dnsNames[0] : "127.0.0.1"));
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
                new Date(now - 60_000L), new Date(now + 3_600_000L), name, keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        GeneralName[] sans = new GeneralName[dnsNames.length + (withLoopbackIp ? 1 : 0)];
        for (int i = 0; i < dnsNames.length; i++) {
            sans[i] = new GeneralName(GeneralName.dNSName, dnsNames[i]);
        }
        if (withLoopbackIp) {
            sans[dnsNames.length] = new GeneralName(GeneralName.iPAddress, "127.0.0.1");
        }
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
}
