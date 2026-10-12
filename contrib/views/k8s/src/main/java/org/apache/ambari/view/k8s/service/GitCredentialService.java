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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewInstanceDefinition;
import org.apache.ambari.view.k8s.security.AmbariException;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the Git tokens and SSH keys of GitOps releases in Ambari's credential store.
 * Only aliases are stored alongside releases; the raw secret lives in the keystore.
 *
 * <p>Each release has an alias of its own ({@link #releaseAlias}), overwritten in place when its token or key changes,
 * so the store holds one copy per release. A deploy that gives a new one keeps it under the release's pending alias
 * ({@link #pendingAlias}) until it has reached Git with it ({@link #promote}): a mistyped token or a failed move to
 * another Git server leaves the release with the credential that worked. The host of the Git repository a secret was
 * entered for is recorded with its alias ({@link #boundHost}): a stored secret is only ever sent back to that host.</p>
 */
public class GitCredentialService {

    private static final Logger LOG = LoggerFactory.getLogger(GitCredentialService.class);

    /** Prefix of the aliases this view gives each release. */
    public static final String RELEASE_ALIAS_PREFIX = "kdps.gitops.";

    /** Prefix of the aliases earlier versions stored a copy of the secret under at every deploy. */
    public static final String LEGACY_ALIAS_PREFIX = "gitops-";

    /**
     * Prefix of the alias holding the token or SSH key a deploy gave, until the deploy has reached Git with it. Not a
     * suffix of the release's alias: a release name may contain dots, so "name.pending" could be another release's
     * alias.
     */
    private static final String PENDING_ALIAS_PREFIX = "kdps.gitops-pending.";

    /** Prefix of the alias holding the access token a deploy gave next to an SSH key, for that deploy only. */
    private static final String PENDING_TOKEN_ALIAS_PREFIX = "kdps.gitops-pending-token.";

    /** View setting holding the host of the Git repository the secret under an alias was entered for. */
    private static final String HOST_SETTING_PREFIX = "gitops.credential.host.";

    /** View setting set once the copies left by earlier versions were deleted. */
    private static final String LEGACY_CLEANUP_DONE = "gitops.credential.legacyCleanupDone";

    private final ViewContext viewContext;
    private final AmbariAliasResolver aliasResolver;
    private final InstanceSettings settings;

    public GitCredentialService(ViewContext ctx) {
        this(ctx, new AmbariAliasResolver(ctx));
    }

    /**
     * @param ctx           the view instance, whose settings keep the host each secret was entered for
     * @param aliasResolver the credential store the secrets are kept in
     */
    public GitCredentialService(ViewContext ctx, AmbariAliasResolver aliasResolver) {
        this.viewContext = ctx;
        this.aliasResolver = aliasResolver;
        this.settings = new InstanceSettings(ctx);
    }

    /**
     * The alias of a release's Git token or SSH key. Ambari's credential store is shared by every view instance, so
     * the instance is part of it.
     *
     * @param instanceName the view instance, {@code null} when unknown
     * @param namespace    the release namespace
     * @param release      the release name
     * @return the alias, in lower case as the credential store keeps it
     */
    public static String releaseAlias(String instanceName, String namespace, String release) {
        return alias(RELEASE_ALIAS_PREFIX, instanceName, namespace, release);
    }

    /**
     * The alias a deploy keeps the token or SSH key it gives under until it has reached Git with it: only then does
     * the secret become the release's ({@link #promote}).
     */
    public static String pendingAlias(String instanceName, String namespace, String release) {
        return alias(PENDING_ALIAS_PREFIX, instanceName, namespace, release);
    }

    /** The alias of the access token a deploy gives next to an SSH key, which only that deploy's commit uses. */
    public static String pendingTokenAlias(String instanceName, String namespace, String release) {
        return alias(PENDING_TOKEN_ALIAS_PREFIX, instanceName, namespace, release);
    }

    private static String alias(String prefix, String instanceName, String namespace, String release) {
        String instance = instanceName == null || instanceName.isBlank() ? "default" : instanceName.trim();
        return (prefix + instance + "." + namespace + "." + release).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this view created the alias: one of a release or of a deploy, or a copy an earlier version stored.
     * Ambari's credential store holds other secrets too (its database and LDAP passwords, for two), which are never
     * Git credentials.
     *
     * @param alias the alias, may be {@code null}
     */
    public static boolean isViewAlias(String alias) {
        if (alias == null) {
            return false;
        }
        String lower = alias.toLowerCase(Locale.ROOT);
        return lower.startsWith(RELEASE_ALIAS_PREFIX) || lower.startsWith(PENDING_ALIAS_PREFIX)
                || lower.startsWith(PENDING_TOKEN_ALIAS_PREFIX) || lower.startsWith(LEGACY_ALIAS_PREFIX);
    }

    /**
     * The host of a Git repository URL, which a stored secret is bound to: lower case, without user or port. Read as
     * JGit reads it when it fetches or pushes, and none when the pull request calls, which read the URL with
     * {@link URI}, would reach another host: '#' and '?' end the host for {@link URI} but not for JGit, so
     * {@code https://git.example.org#@other.example/x.git} names two hosts. A local repository ({@code file:} or a
     * path) has the empty host.
     *
     * @param repoUrl {@code https://host/org/repo.git}, {@code ssh://git@host:22/org/repo.git},
     *                {@code git@host:org/repo.git} or a local path
     * @return the host, or {@code null} when the URL is blank, cannot be read or does not name one host
     */
    public static String hostOf(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        String url = repoUrl.trim();
        // No Git URL needs them, and the two parsers read them differently.
        if (url.indexOf('#') >= 0 || url.indexOf('?') >= 0 || url.indexOf('\\') >= 0) {
            return null;
        }
        URIish transport;
        try {
            transport = new URIish(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if ("file".equalsIgnoreCase(transport.getScheme())) {
            return "";
        }
        String host = transport.getHost();
        if (host == null || host.isBlank()) {
            // A local path; a URL with a scheme and no host reaches nothing.
            return transport.getScheme() == null ? "" : null;
        }
        if (url.contains("://")) {
            try {
                String read = URI.create(url).getHost();
                if (read == null || !read.equalsIgnoreCase(host)) {
                    return null;
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a Git repository URL that does not name one host ({@link #hostOf}): a Git credential sent through it
     * could reach another server than the one it shows.
     *
     * @param repoUrl the URL; {@code null} or blank is left to the caller
     * @throws IllegalArgumentException when the URL does not name one host
     */
    public static void checkRepoUrl(String repoUrl) {
        if (repoUrl != null && !repoUrl.isBlank() && hostOf(repoUrl) == null) {
            // The URL itself is not repeated: it may hold a user and password.
            throw new IllegalArgumentException("The Git repository URL cannot be used: it must name one Git server, as"
                    + " in https://git.example.org/org/repo.git or git@git.example.org:org/repo.git, without '#', '?'"
                    + " or '\\'.");
        }
    }

    /**
     * Stores a secret string (token or SSH private key) in the credential store under the given alias, replacing what
     * the alias held, and records the host of the Git repository it was entered for.
     *
     * @param alias   the alias, one of {@link #pendingAlias}, {@link #pendingTokenAlias} or {@link #releaseAlias}
     * @param secret  raw secret (token or key)
     * @param repoUrl the Git repository the secret was entered for
     * @return the alias, or {@code null} if the secret was empty, the URL does not name one host ({@link #hostOf}) or
     *         the secret could not be stored
     */
    public String storeSecret(String alias, String secret, String repoUrl) {
        if (secret == null || secret.isBlank() || alias == null || alias.isBlank()) {
            return null;
        }
        String host = hostOf(repoUrl);
        if (host == null) {
            // Never stored without the host it is for: it could not be used, nor told apart from one that can.
            LOG.warn("Did not store git credential alias {}: its repository URL does not name one Git server", alias);
            return null;
        }
        return store(alias, secret, host) ? alias : null;
    }

    /**
     * Makes the secret a deploy kept under a pending alias the release's, once the deploy has reached Git with it:
     * copied to the release's alias with the host it was entered for, replacing what the release had there, then
     * deleted from the pending alias.
     *
     * @param pendingAlias the alias the deploy kept the secret under ({@link #pendingAlias})
     * @param alias        the release's alias ({@link #releaseAlias})
     * @return {@code alias}, or {@code null} when the pending alias holds nothing or the secret could not be copied;
     *         the pending alias is then kept
     */
    public String promote(String pendingAlias, String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String secret = resolveSecret(pendingAlias);
        String host = boundHost(pendingAlias);
        if (secret == null || host == null || !store(alias, secret, host)) {
            return null;
        }
        removeSecret(pendingAlias);
        return alias;
    }

    /** Stores a secret under an alias with the host it is for; {@code false} when nothing usable was stored. */
    private boolean store(String alias, String secret, String host) {
        try {
            aliasResolver.addAliasToCredentialStore(alias, secret);
        } catch (AmbariException | RuntimeException e) {
            LOG.warn("Failed to persist git credential alias {}: {}", alias, e.toString());
            return false;
        }
        try {
            settings.put(hostSetting(alias), host);
        } catch (RuntimeException e) {
            // Without the host the secret can never be used: it is not left in the store either.
            LOG.warn("Failed to record the Git host of credential alias {}: {}", alias, e.toString());
            removeSecret(alias);
            return false;
        }
        return true;
    }

    /**
     * The host of the Git repository the secret under an alias was entered for.
     *
     * @param alias the alias
     * @return the host ({@code ""} for a local repository), or {@code null} when none was recorded
     */
    public String boundHost(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return settings.get(hostSetting(alias));
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

    /**
     * Deletes a secret this view stored, and the host recorded with it. Aliases the view did not create are never
     * deleted.
     *
     * @param alias the alias
     * @return {@code true} when nothing is left under the alias
     */
    public boolean removeSecret(String alias) {
        if (!isViewAlias(alias)) {
            return false;
        }
        boolean removed;
        try {
            aliasResolver.removeAlias(alias);
            removed = true;
        } catch (AmbariException | RuntimeException e) {
            LOG.warn("Failed to delete git credential alias {}: {}", alias, e.toString());
            removed = false;
        }
        try {
            if (settings.get(hostSetting(alias)) != null) {
                settings.remove(hostSetting(alias));
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to forget the Git host of credential alias {}: {}", alias, e.toString());
        }
        return removed;
    }

    /**
     * Deletes, once per view instance, the copies of Git secrets earlier versions stored at every deploy and that no
     * release refers to any more. Skipped while another instance of this view shares Ambari's credential store, since
     * its releases cannot be seen from here, and when the instance is not known to Ambari.
     *
     * @param referencedAliases the aliases the releases of this instance push with
     * @return the number of aliases deleted, {@code -1} when the cleanup did not run
     */
    public int removeUnreferencedLegacySecrets(Collection<String> referencedAliases) {
        if ("true".equals(settings.get(LEGACY_CLEANUP_DONE))) {
            return -1;
        }
        if (!onlyInstanceOfTheView()) {
            LOG.info("Kept the Git credentials earlier versions of KDPS stored: another KDPS view instance may use them");
            return -1;
        }
        Set<String> referenced = new HashSet<>();
        if (referencedAliases != null) {
            referencedAliases.stream().filter(a -> a != null && !a.isBlank())
                    .forEach(a -> referenced.add(a.toLowerCase(Locale.ROOT)));
        }
        Set<String> stored;
        try {
            stored = aliasResolver.listAliases();
        } catch (AmbariException | RuntimeException e) {
            LOG.warn("Could not list the credential store to delete old Git credential copies: {}", e.toString());
            return -1;
        }
        Set<String> orphans = new TreeSet<>();
        for (String alias : stored == null ? Set.<String>of() : stored) {
            String lower = alias.toLowerCase(Locale.ROOT);
            if (lower.startsWith(LEGACY_ALIAS_PREFIX) && !referenced.contains(lower)) {
                orphans.add(alias);
            }
        }
        int removed = 0;
        for (String alias : orphans) {
            if (removeSecret(alias)) {
                removed++;
            }
        }
        if (removed == orphans.size()) {
            settings.put(LEGACY_CLEANUP_DONE, "true");
        }
        if (removed > 0) {
            LOG.info("Deleted {} copies of Git credentials that earlier versions of KDPS stored and no release uses", removed);
        }
        return removed;
    }

    /** Whether Ambari knows this view instance and no other instance of the same view. */
    private boolean onlyInstanceOfTheView() {
        Collection<ViewInstanceDefinition> instances;
        try {
            instances = viewContext.getViewInstanceDefinitions();
        } catch (RuntimeException e) {
            return false;
        }
        String viewName = viewContext.getViewDefinition() == null ? null : viewContext.getViewDefinition().getViewName();
        if (instances == null || viewName == null) {
            return false;
        }
        boolean self = false;
        for (ViewInstanceDefinition instance : instances) {
            if (instance == null || instance.getViewDefinition() == null
                    || !viewName.equals(instance.getViewDefinition().getViewName())) {
                continue;
            }
            if (instance.getInstanceName() != null && instance.getInstanceName().equals(viewContext.getInstanceName())
                    && !self) {
                self = true;
            } else {
                return false;
            }
        }
        return self;
    }

    private static String hostSetting(String alias) {
        return HOST_SETTING_PREFIX + alias.toLowerCase(Locale.ROOT);
    }
}
