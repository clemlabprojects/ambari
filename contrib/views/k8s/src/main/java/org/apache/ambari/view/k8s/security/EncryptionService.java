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

import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encrypts what KDPS keeps (kubeconfig, passwords, tokens, operation parameters) with AES, the key derived from the
 * Ambari master key ({@link MasterKeyService}).
 *
 * <p>There is no fallback key: without a master key, {@link #encrypt} and {@link #decrypt} throw
 * {@link MasterKeyUnavailableException}, which the REST resources answer with HTTP 412. Building the service never
 * fails, so a class that keeps one in a static field still loads on a server without a key.</p>
 *
 * <p>Carry-over: earlier versions encrypted with a passphrase built into the view when Ambari had no master key, and
 * even earlier ones only Base64-encoded. Such values are still read ({@link #decryptStored} tells the caller to
 * encrypt them again with the master key), for this release only.</p>
 */
public class EncryptionService {
    private static final Logger LOG = LoggerFactory.getLogger(EncryptionService.class);

    /**
     * The passphrase earlier versions encrypted with when Ambari had no master key. It is public (it was in the source),
     * so it protects nothing: it is only used to read what they stored, which is then encrypted again with the master
     * key. Remove it, and the Base64-only reading, in the next release.
     */
    private static final String LEGACY_PASSPHRASE = "default-passphrase-change-in-production";

    private static final Object LEGACY_LOCK = new Object();
    private static AESEncryptor legacyEncryptor;
    private static final AtomicBoolean LEGACY_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean MISSING_KEY_REPORTED = new AtomicBoolean();

    /** Below this length a text in a single-byte charset is not told apart from garbage reliably enough. */
    private static final int MIN_SINGLE_BYTE_TEXT = 128;

    /** The service of this Ambari server, once its master key was found. */
    private static volatile EncryptionService serverService;

    private final MasterKeyService masterKeyService;
    /** Built on first use: deriving the key takes a while, and the master key may not be there. */
    private volatile AESEncryptor encryptor;

    /**
     * What {@link #decryptStored} read.
     *
     * @param plain      the decrypted bytes
     * @param reencrypt  {@code true} when they were stored by an earlier version without the master key: the caller
     *                   should store them again, encrypted with {@link #encrypt}
     */
    public record Decrypted(byte[] plain, boolean reencrypt) {
    }

    /**
     * Uses the master key of this Ambari server, found through its {@code ambari.properties} file. Prefer
     * {@link #forServer(ViewContext)}.
     */
    public EncryptionService() {
        this(new MasterKeyServiceImpl());
    }

    /**
     * Uses the master key of the Ambari server a view instance runs in.
     *
     * @param viewContext the view instance
     */
    public EncryptionService(ViewContext viewContext) {
        this(new MasterKeyServiceImpl(viewContext));
    }

    /**
     * Uses the given master key.
     *
     * @param masterKeyService where the master key comes from
     */
    public EncryptionService(MasterKeyService masterKeyService) {
        this.masterKeyService = Objects.requireNonNull(masterKeyService, "masterKeyService");
    }

    /**
     * The service of the Ambari server a view instance runs in. Every view instance of a server has the same master
     * key, so one service is shared once the key is found; while it is not, it is looked for again on each call.
     *
     * @param viewContext the view instance
     * @return the service; check {@link #isMasterKeyAvailable()} before storing anything
     */
    public static EncryptionService forServer(ViewContext viewContext) {
        EncryptionService shared = serverService;
        return shared != null ? shared : share(new EncryptionService(viewContext));
    }

    /**
     * {@link #forServer(ViewContext)} for code that has no view instance at hand: the service already shared, or one
     * that reads the key locations from {@code ambari.properties}.
     *
     * @return the service
     */
    public static EncryptionService forServer() {
        EncryptionService shared = serverService;
        return shared != null ? shared : share(new EncryptionService());
    }

    private static EncryptionService share(EncryptionService candidate) {
        if (!candidate.isMasterKeyAvailable()) {
            if (MISSING_KEY_REPORTED.compareAndSet(false, true)) {
                LOG.warn("The Ambari master key was not found ({}). KDPS cannot store or read credentials until it is: {}",
                        candidate.keySource(), MasterKeyUnavailableException.MESSAGE);
            }
            return candidate;
        }
        synchronized (EncryptionService.class) {
            if (serverService == null) {
                serverService = candidate;
            }
            return serverService;
        }
    }

    /** Whether the master key was found: without it nothing can be encrypted or decrypted. */
    public boolean isMasterKeyAvailable() {
        return masterKeyService.isMasterKeyInitialized();
    }

    /**
     * Checks that the master key was found, before anything is stored.
     *
     * @throws MasterKeyUnavailableException when it was not
     */
    public void requireMasterKey() {
        if (!isMasterKeyAvailable()) {
            throw new MasterKeyUnavailableException();
        }
    }

    /**
     * Encrypts data with the master key.
     *
     * @param data the data: text, the only data {@link #decrypt} gives back (UTF-8, or a single-byte charset for at
     *             least 128 bytes)
     * @return {@code salt:iv:ciphertext}, each part Base64-encoded, as UTF-8 bytes
     * @throws MasterKeyUnavailableException when there is no master key
     */
    public byte[] encrypt(byte[] data) {
        AESEncryptor aes = encryptor();
        try {
            EncryptionResult result = aes.encrypt(data);
            String saltB64 = Base64.getEncoder().encodeToString(result.getSalt());
            String ivB64 = Base64.getEncoder().encodeToString(result.getIv());
            String cipherB64 = Base64.getEncoder().encodeToString(result.getCipher());
            String combined = saltB64 + ":" + ivB64 + ":" + cipherB64;
            return combined.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts data {@link #encrypt} returned, or that an earlier version stored.
     *
     * @param encryptedData {@code salt:iv:ciphertext}
     * @return the data
     * @throws MasterKeyUnavailableException when there is no master key
     * @throws IllegalArgumentException      when the data is not Base64
     * @throws IllegalStateException         when it was encrypted with another key
     */
    public byte[] decrypt(byte[] encryptedData) {
        return decryptStored(encryptedData).plain();
    }

    /**
     * {@link #decrypt}, telling whether the data was stored by an earlier version without the master key, so the
     * caller stores it again encrypted with it.
     *
     * @param encryptedData {@code salt:iv:ciphertext}, or the Base64 of the data itself (written by early versions)
     * @return the data, and whether to encrypt it again
     * @throws MasterKeyUnavailableException when there is no master key
     * @throws IllegalArgumentException      when the data is not Base64
     * @throws IllegalStateException         when it was encrypted with another key
     */
    public Decrypted decryptStored(byte[] encryptedData) {
        AESEncryptor aes = encryptor();
        String combined = new String(encryptedData, StandardCharsets.UTF_8);
        String[] parts = combined.split(":", 3);
        if (parts.length != 3) {
            // Early versions only Base64-encoded.
            byte[] plain = Base64.getDecoder().decode(encryptedData);
            reportLegacy();
            return new Decrypted(plain, true);
        }
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] iv = Base64.getDecoder().decode(parts[1]);
        byte[] cipher = Base64.getDecoder().decode(parts[2]);

        // Without an integrity check, a wrong key passes the padding check about once in 256 tries and gives garbage.
        // What KDPS encrypts is text, so only a result that is text counts as decrypted.
        byte[] withKey = tryDecrypt(aes, salt, iv, cipher);
        if (withKey != null && isText(withKey)) {
            return new Decrypted(withKey, false);
        }
        byte[] withLegacy = tryDecrypt(legacyEncryptor(), salt, iv, cipher);
        if (withLegacy != null && isText(withLegacy)) {
            reportLegacy();
            return new Decrypted(withLegacy, true);
        }
        throw new IllegalStateException("A stored credential cannot be decrypted with the Ambari master key: it was "
                + "encrypted with another key. Enter it again.");
    }

    /** Where the master key was looked for, for messages. */
    private String keySource() {
        String source = masterKeyService instanceof MasterKeyServiceImpl impl ? impl.getSource() : null;
        return source == null ? "no key location" : "looked for in " + source;
    }

    private AESEncryptor encryptor() {
        AESEncryptor aes = encryptor;
        if (aes != null) {
            return aes;
        }
        synchronized (this) {
            if (encryptor == null) {
                char[] secret = masterKeyService.isMasterKeyInitialized() ? masterKeyService.getMasterSecret() : null;
                if (secret == null || secret.length == 0) {
                    throw new MasterKeyUnavailableException();
                }
                try {
                    encryptor = new AESEncryptor(new String(secret));
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }
            return encryptor;
        }
    }

    private static AESEncryptor legacyEncryptor() {
        synchronized (LEGACY_LOCK) {
            if (legacyEncryptor == null) {
                legacyEncryptor = new AESEncryptor(LEGACY_PASSPHRASE);
            }
            return legacyEncryptor;
        }
    }

    private static byte[] tryDecrypt(AESEncryptor aes, byte[] salt, byte[] iv, byte[] cipher) {
        try {
            return aes.decrypt(salt, iv, cipher);
        } catch (Exception wrongKey) {
            return null;
        }
    }

    /**
     * Whether decrypted bytes are text: UTF-8, or, for a file as long as a kubeconfig, text in a single-byte charset
     * (an upload saved as Windows-1252 by an earlier version). Garbage from a wrong key has control bytes: among 128
     * random bytes, in all but about one case in eight million.
     */
    static boolean isText(byte[] bytes) {
        return isUtf8(bytes) || (bytes.length >= MIN_SINGLE_BYTE_TEXT && hasNoControlBytes(bytes));
    }

    private static boolean hasNoControlBytes(byte[] bytes) {
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException notText) {
            return false;
        }
    }

    private static void reportLegacy() {
        if (LEGACY_REPORTED.compareAndSet(false, true)) {
            LOG.warn("KDPS found credentials that an earlier version stored without the Ambari master key (with a "
                    + "passphrase built into the view, or only Base64-encoded). They are encrypted again with the "
                    + "master key as they are read.");
        }
    }
}
