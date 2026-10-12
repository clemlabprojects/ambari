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

package org.apache.ambari.view.k8s.service;

import java.util.Locale;

/**
 * Types a setting the way {@code helm --set} does (Helm's {@code strvals.typedVal}), so a value reaches the chart
 * the same in direct and GitOps deploys: {@code true}/{@code false} in any case are booleans, {@code null} is null
 * (which clears the setting), {@code 0} and whole numbers not starting with {@code 0} are integers, and everything
 * else stays text ({@code 1.10}, {@code 0123}, {@code 1.0e3}, {@code 476.1.3.2.0-37}).
 *
 * <p>One difference: surrounding spaces are ignored when deciding the type (KDPS values come from forms and
 * configuration files, where {@code "false "} must not end up as truthy text); a value kept as text keeps them.
 */
public final class HelmSetValues {

    /** Marks a value to be kept as text, like {@code --set-string}; removed by {@link #typed}. */
    static final String TEXT_PREFIX = "\u0000kdps-text:";

    private HelmSetValues() {
    }

    /**
     * The value kept as text whatever it looks like (e.g. a password "12345", "true" or "null").
     *
     * @param value the value
     * @return the marked value, for an override map that is later read by {@link #typed}
     */
    public static String asText(String value) {
        return value == null ? null : TEXT_PREFIX + value;
    }

    /** The value for a log line: a value kept as text by {@link #textIfSecret} is a secret and is hidden. */
    public static String forLog(String value) {
        return isMarkedSecret(value) ? "<hidden>" : value;
    }

    /**
     * Whether the value was marked by {@link #textIfSecret}: it comes from a password field and must be kept out of
     * logs and out of anything committed to Git.
     *
     * @param value an override value, before {@link #typed} reads it
     */
    public static boolean isMarkedSecret(String value) {
        return value != null && value.startsWith(TEXT_PREFIX);
    }

    /**
     * The value, kept as text when {@code field} is a password in the security profile definition
     * ({@code type: "password"} in KDPS/globals/security.json, e.g. {@code ldap.bindPassword}), else unchanged.
     *
     * @param field the security profile field the value comes from
     * @param value the value
     */
    public static String textIfSecret(String field, String value) {
        return field != null && value != null && passwordFields().contains(field) ? asText(value) : value;
    }

    /**
     * Hides secrets in a JSON text about to be logged (e.g. command parameters):
     * <ul>
     *   <li>the whole {@code formValues} subtree, the wizard's form as filled in, which carries every password and
     *       pasted key the operator typed;</li>
     *   <li>the values {@link #textIfSecret} marked;</li>
     *   <li>any string that contains a PEM private key, whatever its key;</li>
     *   <li>any string or number under a key ending in password, passwd, secret, token, secretKey, accessKey,
     *       accessKeyId, privateKey, sshKey, apiKey, credential(s) or keytab, or under the key {@code key} (e.g.
     *       {@code oidcExternalClientSecret}, the {@code sshKey} of a GitOps deploy's Git options).</li>
     * </ul>
     * Names stay readable: {@code secretName} or {@code truststorePasswordKey} hold the name of a Secret or of one of
     * its keys, not a secret. A text that is not a JSON object or array is hidden by the same rules, matched on the
     * text.
     */
    public static String redactJson(String json) {
        if (json == null) {
            return null;
        }
        com.google.gson.JsonElement tree;
        try {
            tree = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException notJson) {
            tree = null;
        }
        if (tree == null || !(tree.isJsonObject() || tree.isJsonArray())) {
            return redactText(json);
        }
        return LOG_GSON.toJson(redacted(null, tree));
    }

    /** Hidden form values and secrets are replaced by this text. */
    private static final String HIDDEN = "<hidden>";
    /** Keeps {@code <hidden>} readable: Gson escapes {@code <} and {@code >} by default. */
    private static final com.google.gson.Gson LOG_GSON =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /** A key whose value is a secret, any case. */
    private static final java.util.regex.Pattern SECRET_KEY = java.util.regex.Pattern.compile(
            "(?i)key|.*(password|passwd|secret|token|secretkey|accesskey|accesskeyid|privatekey|sshkey|apikey"
                    + "|credentials?|keytab)");

