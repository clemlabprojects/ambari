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

import org.apache.ambari.view.k8s.store.ProvisioningLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Takes back what KDPS registered for a release outside Kubernetes once the release is uninstalled, as its ledger
 * records it ({@link ProvisioningLedger}): the Ranger access KDPS granted, the Atlas federation user and the
 * OpenMetadata source of Ranger TagSync on the managed cluster, Ambari view instances, Vault copies of the client
 * credentials and, for a release whose sign-in Secrets are already gone, its Keycloak clients. Kerberos principals are
 * only reported: KDPS has no way to delete them.
 *
 * <p>Best effort: every step is tried, a failure is reported with what to do by hand, and nothing is thrown, since the
 * release itself is already gone. Something another release still uses (the same Ranger account, principal, Atlas
 * federation user, view instance or Vault path) is kept and said so.
 */
public final class ExternalCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalCleanup.class);

    /** How far the uninstall of a release goes outside Kubernetes. */
    public enum Mode {
        /** Everything that gives access (the default); the Ranger repositories of the release are kept. */
        CREDENTIALS,
        /** Also the Ranger repositories KDPS created for the release, with every policy in them. */
        ALL,
        /** Nothing: what KDPS registered is listed for the operator to remove. */
        NONE;

        /** The mode a request names; {@link #CREDENTIALS} when it names none. */
        public static Mode of(String value) {
            if (value == null || value.isBlank()) return CREDENTIALS;
            String v = value.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "all" -> ALL;
                case "none" -> NONE;
                case "credentials" -> CREDENTIALS;
                default -> throw new IllegalArgumentException("externalCleanup must be 'credentials', 'all' or 'none',"
                        + " not '" + value + "'");
            };
        }
    }

    /** The systems the cleanup acts on. Every call may throw: the failure is then reported. */
    public interface Platform {
        /** Takes back a Ranger grant; returns how many policies changed. */
        int revokeRangerGrant(ProvisioningLedger.RangerGrant grant) throws Exception;

        /** Deletes a Ranger repository; false when Ranger has none of that name. */
        boolean deleteRangerRepository(ProvisioningLedger.RangerRepository repository) throws Exception;

        /** Deletes a Keycloak client KDPS registered for the release, and says what was done. */
        String deleteKeycloakClient(ProvisioningLedger.OidcClient client) throws Exception;

        /** The properties of a config type of the managed cluster. */
        Map<String, String> managedConfig(String type) throws Exception;

        /** Changes properties of a config type of the managed cluster; an empty value removes the key. */
        void updateManagedConfig(String type, Map<String, String> properties, String tagSuffix) throws Exception;

        /** Restarts a component of the managed cluster; returns the Ambari request id. */
        Integer restartManagedComponent(String service, String component, String context) throws Exception;

        /** Deletes an Ambari view instance; false when there is none. */
        boolean deleteViewInstance(ProvisioningLedger.ViewInstance instance) throws Exception;

        /** Deletes a Vault path; false when no Vault is configured for the view. */
        boolean deleteVaultPath(String path) throws Exception;
    }

    /** One thing left behind: what, why, and the command or action that removes it. */
    public record Leftover(String object, String reason, String command) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("object", object);
            m.put("reason", reason);
            if (command != null) m.put("command", command);
            return m;
        }
    }

    /** What the cleanup did: removed, could not remove ({@code left}), left on purpose ({@code kept}). */
    public record Report(List<String> removed, List<Leftover> left, List<Leftover> kept) {
        public Report {
            removed = List.copyOf(removed);
            left = List.copyOf(left);
            kept = List.copyOf(kept);
        }

        public boolean isEmpty() {
            return removed.isEmpty() && left.isEmpty() && kept.isEmpty();
        }

        /** What the operator reads. */
        public String note() {
            List<String> lines = new ArrayList<>();
            if (!removed.isEmpty()) {
                lines.add("Removed what KDPS registered for the release outside Kubernetes:");
                removed.forEach(r -> lines.add("  " + r));
            }
            if (!left.isEmpty()) {
                lines.add("Could not remove; finish by hand:");
                left.forEach(l -> lines.add(line(l)));
            }
            if (!kept.isEmpty()) {
                lines.add("Left in place:");
                kept.forEach(l -> lines.add(line(l)));
            }
            return String.join("\n", lines);
        }

        private static String line(Leftover l) {
            return "  " + l.object() + " (" + l.reason() + ")" + (l.command() == null ? "" : ": " + l.command());
        }

        /** The report the uninstall answers with: {@code removed}, {@code left}, {@code kept} and {@code note}. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("removed", removed);
            m.put("left", left.stream().map(Leftover::toMap).toList());
            m.put("kept", kept.stream().map(Leftover::toMap).toList());
            m.put("note", note());
            return m;
        }
    }

    /** Keys of the OpenMetadata federation user in {@code atlas-env}. */
    static final List<String> ATLAS_FEDERATION_KEYS = List.of("openmetadata.federation.username",
            "openmetadata.federation.password_hash", "openmetadata.federation.role");
    static final String ATLAS_FILE_AUTH = "atlas.authentication.method.file";
    static final String TAGSYNC_SITE = "ranger-tagsync-site";
    static final String TAGSYNC_SOURCES = "ranger.tagsync.sources";

    private final String namespace;
    private final String releaseName;
    private final Platform platform;
    private final Map<String, ProvisioningLedger> otherReleases;

    private final List<String> removed = new ArrayList<>();
    private final List<Leftover> left = new ArrayList<>();
    private final List<Leftover> kept = new ArrayList<>();

    /**
     * @param otherReleases the ledgers of the other releases, by {@code namespace/release}; null when they cannot be
     *                      read, and then nothing that may be shared is taken back
     */
    ExternalCleanup(String namespace, String releaseName, Platform platform,
                    Map<String, ProvisioningLedger> otherReleases) {
        this.namespace = namespace;
        this.releaseName = releaseName;
        this.platform = platform;
        this.otherReleases = otherReleases;
    }

    /**
     * Takes back what the ledger records. Never throws.
     *
     * @param ledger            what KDPS registered for the release
     * @param mode              how far to go
     * @param signInSecretsSeen whether the release's sign-in is already dealt with: its Secrets were found, and their
     *                          own cleanup deleted its Keycloak clients, or the uninstall asked to keep it. The
     *                          ledger's clients are then left alone
     * @return the report; empty when nothing is recorded
     */
    public static Report run(String namespace, String releaseName, ProvisioningLedger ledger, Mode mode,
                             boolean signInSecretsSeen, Map<String, ProvisioningLedger> otherReleases,
                             Platform platform) {
        ExternalCleanup cleanup = new ExternalCleanup(namespace, releaseName, platform, otherReleases);
        try {
            cleanup.runAll(ledger == null ? new ProvisioningLedger() : ledger, mode, signInSecretsSeen);
        } catch (RuntimeException e) {
            LOG.warn("Cleanup of what KDPS registered for {}/{} outside Kubernetes stopped: {}", namespace,
                    releaseName, e.toString());
            cleanup.left.add(new Leftover("The rest of what KDPS registered for the release", "the cleanup stopped: "
                    + reasonOf(e), null));
        }
        return new Report(cleanup.removed, cleanup.left, cleanup.kept);
    }

    /**
     * What the uninstall of a release would do outside Kubernetes, for its confirmation: the report of a run that
     * changes nothing, with every item it would remove listed as {@code removed}.
     */
    public static Report preview(ProvisioningLedger ledger, Mode mode, Map<String, ProvisioningLedger> otherReleases) {
        return preview(null, ledger, mode, otherReleases);
    }

    /**
     * {@link #preview(ProvisioningLedger, Mode, Map)} for a release of {@code namespace}; without one, every release
     * deployed before KDPS kept ledgers is taken as possibly sharing the accounts KDPS minted.
     */
    public static Report preview(String namespace, ProvisioningLedger ledger, Mode mode,
                                 Map<String, ProvisioningLedger> otherReleases) {
        ProvisioningLedger l = ledger == null ? new ProvisioningLedger() : ledger;
        ExternalCleanup cleanup = new ExternalCleanup(namespace, null, null, otherReleases);
        if (mode == Mode.NONE) {
            cleanup.keepAll(l, "kept, as asked");
            return new Report(List.of(), List.of(), cleanup.kept);
        }
        l.oidcClients().forEach(c -> cleanup.removed.add(describe(c)));
        for (ProvisioningLedger.RangerGrant g : l.rangerGrants()) {
            String sharedWith = cleanup.grantSharedWith(g);
            if (sharedWith != null) {
                cleanup.kept.add(new Leftover(describe(g), cleanup.sharedReason(sharedWith, "uses it"), null));
                continue;
            }
            String unrecorded = g.exclusive() ? cleanup.unrecordedReleaseSharing(g.perNamespace()) : null;
            if (unrecorded != null) {
                cleanup.removed.add(describe(g) + " (from that policy only)");
                cleanup.kept.add(cleanup.otherPoliciesKept(g, unrecorded));
            } else {
                cleanup.removed.add(describe(g));
            }
        }
        for (ProvisioningLedger.RangerRepository r : l.rangerRepositories()) {
            String sharedWith = mode == Mode.ALL ? cleanup.repositorySharedWith(r) : null;
            if (sharedWith != null) cleanup.kept.add(new Leftover(describe(r), cleanup.sharedReason(sharedWith,
                    "records it too"), null));
            else if (mode == Mode.ALL) cleanup.removed.add(describe(r));
            else cleanup.kept.add(new Leftover(describe(r), "kept with the policies in it, unless the uninstall also"
                    + " removes the Ranger repositories", null));
        }
        l.principals().forEach(p -> cleanup.kept.add(new Leftover(describe(p),
                "KDPS cannot delete Kerberos principals; delete it once nothing uses it", null)));
        if (l.atlasFederation() != null) {
            String sharedReason = cleanup.atlasFederationSharedReason();
            if (sharedReason != null) {
                cleanup.kept.add(new Leftover(describe(l.atlasFederation()), sharedReason, null));
            } else {
                cleanup.removed.add(describe(l.atlasFederation()));
            }
        }
        if (l.tagSyncSource() != null) cleanup.removed.add(describe(l.tagSyncSource()));
        for (ProvisioningLedger.ViewInstance v : l.viewInstances()) {
            Leftover keep = cleanup.viewInstanceKept(v);
            if (keep != null) cleanup.kept.add(keep);
            else cleanup.removed.add(describe(v));
        }
        for (String p : l.vaultPaths()) {
            String reason = cleanup.otherReleaseUsing(o -> o.vaultPaths().contains(p));
            if (reason != null) cleanup.kept.add(new Leftover(describeVault(p), reason, null));
            else cleanup.removed.add(describeVault(p));
        }
        if (l.isTruncated()) cleanup.left.add(truncatedLeftover());
        return new Report(cleanup.removed, cleanup.left, cleanup.kept);
    }

    private void runAll(ProvisioningLedger ledger, Mode mode, boolean signInSecretsSeen) {
        if (mode == Mode.NONE) {
            keepAll(ledger, "kept, as the uninstall asked");
            return;
        }
        if (!signInSecretsSeen) {
            ledger.oidcClients().forEach(this::deleteKeycloakClient);
        }
        ledger.rangerGrants().forEach(this::revokeRangerGrant);
        for (ProvisioningLedger.RangerRepository repository : ledger.rangerRepositories()) {
            if (mode == Mode.ALL) {
                deleteRangerRepository(repository);
            } else {
                kept.add(new Leftover(describe(repository), "kept with the policies an operator may have added to it",
                        "delete the service '" + repository.name() + "' in Ranger once they are no longer needed"));
            }
        }
        ledger.principals().forEach(this::reportPrincipal);
        if (ledger.atlasFederation() != null) removeAtlasFederation(ledger.atlasFederation());
        if (ledger.tagSyncSource() != null) removeTagSyncSource(ledger.tagSyncSource());
        ledger.viewInstances().forEach(this::deleteViewInstance);
        ledger.vaultPaths().forEach(this::deleteVaultPath);
        if (ledger.isTruncated()) left.add(truncatedLeftover());
    }

    private void keepAll(ProvisioningLedger ledger, String reason) {
        ledger.oidcClients().forEach(c -> kept.add(new Leftover(describe(c), reason, null)));
        ledger.rangerGrants().forEach(g -> kept.add(new Leftover(describe(g), reason, null)));
        ledger.rangerRepositories().forEach(r -> kept.add(new Leftover(describe(r), reason, null)));
        ledger.principals().forEach(p -> kept.add(new Leftover(describe(p), reason, null)));
        if (ledger.atlasFederation() != null) kept.add(new Leftover(describe(ledger.atlasFederation()), reason, null));
        if (ledger.tagSyncSource() != null) kept.add(new Leftover(describe(ledger.tagSyncSource()), reason, null));
        ledger.viewInstances().forEach(v -> kept.add(new Leftover(describe(v), reason, null)));
        ledger.vaultPaths().forEach(p -> kept.add(new Leftover(describeVault(p), reason, null)));
        if (ledger.isTruncated()) kept.add(truncatedLeftover());
    }

    // ---------- Keycloak ----------

    private void deleteKeycloakClient(ProvisioningLedger.OidcClient client) {
        try {
            String note = platform.deleteKeycloakClient(client);
            if (note != null && note.startsWith("Removed")) {
                removed.add(describe(client));
            } else {
                kept.add(new Leftover(describe(client), note == null ? "not removed" : note, null));
            }
        } catch (Exception e) {
            left.add(new Leftover(describe(client), "Keycloak could not be asked: " + reasonOf(e),
                    CommandService.kcadmDeleteCommand(client.clientId(), client.realm())));
        }
    }

    // ---------- Ranger ----------

    private void revokeRangerGrant(ProvisioningLedger.RangerGrant grant) {
        String sharedWith = grantSharedWith(grant);
        if (sharedWith != null) {
            kept.add(new Leftover(describe(grant), sharedReason(sharedWith, "was given the same access"), null));
            return;
        }
        // A release deployed before KDPS kept ledgers may sign in with the same account and hold the same access in a
        // policy of its own or a default one: only the policy named for this release is then changed.
        String unrecorded = grant.exclusive() ? unrecordedReleaseSharing(grant.perNamespace()) : null;
        ProvisioningLedger.RangerGrant toRevoke = unrecorded == null ? grant
                : new ProvisioningLedger.RangerGrant(grant.contextId(), grant.service(), grant.user(), grant.access(),
                        grant.resources(), grant.policy(), false, grant.perNamespace());
        try {
            int changed = platform.revokeRangerGrant(toRevoke);
            removed.add(describe(grant) + (unrecorded == null ? "" : " (from that policy only)")
                    + (changed == 0 ? " (already gone)" : ""));
            if (unrecorded != null) kept.add(otherPoliciesKept(grant, unrecorded));
        } catch (Exception e) {
            LOG.warn("Uninstall of {}/{}: Ranger access of '{}' on '{}' not taken back: {}", namespace, releaseName,
                    grant.user(), grant.service(), e.toString());
            left.add(new Leftover(describe(grant), "Ranger could not be changed: " + reasonOf(e),
                    "in Ranger, remove '" + grant.access() + "' of " + grant.user() + " from the policies of service '"
                            + grant.service() + "'" + (grant.policy() == null ? "" : ", and delete the policy '"
                            + grant.policy() + "' if it is left empty")));
        }
    }

    /** Why something shared is kept: the release that shares it, or that the other releases could not be read. */
    private String sharedReason(String sharedWith, String what) {
        return sharedWith.isEmpty() ? "other releases may use it (they could not be read)"
                : "release " + sharedWith + " " + what;
    }

    /**
     * The release that holds the same Ranger account on the same service, or null; empty when the other releases
     * could not be read.
     */
    private String grantSharedWith(ProvisioningLedger.RangerGrant grant) {
        if (otherReleases == null) return "";
        for (Map.Entry<String, ProvisioningLedger> other : otherReleases.entrySet()) {
            for (ProvisioningLedger.RangerGrant g : other.getValue().rangerGrants()) {
                if (Objects.equals(g.user(), grant.user()) && Objects.equals(g.service(), grant.service())
                        && sameContext(g.contextId(), grant.contextId())) {
                    return other.getKey();
                }
            }
        }
        return null;
    }

    /**
     * A release deployed before KDPS kept ledgers that may sign in with the same account, or null. An account KDPS
     * minted for the release's namespace ({@code perNamespace}, recorded when the account was named) is only shared
     * within it; any other may be shared by a release of any namespace. Without a namespace (a preview made without
     * one) every such release may share it.
     */
    private String unrecordedReleaseSharing(boolean perNamespace) {
        if (otherReleases == null) return null;
        boolean namespaced = namespace != null && perNamespace;
        for (Map.Entry<String, ProvisioningLedger> other : otherReleases.entrySet()) {
            if (!other.getValue().isUnrecorded()) continue;
            if (!namespaced || other.getKey().startsWith(namespace + "/")) return other.getKey();
        }
        return null;
    }

    /** The access of a minted account left in policies other than the release's own, for a release that may use it. */
    private Leftover otherPoliciesKept(ProvisioningLedger.RangerGrant grant, String unrecorded) {
        return new Leftover("Ranger access '" + grant.access() + "' of " + grant.user() + " on service '"
                + grant.service() + "' in its other policies", "release " + unrecorded + " was deployed by an earlier"
                + " version of KDPS and may use the same account",
                "in Ranger, remove '" + grant.access() + "' of " + grant.user() + " from the policies of service '"
                        + grant.service() + "' once that release is gone");
    }

    /** The other release that records the same Ranger repository, or null; empty when they could not be read. */
    private String repositorySharedWith(ProvisioningLedger.RangerRepository repository) {
        if (otherReleases == null) return "";
        for (Map.Entry<String, ProvisioningLedger> other : otherReleases.entrySet()) {
            for (ProvisioningLedger.RangerRepository r : other.getValue().rangerRepositories()) {
                if (Objects.equals(r.name(), repository.name()) && sameContext(r.contextId(), repository.contextId())) {
                    return other.getKey();
                }
            }
        }
        return null;
    }

    private void deleteRangerRepository(ProvisioningLedger.RangerRepository repository) {
        String sharedWith = repositorySharedWith(repository);
        if (sharedWith != null) {
            kept.add(new Leftover(describe(repository), sharedReason(sharedWith, "records it too"), null));
            return;
        }
        try {
            boolean deleted = platform.deleteRangerRepository(repository);
            removed.add(describe(repository) + (deleted ? "" : " (already gone)"));
        } catch (Exception e) {
            left.add(new Leftover(describe(repository), "it could not be deleted: " + reasonOf(e),
                    "delete the service '" + repository.name() + "' in Ranger (Service Manager)"));
        }
    }

    // ---------- Kerberos ----------

    private void reportPrincipal(ProvisioningLedger.Principal principal) {
        String sharedWith = null;
        if (otherReleases != null) {
            for (Map.Entry<String, ProvisioningLedger> other : otherReleases.entrySet()) {
                if (other.getValue().principals().stream()
                        .anyMatch(p -> Objects.equals(p.principal(), principal.principal()))) {
                    sharedWith = other.getKey();
                    break;
                }
            }
        }
        if (sharedWith != null || otherReleases == null) {
            kept.add(new Leftover(describe(principal), sharedWith != null ? "release " + sharedWith + " uses it"
                    : "other releases may use it (they could not be read)", null));
            return;
        }
        // Whether a principal is minted per namespace is not recorded: any earlier release may share it.
        String unrecorded = unrecordedReleaseSharing(false);
        if (unrecorded != null) {
            kept.add(new Leftover(describe(principal), "release " + unrecorded + " was deployed by an earlier version of"
                    + " KDPS and may use it", null));
            return;
        }
        // Its keytab Secret goes with the release's other objects; the principal itself stays in the KDC.
        left.add(new Leftover(describe(principal), "KDPS cannot delete Kerberos principals; no other release uses it",
                "kadmin -q \"delprinc -force " + principal.principal() + "\""));
    }

    // ---------- Atlas ----------

    /**
     * Why another release may still sign in to Atlas as the federation user, or null when none does.
     * {@code atlas-env} holds one federation user for the whole cluster, under a fixed default name, so any other
     * release with one shares it, and so may a release deployed before KDPS kept ledgers, whose ledger cannot say.
     */
    private String atlasFederationSharedReason() {
        return otherReleaseUsing(o -> o.atlasFederation() != null);
    }

    private void removeAtlasFederation(ProvisioningLedger.AtlasFederation federation) {
        String sharedReason = atlasFederationSharedReason();
        if (sharedReason != null) {
            kept.add(new Leftover(describe(federation), sharedReason, null));
            return;
        }
        try {
            Map<String, String> env = platform.managedConfig("atlas-env");
            String current = env == null ? null : env.get(ATLAS_FEDERATION_KEYS.get(0));
            if (current == null || current.isBlank()) {
                removed.add(describe(federation) + " (already gone)");
            } else if (!current.trim().equals(federation.username())) {
                kept.add(new Leftover(describe(federation), "atlas-env now holds the user '" + current.trim()
                        + "', which is not this release's", null));
                return;
            } else {
                Map<String, String> props = new LinkedHashMap<>();
                ATLAS_FEDERATION_KEYS.forEach(k -> props.put(k, ""));
                platform.updateManagedConfig("atlas-env", props, "kdps-om-atlas-federation-removed");
                removed.add(describe(federation));
            }
            if (federation.fileAuthTurnedOn()) {
                platform.updateManagedConfig("application-properties", Map.of(ATLAS_FILE_AUTH, "false"),
                        "kdps-om-atlas-fileauth-removed");
                removed.add("Atlas file authentication, which KDPS turned on for it");
            }
            // Atlas reads its user store and authentication methods at start only; restarting it is the operator's call.
            left.add(new Leftover("Atlas restart", "Atlas keeps the federation user until it restarts",
                    "restart Atlas from Ambari"));
        } catch (Exception e) {
            left.add(new Leftover(describe(federation), "atlas-env could not be changed: " + reasonOf(e),
                    "in Ambari, remove " + String.join(", ", ATLAS_FEDERATION_KEYS) + " from atlas-env"
                            + (federation.fileAuthTurnedOn() ? ", set " + ATLAS_FILE_AUTH + "=false in"
                            + " application-properties" : "") + " and restart Atlas"));
        }
    }

    // ---------- Ranger TagSync ----------

    private void removeTagSyncSource(ProvisioningLedger.TagSyncSource source) {
        String prefix = "ranger.tagsync.source." + source.sourceKey();
        try {
            Map<String, String> site = platform.managedConfig(TAGSYNC_SITE);
            String endpoint = site == null ? null : site.get(prefix + ".endpoint");
            if (endpoint == null || endpoint.isBlank()) {
                removed.add(describe(source) + " (already gone)");
                return;
            }
            if (source.endpoint() != null && !source.endpoint().equals(endpoint.trim())) {
                kept.add(new Leftover(describe(source), "Ranger TagSync now reads another OpenMetadata ("
                        + endpoint.trim() + ")", null));
                return;
            }
            Map<String, String> props = new LinkedHashMap<>();
            for (String key : site.keySet()) {
                if (key.equals(prefix) || key.startsWith(prefix + ".")) props.put(key, "");
            }
            if (source.mapperKey() != null && site.containsKey(source.mapperKey())) props.put(source.mapperKey(), "");
            String sources = site.get(TAGSYNC_SOURCES);
            if (sources != null) {
                List<String> rest = new ArrayList<>();
                for (String s : sources.split(",")) {
                    if (!s.trim().isEmpty() && !s.trim().equals(source.sourceKey())) rest.add(s.trim());
                }
                props.put(TAGSYNC_SOURCES, String.join(",", rest));
            }
            platform.updateManagedConfig(TAGSYNC_SITE, props, "kdps-om-tagsync-removed-" + releaseName);
            removed.add(describe(source));
            try {
                Integer request = platform.restartManagedComponent("RANGER", "RANGER_TAGSYNC",
                        "KDPS: restart Ranger TagSync without the OpenMetadata source of " + releaseName);
                removed.add("Ranger TagSync restart requested" + (request == null ? "" : " (Ambari request " + request + ")"));
            } catch (Exception e) {
                left.add(new Leftover("Ranger TagSync restart", "it could not be requested: " + reasonOf(e),
                        "restart Ranger TagSync from Ambari"));
            }
        } catch (Exception e) {
            left.add(new Leftover(describe(source), "ranger-tagsync-site could not be changed: " + reasonOf(e),
                    "in Ambari, remove the " + prefix + ".* keys from ranger-tagsync-site and restart Ranger TagSync"));
        }
    }

    // ---------- Ambari views, Vault ----------

    /**
     * Why another release may use what {@code records} finds in its ledger, or null when none does: a release that
     * records it, one deployed before KDPS kept ledgers (which may use it unrecorded), or the other releases could
     * not be read.
     */
    private String otherReleaseUsing(java.util.function.Predicate<ProvisioningLedger> records) {
        if (otherReleases == null) return "other releases may use it (they could not be read)";
        String unrecorded = null;
        for (Map.Entry<String, ProvisioningLedger> other : otherReleases.entrySet()) {
            if (other.getValue().isUnrecorded()) {
                if (unrecorded == null) unrecorded = other.getKey();
            } else if (records.test(other.getValue())) {
                return "release " + other.getKey() + " uses it too";
            }
        }
        return unrecorded == null ? null
                : "release " + unrecorded + " was deployed by an earlier version of KDPS and may use it";
    }

    /**
     * Why a view instance is left in place, or null when it is the release's alone: the deploy did not create it, or
     * another release uses it. Its name need not hold the namespace, so releases of the same name in two namespaces
     * share one.
     */
    private Leftover viewInstanceKept(ProvisioningLedger.ViewInstance instance) {
        if (!instance.created()) {
            return new Leftover(describe(instance), "it existed before the release; KDPS only updated it",
                    "delete it in Ambari (Manage Ambari > Views) once nothing uses it");
        }
        String reason = otherReleaseUsing(o -> o.viewInstances().stream().anyMatch(v ->
                Objects.equals(v.view(), instance.view()) && Objects.equals(v.version(), instance.version())
                        && Objects.equals(v.instance(), instance.instance())));
        return reason == null ? null : new Leftover(describe(instance), reason, null);
    }

    private void deleteViewInstance(ProvisioningLedger.ViewInstance instance) {
        Leftover keep = viewInstanceKept(instance);
        if (keep != null) {
            kept.add(keep);
            return;
        }
        try {
            boolean deleted = platform.deleteViewInstance(instance);
            removed.add(describe(instance) + (deleted ? "" : " (already gone)"));
        } catch (Exception e) {
            left.add(new Leftover(describe(instance), "Ambari refused or could not be asked: " + reasonOf(e),
                    "delete it in Ambari (Manage Ambari > Views)"));
        }
    }

    private void deleteVaultPath(String path) {
        String what = describeVault(path);
        // The path a service writes its client credentials to is the same for each of its releases.
        String sharedReason = otherReleaseUsing(o -> o.vaultPaths().contains(path));
        if (sharedReason != null) {
            kept.add(new Leftover(what, sharedReason, null));
            return;
        }
        try {
            if (platform.deleteVaultPath(path)) {
                removed.add(what);
            } else {
                left.add(new Leftover(what, "no Vault is configured for this view any more", "vault kv metadata delete "
                        + path.replaceFirst("/data/", "/")));
            }
        } catch (Exception e) {
            left.add(new Leftover(what, "Vault could not be asked: " + reasonOf(e),
                    "vault kv metadata delete " + path.replaceFirst("/data/", "/")));
        }
    }

    // ---------- wording ----------

    private static Leftover truncatedLeftover() {
        return new Leftover("Some of what KDPS registered for the release", "it did not fit the release's record",
                "look in Keycloak and Ranger for clients and policies named after the release");
    }

    static String describe(ProvisioningLedger.OidcClient c) {
        return "Keycloak client '" + c.clientId() + "'" + (c.realm() == null ? "" : " in realm '" + c.realm() + "'");
    }

    static String describe(ProvisioningLedger.RangerGrant g) {
        return "Ranger access '" + g.access() + "' of " + g.user() + " on service '" + g.service() + "'"
                + (g.policy() == null ? "" : " (policy " + g.policy() + ")");
    }

    static String describe(ProvisioningLedger.RangerRepository r) {
        return "Ranger repository '" + r.name() + "'";
    }

    static String describe(ProvisioningLedger.Principal p) {
        return "Kerberos principal " + p.principal();
    }

    static String describe(ProvisioningLedger.AtlasFederation f) {
        return "Atlas federation user '" + f.username() + "' in atlas-env";
    }

    static String describe(ProvisioningLedger.TagSyncSource s) {
        return "OpenMetadata source '" + s.sourceKey() + "' of Ranger TagSync";
    }

    static String describeVault(String path) {
        return "Vault copy of the client credentials at '" + path + "'";
    }

    static String describe(ProvisioningLedger.ViewInstance v) {
        return "Ambari view instance " + v.view() + "/" + v.version() + "/" + v.instance();
    }

    private static boolean sameContext(String a, String b) {
        return contextKey(a).equals(contextKey(b));
    }

    private static String contextKey(String contextId) {
        return contextId == null || contextId.isBlank() ? ContextService.DEFAULT_CONTEXT_ID : contextId.trim();
    }

    private static String reasonOf(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
