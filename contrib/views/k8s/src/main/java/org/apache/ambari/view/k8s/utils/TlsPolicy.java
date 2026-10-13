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

import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the view checks the certificate of an HTTPS server it sends credentials to: Ranger Admin, Atlas, Cloudera
 * Manager.
 *
 * <p>A policy verifies by default: the server must present a chain that the JVM, the Ambari server truststore
 * ({@code ssl.trustStore.*} in ambari.properties) or the CA certificates of the platform context trust, issued for
 * the host name called. Only a platform context whose operator turned verification off gets a policy that accepts
 * any certificate and any name, and only on the connections made with it: nothing here changes a JVM-wide default
 * ({@link HttpsURLConnection#setDefaultSSLSocketFactory}, system properties), which the other views and the Ambari
 * server itself share.
 */
public final class TlsPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(TlsPolicy.class);

    /** Verifies against the JVM trust only, for callers that have no platform context. */
    public static final TlsPolicy JVM_DEFAULT = new TlsPolicy(true, List.of(), "HTTPS server");

    private final boolean verify;
    private final List<X509Certificate> extraTrust;
    private final String what;
    private final Set<String> warnedHosts = ConcurrentHashMap.newKeySet();
    private volatile SSLContext sslContext;

    private TlsPolicy(boolean verify, List<X509Certificate> extraTrust, String what) {
        this.verify = verify;
        this.extraTrust = extraTrust == null ? List.of() : List.copyOf(extraTrust);
        this.what = what == null || what.isBlank() ? "HTTPS server" : what;
    }

    /**
     * A policy that verifies the certificate and the host name.
     *
     * @param extraTrust CA certificates trusted in addition to the JVM ones
     * @param what       what is called, for the log ("Ranger of platform context prod")
     */
    public static TlsPolicy verifying(Collection<X509Certificate> extraTrust, String what) {
        return new TlsPolicy(true, extraTrust == null ? List.of() : new ArrayList<>(extraTrust), what);
    }

    /**
     * A policy that accepts any certificate for any host name: only for a platform context whose operator turned
     * certificate verification off. Every host it is used for is logged once as a warning.
     *
     * @param what what is called, for the log
     */
    public static TlsPolicy trustingAnyCertificate(String what) {
        return new TlsPolicy(false, List.of(), what);
    }

    /**
     * The policy of a platform context: verifying unless {@code verifySsl} is false, trusting, besides the JVM, the
     * Ambari server truststore and the CA certificates the operator put on the context.
     *
     * @param ctx        the view context, for the Ambari server truststore; may be null
     * @param verifySsl  false only when the operator turned verification off for the context
     * @param contextPem PEM CA certificates stored on the context; may be null or blank
     * @param what       what is called, for the log
     */
    public static TlsPolicy forContext(ViewContext ctx, boolean verifySsl, String contextPem, String what) {
        if (!verifySsl) {
            return trustingAnyCertificate(what);
        }
        List<X509Certificate> extra = new ArrayList<>(ambariServerTrust(ctx));
        if (contextPem != null && !contextPem.isBlank()) {
            try {
                extra.addAll(TlsChainCapture.parsePem(contextPem));
            } catch (Exception e) {
                LOG.warn("The CA certificate stored for {} could not be read and is ignored: {}", what, e.getMessage());
            }
        }
        return verifying(extra, what);
    }

    /**
     * The certificates of the Ambari server truststore ({@code ssl.trustStore.path}), or none when it is not set or
     * cannot be read. The password may be a credential-store alias.
     */
    public static List<X509Certificate> ambariServerTrust(ViewContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        String path = ctx.getAmbariProperty("ssl.trustStore.path");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        try {
            String type = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                    .filter(s -> !s.isBlank()).orElse("JKS");
            String passProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password")).orElse("");
            char[] password = passProp.isBlank() ? null
                    : passProp.trim().startsWith("${alias=") ? new AmbariAliasResolver(ctx).resolve(ctx, passProp)
                    : passProp.toCharArray();
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(Paths.get(path))) {
                ks.load(in, password);
            }
            List<X509Certificate> out = new ArrayList<>();
            for (Enumeration<String> aliases = ks.aliases(); aliases.hasMoreElements(); ) {
                Certificate c = ks.getCertificate(aliases.nextElement());
                if (c instanceof X509Certificate x) {
                    out.add(x);
                }
            }
            return out;
        } catch (Exception e) {
            LOG.warn("The Ambari server truststore {} could not be read; only the JVM trust is used: {}",
                    path, e.getMessage());
            return List.of();
        }
    }

    /** Whether certificates and host names are checked. */
    public boolean verifies() {
        return verify;
    }

    /**
     * Sets this policy on one connection before it connects. A plain HTTP connection is left as it is.
     */
    public void apply(URLConnection connection) throws GeneralSecurityException {
        if (!(connection instanceof HttpsURLConnection https)) {
            return;
        }
        if (verify) {
            https.setSSLSocketFactory(new HostnameCheckingSocketFactory(sslContext().getSocketFactory()));
            return;
        }
        warnOnce(https.getURL() == null ? null : https.getURL().getHost());
        https.setSSLSocketFactory(sslContext().getSocketFactory());
        https.setHostnameVerifier((host, session) -> true);
    }

    /**
     * The SSL context of this policy, for clients that take one ({@link java.net.http.HttpClient.Builder#sslContext}).
     * java.net.http always checks the host name through the trust manager, so the context of a policy that does not
     * verify skips that check for the clients built with it only.
     *
     * @param host the host the client calls, for the warning of a policy that does not verify; may be null
     */
    public SSLContext sslContext(String host) throws GeneralSecurityException {
        if (!verify) {
            warnOnce(host);
        }
        return sslContext();
    }

    private SSLContext sslContext() throws GeneralSecurityException {
        SSLContext built = sslContext;
        if (built == null) {
            built = SSLContext.getInstance("TLS");
            TrustManager tm;
            if (!verify) {
                tm = new AnyCertificate();
            } else {
                X509TrustManager pkix = trustManager(extraTrust);
                tm = pkix instanceof X509ExtendedTrustManager extended ? new Explaining(extended, what) : pkix;
            }
            built.init(null, new TrustManager[]{tm}, new SecureRandom());
            sslContext = built;
        }
        return built;
    }

    private void warnOnce(String host) {
        String key = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (warnedHosts.add(key)) {
            LOG.warn("Certificate verification is off for {}: any certificate {} is accepted, as the platform "
                    + "context asks. Turn verification on for the context once its CA is trusted.",
                    what, host == null ? "from the server" : "from " + host);
        }
    }

    /**
     * One PKIX trust manager over the JVM's trusted certificates and {@code extra}. A real
     * {@link X509ExtendedTrustManager}, so the TLS stack runs the host-name check inside it.
     */
    static X509TrustManager trustManager(List<X509Certificate> extra) throws GeneralSecurityException {
        TrustManagerFactory jvm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jvm.init((KeyStore) null);
        if (extra.isEmpty()) {
            return firstX509(jvm.getTrustManagers());
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException("Cannot create an in-memory truststore", e);
        }
        int n = 0;
        for (X509Certificate c : firstX509(jvm.getTrustManagers()).getAcceptedIssuers()) {
            ks.setCertificateEntry("jvm-" + n++, c);
        }
        for (X509Certificate c : extra) {
            ks.setCertificateEntry("extra-" + n++, c);
        }
        TrustManagerFactory merged = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        merged.init(ks);
        return firstX509(merged.getTrustManagers());
    }

    private static X509TrustManager firstX509(TrustManager[] managers) throws GeneralSecurityException {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new GeneralSecurityException("The JVM offers no X.509 trust manager");
    }

    /**
     * Accepts any certificate and, being an {@link X509ExtendedTrustManager}, skips the host-name check the TLS stack
     * would otherwise run around a plain {@link X509TrustManager}. Used only by a policy that does not verify.
     */
    private static final class AnyCertificate extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /**
     * Turns a refused certificate into a message that says what was called and what the operator can do, so that the
     * failed step shows it rather than a bare "PKIX path building failed". The checks themselves, the host name
     * included, are the ones of the wrapped trust manager.
     */
    private static final class Explaining extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final String what;

        Explaining(X509ExtendedTrustManager delegate, String what) {
            this.delegate = delegate;
            this.what = what;
        }

        private CertificateException explained(CertificateException e, String host) {
            return new CertificateException("The certificate of " + (host == null ? "the server" : host)
                    + " is not trusted for " + what + ": add the CA that signed it to the platform context or to the"
                    + " Ambari server truststore, or turn certificate verification off for the platform context ("
                    + e.getMessage() + ")", e);
        }

        private static String host(Socket socket) {
            if (socket instanceof SSLSocket ssl && ssl.getHandshakeSession() != null) {
                return ssl.getHandshakeSession().getPeerHost();
            }
            return socket == null || socket.getInetAddress() == null ? null : socket.getInetAddress().getHostName();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException e) {
                throw explained(e, host(socket));
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException e) {
                throw explained(e, engine == null ? null : engine.getPeerHost());
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                throw explained(e, null);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    /**
     * Asks the TLS stack to check the host name on every socket. HttpsURLConnection only does so itself while the
     * JVM-wide default host-name verifier is the JDK's own, which another component of the server may have replaced.
     */
    private static final class HostnameCheckingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        HostnameCheckingSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private static Socket checked(Socket socket) {
            if (socket instanceof SSLSocket ssl) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return checked(delegate.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return checked(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return checked(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return checked(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    @Override
    public String toString() {
        return "TlsPolicy{" + (verify ? "verify, " + extraTrust.size() + " extra CA(s)" : "any certificate")
                + ", " + what + "}";
    }
}
