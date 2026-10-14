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

package org.apache.ambari.view.k8s.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Master keys for tests, and data as earlier versions stored it. */
public final class TestMasterKeys {

    /** The passphrase earlier versions encrypted with when Ambari had no master key. */
    public static final String LEGACY_PASSPHRASE = "default-passphrase-change-in-production";

    private TestMasterKeys() {
    }

    /** A master key service holding the given key. */
    public static MasterKeyService key(String secret) {
        return new MasterKeyService() {
            @Override
            public char[] getMasterSecret() {
                return secret.toCharArray();
            }

            @Override
            public boolean isMasterKeyInitialized() {
                return true;
            }
        };
    }

    /** A master key service that found no key, as on an Ambari server set up without one. */
    public static MasterKeyService none() {
        return new MasterKeyService() {
            @Override
            public char[] getMasterSecret() {
                return null;
            }

            @Override
            public boolean isMasterKeyInitialized() {
                return false;
            }
        };
    }

    /** An encryption service with the given master key. */
    public static EncryptionService encryptionWith(String secret) {
        return new EncryptionService(key(secret));
    }

    /** An encryption service on a server without a master key. */
    public static EncryptionService encryptionWithoutKey() {
        return new EncryptionService(none());
    }

    /** {@code salt:iv:ciphertext} as earlier versions wrote it with their built-in passphrase. */
    public static byte[] legacyCiphertext(String plain) {
        return ciphertext(LEGACY_PASSPHRASE, plain);
    }

    /** {@link #legacyCiphertext(String)} of bytes that need not be UTF-8. */
    public static byte[] legacyCiphertext(byte[] plain) {
        return ciphertext(LEGACY_PASSPHRASE, plain);
    }

    /** {@code salt:iv:ciphertext} encrypted with a passphrase. */
    public static byte[] ciphertext(String passphrase, String plain) {
        return ciphertext(passphrase, plain.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code salt:iv:ciphertext} of bytes encrypted with a passphrase. */
    public static byte[] ciphertext(String passphrase, byte[] plain) {
        try {
            EncryptionResult r = new AESEncryptor(passphrase).encrypt(plain);
            Base64.Encoder b64 = Base64.getEncoder();
            return (b64.encodeToString(r.getSalt()) + ":" + b64.encodeToString(r.getIv()) + ":"
                    + b64.encodeToString(r.getCipher())).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A master key file as {@code ambari-server setup-security} persists it. */
    public static String ambariMasterKeyFile(String key) {
        try {
            EncryptionResult r = new AESEncryptor("masterpassphrase").encrypt(key.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            String line = b64.encodeToString(r.getSalt()) + "::" + b64.encodeToString(r.getIv()) + "::"
                    + b64.encodeToString(r.getCipher());
            return "#1.0# Tue Oct 13 07:00:00 UTC 2026\n" + b64.encodeToString(line.getBytes(StandardCharsets.UTF_8)) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
