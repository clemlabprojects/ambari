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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The KDC account of a platform context: the account KDPS creates the principals of the context's releases with, in a
 * KDC no Ambari cluster manages (the Active Directory of a CDP cluster, a FreeIPA or MIT KDC). It is kept as one
 * record in Ambari's credential store, under {@code kdps.context.<id>.kdc.admin}: the KDC type, realm and address,
 * the account and its password, bound together, so Ambari server's {@code /kerberos/adhoc_keytab} reads all of it from
 * there and the password only ever goes to the KDC it was entered for. The password is never kept in the view's
 * settings nor returned.
 * <p/>
 * Changing what the password is sent to (the KDC type, realm, account, LDAPS URL or KDC hosts) needs the password
 * again; the organizational unit and encryption types may change without it.
 */
public class ContextKdcAccount {

    /** Context fields (their storage keys), from the kerberos capability. */
    public static final String TYPE_KEY = "kdcType";
    public static final String ADMIN_KEY = "kdcAdminPrincipal";
    public static final String PASSWORD_KEY = "kdcAdminPassword";
    public static final String LDAP_URL_KEY = "kdcLdapUrl";
    public static final String CONTAINER_KEY = "kdcContainerDn";
    public static final String ENCRYPTION_TYPES_KEY = "kdcEncryptionTypes";
    public static final String ADMIN_SERVER_KEY = "kdcAdminServerHost";
    static final String REALM_KEY = "realm";
    static final String KDC_HOST_KEY = "kdcHost";

    public static final String ACTIVE_DIRECTORY = "active-directory";
    public static final List<String> TYPES = List.of(ACTIVE_DIRECTORY, "ipa", "mit-kdc");
    static final String DEFAULT_ENCRYPTION_TYPES = "aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96";

    /** The context ids Ambari server accepts in an alias. */
    private static final Pattern CONTEXT_ID = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

    /** The record fields the password is bound to. */
    private static final List<String> BOUND = List.of("kdc_type", "realm", "admin_principal", "ldap_url", "kdc_hosts",
            "admin_server_host");

    private static final Gson GSON = new Gson();

    /** Where the records are kept: Ambari's credential store, or a map in tests. */
    public interface Store {
        void put(String alias, String record) throws Exception;

        /** @return the record, or {@code null} when there is none */
        String get(String alias) throws Exception;

        void remove(String alias) throws Exception;
    }

    /** Ambari's credential store, which Ambari server reads the records from. */
    public static Store credentialStore(AmbariAliasResolver resolver) {
        return new Store() {
            @Override
            public void put(String alias, String record) throws Exception {
                resolver.addAliasToCredentialStore(alias, record);
            }

            @Override
            public String get(String alias) throws Exception {
                char[] value = resolver.getPasswordForAlias(alias);
                return value == null ? null : new String(value);
            }

            @Override
            public void remove(String alias) throws Exception {
                resolver.removeAlias(alias);
            }
        };
    }

    private final Store store;

