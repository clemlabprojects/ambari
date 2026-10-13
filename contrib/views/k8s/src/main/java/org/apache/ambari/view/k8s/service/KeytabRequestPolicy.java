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

import org.apache.ambari.view.k8s.requests.KeytabRequest;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which keytabs the view's keytab endpoint ({@code POST /kerberos/keytab}) hands out: only for the releases KDPS
 * deployed, and only principals named after the namespace that receives the keytab. Ambari checks the principal
 * again when it issues the keytab; this check keeps a caller from even asking for a keytab KDPS has no use for.
 *
 * <p>A request from the keytab webhook must also name the release of its pod, and may only write the per-pod keytab
 * Secrets the webhook mounts ({@value #WEBHOOK_SECRET_PREFIX}...), so the webhook's credential cannot overwrite any
 * other Secret of a KDPS namespace.
 */
public final class KeytabRequestPolicy {

    /** Prefix of the Secrets the keytab webhook asks the view to write, one per pod. */
    public static final String WEBHOOK_SECRET_PREFIX = "keytab-";

    /** Kubernetes namespace names (RFC 1123 labels). */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    /** Kubernetes Secret names (RFC 1123 subdomains). */
    private static final Pattern SECRET_NAME =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");
    private static final Pattern SECRET_KEY = Pattern.compile("[-._a-zA-Z0-9]+");
    /** {@code name[/host]@REALM}, with the characters KDC tooling accepts without quoting (as Ambari checks it). */
    private static final Pattern PRINCIPAL = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9._-]*)(?:/([A-Za-z0-9][A-Za-z0-9.-]*))?@([A-Za-z0-9][A-Za-z0-9.-]*)");

    private KeytabRequestPolicy() {
    }

    /** A keytab request the policy refuses; the message says why and is returned to the caller. */
    public static class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }

    /**
     * Checks a keytab request against the releases KDPS deployed in its namespace.
     *
     * @param request             the request
     * @param releasesInNamespace the releases KDPS recorded in the request's namespace
     * @param fromWebhook         whether the request comes from the keytab webhook
     * @return the release the request names; null when an operator's request names none
     * @throws RefusedException when the request is not for a release KDPS deployed, or its principal or Secret is not
     *                          one KDPS gives such a release
     */
    public static K8sReleaseEntity check(KeytabRequest request, List<K8sReleaseEntity> releasesInNamespace,
                                         boolean fromWebhook) {
        String namespace = request.getNamespace();
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new RefusedException("'" + namespace + "' is not a Kubernetes namespace name.");
        }
        if (releasesInNamespace == null || releasesInNamespace.isEmpty()) {
            throw new RefusedException("KDPS deployed no release in namespace '" + namespace
                    + "': keytabs are only issued for its releases.");
        }

        K8sReleaseEntity release = null;
        String releaseName = request.getReleaseName();
        if (releaseName == null || releaseName.isBlank()) {
            if (fromWebhook) {
                throw new RefusedException("The keytab webhook did not name the release of pod '"
                        + request.getPodName() + "' (label app.kubernetes.io/instance): keytabs are only issued for "
                        + "the releases KDPS deployed.");
            }
        } else {
            for (K8sReleaseEntity candidate : releasesInNamespace) {
                if (releaseName.equals(candidate.getReleaseName())) {
                    release = candidate;
                    break;
                }
            }
            if (release == null) {
                throw new RefusedException("KDPS did not deploy a release '" + releaseName + "' in namespace '"
                        + namespace + "': keytabs are only issued for its releases.");
            }
        }

        checkPrincipal(request.getPrincipal(), namespace);

        String secretName = request.getSecretName();
        if (secretName == null || secretName.length() > 253 || !SECRET_NAME.matcher(secretName).matches()) {
            throw new RefusedException("'" + secretName + "' is not a Kubernetes Secret name.");
        }
        if (fromWebhook && !secretName.startsWith(WEBHOOK_SECRET_PREFIX)) {
            throw new RefusedException("The keytab webhook may only write the keytab Secrets of its pods ("
                    + WEBHOOK_SECRET_PREFIX + "<pod>), not '" + secretName + "'.");
        }
        String key = request.getKeyNameInSecret();
        if (key != null && !key.isBlank() && !SECRET_KEY.matcher(key).matches()) {
            throw new RefusedException("'" + key + "' is not a Secret key name.");
        }
        return release;
    }

    /**
     * Accepts the principal shapes KDPS gives the principals of a namespace: {@code <service>-<namespace>@REALM} (or
     * {@code <service>-<namespace>-<cluster>@REALM}, the webhook's headless template with the cluster name, which
     * Ambari issues only when an administrator allowed it), or {@code <service>/<host>@REALM} whose host names the
     * namespace ({@code <name>.<namespace>.<suffix>}) or is the synthetic {@code <service>-<namespace>[.<suffix>]}.
     */
    private static void checkPrincipal(String principal, String namespace) {
        Matcher matcher = principal == null ? null : PRINCIPAL.matcher(principal.trim());
        if (matcher == null || !matcher.matches()) {
            throw new RefusedException("'" + principal + "' is not a Kerberos principal (name@REALM or "
                    + "name/host@REALM).");
        }
        String primary = matcher.group(1).toLowerCase(Locale.ROOT);
        String instance = matcher.group(2) == null ? null : matcher.group(2).toLowerCase(Locale.ROOT);
        boolean named;
        if (instance == null) {
            named = primary.endsWith("-" + namespace) || primary.contains("-" + namespace + "-");
        } else {
            String[] labels = instance.split("\\.", -1);
            named = labels[0].equals(primary + "-" + namespace);
            for (int i = 1; i < labels.length && !named; i++) {
                named = labels[i].equals(namespace);
            }
        }
        if (!named) {
            throw new RefusedException("Principal '" + principal + "' is not named after namespace '" + namespace
                    + "' (expected <service>-" + namespace + "@REALM or <service>/<name>." + namespace
                    + ".<suffix>@REALM): KDPS gives no such principal to its releases there.");
        }
    }
}
