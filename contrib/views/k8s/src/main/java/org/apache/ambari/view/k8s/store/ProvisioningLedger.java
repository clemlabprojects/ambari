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

package org.apache.ambari.view.k8s.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What KDPS registered for a release outside Kubernetes, which the release's uninstall takes back: Keycloak clients,
 * Ranger access and repositories, Kerberos principals, the Atlas federation user, the OpenMetadata source of Ranger
 * TagSync, Ambari view instances and Vault copies. Each deploy step records what it registered as it registers it;
 * the uninstall reads the ledger before the release's record is deleted.
 *
 * <p>Kept as one small JSON value with the release's record ({@link K8sReleaseEntity#getProvisioningJson}), whose
 * column holds {@link InstanceSettings#MAX_VALUE_LENGTH} characters, so it is written compactly (see {@link #toJson}):
 * an entry that would still not fit is not recorded and the ledger says so ({@link #isTruncated}), so the uninstall
 * can tell the operator to look for the rest by hand.
 * Unlike the annotations on a release's Secrets, the ledger cannot be changed by someone who can only edit objects in
 * the release namespace, so the uninstall may act on what it names.
 */
public final class ProvisioningLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A client KDPS registered in Keycloak for the release. */
    public record OidcClient(String clientId, String realm, String contextId) {}

    /**
     * Access KDPS granted in Ranger for the release: {@code access} (comma-separated) to {@code user} over
     * {@code resources} (a JSON object of resource name to values) of {@code service}, in a policy named
     * {@code policy} unless Ranger already had one over those resources, which then got the access instead.
     *
     * @param exclusive whether {@code user} is an account KDPS minted for its releases, which no operator input can
     *                  name and which holds nothing of its own: its access is then taken back from whichever policy
     *                  has it. Any other account (a service user of the platform, an external keytab's principal)
     *                  loses only what the policy named {@code policy} gives it
     * @param perNamespace whether {@code user} is an account KDPS mints for one namespace (its name is built from
     *                     it), which only releases of that namespace can share; set where the account is named, never
     *                     guessed from the name, so an account that is not known to be one is taken as shared by the
     *                     releases of every namespace
     */
    public record RangerGrant(String contextId, String service, String user, String access, String resources,
                              String policy, boolean exclusive, boolean perNamespace) {

        /** A grant to an account that is not known to be minted for one namespace. */
        public RangerGrant(String contextId, String service, String user, String access, String resources,
                           String policy, boolean exclusive) {
            this(contextId, service, user, access, resources, policy, exclusive, false);
        }
    }

    /** A Ranger repository KDPS created for the release. */
    public record RangerRepository(String contextId, String name) {}

    /** A Kerberos principal KDPS had issued for the release, and the Secret its keytab was written to. */
    public record Principal(String principal, String secret) {}

    /**
     * The OpenMetadata federation user KDPS wrote into the managed cluster's {@code atlas-env}, and whether KDPS
     * turned Atlas file authentication on for it.
     */
    public record AtlasFederation(String username, boolean fileAuthTurnedOn) {}

    /** The OpenMetadata tag source KDPS added to the managed cluster's Ranger TagSync, and the mapper key it wrote. */
    public record TagSyncSource(String sourceKey, String endpoint, String mapperKey) {}

    /**
     * An Ambari view instance KDPS provisioned for the release.
     *
     * @param created whether the release's deploy created it; one that already existed (made by an operator, or by
     *                another release of the same name) was only updated, and is never the release's to delete
     */
    public record ViewInstance(String view, String version, String instance, boolean created) {

        /** An instance the release's deploy created. */
        public ViewInstance(String view, String version, String instance) {
            this(view, version, instance, true);
        }

        boolean sameInstance(ViewInstance other) {
            return other != null && Objects.equals(view, other.view) && Objects.equals(version, other.version)
                    && Objects.equals(instance, other.instance);
        }
    }

    private final List<OidcClient> oidcClients = new ArrayList<>();
    private final List<RangerGrant> rangerGrants = new ArrayList<>();
    private final List<RangerRepository> rangerRepositories = new ArrayList<>();
    private final List<Principal> principals = new ArrayList<>();
    private AtlasFederation atlasFederation;
    private TagSyncSource tagSyncSource;
    private final List<ViewInstance> viewInstances = new ArrayList<>();
    private final List<String> vaultPaths = new ArrayList<>();
    private boolean truncated;
    private boolean unrecorded;

    /**
     * The ledger of a release deployed before KDPS kept ledgers: what it registered is not known, so the uninstall of
     * another release treats it as possibly sharing what KDPS minted per namespace. Never stored.
     */
    public static ProvisioningLedger unrecorded() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.unrecorded = true;
        return ledger;
    }

    /** Whether this stands for a release deployed before KDPS kept ledgers (see {@link #unrecorded()}). */
    public boolean isUnrecorded() { return unrecorded; }

    public List<OidcClient> oidcClients() { return List.copyOf(oidcClients); }
    public List<RangerGrant> rangerGrants() { return List.copyOf(rangerGrants); }
    public List<RangerRepository> rangerRepositories() { return List.copyOf(rangerRepositories); }
    public List<Principal> principals() { return List.copyOf(principals); }
    public AtlasFederation atlasFederation() { return atlasFederation; }
    public TagSyncSource tagSyncSource() { return tagSyncSource; }
    public List<ViewInstance> viewInstances() { return List.copyOf(viewInstances); }
    public List<String> vaultPaths() { return List.copyOf(vaultPaths); }

    /** Whether something KDPS registered could not be recorded because the ledger was full. */
    public boolean isTruncated() { return truncated; }

    /** Whether nothing is recorded. */
    public boolean isEmpty() {
        return oidcClients.isEmpty() && rangerGrants.isEmpty() && rangerRepositories.isEmpty() && principals.isEmpty()
                && atlasFederation == null && tagSyncSource == null && viewInstances.isEmpty() && vaultPaths.isEmpty()
                && !truncated;
    }

    public void addOidcClient(OidcClient client) { addOnce(oidcClients, client); }

    /** Records a grant; one already recorded for the same user, service and policy is replaced. */
    public void addRangerGrant(RangerGrant grant) {
        if (grant == null) return;
        rangerGrants.removeIf(g -> Objects.equals(g.service(), grant.service()) && Objects.equals(g.user(), grant.user())
                && Objects.equals(g.policy(), grant.policy()) && Objects.equals(g.contextId(), grant.contextId()));
        rangerGrants.add(grant);
    }

    public void addRangerRepository(RangerRepository repository) { addOnce(rangerRepositories, repository); }

    public void addPrincipal(Principal principal) { addOnce(principals, principal); }

    public void setAtlasFederation(AtlasFederation federation) {
        // A redeploy does not undo what an earlier one turned on: the uninstall still has to know.
        if (federation != null && atlasFederation != null && atlasFederation.fileAuthTurnedOn()
                && !federation.fileAuthTurnedOn()) {
            federation = new AtlasFederation(federation.username(), true);
        }
        this.atlasFederation = federation;
    }

    public void setTagSyncSource(TagSyncSource source) { this.tagSyncSource = source; }

    /**
     * Records a view instance; one already recorded with the same coordinates is replaced, and stays created by the
     * release when an earlier deploy created it (a redeploy finds it existing).
     */
    public void addViewInstance(ViewInstance instance) {
        if (instance == null) return;
        boolean created = instance.created();
        for (ViewInstance v : viewInstances) {
            if (v.sameInstance(instance)) created |= v.created();
        }
        viewInstances.removeIf(instance::sameInstance);
        viewInstances.add(new ViewInstance(instance.view(), instance.version(), instance.instance(), created));
    }

    public void addVaultPath(String path) {
        if (path != null && !path.isBlank()) addOnce(vaultPaths, path.trim());
    }

    /** Marks the ledger as missing something KDPS registered. */
    public void markTruncated() { this.truncated = true; }

    private static <T> void addOnce(List<T> list, T value) {
        if (value != null && !list.contains(value)) list.add(value);
    }

    /** Adds everything {@code other} records (a ledger kept for the release before its record existed). */
    public void merge(ProvisioningLedger other) {
        if (other == null) return;
        other.oidcClients.forEach(this::addOidcClient);
        other.rangerGrants.forEach(this::addRangerGrant);
        other.rangerRepositories.forEach(this::addRangerRepository);
        other.principals.forEach(this::addPrincipal);
        if (other.atlasFederation != null) setAtlasFederation(other.atlasFederation);
        if (other.tagSyncSource != null) setTagSyncSource(other.tagSyncSource);
        other.viewInstances.forEach(this::addViewInstance);
        other.vaultPaths.forEach(this::addVaultPath);
        if (other.truncated) markTruncated();
    }

    /** A copy, for a change that may be given up. */
    public ProvisioningLedger copy() {
        ProvisioningLedger copy = new ProvisioningLedger();
        copy.merge(this);
        return copy;
    }

    /**
     * The ledger recorded in {@code json}; empty when there is none. Never throws: a ledger that cannot be read is
     * returned empty and truncated, so the uninstall says that what KDPS registered is not known.
     */
    public static ProvisioningLedger parse(String json) {
        ProvisioningLedger ledger = new ProvisioningLedger();
        if (json == null || json.isBlank()) return ledger;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                ledger.markTruncated();
                return ledger;
            }
            // Entries name their platform context only when it is not the ledger's own ("ctx" at the top).
            String ledgerContext = text(root, "ctx");
            for (JsonNode n : root.path("oidc")) {
                ledger.addOidcClient(new OidcClient(text(n, "id"), text(n, "realm"), contextOf(n, ledgerContext)));
            }
            for (JsonNode group : root.path("grants")) {
                String context = contextOf(group, ledgerContext);
                String prefix = text(group, "pfx");
                for (JsonNode n : group.path("items")) {
                    String policy = text(n, "policy");
                    if (policy != null && prefix != null) policy = prefix + policy;
                    ledger.addRangerGrant(new RangerGrant(context, text(group, "svc"), text(group, "user"),
                            text(n, "access"), resourcesOf(n.get("res")), policy, group.path("own").asBoolean(false),
                            group.path("ns").asBoolean(false)));
                }
            }
            for (JsonNode n : root.path("repos")) {
                ledger.addRangerRepository(new RangerRepository(contextOf(n, ledgerContext), text(n, "name")));
            }
            for (JsonNode n : root.path("principals")) {
                ledger.addPrincipal(new Principal(text(n, "name"), text(n, "secret")));
            }
            JsonNode atlas = root.path("atlas");
            if (atlas.isObject()) {
                ledger.setAtlasFederation(new AtlasFederation(text(atlas, "user"), atlas.path("fileAuth").asBoolean(false)));
            }
            JsonNode tagSync = root.path("tagSync");
            if (tagSync.isObject()) {
                ledger.setTagSyncSource(new TagSyncSource(text(tagSync, "key"), text(tagSync, "endpoint"),
                        text(tagSync, "mapper")));
            }
            for (JsonNode n : root.path("views")) {
                ledger.addViewInstance(new ViewInstance(text(n, "view"), text(n, "version"), text(n, "instance"),
                        !n.path("adopted").asBoolean(false)));
            }
            for (JsonNode n : root.path("vault")) {
                ledger.addVaultPath(n.asText(null));
            }
            if (root.path("truncated").asBoolean(false)) ledger.markTruncated();
        } catch (Exception malformed) {
            ledger.markTruncated();
        }
        return ledger;
    }

    /** The ledger as stored; null when empty. */
    public String toJson() {
        if (isEmpty()) return null;
        ObjectNode root = MAPPER.createObjectNode();
        // The column is small and a release may hold a dozen grants, so the ledger is written compactly: the platform
        // context most entries share is written once, the grants of one account are grouped with the prefix their
        // policy names share, and resources are written as name=value lists rather than as escaped JSON.
        String ledgerContext = mostCommonContext();
        put(root, "ctx", ledgerContext);
        if (!oidcClients.isEmpty()) {
            ArrayNode a = root.putArray("oidc");
            oidcClients.forEach(c -> putContext(put(put(a.addObject(), "id", c.clientId()), "realm", c.realm()),
                    c.contextId(), ledgerContext));
        }
        if (!rangerGrants.isEmpty()) {
            ArrayNode a = root.putArray("grants");
            Map<List<Object>, List<RangerGrant>> groups = new LinkedHashMap<>();
            for (RangerGrant g : rangerGrants) {
                groups.computeIfAbsent(Arrays.asList(g.contextId(), g.service(), g.user(), g.exclusive(),
                        g.perNamespace()), k -> new ArrayList<>()).add(g);
            }
            for (List<RangerGrant> grants : groups.values()) {
                RangerGrant first = grants.get(0);
                ObjectNode group = put(put(a.addObject(), "svc", first.service()), "user", first.user());
                putContext(group, first.contextId(), ledgerContext);
                if (first.exclusive()) group.put("own", true);
                if (first.perNamespace()) group.put("ns", true);
                String prefix = policyPrefix(grants);
                put(group, "pfx", prefix);
                ArrayNode items = group.putArray("items");
                for (RangerGrant g : grants) {
                    ObjectNode n = put(items.addObject(), "access", g.access());
                    putResources(n, g.resources());
                    if (g.policy() != null) n.put("policy", prefix == null ? g.policy() : g.policy().substring(prefix.length()));
                }
            }
        }
        if (!rangerRepositories.isEmpty()) {
            ArrayNode a = root.putArray("repos");
            rangerRepositories.forEach(r -> putContext(put(a.addObject(), "name", r.name()), r.contextId(), ledgerContext));
        }
        if (!principals.isEmpty()) {
            ArrayNode a = root.putArray("principals");
            principals.forEach(p -> put(put(a.addObject(), "name", p.principal()), "secret", p.secret()));
        }
        if (atlasFederation != null) {
            ObjectNode n = put(root.putObject("atlas"), "user", atlasFederation.username());
            if (atlasFederation.fileAuthTurnedOn()) n.put("fileAuth", true);
        }
        if (tagSyncSource != null) {
            put(put(put(root.putObject("tagSync"), "key", tagSyncSource.sourceKey()), "endpoint", tagSyncSource.endpoint()),
                    "mapper", tagSyncSource.mapperKey());
        }
        if (!viewInstances.isEmpty()) {
            ArrayNode a = root.putArray("views");
            for (ViewInstance v : viewInstances) {
                ObjectNode n = put(put(put(a.addObject(), "view", v.view()), "version", v.version()), "instance",
                        v.instance());
                if (!v.created()) n.put("adopted", true);
            }
        }
        if (!vaultPaths.isEmpty()) {
            ArrayNode a = root.putArray("vault");
            vaultPaths.forEach(a::add);
        }
        if (truncated) root.put("truncated", true);
        return root.toString();
    }

    /**
     * {@code updated} when it fits {@code maxLength} characters; otherwise {@code current}, marked as missing
     * something, so that what is already recorded is never lost to what is not.
     */
    public static ProvisioningLedger fitting(ProvisioningLedger current, ProvisioningLedger updated, int maxLength) {
        String json = updated.toJson();
        if (json == null || json.length() <= maxLength) return updated;
        ProvisioningLedger kept = current == null ? new ProvisioningLedger() : current.copy();
        kept.markTruncated();
        return kept;
    }

    /** The platform context most entries name; null when none names one. */
    private String mostCommonContext() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        oidcClients.forEach(c -> count(counts, c.contextId()));
        rangerGrants.forEach(g -> count(counts, g.contextId()));
        rangerRepositories.forEach(r -> count(counts, r.contextId()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static void count(Map<String, Integer> counts, String contextId) {
        if (contextId != null && !contextId.isBlank()) counts.merge(contextId, 1, Integer::sum);
    }

    /** Writes the entry's context when it is not the ledger's; an empty one stands for the default context. */
    private static ObjectNode putContext(ObjectNode node, String contextId, String ledgerContext) {
        String own = contextId == null || contextId.isBlank() ? null : contextId;
        if (!Objects.equals(own, ledgerContext)) node.put("ctx", own == null ? "" : own);
        return node;
    }

    private static String contextOf(JsonNode node, String ledgerContext) {
        JsonNode v = node.get("ctx");
        if (v == null || v.isNull()) return ledgerContext;
        return v.asText().isBlank() ? null : v.asText();
    }

    /** The prefix the policy names of a group of grants share, when there are several and all are named. */
    private static String policyPrefix(List<RangerGrant> grants) {
        if (grants.size() < 2 || grants.stream().anyMatch(g -> g.policy() == null)) return null;
        String prefix = grants.get(0).policy();
        for (RangerGrant g : grants) {
            int i = 0;
            while (i < prefix.length() && i < g.policy().length() && prefix.charAt(i) == g.policy().charAt(i)) i++;
            prefix = prefix.substring(0, i);
        }
        return prefix.isEmpty() ? null : prefix;
    }

    /** What a resource name or value written compactly may hold: nothing that separates them. */
    private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("[A-Za-z0-9_.*{}-]+");

    /**
     * Writes resources compactly, as {@code name=value,value;name=value}, when they are a JSON object of plain names
     * to plain values; anything else as given.
     */
    private static void putResources(ObjectNode node, String resources) {
        if (resources == null || resources.isBlank()) return;
        try {
            JsonNode parsed = MAPPER.readTree(resources);
            if (parsed != null && parsed.isObject() && parsed.size() > 0) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) parsed::fields) {
                    if (!PLAIN.matcher(e.getKey()).matches() || !e.getValue().isArray() || e.getValue().isEmpty()) {
                        parts = null;
                        break;
                    }
                    List<String> values = new ArrayList<>();
                    for (JsonNode v : e.getValue()) {
                        if (!v.isTextual() || !PLAIN.matcher(v.asText()).matches()) {
                            values = null;
                            break;
                        }
                        values.add(v.asText());
                    }
                    if (values == null) {
                        parts = null;
                        break;
                    }
                    parts.add(e.getKey() + "=" + String.join(",", values));
                }
                if (parts != null) {
                    node.put("res", String.join(";", parts));
                    return;
                }
            }
        } catch (Exception notJson) {
            // Kept as the text it is.
        }
        node.put("res", resources);
    }

    /** The resources {@link #putResources} wrote, as the JSON object they were. */
    private static String resourcesOf(JsonNode res) {
        if (res == null || res.isNull()) return null;
        String text = res.asText();
        if (text.isEmpty() || text.startsWith("{") || !text.contains("=")) return text;
        ObjectNode resources = MAPPER.createObjectNode();
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) return text;
            ArrayNode values = resources.putArray(part.substring(0, eq));
            for (String v : part.substring(eq + 1).split(",")) values.add(v);
        }
        return resources.toString();
    }

    private static ObjectNode put(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.put(key, value);
        return node;
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}
