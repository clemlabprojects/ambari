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

import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks, before a deploy starts, that the operator's Ambari account holds the Ambari permissions its planned steps
 * use. Steps such as keytab creation replay the operator's own Ambari session, so Ambari refuses them when the account
 * lacks the permission. Without this check such a deploy stops halfway, after its first steps already ran, with an
 * authorization error from Ambari. A KDPS operator therefore also needs these Ambari permissions on the cluster
 * (the Cluster Administrator role holds the cluster ones).
 *
 * <p>One preflight serves one deploy: the deploy checks what it knows before it writes anything (reading the cluster,
 * keytabs, view instance, the Iceberg catalog's bucket), then the steps it only plans later, and Ambari is read once
 * for both.
 */
public final class AmbariPermissionPreflight {

    private static final Logger LOG = LoggerFactory.getLogger(AmbariPermissionPreflight.class);

    /**
     * An Ambari authorization a step needs.
     *
     * @param authorizationId Ambari authorization id, as Ambari reports it
     * @param label           the name Ambari shows for it
     * @param purpose         what the deploy needs it for, completing "needed to ..."
     */
    public record Requirement(String authorizationId, String label, String purpose) {}

    /**
     * Every deploy on the Ambari-managed context reads the cluster with the operator's session. An account that may
     * not read it would deploy as if the cluster were not Kerberized, so this is checked like the step permissions.
     */
    public static final Requirement READ_CLUSTER = new Requirement("CLUSTER.VIEW_CONFIGS", "View configuration",
            "read the cluster's Kerberos state and the settings of the services the release connects to");
    static final Requirement TOGGLE_KERBEROS = new Requirement("CLUSTER.TOGGLE_KERBEROS",
            "Enable/disable Kerberos", "create the release's Kerberos keytabs");
    static final Requirement MANAGE_VIEWS = new Requirement("AMBARI.MANAGE_VIEWS",
            "Manage Ambari Views", "create the release's Ambari view instance");
    static final Requirement MODIFY_CONFIGS = new Requirement("SERVICE.MODIFY_CONFIGS",
            "Modify configurations", "update the configuration of the Ambari services the release connects to");
    static final Requirement RUN_CUSTOM_COMMAND = new Requirement("SERVICE.RUN_CUSTOM_COMMAND",
            "Perform service-specific tasks", "restart those Ambari services so they read the new configuration");
    static final Requirement PROVISION_OBJECT_STORE = new Requirement("SERVICE.RUN_CUSTOM_COMMAND",
            "Perform service-specific tasks", "create the Ozone bucket and S3 identity of the release's Iceberg catalog");

    /** Steps that call the local Ambari with the operator's session whatever the platform context. */
    private static final Map<CommandType, List<Requirement>> ANY_CONTEXT = Map.of(
            CommandType.KEYTAB_ISSUE_PRINCIPAL, List.of(TOGGLE_KERBEROS),
            CommandType.AMBARI_VIEW_PROVISION, List.of(MANAGE_VIEWS));

    /**
     * Steps that call the local Ambari with the operator's session when the deploy uses the Ambari-managed context;
     * with another context they talk to that context's own endpoints with its stored credentials. The Polaris step
     * runs a Polaris service command to create the catalog's bucket; the deploy leaves it out of the check when the
     * operator gave an S3 key, which the step then uses instead.
     */
    private static final Map<CommandType, List<Requirement>> MANAGED_CONTEXT = Map.of(
            CommandType.OM_RANGER_TAGSYNC_REGISTER, List.of(MODIFY_CONFIGS, RUN_CUSTOM_COMMAND),
            CommandType.ATLAS_USER_PROVISION_OM, List.of(MODIFY_CONFIGS, RUN_CUSTOM_COMMAND),
            CommandType.ATLAS_SIMPLE_AUTHZ_GRANT_OM, List.of(MODIFY_CONFIGS, RUN_CUSTOM_COMMAND),
            CommandType.POLARIS_PROVISION_CATALOG, List.of(PROVISION_OBJECT_STORE));

    private final AmbariActionClient ambari;
    private final String user;
    private final String cluster;
    /** Whether Ambari was already asked; {@link #granted} stays {@code null} when it could not answer. */
    private boolean asked;
    private Set<String> granted;

    /**
     * @param ambari  client carrying the operator's Ambari session; {@code null} when Ambari is not reachable
     * @param user    Ambari user name of the operator
     * @param cluster cluster the steps act on, or {@code null} when unknown
     */
    public AmbariPermissionPreflight(AmbariActionClient ambari, String user, String cluster) {
        this.ambari = ambari;
        this.user = user;
        this.cluster = cluster;
    }

    /**
     * The Ambari authorizations the planned steps need, each once, in the order the steps come.
     *
     * @param stepTypes      the {@link CommandType} names of the planned steps
     * @param managedContext whether the deploy uses the Ambari-managed platform context
     * @return the required authorizations; empty when no step uses the operator's Ambari session
     */
    public static List<Requirement> requiredFor(Collection<String> stepTypes, boolean managedContext) {
        List<Requirement> required = new ArrayList<>();
        if (stepTypes == null) {
            return required;
        }
        for (String stepType : stepTypes) {
            CommandType type;
            try {
                type = CommandType.valueOf(stepType);
            } catch (IllegalArgumentException | NullPointerException unknown) {
                continue;
            }
            List<Requirement> needed = new ArrayList<>(ANY_CONTEXT.getOrDefault(type, List.of()));
            if (managedContext) {
                needed.addAll(MANAGED_CONTEXT.getOrDefault(type, List.of()));
            }
            for (Requirement requirement : needed) {
                if (!required.contains(requirement)) {
                    required.add(requirement);
                }
            }
        }
        return required;
    }

    /**
     * The message refusing the deploy, or {@code null} when the account holds every required authorization.
     *
     * @param user     Ambari user name of the operator
     * @param cluster  cluster the steps act on, or {@code null} when unknown
     * @param required the authorizations the planned steps need
     * @param granted  the authorizations the operator holds on that cluster
     * @return the message to show, naming each missing permission and what it is needed for
     */
    public static String missingMessage(String user, String cluster, Collection<Requirement> required, Set<String> granted) {
        // A permission several steps need is named once, with everything it is needed for.
        Map<String, List<Requirement>> missing = required.stream()
                .filter(r -> granted == null || !granted.contains(r.authorizationId()))
                .collect(Collectors.groupingBy(Requirement::authorizationId, LinkedHashMap::new, Collectors.toList()));
        if (missing.isEmpty()) {
            return null;
        }
        String details = missing.values().stream()
                .map(uses -> "'" + uses.get(0).label() + "' (" + uses.get(0).authorizationId() + "), needed to "
                        + uses.stream().map(Requirement::purpose).distinct().collect(Collectors.joining(", and to ")))
                .collect(Collectors.joining("; "));
        return "This deploy needs Ambari permissions that the Ambari account '" + user + "' does not have"
                + (cluster == null || cluster.isBlank() ? "" : " on cluster '" + cluster + "'") + ": " + details
                + ". The deploy did not start. Ask an Ambari administrator to grant these permissions to the account, "
                + "then deploy again.";
    }

    /**
     * Checks the operator's Ambari authorizations against the ones the deploy needs.
     *
     * @param required the authorizations needed, e.g. from {@link #requiredFor}
     * @return the message refusing the deploy, or {@code null} when nothing is missing or the permissions could not
     *         be read (Ambari still enforces them when the steps run)
     */
    public String check(Collection<Requirement> required) {
        if (required == null || required.isEmpty() || ambari == null || user == null || user.isBlank()) {
            return null;
        }
        if (!asked) {
            asked = true;
            try {
                granted = ambari.listUserAuthorizations(user, cluster);
            } catch (Exception e) {
                LOG.warn("Could not read the Ambari permissions of '{}' before the deploy; the steps will run and Ambari "
                        + "will enforce them: {}", user, e.toString());
            }
        }
        if (granted == null) {
            return null;
        }
        String missing = missingMessage(user, cluster, required, granted);
        if (missing != null) {
            LOG.warn("Deploy refused before it started: {}", missing);
        }
        return missing;
    }
}