    public ContextKdcAccount(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The alias a context's KDC record is kept under. */
    public static String aliasOf(String contextId) {
        return "kdps.context." + contextId + ".kdc.admin";
    }

    /**
     * Applies the KDC account of a context being saved: stores its record, updates it, or removes it when the context
     * names no KDC type any more. Nothing is stored when the account is incomplete or would reuse the saved password for
     * another KDC.
     *
     * @param contextId the context id
     * @param config    the context's settings, without secrets
     * @param password  the KDC administrator password entered with this save; blank to keep the saved one
     * @return whether the context holds a KDC account afterwards
     * @throws IllegalArgumentException saying what is missing or must be entered again; nothing was changed
     * @throws IllegalStateException    when Ambari's credential store cannot take the record
     */
    public boolean apply(String contextId, Map<String, Object> config, String password) {
        String type = text(config, TYPE_KEY).toLowerCase(Locale.ROOT);
        if (!namesKdc(config)) {
            remove(contextId);
            return false;
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("The KDC type must be one of " + TYPES + ".");
        }
        if (contextId == null || !CONTEXT_ID.matcher(contextId).matches()) {
            throw new IllegalArgumentException("A context with a KDC account needs an id of lower-case letters, digits and "
                    + "dashes: it names the record kept in Ambari's credential store.");
        }
        JsonObject record = new JsonObject();
        record.addProperty("kdc_type", type);
        record.addProperty("realm", required(config, REALM_KEY, "the context's Kerberos realm"));
        record.addProperty("admin_principal", required(config, ADMIN_KEY, "the KDC administrator account"));
        String encryptionTypes = text(config, ENCRYPTION_TYPES_KEY);
        record.addProperty("encryption_types", encryptionTypes.isEmpty() ? DEFAULT_ENCRYPTION_TYPES : encryptionTypes);
        if (ACTIVE_DIRECTORY.equals(type)) {
            String url = required(config, LDAP_URL_KEY, "the LDAPS URL of the Active Directory");
            if (!url.toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
                throw new IllegalArgumentException("The Active Directory URL must start with ldaps://: the administrator "
                        + "password is never sent over plain LDAP.");
            }
            record.addProperty("ldap_url", url);
            record.addProperty("container_dn", required(config, CONTAINER_KEY,
                    "the organizational unit (container DN) new accounts go to"));
        } else {
            String kdcHost = required(config, KDC_HOST_KEY, "the KDC host");
            String adminServer = text(config, ADMIN_SERVER_KEY);
            record.addProperty("kdc_hosts", kdcHost);
            record.addProperty("admin_server_host", adminServer.isEmpty() ? kdcHost : adminServer);
        }

        String alias = aliasOf(contextId);
        JsonObject saved = read(alias);
        if (password == null || password.isBlank()) {
            if (saved == null || !saved.has("password")) {
                throw new IllegalArgumentException("Enter the KDC administrator password.");
            }
            for (String field : BOUND) {
                if (!Objects.equals(value(saved, field), value(record, field))) {
                    throw new IllegalArgumentException("Enter the KDC administrator password again: the KDC "
                            + field.replace('_', ' ') + " changed, and the saved password is only sent to the KDC "
                            + "it was entered for.");
                }
            }
            record.addProperty("password", saved.get("password").getAsString());
        } else {
            record.addProperty("password", password);
        }
        try {
            store.put(alias, GSON.toJson(record));
        } catch (Exception e) {
            throw new IllegalStateException("The KDC account could not be kept in Ambari's credential store (" + e.getMessage()
                    + "): check that Ambari's master key is persisted (ambari-server setup-security).", e);
        }
        return true;
    }

    /** Whether a context's settings name a KDC type ({@code none} names none). */
    public static boolean namesKdc(Map<String, Object> config) {
        String type = text(config, TYPE_KEY).toLowerCase(Locale.ROOT);
        return !type.isEmpty() && !"none".equals(type);
    }

    /** Removes a context's KDC record, if any. */
    public void remove(String contextId) {
        if (contextId == null || !CONTEXT_ID.matcher(contextId).matches()) {
            return;
        }
        try {
            store.remove(aliasOf(contextId));
        } catch (Exception e) {
            throw new IllegalStateException("The KDC account could not be removed from Ambari's credential store: "
                    + e.getMessage(), e);
        }
    }

    /** Whether a context holds a KDC account. */
    public boolean exists(String contextId) {
        return contextId != null && CONTEXT_ID.matcher(contextId).matches() && read(aliasOf(contextId)) != null;
    }

    private JsonObject read(String alias) {
        String stored;
        try {
            stored = store.get(alias);
        } catch (Exception e) {
            return null;
        }
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(stored);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String value(JsonObject record, String field) {
        JsonElement value = record.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String required(Map<String, Object> config, String key, String what) {
        String value = text(config, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A KDC account needs " + what + ".");
        }
        return value;
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