    private static com.google.gson.JsonElement redacted(String key, com.google.gson.JsonElement element) {
        if (element.isJsonObject()) {
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : element.getAsJsonObject().entrySet()) {
                out.add(e.getKey(), "formValues".equals(e.getKey())
                        ? new com.google.gson.JsonPrimitive(HIDDEN) : redacted(e.getKey(), e.getValue()));
            }
            return out;
        }
        if (element.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            element.getAsJsonArray().forEach(item -> out.add(redacted(key, item)));
            return out;
        }
        if (element.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive value = element.getAsJsonPrimitive();
            boolean secretKey = key != null && SECRET_KEY.matcher(key).matches();
            if (value.isString() && (secretKey || isMarkedSecret(value.getAsString())
                    || containsPrivateKey(value.getAsString()))) {
                return new com.google.gson.JsonPrimitive(HIDDEN);
            }
            if (value.isNumber() && secretKey) {
                return new com.google.gson.JsonPrimitive(HIDDEN);
            }
        }
        return element;
    }

    /** Whether the text holds a PEM private key (PKCS#8, RSA, EC, OpenSSH...). */
    static boolean containsPrivateKey(String text) {
        int begin = text == null ? -1 : text.indexOf("-----BEGIN");
        return begin >= 0 && text.indexOf("PRIVATE KEY", begin) > 0;
    }

    /** The same rules on a text that is not JSON: matched on the text. */
    private static String redactText(String text) {
        String hidden = MARKED_JSON_STRING.matcher(text).replaceAll("\"" + HIDDEN + "\"");
        hidden = PRIVATE_KEY_PEM.matcher(hidden).replaceAll(HIDDEN);
        return SECRET_KEY_JSON_STRING.matcher(hidden).replaceAll("$1\"" + HIDDEN + "\"");
    }

    /** A JSON string value under a secret key ({@link #SECRET_KEY}), any case. */
    private static final java.util.regex.Pattern SECRET_KEY_JSON_STRING = java.util.regex.Pattern.compile(
            "(\"(?i:key|[^\"\\\\]*(?:password|passwd|secret|token|secretkey|accesskey|accesskeyid|privatekey"
                    + "|sshkey|apikey|credentials?|keytab))\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    /** A JSON string whose content starts with the marker, as Gson writes it ({@code \\u0000} escaped). */
    private static final java.util.regex.Pattern MARKED_JSON_STRING =
            java.util.regex.Pattern.compile("\"\\\\u0000kdps-text:(?:[^\"\\\\]|\\\\.)*\"");

    /** A PEM private key, up to its end line (or the end of the text when it is cut). */
    private static final java.util.regex.Pattern PRIVATE_KEY_PEM = java.util.regex.Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----(?s:.*?)(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)");

    /** The security profile fields of type password ({@link SensitiveFields#ofSecurityProfile}). */
    static java.util.Set<String> passwordFields() {
        return SensitiveFields.ofSecurityProfile();
    }

    /**
     * @param raw the setting as text
     * @return Boolean, null, Integer (Long when it does not fit) or the text unchanged
     */
    public static Object typed(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.startsWith(TEXT_PREFIX)) {
            return raw.substring(TEXT_PREFIX.length());
        }
        String t = raw.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return Boolean.TRUE;
        }
        if (lower.equals("false")) {
            return Boolean.FALSE;
        }
        if (lower.equals("null")) {
            return null;
        }
        if (t.equals("0")) {
            return 0;
        }
        // Like strconv.ParseInt(val, 10, 64): optional sign, decimal digits, 64-bit range; never a leading zero.
        if (!t.isEmpty() && t.charAt(0) != '0' && asciiInteger(t)) {
            try {
                long n = Long.parseLong(t);
                return n == (int) n ? (Object) (int) n : (Object) n;
            } catch (NumberFormatException notAnInteger) {
                return raw;
            }
        }
        return raw;
    }

    /** Optional sign then ASCII digits only (Java would also accept other scripts' digits; Go does not). */
    private static boolean asciiInteger(String s) {
        int start = s.charAt(0) == '+' || s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
