package org.apache.ambari.server.serveraction.kerberos;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.KerberosHelper;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.serveraction.kerberos.stageutils.ResolvedKerberosPrincipal;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
/**
 * Server action to (idempotently) create/rotate a single principal and return its keytab (base64)
 * via structured out. Intended for ad-hoc principals requested by external systems (e.g. webhook).
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
 *    "keytab_b64": "<base64>"
 *  }
 */
public class GenerateAdhocKeytabServerAction extends KerberosServerAction {

    private static final Logger LOG = LoggerFactory.getLogger(GenerateAdhocKeytabServerAction.class);

    @Inject
    private KerberosOperationHandlerFactory kerberosOperationHandlerFactory;

    @Inject
    private KerberosHelper kerberosHelper;

    @Override
    public CommandReport execute(java.util.concurrent.ConcurrentMap<String, Object> requestSharedDataContext) throws AmbariException {
        final Map<String, String> params = getCommandParameters();
        final String principal = (params == null) ? null : params.get("principal");

        if (StringUtils.isBlank(principal)) {
            String msg = "Required parameter 'principal' is missing or empty";
            actionLog.writeStdErr(msg);
            LOG.error(msg);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), msg);
        }

        final String defaultRealm = getDefaultRealm(params);
        final KDCType kdcType = getKDCType(params);

        actionLog.writeStdOut(String.format("Generating keytab for principal '%s' (kdcType=%s, realm=%s)...",
                principal, kdcType, defaultRealm));

        KerberosOperationHandler handler = null;
        File tmpKeytab = null;

        try {
            // Cluster context + admin credential
            String clusterName = getClusterName();
            PrincipalKeyCredential adminCred = kerberosHelper.getKDCAdministratorCredentials(clusterName);

            // Kerberos env (kerberos-env) from cluster
            Map<String, String> kerberosEnv = getConfigurationProperties("kerberos-env");

            // Pick handler
            handler = kerberosOperationHandlerFactory.getKerberosOperationHandler(kdcType);
            handler.open(adminCred, defaultRealm, kerberosEnv);

            // Ensure principal exists (create or rotate)
            boolean exists = handler.principalExists(principal, true);
            String password = generateSecurePassword(32);
            Integer kvno;

            if (!exists) {
                actionLog.writeStdOut("Principal does not exist; creating...");
                kvno = handler.createPrincipal(principal, password, true);
            } else {
                actionLog.writeStdOut("Principal exists; rotating password...");
                kvno = handler.setPrincipalPassword(principal, password, true);
            }

            // Generate keytab (write to temp file, then base64 it for structured out)
            tmpKeytab = File.createTempFile("adhoc_", ".keytab");
            // Using protected helpers from KerberosOperationHandler (same package access)
            handler.createKeytabFile(principal, password, kvno, tmpKeytab);

            byte[] keytabBytes = Files.readAllBytes(tmpKeytab.toPath());
            String keytabB64 = Base64.encodeBase64String(keytabBytes);

            // Structured out payload
            Map<String, Object> out = new HashMap<>();
            out.put("principal", principal);
            out.put("kvno", kvno);
            out.put("created", !exists);
            out.put("keytab_b64", keytabB64);

            String structuredOut = toJson(out);
            String stdout = String.format("Keytab generated for principal '%s' (kvno=%d, created=%s)",
                    principal, kvno, !exists);

            actionLog.writeStdOut(stdout);
            return createCommandReport(0, HostRoleStatus.COMPLETED, structuredOut, stdout, actionLog.getStdErr());

        } catch (Exception e) {
            String err = String.format("Failed generating keytab for '%s': %s", principal, e.getMessage());
            LOG.error(err, e);
            actionLog.writeStdErr(err);
            return createCommandReport(0, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
        } finally {
            if (handler != null) {
                try { handler.close(); } catch (Exception ignore) { /* no-op */ }
            }
            if (tmpKeytab != null) {
                try { Files.deleteIfExists(tmpKeytab.toPath()); } catch (Exception ignore) { /* no-op */ }
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

    private static String generateSecurePassword(int len) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{}";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
