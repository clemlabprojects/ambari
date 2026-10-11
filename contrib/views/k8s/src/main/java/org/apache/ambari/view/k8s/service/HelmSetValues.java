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
        return value != null && value.startsWith(TEXT_PREFIX) ? "<hidden>" : value;
    }

    /**
     * The value, kept as text when {@code field} names a password or a secret (its last segment contains "password",
     * "passwd" or "secret", any case), else unchanged.
     *
     * @param field the setting's source field (e.g. {@code ldap.bindPassword})
     * @param value the value
     */
    public static String textIfSecret(String field, String value) {
        if (field == null || value == null) {
            return value;
        }
        String last = field.substring(field.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return last.contains("password") || last.contains("passwd") || last.contains("secret") ? asText(value) : value;
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
