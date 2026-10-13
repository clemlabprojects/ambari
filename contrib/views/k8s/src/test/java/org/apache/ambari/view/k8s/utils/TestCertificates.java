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

package org.apache.ambari.view.k8s.utils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** Self-signed CA certificates for tests that handle trust anchors. */
public final class TestCertificates {

    /** Distinct serials even for certificates made within the same millisecond. */
    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    private TestCertificates() {
    }

    /**
     * A self-signed CA certificate, valid for a day, in PEM.
     *
     * @param subject its distinguished name, e.g. {@code CN=Corporate Root CA}
     */
    public static String caPem(String subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            X500Name name = new X500Name(subject);
            long now = System.currentTimeMillis();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                    BigInteger.valueOf(SERIAL.incrementAndGet()), new Date(now - 60_000L), new Date(now + 86_400_000L),
                    name, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not make a test CA certificate", e);
        }
    }
}
