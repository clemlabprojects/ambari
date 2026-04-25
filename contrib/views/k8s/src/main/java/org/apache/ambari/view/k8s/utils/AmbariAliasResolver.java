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
import org.apache.ambari.view.k8s.security.*;
import org.apache.ambari.view.k8s.security.AESEncryptor;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static java.lang.System.getProperty;

public final class AmbariAliasResolver {
    private static final Logger LOG = LoggerFactory.getLogger(AmbariAliasResolver.class);

    private FileBasedCredentialStore persistedCredentialStore = null;

    private Map<String, String> properties;
    // Mimic Ambari Configuration.java

    private ViewContext ctx = null;

    public static final ConfigurationProperty<String> SRVR_KSTR_DIR = new ConfigurationProperty<>(
            "security.server.keys_dir", ".");

    public static final String MASTER_KEYSTORE_LOCATION = "security.master.keystore.location";

    public static final String MASTER_KEYSTORE_DEFAULT_LOCATION = "/var/lib/ambari-server/keys/master";

    private static String opt(String v, String def) {
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }
    private char[] master = null;
    private static final String MASTER_PASSPHRASE = "masterpassphrase";
    private static final String MASTER_PERSISTENCE_TAG_PREFIX = "#1.0# ";
    private static final AESEncryptor aes = new AESEncryptor(MASTER_PASSPHRASE);
    private CredentialProvider credentialProvider;

    public AmbariAliasResolver(ViewContext ctx){
        this.properties = ctx.getProperties();
        this.ctx = ctx;
        persistedCredentialStore = new FileBasedCredentialStore(this.getMasterKeyStoreLocation());
        MasterKeyService masterKeyService = null;
        LOG.info("Persisted Crendential Store file at path: {}", persistedCredentialStore.getKeyStorePath().getAbsolutePath());
        masterKeyService = new MasterKeyServiceImpl();
        persistedCredentialStore.setMasterKeyService(masterKeyService);
        this.credentialProvider = new CredentialProvider(this.persistedCredentialStore);

    }

    public <T> String getProperty(ConfigurationProperty<T> configurationProperty) {
        String defaultStringValue = null;
        if (null != configurationProperty.getDefaultValue()) {
            defaultStringValue = String.valueOf(configurationProperty.getDefaultValue());
        }

        return properties.get(configurationProperty.getKey());
    }
    public File getServerKeyStoreDirectory() {
        String path = getProperty(SRVR_KSTR_DIR);
        return ((path == null) || path.isEmpty())
                ? new File(".")
                : new File(path);
    }

    public char[] getPasswordForAlias(String alias) throws AmbariException {
        Credential credential = (CredentialProvider.isAliasString(alias))
            ? persistedCredentialStore.getCredential(CredentialProvider.getAliasFromString(alias))
            : persistedCredentialStore.getCredential(alias);

        return (credential instanceof GenericKeyCredential)
            ? ((GenericKeyCredential) credential).getKey()
            : null;
    }

