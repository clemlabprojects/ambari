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

package org.apache.ambari.view.k8s.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Secret;
import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Ranger account the Atlas tag projector of a Trino release writes tags with (the chart's {@code tagSync}
 * CronJob, which copies the Hive tag associations onto the release's Ranger repository).
 *
 * <p>Each release gets an account of its own, created on the Ranger of its platform context with a password generated
 * on every deploy. Only that account reaches the release's project, in the Secret {@code <release>-tagsync-ranger}:
 * the Ranger administrator credentials KDPS creates it with never do. Ranger lets only its administrators write tags
 * (its tag import requires {@code ROLE_SYS_ADMIN}, and Ranger has no narrower role or per-repository right for it), so
 * the account holds that role: whoever can read Secrets in the project can act as a Ranger administrator with it.
 * Disabling it when the release is uninstalled ends that, but does not undo what was done with it.
 *
 * <p>KDPS records in the view which account it gave a release and on which platform context ({@link Records}), so an
 * uninstall disables that account even when the Secret was deleted or edited in the project. An account the release
 * no longer uses stays recorded until it is disabled, so that the next deploy or the uninstall tries again. The
 * account's Ranger description names the release it belongs to, and an account whose description names another is
 * neither reset nor disabled.
 */
final class TagSyncRangerAccount {

    private static final Logger LOG = LoggerFactory.getLogger(TagSyncRangerAccount.class);

    /** The only Ranger role that may import tags. */
    static final String ROLE = "ROLE_SYS_ADMIN";

    private static final String USER_PREFIX = "kdps-tagsync-";

    /** Key prefix of the view settings recording the account of each release. */
    static final String RECORD_PREFIX = "tagsync.rangerAccount.";

    /** Key prefix of the view settings recording the accounts of each release that are still to be disabled. */
    static final String TO_DISABLE_PREFIX = "tagsync.rangerAccountsToDisable.";

    /** The view setting holding what tells this KDPS instance apart from any other (see {@link #instanceId}). */
    static final String INSTANCE_ID_KEY = "tagsync.instanceId";

    /** Annotation every Secret KDPS writes carries, and has since before tag sync existed. */
    private static final String KDPS_ANNOTATION = "managed-by";
    private static final String KDPS_ANNOTATION_VALUE = "ambari-k8s-view";
    /** Label KDPS puts on the Secret holding a release's account. */
    private static final String KDPS_LABEL = "app.kubernetes.io/managed-by";
    private static final String KDPS_LABEL_VALUE = "kdps";

    /** Two first deploys at the same moment must not each choose another instance id. */
    private static final Object INSTANCE_ID_LOCK = new Object();

    /** How long the Ambari server action changing a Ranger account may run. */
    static final int AMBARI_TIMEOUT_SECONDS = 90;

    /** How much longer than its action the Ambari request is waited for, since it may first have to be scheduled. */
    private static final int AMBARI_QUEUE_SECONDS = 30;

    private TagSyncRangerAccount() {
    }

    /** Where a release's account is created and disabled: the Ranger of its platform context. */
    interface RangerUsers {

        /**
         * Creates the account with {@link #ROLE} and {@code password}, its description naming {@code owner}, or resets
         * an existing one to them, enabled. An existing account whose description names anything else is refused.
         */
        void ensure(String userName, String password, String owner) throws Exception;

        /**
         * Disables the account so that it no longer logs in and holds no administrator role.
         *
         * @param owner what its description must name, or null for an account earlier KDPS versions created without
         *              recording its release
         * @return false when Ranger has no such account
         */
        boolean disable(String userName, String owner) throws Exception;
    }

    /** Finds the Ranger of a platform context, or says why it cannot be reached. */
    interface RangerLocator {
        /**
         * @param contextId the platform context, or null for the default one
         * @throws ContextGone when KDPS no longer has the platform context
         */
        RangerUsers forContext(String contextId) throws Exception;
    }

    /**
     * The platform context of an account no longer exists in KDPS, which can then never reach its Ranger: trying
     * again later is pointless, so the account is reported once and no longer kept to be disabled.
     */
    static final class ContextGone extends IllegalStateException {
        ContextGone(String contextId) {
            super("its platform context '" + contextId + "' no longer exists in KDPS");
        }
    }

    /**
     * What KDPS recorded, in the view, about the account it gave a release: an uninstall reads it, rather than the
     * release's Secret, which anyone who can edit Secrets in the project can change or delete.
     *
     * @param userName  the account
     * @param contextId the platform context whose Ranger has it, null for the default one
     * @param owner     the release its Ranger description names (see {@link #owner})
     * @param grant     the deploy that last gave the release the account, set anew by each one: a deploy that did not
     *                  install the release takes back only the account no later deploy was given since; null when no
     *                  deploy is named (accounts still to be disabled, or recorded by earlier KDPS versions)
     */
    record Recorded(String userName, String contextId, String owner, String grant) {

        Recorded(String userName, String contextId, String owner) {
            this(userName, contextId, owner, null);
        }

        /** The account alone, without the deploy that gave it: what the accounts still to be disabled keep. */
        Recorded withoutGrant() {
            return grant == null ? this : new Recorded(userName, contextId, owner);
        }
    }

    /** The accounts KDPS gave releases, by namespace and release. */
    interface Records {
        /** @return the release's account, or null when KDPS recorded none */
        Recorded find(String namespace, String release);

        void put(String namespace, String release, Recorded account);

        void remove(String namespace, String release);

        /** @return the accounts the release no longer uses that KDPS could not disable yet; empty when none */
        List<Recorded> toDisable(String namespace, String release);

        /** Replaces the accounts of the release that are still to be disabled; an empty list removes them. */
        void putToDisable(String namespace, String release, List<Recorded> accounts);
    }

    /** The records kept in the settings of the view instance, shared by every user and by background work. */
    static Records recordsIn(InstanceSettings settings) {
        return new Records() {
            @Override
            public Recorded find(String namespace, String release) {
                String json = settings.get(RECORD_PREFIX + namespace + "/" + release);
                if (json == null || json.isBlank()) return null;
                try {
                    return fromJson(JsonParser.parseString(json).getAsJsonObject());
                } catch (RuntimeException malformed) {
                    LOG.warn("Atlas tag sync: the record of release {}/{} cannot be read: {}", namespace, release,
                            malformed.toString());
                    return null;
                }
            }

            @Override
            public void put(String namespace, String release, Recorded account) {
                settings.put(RECORD_PREFIX + namespace + "/" + release, toJson(account).toString());
            }

            @Override
            public void remove(String namespace, String release) {
                settings.remove(RECORD_PREFIX + namespace + "/" + release);
            }

            @Override
            public List<Recorded> toDisable(String namespace, String release) {
                String json = settings.get(TO_DISABLE_PREFIX + namespace + "/" + release);
                if (json == null || json.isBlank()) return List.of();
                try {
                    List<Recorded> accounts = new ArrayList<>();
                    for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                        Recorded account = e.isJsonObject() ? fromJson(e.getAsJsonObject()) : null;
                        if (account != null) accounts.add(account);
                    }
                    return accounts;
                } catch (RuntimeException malformed) {
                    LOG.warn("Atlas tag sync: the accounts of release {}/{} still to be disabled cannot be read: {}",
                            namespace, release, malformed.toString());
                    return List.of();
                }
            }

            @Override
            public void putToDisable(String namespace, String release, List<Recorded> accounts) {
                String key = TO_DISABLE_PREFIX + namespace + "/" + release;
                if (accounts.isEmpty()) {
                    settings.remove(key);
                    return;
                }
                JsonArray array = new JsonArray();
                accounts.forEach(account -> array.add(toJson(account)));
                settings.put(key, array.toString());
            }
        };
    }

    private static Recorded fromJson(JsonObject o) {
        String user = text(o, "user");
        return user == null ? null
                : new Recorded(user, contextKey(text(o, "context")), text(o, "owner"), text(o, "grant"));
    }

    private static JsonObject toJson(Recorded account) {
        JsonObject o = new JsonObject();
        o.addProperty("user", account.userName());
        if (account.contextId() != null) o.addProperty("context", account.contextId());
        if (account.owner() != null) o.addProperty("owner", account.owner());
        if (account.grant() != null) o.addProperty("grant", account.grant());
        return o;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** A Ranger the view reaches directly, with the administrator credentials stored on the platform context. */
    static RangerUsers direct(String rangerUrl, String adminUser, String adminPassword) {
        return new RangerUsers() {
            @Override
            public void ensure(String userName, String password, String owner) throws Exception {
                OmAtlasProvisioning.ensureInternalRangerUser(rangerUrl, adminUser, adminPassword, userName, password,
                        List.of(ROLE), owner);
            }

            @Override
            public boolean disable(String userName, String owner) throws Exception {
                return OmAtlasProvisioning.disableInternalRangerUser(rangerUrl, adminUser, adminPassword, userName,
                        owner);
            }
        };
    }

    /** The Ranger of the Ambari-managed cluster, reached through the Ambari server, which holds its administrator. */
    static RangerUsers viaAmbari(AmbariActionClient ambari, int timeoutSeconds) {
        return new RangerUsers() {
            @Override
            public void ensure(String userName, String password, String owner) throws Exception {
                int req = ambari.submitRangerUserUpsert(userName, password, ROLE, true, owner, timeoutSeconds,
                        "KDPS tag sync: Ranger account " + userName);
                awaitCompletion(ambari, req, "Ranger account '" + userName + "'", timeoutSeconds);
            }

            @Override
            public boolean disable(String userName, String owner) throws Exception {
                int req = ambari.submitRangerUserDisable(userName, owner, timeoutSeconds,
                        "KDPS tag sync: disable Ranger account " + userName);
                awaitCompletion(ambari, req, "Disabling Ranger account '" + userName + "'", timeoutSeconds);
                // A server that reports nothing did run the disable: only an explicit "found": false means absent.
                return ambari.taskStructuredOut(req)
                        .filter(out -> out.has("found") && out.get("found").isJsonPrimitive())
                        .map(out -> out.get("found").getAsBoolean())
                        .orElse(true);
            }
        };
    }

    private static void awaitCompletion(AmbariActionClient ambari, int requestId, String what, int timeoutSeconds)
            throws Exception {
        if (!ambari.waitUntilComplete(requestId, timeoutSeconds + AMBARI_QUEUE_SECONDS, TimeUnit.SECONDS)) {
            String detail = ambari.failureDetail(requestId);
            throw new IllegalStateException(what + ": Ambari request " + requestId + " did not complete"
                    + (detail == null || detail.isBlank() ? "; see the Ambari request log" : ": " + detail));
        }
    }

    /**
     * The account of a release, {@code kdps-tagsync-<namespace>_<release>_<hash>}. Neither a namespace nor a release
     * name can hold an underscore, so no two releases share a name, nor does a release share one with an account an
     * earlier KDPS version named after a release alone ({@link #legacyUserName}). The last part, a hash of the KDPS
     * instance, of the Kubernetes API server and of the platform context, keeps apart releases of the same name
     * deployed by two KDPS that use the same Ranger: their API server URL alone may be the same (an in-cluster
     * {@code https://kubernetes.default.svc}, every CRC's {@code api.crc.testing}), and so may their context ids,
     * which operators choose. It also gives a release moved to another platform context another account: two
     * contexts may reach the same Ranger, and disabling the account the release had on the first must not disable the
     * one it now has.
     *
     * @param instance  the KDPS instance deploying the release (see {@link #instanceId}); null when unknown
     * @param cluster   the Kubernetes cluster of the release, as its API server URL; null when unknown
     * @param contextId the platform context of the release; null, blank or the default one for the managed cluster
     */
    static String userName(String release, String namespace, String instance, String cluster, String contextId) {
        String name = USER_PREFIX + namespace + "_" + release;
        String scope = Stream.of(blankToNull(instance), blankToNull(cluster), contextKey(contextId))
                .map(part -> part == null ? "" : part)
                .collect(Collectors.joining("\n"));
        if (!scope.isBlank()) name += "_" + shortHash(scope);
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * What tells this KDPS instance apart from any other that may use the same Ranger: a random id chosen on its first
     * tag sync deploy and kept in its settings.
     *
     * @throws RuntimeException when the id cannot be kept: the account names of later deploys would not match
     */
    static String instanceId(InstanceSettings settings) {
        synchronized (INSTANCE_ID_LOCK) {
            String id = blankToNull(settings.get(INSTANCE_ID_KEY));
            if (id == null) {
                id = UUID.randomUUID().toString();
                settings.put(INSTANCE_ID_KEY, id);
            }
            return id;
        }
    }

    /**
     * The platform context as KDPS records it: null for the managed cluster, which a deploy names either as the
     * default context (the wizard) or not at all (a deploy through the API), so that both mean the same account.
     */
    static String contextKey(String contextId) {
        String id = blankToNull(contextId);
        return id == null || ContextService.DEFAULT_CONTEXT_ID.equals(id) ? null : id;
    }

    /** What the Ranger description of a release's account says: the release it belongs to, and which KDPS gave it. */
    static String owner(String namespace, String release, String instance, String cluster) {
        return "KDPS Atlas tag sync account of Trino release " + namespace + "/" + release
                + (cluster == null || cluster.isBlank() ? "" : " on Kubernetes " + cluster)
                + (instance == null || instance.isBlank() ? "" : " (KDPS instance " + instance + ")");
    }

    /** The account earlier KDPS versions created for a release on a managed context, before it named the namespace. */
    static String legacyUserName(String release) {
        return (USER_PREFIX + release).toLowerCase(Locale.ROOT);
    }

    /** The Secret the chart reads the account from ({@code tagSync.ranger.existingSecret}). */
    static String secretName(String release) {
        return release + "-tagsync-ranger";
    }

    /**
     * Whether KDPS wrote a Secret: every Secret it writes carries its {@code managed-by} annotation, and the one holding
     * a release's account also its label. Any other Secret of that name is the operator's, which KDPS neither
     * replaces nor deletes.
     */
    static boolean writtenByKdps(Secret secret) {
        if (secret == null || secret.getMetadata() == null) return false;
        Map<String, String> annotations = secret.getMetadata().getAnnotations();
        Map<String, String> labels = secret.getMetadata().getLabels();
        return (annotations != null && KDPS_ANNOTATION_VALUE.equals(annotations.get(KDPS_ANNOTATION)))
                || (labels != null && KDPS_LABEL_VALUE.equals(labels.get(KDPS_LABEL)));
    }

    /**
     * Whether the release's project holds a Secret named {@link #secretName} that KDPS did not write: the operator
     * brought the account there. False when there is none, or it cannot be read.
     */
    static boolean secretBroughtByOperator(KubernetesService k8s, String namespace, String release) {
        Secret secret = readSecret(k8s, namespace, secretName(release));
        return secret != null && !writtenByKdps(secret);
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 5);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * What a deploy did: the account the release's Secret now holds, and what the operator must hear about the
     * credentials it replaced.
     */
    record Provisioned(String userName, List<String> warnings) {
    }

    /**
     * Gives a release its account: creates it on {@code ranger}, or resets it with a new password, records it, and
     * writes the Secret the chart reads it from. Whatever the Secret held before is replaced. The accounts the release
     * no longer uses are then disabled: the one an earlier deploy gave it under another name (on another platform
     * context or Kubernetes cluster), those KDPS could not disable before, and the account earlier KDPS versions
     * created for it. Those that still cannot be disabled stay recorded for the next deploy or the uninstall to try
     * again. Another account found in the Secret (earlier versions copied the platform context's own Ranger
     * administrator there) is left as it is in Ranger, and the warning says to change its password, since anyone who
     * could read Secrets in the project may have kept it.
     *
     * @param ranger          the Ranger of the release's platform context
     * @param locator         the Ranger of the platform context an earlier deploy gave the release its account on
     * @param contextId       the platform context whose Ranger {@code ranger} is, recorded for the uninstall; null for
     *                        the default one
     * @param legacyContextId the platform context the release was deployed on before this deploy, as the view
     *                        recorded it: where an earlier KDPS version created the account it named after the release
     *                        alone; null for the default one
     * @param instance        the KDPS instance deploying the release (see {@link #userName})
     * @param cluster         the Kubernetes cluster of the release (see {@link #userName})
     * @param grant           the deploy giving the account, recorded with it (see {@link Recorded#grant}); null when
     *                        none is named
     * @throws IllegalArgumentException when the release's project holds a Secret of that name that KDPS did not write
     * @throws IllegalStateException    when Ranger did not take the account (the projector would be refused), or when
     *                                  KDPS could not record it or write the Secret
     */
    static Provisioned provision(KubernetesService k8s, Records records, RangerUsers ranger, RangerLocator locator,
                                 String namespace, String release, String contextId, String legacyContextId,
                                 String instance, String cluster, String grant) {
        String context = contextKey(contextId);
        String user = userName(release, namespace, instance, cluster, context);
        String owner = owner(namespace, release, instance, cluster);
        String secretName = secretName(release);
        Secret existing = readSecret(k8s, namespace, secretName);
        if (existing != null && !writtenByKdps(existing)) {
            throw new IllegalArgumentException("Atlas tag sync: the Secret " + namespace + "/" + secretName + " was not"
                    + " written by KDPS, which does not replace it. Name it in 'tagSync.ranger.existingSecret' to use the"
                    + " Ranger account it holds, or delete it to let KDPS give the release an account of its own.");
        }
        Recorded before = records.find(namespace, release);
        String inSecret = secretUser(existing);

        String password = CommandService.generateRangerPassword();
        try {
            ranger.ensure(user, password, owner);
        } catch (Exception e) {
            throw new IllegalStateException("Atlas tag sync: the Ranger account '" + user + "' of release " + release
                    + " could not be created: " + reasonOf(e), e);
        }
        List<String> warnings = new ArrayList<>();
        List<Recorded> pending = new ArrayList<>(toDisable(records, namespace, release, warnings));
        // The account just given is no longer one to disable, should the release have had it before.
        boolean changed = pending.removeIf(a -> a.userName().equals(user));
        if (before != null && !before.userName().equals(user) && !pending.contains(before.withoutGrant())) {
            // Kept to be disabled before the record names the new account: should this deploy stop before it disables
            // the earlier one (the Secret cannot be written), the next deploy or the uninstall still does.
            pending.add(before.withoutGrant());
            changed = true;
        }
        try {
            if (changed) records.putToDisable(namespace, release, pending);
            records.put(namespace, release, new Recorded(user, context, owner, grant));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Atlas tag sync: KDPS could not record the Ranger account '" + user
                    + "' of release " + release + ", which it needs to disable the account on uninstall: "
                    + reasonOf(e), e);
        }

        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("user", user.getBytes(StandardCharsets.UTF_8));
        data.put("password", password.getBytes(StandardCharsets.UTF_8));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "kdps");
        labels.put("app.kubernetes.io/instance", release);
        try {
            k8s.createOrUpdateOpaqueSecret(namespace, secretName, data, labels);
        } catch (RuntimeException e) {
            // The account now has a password nobody else knows: without the Secret the projector cannot log in.
            throw new IllegalStateException("Atlas tag sync: the Secret " + namespace + "/" + secretName
                    + " holding the Ranger account of release " + release + " could not be written: " + reasonOf(e), e);
        }
        LOG.info("Atlas tag sync: Ranger account '{}' ready for release {}/{}, in Secret {}", user, namespace, release,
                secretName);

        // The account just given is never disabled: it keeps its name only on the platform context it is on.
        List<Recorded> left = disableAll(locator, pending, user, false, new ArrayList<>(), warnings);
        if (legacyUserName(release).equals(inSecret)) {
            // Earlier versions created it on the Ranger of the context the release was on then, which this deploy may
            // have moved it away from: it is disabled there, as on uninstall, and reported when it is not found.
            Recorded legacy = new Recorded(inSecret, contextKey(legacyContextId), null);
            List<Recorded> legacyLeft = disableAll(locator, List.of(legacy), user, true, new ArrayList<>(), warnings);
            // The Secret no longer names it: only the record lets the next deploy or the uninstall find it.
            legacyLeft.stream().filter(a -> !left.contains(a)).forEach(left::add);
        } else if (inSecret != null && !inSecret.equals(user)
                && pending.stream().noneMatch(a -> a.userName().equals(inSecret))) {
            warnings.add(copiedCredentialWarning(inSecret, namespace, secretName));
        }
        if (!pending.isEmpty() || !left.isEmpty()) keepToDisable(records, namespace, release, left, warnings);
        return new Provisioned(user, Collections.unmodifiableList(warnings));
    }

    /**
     * What a deploy that cannot give a release an account does when the release keeps using its Secret as it is (see
     * {@code CommandService#keepOrTakeBackTagSyncRangerAccount}). The accounts the release no longer uses that KDPS
     * could not disable before are tried again, except one the Secret still holds (a deploy that could not write the
     * Secret left the release running with it). Nothing is said when the Secret holds an account KDPS gave the release,
     * or when there is no Secret. Any other account in it was put there by an earlier KDPS version, possibly the
     * platform context's own Ranger administrator, and only a deploy with Ranger wiring replaces it. Never throws.
     *
     * @param locator the Ranger of the platform context each account still to be disabled is on
     * @return what the operator must hear
     */
    static List<String> kept(KubernetesService k8s, Records records, RangerLocator locator, String namespace,
                             String release) {
        String secretName = secretName(release);
        String inSecret;
        Recorded account;
        try {
            // A Secret of that name that KDPS did not write is the operator's, whatever account it holds.
            Secret secret = k8s.getSecret(namespace, secretName);
            inSecret = writtenByKdps(secret) ? secretUser(secret) : null;
            account = records.find(namespace, release);
        } catch (RuntimeException e) {
            // Without knowing which account the release runs with, none is disabled.
            LOG.warn("Atlas tag sync: the Secret {}/{} could not be checked: {}", namespace, secretName, e.toString());
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        List<Recorded> pending = toDisable(records, namespace, release, notes);
        List<Recorded> inUse = new ArrayList<>();
        List<Recorded> retry = new ArrayList<>();
        for (Recorded a : pending) {
            boolean used = a.userName().equals(inSecret) || (account != null && a.userName().equals(account.userName()));
            (used ? inUse : retry).add(a);
        }
        if (!retry.isEmpty()) {
            List<Recorded> left = new ArrayList<>(inUse);
            left.addAll(disableAll(locator, retry, null, false, new ArrayList<>(), notes));
            keepToDisable(records, namespace, release, left, notes);
        }
        boolean given = inSecret == null || (account != null && inSecret.equals(account.userName()))
                || pending.stream().anyMatch(a -> a.userName().equals(inSecret));
        if (!given) {
            notes.add("The Secret " + namespace + "/" + secretName + " holds the Ranger account '" + inSecret
                    + "', which KDPS did not create for this release: earlier KDPS versions put the platform context's"
                    + " own Ranger administrator account there, or an account shared by every release of that name."
                    + " This deploy carries no Ranger wiring, so the Secret is left as it is. Redeploy the release with"
                    + " Upgrade / Config, its platform context selected, to give it an account of its own; then, if '"
                    + inSecret + "' is the platform context's administrator, change its password in Ranger and on the"
                    + " platform context.");
        }
        return notes;
    }

    /**
     * What taking back a release's account did.
     *
     * @param done what was taken back, such as "disabled the Ranger account 'x'"; empty when there was nothing
     * @param note what is left for an administrator to do, or null when there is nothing
     */
    record Revoked(List<String> done, String note) {
    }

    /**
     * Takes back the account of a release that no longer uses it (uninstalled, or redeployed without KDPS's account):
     * disables it in Ranger and deletes its Secret. The account and the Ranger it is on are the ones KDPS recorded
     * when it created them; the Secret only tells what earlier KDPS versions left there, and nothing in it chooses
     * which account is disabled or where, beyond the one an earlier version created for the release. The accounts an
     * earlier deploy could not disable are tried again. An account that still cannot be disabled stays recorded, so
     * that the next deploy or uninstall of the release tries again. Never throws: what could not be done is returned
     * for an administrator to finish.
     *
     * @param recordedContextId the platform context of the release as the view recorded it, where an earlier KDPS
     *                          version created the release's account; null for the default one
     */
    static Revoked revoke(KubernetesService k8s, Records records, String namespace, String release,
                          String recordedContextId, RangerLocator locator) {
        String secretName = secretName(release);
        List<String> notes = new ArrayList<>();
        Recorded account = null;
        try {
            account = records.find(namespace, release);
        } catch (RuntimeException e) {
            LOG.warn("Atlas tag sync: the record of release {}/{} could not be read: {}", namespace, release,
                    e.toString());
            notes.add("KDPS could not read which Ranger account it gave this release (" + reasonOf(e) + "). If the"
                    + " release synced Atlas tags, disable its account in Ranger: its name starts with '"
                    + userName(release, namespace, null, null, null) + "'.");
        }
        List<Recorded> pending = toDisable(records, namespace, release, notes);
        List<Recorded> accounts = new ArrayList<>();
        if (account != null) accounts.add(account.withoutGrant());
        accounts.addAll(pending);
        Secret secret = null;
        try {
            secret = k8s.getSecret(namespace, secretName);
        } catch (Exception e) {
            LOG.warn("Atlas tag sync: the Secret {}/{} could not be read: {}", namespace, secretName, e.toString());
            notes.add("The Secret " + namespace + "/" + secretName + " could not be read (" + reasonOf(e) + "). If it"
                    + " still exists, delete it.");
        }
        if (secret != null && !writtenByKdps(secret)) {
            // The operator brought it, with an account KDPS knows nothing of: it is theirs to keep or delete.
            LOG.info("Atlas tag sync: the Secret {}/{} was not written by KDPS and is left as it is", namespace,
                    secretName);
            secret = null;
        }
        String inSecret = secretUser(secret);
        boolean recorded = inSecret == null || accounts.stream().anyMatch(a -> a.userName().equals(inSecret));
        if (legacyUserName(release).equals(inSecret)) {
            accounts.add(new Recorded(inSecret, contextKey(recordedContextId), null));
        } else if (!recorded) {
            notes.add(copiedCredentialWarning(inSecret, namespace, secretName));
        }
        List<String> done = new ArrayList<>();
        List<Recorded> left = disableAll(locator, accounts, null, true, done, notes);
        if (secret != null) {
            try {
                k8s.deleteSecret(namespace, secretName);
                done.add("deleted the Secret " + namespace + "/" + secretName);
            } catch (Exception e) {
                LOG.warn("Atlas tag sync: the Secret {}/{} could not be deleted: {}", namespace, secretName,
                        e.toString());
                notes.add("The Secret " + namespace + "/" + secretName + " could not be deleted (" + reasonOf(e)
                        + "): delete it.");
            }
        }
        // The release no longer uses the account: an account still enabled is kept in the accounts to disable.
        if (!pending.isEmpty() || !left.isEmpty()) keepToDisable(records, namespace, release, left, notes);
        if (account != null) {
            try {
                records.remove(namespace, release);
            } catch (RuntimeException e) {
                LOG.warn("Atlas tag sync: the record of release {}/{} could not be removed: {}", namespace, release,
                        e.toString());
            }
        }
        return new Revoked(Collections.unmodifiableList(done), notes.isEmpty() ? null : String.join(" ", notes));
    }

    /** Said of an account that could not be disabled and stays recorded. */
    private static final String TRIED_AGAIN = "KDPS tries again on the next deploy or uninstall of the release.";

    /** The accounts of a release still to be disabled; empty, with a note, when they cannot be read. */
    private static List<Recorded> toDisable(Records records, String namespace, String release, List<String> notes) {
        try {
            return records.toDisable(namespace, release);
        } catch (RuntimeException e) {
            LOG.warn("Atlas tag sync: the accounts of release {}/{} still to be disabled could not be read: {}",
                    namespace, release, e.toString());
            notes.add("KDPS could not read which Ranger accounts of this release are still to be disabled ("
                    + reasonOf(e) + ").");
            return List.of();
        }
    }

    /** Records the accounts of a release still to be disabled, adding a note when that fails. */
    private static void keepToDisable(Records records, String namespace, String release, List<Recorded> accounts,
                                      List<String> notes) {
        try {
            records.putToDisable(namespace, release, accounts);
        } catch (RuntimeException e) {
            LOG.warn("Atlas tag sync: the accounts of release {}/{} still to be disabled could not be recorded: {}",
                    namespace, release, e.toString());
            if (!accounts.isEmpty()) {
                notes.add("KDPS could not record that it is still to disable the Ranger account(s) "
                        + accounts.stream().map(a -> "'" + a.userName() + "'").collect(Collectors.joining(", "))
                        + " (" + reasonOf(e) + "), and will not try again: disable them in Ranger.");
            }
        }
    }

    /**
     * Disables accounts a release no longer uses, each on the Ranger of its platform context.
     *
     * @param keep the account the release now has, which is never disabled; null when it has none
     * @return the accounts that could not be disabled and are worth trying again
     */
    private static List<Recorded> disableAll(RangerLocator locator, List<Recorded> accounts, String keep,
                                             boolean reportAbsent, List<String> done, List<String> notes) {
        List<Recorded> left = new ArrayList<>();
        Set<Recorded> seen = new HashSet<>();
        for (Recorded account : accounts) {
            if (account.userName().equals(keep) || !seen.add(account)) continue;
            if (!disable(locator, account, reportAbsent, done, notes)) left.add(account);
        }
        return left;
    }

    /**
     * Disables an account on the Ranger of its platform context, adding what it did to {@code done} and what is left
     * for an administrator to {@code notes}.
     *
     * @param reportAbsent whether an account Ranger does not have is worth a note: it was expected there
     * @return false when the account could not be disabled and is worth trying again
     */
    private static boolean disable(RangerLocator locator, Recorded account, boolean reportAbsent, List<String> done,
                                   List<String> notes) {
        String user = account.userName();
        String where = account.contextId() == null ? "the managed cluster"
                : "platform context '" + account.contextId() + "'";
        try {
            boolean found = locator.forContext(account.contextId()).disable(user, account.owner());
            LOG.info("Atlas tag sync: {} the Ranger account '{}' in the Ranger of {}", found ? "disabled" : "found no",
                    user, where);
            if (found) {
                done.add("disabled the Ranger account '" + user + "'");
            } else if (reportAbsent) {
                notes.add("The Ranger account '" + user + "' that KDPS created for this release was not found in the"
                        + " Ranger of " + where + ". If it exists in another Ranger, disable it there: it holds the"
                        + " Ranger administrator role.");
            }
            return true;
        } catch (Exception e) {
            boolean unreachable = e instanceof ContextGone;
            LOG.warn("Atlas tag sync: the Ranger account '{}' in the Ranger of {} could not be disabled: {}", user,
                    where, e.toString());
            notes.add("The Ranger account '" + user + "' that synced Atlas tags for this release could not be disabled"
                    + " in the Ranger of " + where + " (" + reasonOf(e) + "). It holds the Ranger administrator role:"
                    + " disable it in Ranger." + (unreachable ? "" : " " + TRIED_AGAIN));
            return unreachable;
        }
    }

    /**
     * What to tell about an account in the release's Secret that is not one KDPS created for the release: earlier KDPS
     * versions copied the platform context's own Ranger administrator account there. Its password must be changed.
     */
    private static String copiedCredentialWarning(String account, String namespace, String secretName) {
        return "The Secret " + namespace + "/" + secretName + " held the password of the Ranger account '" + account
                + "', which is not an account KDPS created for this release (earlier KDPS versions copied the platform"
                + " context's own Ranger administrator account there). Change the password of '" + account
                + "' in Ranger, and then on the platform context: anyone who could read Secrets in project '"
                + namespace + "' may have kept a copy.";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** The release's Secret, or null when there is none or it cannot be read. */
    private static Secret readSecret(KubernetesService k8s, String namespace, String secretName) {
        try {
            return k8s.getSecret(namespace, secretName);
        } catch (Exception e) {
            LOG.warn("Atlas tag sync: the Secret {}/{} could not be read: {}", namespace, secretName, e.toString());
            return null;
        }
    }

    /** The account a Secret holds, as the chart reads it, or null when there is no Secret or no account in it. */
    private static String secretUser(Secret secret) {
        return blankToNull(secretValue(secret, "user"));
    }

    /** A value of a Secret as text, or null when absent. */
    private static String secretValue(Secret secret, String key) {
        if (secret == null) return null;
        if (secret.getStringData() != null && secret.getStringData().get(key) != null) {
            return secret.getStringData().get(key);
        }
        if (secret.getData() == null || secret.getData().get(key) == null) return null;
        try {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    private static String reasonOf(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
