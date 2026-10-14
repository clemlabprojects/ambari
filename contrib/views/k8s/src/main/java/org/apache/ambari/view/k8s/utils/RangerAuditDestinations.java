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

package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.k8s.service.ConfigResolutionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses where the audit events of a Ranger plugin running in a pod are written. A plugin knows no cluster, so left
 * to its defaults it only logs to the pod: nothing reaches the Solr or HDFS the Ranger Admin UI reads its audit
 * page from. The destinations follow the platform context's Ranger instead ({@code ranger.audit*} fields), and the
 * wizard may override each of them, so one release can audit elsewhere than the cluster does.
 *
 * <p>The wizard's choices are chart values under {@code ranger.audit}: {@code solrMode} and {@code hdfsMode} (each
 * {@code auto}, {@code on} or {@code off}), the endpoints {@code solrUrls}, {@code solrZookeepers} and
 * {@code hdfsDir} that replace the context's, and {@code log4j}, the pod log, kept on unless turned off.
 */
public final class RangerAuditDestinations {

    /** Follow the platform context. */
    public static final String AUTO = "auto";
    public static final String ON = "on";
    public static final String OFF = "off";

    private static final String VALUES = "ranger.audit.";

    private RangerAuditDestinations() {
    }

    /**
     * Writes the audit destinations into the plugin's audit properties.
     *
     * @param audit         the plugin's audit properties, starting from the plugin defaults; changed in place
     * @param contextFields the platform context's resolved fields ({@code ranger.audit*}); may be null
     * @param values        the release's chart values, holding the wizard's {@code ranger.audit} choices; may be null
     * @param unreadContextFields the context fields that could not be read from the cluster; may be null
     * @param kerberosPrincipal the principal the plugin's keytab holds, when the plugin logs in with Kerberos; null
     *                      otherwise. Solr is written to with it.
     * @param kerberosKeytab the keytab path that goes with {@code kerberosPrincipal}
     * @param hadoopConfAvailable whether the release gets the cluster's core-site and hdfs-site, which the HDFS
     *                      writer needs to resolve the file system (a high-availability name service, say)
     * @return what the operator must still know: a destination that was asked for but has nowhere to write to, that
     *         a Kerberized cluster only accepts from users it was told about, or that the context's cluster could not
     *         be asked about
     */
    public static List<String> apply(Map<String, String> audit, Map<String, String> contextFields,
                                     Map<String, Object> values, List<String> unreadContextFields,
                                     String kerberosPrincipal, String kerberosKeytab,
                                     boolean hadoopConfAvailable) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> context = contextFields == null ? Map.of() : contextFields;

        String solrMode = mode(values, "solrMode");
        // The Solr endpoint is one choice, a ZooKeeper or URLs: a wizard endpoint of either kind replaces the
        // context's whole endpoint, or the context's ZooKeeper would silently win over a URL typed in the wizard.
        String solrUrls = text(values, "solrUrls");
        String solrZk = text(values, "solrZookeepers");
        if (solrUrls == null && solrZk == null) {
            solrUrls = firstNonBlank(context.get("ranger.auditSolrUrls"));
            solrZk = firstNonBlank(context.get("ranger.auditSolrZookeepers"));
        }
        boolean solr = wanted(solrMode, context.get("ranger.auditSolrEnabled"));
        if (solr && solrUrls == null && solrZk == null) {
            warnings.add("Ranger audit to Solr is " + (ON.equals(solrMode) ? "turned on for this release" : "on for the"
                    + " platform context") + " but no Solr URL or ZooKeeper is known, so Trino does not audit to Solr."
                    + " Set the Solr endpoint in the wizard or on the platform context.");
            solr = false;
        }
        flag(audit, "xasecure.audit.destination.solr", "xasecure.audit.solr.is.enabled", solr);
        String writer = shortName(kerberosPrincipal);
        if (solr) {
            // The plugin reaches a SolrCloud through its ZooKeeper and a plain Solr through its URLs: only the one
            // that describes the Solr is written, so the other cannot send it the wrong way.
            audit.put("xasecure.audit.destination.solr.zookeepers", solrZk == null ? "" : solrZk);
            audit.put("xasecure.audit.solr.zookeepers", solrZk == null ? "" : solrZk);
            audit.put("xasecure.audit.destination.solr.urls", solrZk == null ? solrUrls : "");
            String collection = context.get("ranger.auditSolrCollection");
            if (collection != null && !collection.isBlank()) {
                audit.put("xasecure.audit.destination.solr.collection", collection.trim());
                audit.put("xasecure.audit.solr.collection.name", collection.trim());
            }
            if (kerberosPrincipal != null && kerberosKeytab != null) {
                // Without a login of its own the writer's requests to a Kerberized Solr are all rejected, and the
                // events dropped. The same keytab as the policy download is used.
                audit.put("xasecure.audit.destination.solr.force.use.inmemory.jaas.config", "true");
                audit.put("xasecure.audit.jaas.Client.loginModuleName", "com.sun.security.auth.module.Krb5LoginModule");
                audit.put("xasecure.audit.jaas.Client.loginModuleControlFlag", "required");
                audit.put("xasecure.audit.jaas.Client.option.useKeyTab", "true");
                audit.put("xasecure.audit.jaas.Client.option.storeKey", "true");
                audit.put("xasecure.audit.jaas.Client.option.serviceName", "solr");
                audit.put("xasecure.audit.jaas.Client.option.keyTab", kerberosKeytab);
                audit.put("xasecure.audit.jaas.Client.option.principal", kerberosPrincipal);
            }
            if (writer != null) {
                // A Kerberized Infra Solr only lets the users on its audit allow-list write to the audit collection,
                // and the plugin's user is new to the cluster: the events would be refused and spooled in the pod.
                warnings.add("Ranger audit to Solr is on, and the cluster is Kerberized: Solr only accepts audit"
                        + " events from the users it was told about. Add '" + writer + "' to the 'Ranger audit"
                        + " service users' of Ambari Infra Solr and restart it, or the events never reach the Ranger"
                        + " Admin audit page.");
            }
        }

