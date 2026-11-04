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

package org.apache.ambari.server.serveraction.kerberos;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.KerberosHelper;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.serveraction.kerberos.stageutils.ResolvedKerberosPrincipal;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Server action to (idempotently) create/rotate a single principal and stash its keytab in
 * Ambari's temporary credential store. structured_out carries only non-secret metadata.
 *
 * Required command params:
 *  - "principal": the Kerberos principal to create/rotate (e.g. "HTTP/myhost@EXAMPLE.COM")
 *  - "default_realm": realm for the handler open()
 *  - "kdc_type": one of KDCType values (e.g. "MIT_KDC", "AD", "IPA", "NONE")
 *
 * Structured out JSON:
 *  {
 *    "principal": "<principal>",
 *    "kvno": <int>,
 *    "created": true|false,
 *    "payload_ref": "<temporary-store-reference>",
 *    "payload_sha256": "<sha256-hex>"
 *  }
 */
public class GenerateAdhocKeytabServerAction extends KerberosServerAction {

    private static final Logger LOG = LoggerFactory.getLogger(GenerateAdhocKeytabServerAction.class);

    @Inject
    private KerberosOperationHandlerFactory kerberosOperationHandlerFactory;

    @Inject
    private KerberosHelper kerberosHelper;

    @Inject
    private AdhocKeytabPayloadStore adhocKeytabPayloadStore;

    @Override
    public CommandReport execute(java.util.concurrent.ConcurrentMap<String, Object> requestSharedDataContext) throws AmbariException {
        final Map<String, String> params = getCommandParameters();

        if (params != null) {
            LOG.info("GenerateAdhocKeytabServerAction called with params: principal='{}', default_realm='{}', kdc_type='{}'",
                    params.get("principal"),
                    params.get("default_realm"),
                    params.get("kdc_type"));
        } else {
            LOG.info("GenerateAdhocKeytabServerAction called with null params");
        }

        final String principal = (params == null) ? null : params.get("principal");

        if (StringUtils.isBlank(principal)) {
            String msg = "Required parameter 'principal' is missing or empty";
            actionLog.writeStdErr(msg);
            LOG.error(msg);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), msg);
        }

        final String defaultRealm = getDefaultRealm(params);
        KDCType kdcType = getKDCType(params);

        actionLog.writeStdOut(String.format("Generating keytab for principal '%s' (kdcType=%s, realm=%s)...",
                principal, kdcType, defaultRealm));
        LOG.info("Generating keytab for principal '{}' (kdcType={}, realm={})", principal, kdcType, defaultRealm);

        KerberosOperationHandler handler = null;

        try {
            // Cluster context + admin credential
            String clusterName = getClusterName();
            LOG.info("Determined cluster name for GenerateAdhocKeytabServerAction: '{}'", clusterName);

            PrincipalKeyCredential adminCred = kerberosHelper.getKDCAdministratorCredentials(clusterName);
            LOG.info("Obtained KDC administrator credentials for cluster '{}'", clusterName);

            // Kerberos env (kerberos-env) from cluster
            Map<String, String> kerberosEnv = getConfigurationProperties("kerberos-env");
            LOG.debug("kerberos-env properties keys: {}", kerberosEnv != null ? kerberosEnv.keySet() : "null");

            if (kdcType == KDCType.NONE) {
                kdcType = KDCType.translate(kerberosEnv.get(this.kerberosHelper.KDC_TYPE));
                LOG.info("KDC type resolved from kerberos-env to '{}'", kdcType);
            }

            // Pick handler
            handler = kerberosOperationHandlerFactory.getKerberosOperationHandler(kdcType);
            LOG.info("Using KerberosOperationHandler implementation '{}' for kdcType={}", handler.getClass().getName(), kdcType);

            handler.open(adminCred, defaultRealm, kerberosEnv);
            LOG.info("KerberosOperationHandler opened for realm '{}' and kdcType={}", defaultRealm, kdcType);

            // The principal and its keytab: an existing principal keeps its key when the KDC exports it, and is
            // rotated otherwise (the cluster's KDC, whose principals Ambari manages).
            AdhocKeytabGenerator.Result issued = AdhocKeytabGenerator.issue(handler, principal, true,
                    actionLog::writeStdOut);
            int kvno = issued.getKvno();
            boolean exists = !issued.isCreated();
            byte[] keytabBytes = issued.getKeytab();
            String payloadRef = adhocKeytabPayloadStore.storePayload(clusterName, keytabBytes);
            LOG.info("Stored ad-hoc keytab payload in temporary credential store under ref '{}'", payloadRef);

            // Structured out payload
            Map<String, Object> out = new HashMap<>();
            out.put("principal", principal);
            out.put("kvno", kvno);
            out.put("created", !exists);
            out.put("payload_ref", payloadRef);
            out.put("payload_sha256", sha256Hex(keytabBytes));

            String structuredOut = toJson(out);
            String stdout = String.format("Keytab generated for principal '%s' (kvno=%d, created=%s)",
                    principal, kvno, !exists);

            actionLog.writeStdOut(stdout);
            LOG.info(stdout);

            return createCommandReport(0, HostRoleStatus.COMPLETED, structuredOut, stdout, actionLog.getStdErr());

        } catch (Exception e) {
            String err = String.format("Failed generating keytab for '%s': %s", principal, e.getMessage());
            LOG.error(err, e);
            actionLog.writeStdErr(err);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
        } finally {
            if (handler != null) {
                try {
                    handler.close();
                    LOG.info("KerberosOperationHandler closed");
                } catch (Exception ignore) {
                    LOG.warn("Error closing KerberosOperationHandler: {}", ignore.getMessage());
                }
            }
        }
    }

    /**
     * KerberosServerAction demands this, but this action isn't identity-iterator based.
     * Mirror the pattern from DestroyPrincipalsServerAction.
     */
    @Override
    protected CommandReport processIdentity(ResolvedKerberosPrincipal resolvedPrincipal,
                                            KerberosOperationHandler operationHandler,
                                            Map<String, String> kerberosConfiguration,
                                            boolean includedInFilter,
                                            Map<String, Object> requestSharedDataContext) throws AmbariException {
        throw new UnsupportedOperationException("processIdentity is not used by GenerateAdhocKeytabServerAction");
    }

    // ---- helpers ----

    private static String toJson(Map<String, Object> map) {
        // Ambari uses StageUtils.getGson() elsewhere, but we keep it simple here
        // to avoid pulling in utils from this action.
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v.toString());
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }



    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
