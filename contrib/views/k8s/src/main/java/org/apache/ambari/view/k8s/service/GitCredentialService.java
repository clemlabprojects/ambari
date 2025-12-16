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

import java.util.UUID;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AmbariException;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to persist Git tokens/SSH keys in Ambari's credential store.
 * Only aliases are stored alongside releases; the raw secret lives in the keystore.
 */
public class GitCredentialService {

    private static final Logger LOG = LoggerFactory.getLogger(GitCredentialService.class);
    private final AmbariAliasResolver aliasResolver;

    public GitCredentialService(ViewContext ctx) {
        this.aliasResolver = new AmbariAliasResolver(ctx);
    }

    /**
     * Store a secret string (token or SSH private key) in the credential store.
     *
     * @param secret raw secret (token or key).
     * @param prefix prefix for alias generation.
     * @return alias name, or null if secret was empty.
     */
    public String storeSecret(String secret, String prefix) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String alias = (prefix == null || prefix.isBlank() ? "gitops" : prefix) + "-" + UUID.randomUUID();
        try {
            aliasResolver.addAliasToCredentialStore(alias, secret);
            return alias;
        } catch (AmbariException e) {
            LOG.warn("Failed to persist git credential alias {}: {}", alias, e.toString());
            return null;
        }
    }

    /**
     * Resolve a stored secret from an alias.
     *
     * @param alias alias to resolve.
     * @return secret value as String, or null if not found.
     */
    public String resolveSecret(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        try {
            char[] value = aliasResolver.getPasswordForAlias(alias);
            return value != null ? new String(value) : null;
        } catch (AmbariException e) {
            LOG.warn("Failed to resolve git credential alias {}: {}", alias, e.toString());
            return null;
        }
    }
}