    /**
     * Reads the OIDC admin credential from the Ambari credential store.
     * The credential is stored at alias "oidc.admin.credential" (canonicalized by cluster name)
     * as a PrincipalKeyCredential containing the admin username (principal) and password (key).
     * This is the same alias used by ConfigureOidcServerAction on the server side.
     *
     * @param clusterName the cluster name used to canonicalize the alias
     * @return the PrincipalKeyCredential, or null if not found or wrong type
     */
    public PrincipalKeyCredential getOidcAdminCredential(String clusterName) {
        try {
            String alias = canonicalizeAlias(clusterName, "oidc.admin.credential");
            LOG.info("Reading OIDC admin credential from store, alias='{}'", alias);
            Credential credential = persistedCredentialStore.getCredential(alias);
            if (credential instanceof PrincipalKeyCredential pkc) {
                LOG.info("Resolved OIDC admin credential: principal='{}'", pkc.getPrincipal());
                return pkc;
            }
            LOG.warn("OIDC admin credential alias '{}' not found or not a PrincipalKeyCredential (got {})",
                alias, credential == null ? "null" : credential.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            LOG.warn("Failed to read OIDC admin credential from store: {}", e.toString());
            return null;
        }
    }
    public void addAliasToCredentialStore(String alias, String password) throws AmbariException {
        this.credentialProvider.addAliasToCredentialStore(alias, password);
    }
    public File getMasterKeyStoreLocation() {
        final String keysDir = opt(ctx.getAmbariProperty("security.server.keys_dir"),
                "/var/lib/ambari-server/keys");
        final Path credPath = Paths.get(
                opt(ctx.getAmbariProperty("security.server.credential_store_path"),
                        Paths.get(keysDir, "credentials.jceks").toString()));
        LOG.info("  keysDir          : {}", keysDir);
        LOG.info("  credential store : {}", credPath);
        return credPath.toFile();
    }


    /**
     * Resolve ${alias=...} via Ambari's credential store (credentials.jceks).
     * If the input is not an alias expression, returns it unchanged.
     *
     * Deterministic flow:
     *  - keysDir = ambari.prop 'security.server.keys_dir' or /var/lib/ambari-server/keys
     *  - store   = keysDir + /credentials.jceks
     *  - keypass = contents of keysDir + /pass.txt  (trimmed)
     *  - keystore.load(..., null)  // Ambari creates JCEKS with empty store password
     *  - getKey(alias, keypass)
     */
    public char[] resolve(ViewContext ctx, String raw) {
        if (raw == null || raw.isBlank()) return new char[0];
        final String v = raw.trim();

        if (!v.startsWith("${alias=") || !v.endsWith("}")) {
            LOG.info("Value is not an alias expression; returning as-is.");
            return v.toCharArray();
        }

        final String alias = v.substring("${alias=".length(), v.length() - 1).trim();
        Objects.requireNonNull(alias, "alias");
        LOG.info("Resolving Ambari alias '{}'", alias);


        try {
            LOG.info("Found aliases {}", this.persistedCredentialStore.listCredentials().toString());
            char[] pwd = getPasswordForAlias(alias);
            LOG.info("Resolved alias '{}' from credential store", alias);
            return pwd;
        } catch (Exception e) {
            // scrub
            throw new IllegalStateException("Failed to resolve alias '" + alias , e);
        }
    }

    /**
     * Canonicalizes an alias name by making sure that is contains the prefix indicating what cluster it belongs to.
     * This helps to reduce collisions of alias between clusters pointing to the same keystore files.
     * <p/>
     * Each alias is expected to have a prefix of <code>cluster.:clusterName.</code>, and the
     * combination is to be converted to have all lowercase characters.  For example if the alias was
     * "external.DB" and the cluster name is "c1", then the canonicalized alias name would be
     * "cluster.c1.external.db".
     *
     * @param clusterName the name of the cluster
     * @param alias       a string declaring the alias (or name) of the credential
     * @return a ccanonicalized alias name
     */
    public static String canonicalizeAlias(String clusterName, String alias) {
        String canonicaizedAlias;

        if ((clusterName == null) || clusterName.isEmpty() || (alias == null) || alias.isEmpty()) {
            canonicaizedAlias = alias;
        } else {
            String prefix = createAliasPrefix(clusterName);

            if (alias.toLowerCase().startsWith(prefix)) {
                canonicaizedAlias = alias;
            } else {
                canonicaizedAlias = prefix + alias;
            }
        }

        return (canonicaizedAlias == null)
                ? null
                : canonicaizedAlias.toLowerCase();
    }

    /**
     * Creates the prefix that is to be set in a canonicalized alias name.
     *
     * @param clusterName the name of the cluster
     * @return the prefix value
     */
    private static String createAliasPrefix(String clusterName) {
        return ("cluster." + clusterName + ".").toLowerCase();
    }

    /**
     * Gets either the persisted or temporary CredentialStore as requested
     *
     * @return a CredentialStore implementation
     */
    public CredentialStore getCredentialStore() {
        return persistedCredentialStore;
    }
    /**
     * The {@link ConfigurationProperty} class is used to wrap an Ambari property
     * key, type, and default value.
     *
     * @param <T>
     */
    public static class ConfigurationProperty<T> implements Comparable<ConfigurationProperty<?>> {

        private final String m_key;
        private final T m_defaultValue;

        /**
         * Constructor.
         *
         * @param key
         *          the property key name (not {@code null}).
         * @param defaultValue
         *          the default value or {@code null} for none.
         */
        public ConfigurationProperty(String key, T defaultValue) {
            m_key = key;
            m_defaultValue = defaultValue;
        }

        /**
         * Gets the key.
         *
         * @return the key (never {@code null}).
         */
        public String getKey(){
            return m_key;
        }

        /**
         * Gets the default value for this key if its undefined.
         *
         * @return
         */
        public T getDefaultValue() {
            return m_defaultValue;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return m_key.hashCode();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj == null) {
                return false;
            }

            if (getClass() != obj.getClass()) {
                return false;
            }

            ConfigurationProperty<?> other = (ConfigurationProperty<?>) obj;
            return StringUtils.equals(this.m_key, other.m_key);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return m_key;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int compareTo(ConfigurationProperty<?> o) {
            return this.m_key.compareTo(o.m_key);
        }
    }


}