        String hdfsMode = mode(values, "hdfsMode");
        String hdfsDir = firstNonBlank(text(values, "hdfsDir"), context.get("ranger.auditHdfsDir"));
        boolean hdfs = wanted(hdfsMode, context.get("ranger.auditHdfsEnabled"));
        if (hdfs && hdfsDir == null) {
            warnings.add("Ranger audit to HDFS is " + (ON.equals(hdfsMode) ? "turned on for this release" : "on for the"
                    + " platform context") + " but no audit directory is known, so Trino does not audit to HDFS."
                    + " Set the directory in the wizard or on the platform context.");
            hdfs = false;
        }
        if (hdfs && !hadoopConfAvailable) {
            // The writer cannot resolve a name service or reach a secured NameNode without the cluster's Hadoop
            // configuration: it would fail in a loop while the deploy reported the destination as on. What the
            // operator asked for explicitly is kept; what only follows the context is not switched on blindly.
            warnings.add("Ranger audit to HDFS needs the cluster's Hadoop configuration (core-site and hdfs-site) in"
                    + " the Trino pods, which this release does not have." + (ON.equals(hdfsMode)
                    ? " Audit to HDFS may fail until it is added."
                    : " Trino does not audit to HDFS; turn it on in the wizard once the configuration is provided."));
            hdfs = ON.equals(hdfsMode);
        }
        flag(audit, "xasecure.audit.destination.hdfs", "xasecure.audit.hdfs.is.enabled", hdfs);
        if (hdfs) {
            audit.put("xasecure.audit.destination.hdfs.dir", hdfsDir);
            audit.put("xasecure.audit.hdfs.dir", hdfsDir);
            if (writer != null) {
                // The cluster only prepares the audit folders of the services it manages, each closed to other users.
                warnings.add("Ranger audit to HDFS is on, and the cluster is Kerberized: the audit directory has no"
                        + " folder for Trino. Create '" + hdfsDir.replaceAll("/+$", "") + "/trino' owned by '" + writer
                        + "', or let '" + writer + "' write to the directory, or the events never reach the Ranger"
                        + " Admin audit page.");
            }
        }

        // The pod log stays an extra next to the cluster's destinations unless the operator turns it off.
        String log4j = text(values, "log4j");
        flag(audit, "xasecure.audit.destination.log4j", "xasecure.audit.log4j.is.enabled",
                log4j == null || !"false".equalsIgnoreCase(log4j));

        // A destination left to the context, whose settings could not be read, is not on although the cluster may
        // write to it: say so, rather than let the plugin quietly audit to the pod log only.
        boolean followsContext = AUTO.equals(solrMode) || AUTO.equals(hdfsMode);
        if (followsContext && unreadContextFields != null) {
            for (String field : unreadContextFields) {
                if (field != null && field.startsWith("ranger.audit")) {
                    warnings.add("The audit settings of the platform context's Ranger could not be read, so Trino"
                            + " may audit to the pod log only. Check the context's connection and deploy again.");
                    break;
                }
            }
        }
        return warnings;
    }

    /** The wizard's mode for a destination; anything but {@code on} or {@code off} follows the context. */
    private static String mode(Map<String, Object> values, String name) {
        String mode = text(values, name);
        if (mode == null) {
            return AUTO;
        }
        mode = mode.toLowerCase(Locale.ROOT);
        return ON.equals(mode) || OFF.equals(mode) ? mode : AUTO;
    }

    /** The user a principal stands for on the cluster: no host, no realm. Null when there is no principal. */
    private static String shortName(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        String name = principal.trim();
        int at = name.indexOf('@');
        if (at >= 0) {
            name = name.substring(0, at);
        }
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(0, slash) : name;
    }

    private static boolean wanted(String mode, String contextFlag) {
        if (ON.equals(mode)) {
            return true;
        }
        return AUTO.equals(mode) && contextFlag != null && "true".equalsIgnoreCase(contextFlag.trim());
    }

    private static void flag(Map<String, String> audit, String key, String legacyKey, boolean on) {
        audit.put(key, String.valueOf(on));
        audit.put(legacyKey, String.valueOf(on));
    }

    private static String text(Map<String, Object> values, String name) {
        if (values == null) {
            return null;
        }
        Object v = ConfigResolutionService.getByDottedPath(values, VALUES + name);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return String.valueOf(v).trim();
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }
}
