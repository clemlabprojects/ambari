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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.utils.CommandUtils.AmbariConfigRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@code dynamicValues} of a service definition, or of one of its dependencies, into Helm overrides:
 * each entry {@code TOKEN:helm.path} names a value of the cluster (its Kerberos realm, its OIDC issuer and realm...)
 * and the chart value it is written to.
 *
 * <p>A token is read from the platform context the deploy selected when that context is not the managed one, and
 * otherwise from the managed cluster's Ambari configuration ({@link CommandUtils#DYNAMIC_SOURCE_MAP}). Written
 * {@code TOKEN[/sub/path]:helm.path}, the sub-path is appended to the value, so one property feeds several chart values
 * (the Keycloak issuer URL gives the authorize, token and userinfo endpoints). A token that resolves to nothing is left
 * out, so the chart keeps its own default rather than an empty or half-built value.</p>
 *
 * <p>The release's chart and its dependencies are resolved here alike, whether the deploy is being planned or its
 * Helm steps are being created.</p>
 */
public class DynamicValuesResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicValuesResolver.class);

    /** The token of the OIDC issuer URL, which is composed from the Keycloak address and realm when not set. */
    static final String OIDC_ISSUER_TOKEN = "AMBARI_OIDC_ISSUER_URL";

    private final ViewContext ctx;
    private final CommandUtils commandUtils;

    /**
     * @param ctx          the view context, through which Ambari and the platform contexts are read
     * @param commandUtils finds the managed cluster's name
     */
    public DynamicValuesResolver(ViewContext ctx, CommandUtils commandUtils) {
        this.ctx = ctx;
        this.commandUtils = commandUtils;
    }

    /**
     * The entries of a {@code dynamicValues} list: its non-blank strings, trimmed.
     *
     * @param dynamicValues the list as the service definition gives it; anything else gives no entry
     * @return the entries, in order
     */
    public static List<String> entriesOf(Object dynamicValues) {
        List<String> entries = new ArrayList<>();
        if (dynamicValues instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    entries.add(s.trim());
                }
            }
        }
        return entries;
    }

    /**
     * Resolves the {@code dynamicValues} of a chart.
     *
     * @param dynamicValues the service definition's list (see {@link #entriesOf})
     * @param formValues    the form of the deploy, whose {@code platformContextId} selects the context tokens are read
     *                      from; null for the managed cluster
     * @param ambariBaseUri the Ambari address the deploy came through; null when unknown, and then nothing is resolved
     * @param authHeaders   the operator's Ambari session
     * @param chartLabel    what the values are for, in the log ("the release chart", "dependency keda")
     * @return Helm path to value, in the order of the list; empty when nothing resolved
     */
    public Map<String, String> resolve(Object dynamicValues, Object formValues, String ambariBaseUri,
                                       Map<String, String> authHeaders, String chartLabel) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> entries = entriesOf(dynamicValues);
        if (entries.isEmpty()) {
            return resolved;
        }
        if (ambariBaseUri == null || ambariBaseUri.isBlank()) {
            LOG.warn("No Ambari address to resolve the dynamic values {} of {}: they are not set", entries, chartLabel);
            return resolved;
        }
        // Without a managed cluster (a standalone Ambari) the values of a selected platform context still apply.
        String cluster = null;
        try {
            cluster = commandUtils.resolveClusterName(ambariBaseUri, authHeaders);
        } catch (Exception e) {
            LOG.warn("Could not find the Ambari cluster for the dynamic values of {} ({}): only those of a selected"
                    + " platform context are set", chartLabel, e.toString());
        }
        AmbariActionClient ambari = new AmbariActionClient(ctx, ambariBaseUri, cluster, authHeaders);
        Map<String, String> contextFields = contextResolvedFields(formValues, ambari, cluster);
        for (String entry : entries) {
            resolveEntry(entry, ambari, cluster, contextFields, resolved);
        }
        return resolved;
    }

    /** Resolves one {@code TOKEN[/suffix]:helm.path} entry into {@code resolved}; logs why when it is left out. */
    private void resolveEntry(String entry, AmbariActionClient ambari, String cluster,
                              Map<String, String> contextFields, Map<String, String> resolved) {
        String[] parts = entry.split(":", 2);
        if (parts.length != 2) {
            LOG.warn("Invalid dynamic value '{}', expected 'TOKEN:helm.path'", entry);
            return;
        }
        String token = parts[0].trim();
        String helmPath = parts[1].trim();
        if (helmPath.isEmpty()) {
            LOG.warn("Empty Helm path in dynamic value '{}', skipping", entry);
            return;
        }
        String suffix = "";
        int bracket = token.indexOf('[');
        if (bracket > 0 && token.endsWith("]")) {
            suffix = token.substring(bracket + 1, token.length() - 1);
            token = token.substring(0, bracket);
        }
        AmbariConfigRef ref = CommandUtils.DYNAMIC_SOURCE_MAP.get(token);
        if (ref == null) {
            LOG.warn("No source for dynamic value token '{}', skipping", token);
            return;
        }
        String value;
        try {
            value = CommandUtils.contextValueForToken(token, contextFields);
            if ((value == null || value.isBlank()) && cluster != null) {
                value = ambari.getDesiredConfigProperty(cluster, ref.type, ref.key);
            }
            // Many clusters set only the Keycloak address and realm, not the issuer itself.
            if ((value == null || value.isBlank()) && OIDC_ISSUER_TOKEN.equals(token) && cluster != null) {
                value = composedOidcIssuerUrl(ambari, cluster);
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve dynamic value token '{}' (type={}, key={}): {}", token, ref.type, ref.key,
                    e.toString());
            return;
        }
        if (value == null || value.isBlank()) {
            LOG.warn("Dynamic value token '{}' resolved to nothing (type={}, key={}): {} is not set", token, ref.type,
                    ref.key, helmPath);
            return;
        }
        // "https://issuer/" + "/path" must not give a double slash.
        String withSuffix = suffix.isEmpty() ? value
                : (value.endsWith("/") ? value.substring(0, value.length() - 1) : value) + suffix;
        resolved.put(helmPath, withSuffix);
        LOG.info("Resolved dynamic value '{}' -> {} = '{}'", token, helmPath, withSuffix);
    }

    /**
     * The fields a non-managed platform context resolves ({@code <capability>.<field>}), which the tokens are read from
     * in place of the managed cluster's configuration.
     *
     * @param formValues the form of the deploy, naming the context in {@code platformContextId}
     * @param ambari     the managed cluster's Ambari, which a context reached through it is resolved with
     * @param cluster    the managed cluster; null on a standalone Ambari
     * @return the fields; empty for the managed context, when none is selected or when it cannot be resolved
     */
    public Map<String, String> contextResolvedFields(Object formValues, AmbariActionClient ambari, String cluster) {
        try {
            if (!(formValues instanceof Map<?, ?> form)) {
                return Collections.emptyMap();
            }
            Object selected = form.get("platformContextId");
            String contextId = selected == null ? null : String.valueOf(selected);
            if (contextId == null || contextId.isBlank() || ContextService.DEFAULT_CONTEXT_ID.equals(contextId)) {
                return Collections.emptyMap();
            }
            ResolvedContext rc = new ContextService(ctx).resolve(contextId, ambari, cluster);
            return rc != null && rc.getResolvedFields() != null ? rc.getResolvedFields() : Collections.emptyMap();
        } catch (Exception e) {
            LOG.warn("Context-aware dynamic values: could not resolve the platform context: {}", e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * The OIDC issuer URL of the managed cluster: {@code oidc-env/oidc_issuer_url}, or else
     * {@link #composedOidcIssuerUrl composed} from the Keycloak address and realm.
     *
     * @return the issuer URL, or null when the cluster sets neither
     */
    public static String oidcIssuerUrl(AmbariActionClient ambari, String cluster) {
        if (ambari == null || cluster == null || cluster.isBlank()) {
            return null;
        }
        try {
            String explicit = ambari.getDesiredConfigProperty(cluster, "oidc-env", "oidc_issuer_url");
            if (explicit != null && !explicit.isBlank()) {
                return explicit;
            }
        } catch (Exception e) {
            LOG.warn("Could not read the OIDC issuer URL of cluster {}: {}", cluster, e.toString());
            return null;
        }
        return composedOidcIssuerUrl(ambari, cluster);
    }

    /**
     * The issuer URL Keycloak gives a realm, {@code <oidc_admin_url>/realms/<oidc_realm>}, from the managed cluster's
     * {@code oidc-env}. Both must be set: either one blank would give an issuer such as {@code /realms/}, which sends
     * every sign-in into a redirect loop.
     *
     * @return the issuer URL, or null when either is blank or cannot be read
     */
    public static String composedOidcIssuerUrl(AmbariActionClient ambari, String cluster) {
        if (ambari == null || cluster == null || cluster.isBlank()) {
            return null;
        }
        try {
            String adminUrl = ambari.getDesiredConfigProperty(cluster, "oidc-env", "oidc_admin_url");
            String realm = ambari.getDesiredConfigProperty(cluster, "oidc-env", "oidc_realm");
            String issuer = composeOidcIssuerUrl(adminUrl, realm);
            if (issuer == null) {
                LOG.info("Cluster {} sets no OIDC issuer URL, and no Keycloak address and realm to build it from"
                        + " (oidc-env oidc_admin_url='{}', oidc_realm='{}'): no OIDC issuer is used", cluster,
                        adminUrl == null ? "" : adminUrl, realm == null ? "" : realm);
            }
            return issuer;
        } catch (Exception e) {
            LOG.warn("Could not compose the OIDC issuer URL of cluster {} from oidc_admin_url and oidc_realm: {}",
                    cluster, e.toString());
            return null;
        }
    }

    /**
     * {@code <adminUrl>/realms/<realm>}, the trailing slash of the address dropped.
     *
     * @return the issuer URL, or null when either part is blank
     */
    static String composeOidcIssuerUrl(String adminUrl, String realm) {
        if (adminUrl == null || adminUrl.isBlank() || realm == null || realm.isBlank()) {
            return null;
        }
        return adminUrl.trim().replaceAll("/+$", "") + "/realms/" + realm.trim();
    }
}
