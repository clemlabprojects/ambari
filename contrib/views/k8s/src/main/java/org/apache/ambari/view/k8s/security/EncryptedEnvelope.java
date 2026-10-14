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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A text encrypted with Ambari's master key ({@link EncryptionService}), kept as a small JSON object:
 * {@code {"_format":"encrypted-v1","payload":"<base64>"}}.
 *
 * <p>The view writes in this form what it keeps on disk or in its database that may hold a secret: the Ambari
 * session headers an operation calls back with, the parameters of its steps and the chart values cached for it.
 * Anything written before that is read back unchanged ({@link #openIfSealed}).
 */
public final class EncryptedEnvelope {

    public static final String FORMAT_KEY = "_format";
    public static final String FORMAT = "encrypted-v1";
    public static final String PAYLOAD_KEY = "payload";
    /** How a text sealed by {@link #sealToJson} starts. */
    private static final String JSON_PREFIX = "{\"" + FORMAT_KEY + "\":\"" + FORMAT + "\"";

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();

    private EncryptedEnvelope() {
    }

    /**
     * Encrypts a text.
     *
     * @param plain the text
     * @return the envelope, to put in a JSON document as a map
     * @throws MasterKeyUnavailableException when there is no master key
     */
    public static Map<String, Object> seal(String plain) {
        byte[] encrypted;
        synchronized (LOCK) {
            encrypted = encryption().encrypt(plain.getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(FORMAT_KEY, FORMAT);
        envelope.put(PAYLOAD_KEY, Base64.getEncoder().encodeToString(encrypted));
        return envelope;
    }

    /**
     * Encrypts a text.
     *
     * @param plain the text
     * @return the envelope as JSON text
     * @throws MasterKeyUnavailableException when there is no master key
     */
    public static String sealToJson(String plain) {
        return GSON.toJson(seal(plain));
    }

    /** Whether the map is an envelope ({@link #seal}). */
    public static boolean isEnvelope(Map<?, ?> map) {
        return map != null && FORMAT.equals(map.get(FORMAT_KEY)) && map.get(PAYLOAD_KEY) instanceof String;
    }

    /** Whether the text is an envelope written by {@link #sealToJson}. */
    public static boolean isSealed(String text) {
        return text != null && text.stripLeading().startsWith(JSON_PREFIX);
    }

    /**
     * The text an envelope holds.
     *
     * @param envelope an envelope ({@link #isEnvelope})
     * @return the decrypted text
     * @throws IllegalStateException         when it cannot be decrypted, e.g. with another master key than the one
     *                                       that encrypted it
     * @throws MasterKeyUnavailableException when there is no master key to decrypt it with
     */
    public static String open(Map<?, ?> envelope) {
        if (!isEnvelope(envelope)) {
            throw new IllegalArgumentException("Not an " + FORMAT + " envelope");
        }
        return decrypt((String) envelope.get(PAYLOAD_KEY)).text();
    }

    /**
     * The text held by a sealed text ({@link #sealToJson}), or the text itself when it is not sealed: it was written
     * before the view encrypted it.
     *
     * @param text the text as read, may be {@code null}
     * @return the plain text, or {@code null}
     * @throws IllegalStateException         when the envelope cannot be decrypted
     * @throws MasterKeyUnavailableException when there is no master key to decrypt it with
     */
    public static String openIfSealed(String text) {
        return openStored(text).text();
    }

    /**
     * {@link #openIfSealed}, telling whether the text should be sealed again: an earlier version sealed it without
     * the master key ({@link EncryptionService#decryptStored}).
     *
     * @param text the text as read, may be {@code null}
     * @return the plain text, and whether to seal it again; a text that is not sealed is returned as it is, with
     *         {@code reseal} false (whether to encrypt it is the caller's choice)
     * @throws IllegalStateException         when the envelope cannot be decrypted
     * @throws MasterKeyUnavailableException when there is no master key to decrypt it with
     */
    public static Opened openStored(String text) {
        if (!isSealed(text)) {
            return new Opened(text, false);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Unreadable " + FORMAT + " envelope", notJson);
        }
        JsonObject object = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        JsonElement payload = object == null ? null : object.get(PAYLOAD_KEY);
        if (payload == null || !payload.isJsonPrimitive() || !payload.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Unreadable " + FORMAT + " envelope: no payload");
        }
        return decrypt(payload.getAsString());
    }

    /**
     * What {@link #openStored} read.
     *
     * @param text   the plain text, or {@code null}
     * @param reseal whether to seal it again ({@link #sealToJson}) and store it in place of what was read
     */
    public record Opened(String text, boolean reseal) {
    }

    private static Opened decrypt(String payload) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(payload);
            EncryptionService.Decrypted decrypted;
            synchronized (LOCK) {
                decrypted = encryption().decryptStored(encrypted);
            }
            return new Opened(new String(decrypted.plain(), StandardCharsets.UTF_8), decrypted.reencrypt());
        } catch (MasterKeyUnavailableException noKey) {
            throw noKey;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not decrypt an " + FORMAT + " envelope", e);
        }
    }

    /**
     * The service of this Ambari server, looked up on each use: until the master key is found, it is looked for again.
     */
    private static EncryptionService encryption() {
        return EncryptionService.forServer();
    }
}
