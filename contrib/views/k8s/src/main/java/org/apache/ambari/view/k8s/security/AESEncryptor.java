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

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class AESEncryptor {
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 128;
    private Cipher ecipher;
    private Cipher dcipher;
    private SecretKey secret;
    /**
     * The key values are encrypted with, derived from {@link #salt}. Kept apart from {@link #secret}, which reading a
     * value replaces with the key of that value's salt.
     */
    private SecretKey encryptionKey;
    private final SecureRandom random = new SecureRandom();
    private byte[] salt = null;
    private char[] passPhrase = null;
    /**
     * Keys derived from the passphrase, by salt. A derivation runs 65536 PBKDF2 rounds: too slow to repeat each time
     * the view reads back something it encrypted, such as the parameters of an operation it polls.
     */
    private final Map<String, SecretKey> keysBySalt = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_KEYS = 64;

    public AESEncryptor(String passPhrase) {
        try {
            this.passPhrase = passPhrase.toCharArray();
            salt = new byte[8];
            SecureRandom rnd = new SecureRandom();
            rnd.nextBytes(salt);

            SecretKey tmp = getKeyFromPassword(passPhrase);
            secret = new SecretKeySpec(tmp.getEncoded(), "AES");
            encryptionKey = secret;

            ecipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            ecipher.init(Cipher.ENCRYPT_MODE, secret);

            dcipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = ecipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV();
            dcipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | InvalidParameterSpecException | InvalidKeyException | NoSuchPaddingException e) {
            e.printStackTrace();
        }
    }

    AESEncryptor(SecretKey secret) {
        try {
            this.secret = new SecretKeySpec (secret.getEncoded(), "AES");
            this.encryptionKey = this.secret;

            ecipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            ecipher.init(Cipher.ENCRYPT_MODE, secret);

            dcipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = ecipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV();
            dcipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | InvalidParameterSpecException | InvalidKeyException | NoSuchPaddingException e) {
            e.printStackTrace();
        }
    }

    public SecretKey getKeyFromPassword(String passPhrase) {
        return getKeyFromPassword(passPhrase, salt);
    }

    public SecretKey getKeyFromPassword(String passPhrase, byte[] salt) {
        SecretKeyFactory factory;
        SecretKey key = null;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            KeySpec spec = new PBEKeySpec(passPhrase.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
            key = factory.generateSecret(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
        }

        return key;
    }

    public String decrypt(String salt, String iv, String cipher) throws Exception {
        byte[] decrypted = decrypt(salt.getBytes("UTF8"), iv.getBytes("UTF8"), cipher.getBytes("UTF8"));
        return new String(decrypted, "UTF8");
    }

    // Synchronized, like the other cipher operations: the ciphers are shared and a Cipher is not thread-safe.
    public synchronized byte[] decrypt(byte[] salt, byte[] iv, byte[] encrypt) throws Exception {
        SecretKey tmp = keyFor(salt);
        secret = new SecretKeySpec(tmp.getEncoded(), "AES");

        dcipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        return dcipher.doFinal(encrypt);
    }

    public synchronized byte[] decrypt(byte[] encrypt) throws Exception {
        dcipher.init(Cipher.DECRYPT_MODE, secret);
        return dcipher.doFinal(encrypt);
    }

    public EncryptionResult encrypt(String encrypt) throws Exception {
        byte[] bytes = encrypt.getBytes("UTF8");
        EncryptionResult atom = encrypt(bytes);
        return atom;
    }

    /**
     * Encrypts with a new random IV each time: one encryptor is shared by everything the view stores, and with a fixed
     * IV equal values would give equal ciphertexts, which tells whoever reads them which credentials are the same.
     */
    public synchronized EncryptionResult encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[ecipher.getBlockSize()];
        random.nextBytes(iv);
        ecipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new IvParameterSpec(iv));
        EncryptionResult atom = new EncryptionResult(salt, iv, ecipher.doFinal(plain));
        return atom;
    }

    /** The key derived from the passphrase with this salt, derived once. */
    private SecretKey keyFor(byte[] salt) {
        String id = Base64.getEncoder().encodeToString(salt);
        SecretKey key = keysBySalt.get(id);
        if (key == null) {
            key = getKeyFromPassword(new String(passPhrase), salt);
            if (key != null) {
                if (keysBySalt.size() >= MAX_CACHED_KEYS) {
                    keysBySalt.clear();
                }
                keysBySalt.put(id, key);
            }
        }
        return key;
    }
}
