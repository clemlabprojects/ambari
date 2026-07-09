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

import java.util.List;

import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a context-schema field's value live from Ambari for a MANAGED context, keyed by
 * the field's {@code managedResolver} (e.g. {@code atlas.url}, {@code ranger.url},
 * {@code kerberos.realm}). Host/port discovery mirrors {@code DiscoveryResource}.
 *
 * <p>One instance per resolution pass (memoizes component-host lookups). Unknown keys return
 * {@code null} so a fragment a downstream company adds without a built-in resolver simply
 * yields a blank live value (the operator can still set it on an external context).
 */
public class ManagedContextResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedContextResolver.class);

    private final AmbariActionClient ambari;
    private final String cluster;

    // Memoized lookups (per pass)
    private List<String> atlasHosts;
    private Boolean atlasResolved;
    private List<String> rangerHosts;
    private Boolean rangerResolved;
    private List<String> hms;
    private Boolean hmsResolved;
    private List<String> hs2;
    private Boolean hs2Resolved;

    public ManagedContextResolver(AmbariActionClient ambari, String cluster) {
        this.ambari = ambari;
        this.cluster = cluster;
    }

    public boolean available() {
        return ambari != null && cluster != null && !cluster.isBlank();
    }

    /** Resolve a single {@code managedResolver} key, or null when unknown/unavailable. */
    public String resolve(String key) {
        if (!available() || key == null) {
            return null;
        }
        try {
            switch (key) {
                case "atlas.url":               return atlasUrl();
                case "atlas.authMode":          return atlasAuthMode();
                case "atlas.aclMode":           return atlasAclMode();
                case "atlas.rangerServiceName": return atlasManaged() ? cluster + "_atlas" : null;
                case "ranger.url":              return rangerUrl();
                case "kerberos.realm":          return cfg("kerberos-env", "realm");
                case "kerberos.kdcHost":        return cfg("kerberos-env", "kdc_hosts");
                case "hive.metastoreUri":       return cfg("hive-site", "hive.metastore.uris");
                case "hive.hs2JdbcUrl":         return hs2JdbcUrl();
                case "hive.hs2HostPort":        return hs2HostPort();
                case "hive.scheme":             return hiveScheme();
                case "hive.transportMode":      return hiveTransportMode();
                case "hive.authMode":           return hiveAuthMode();
                case "hive.rangerServiceName":  return hs2Available() ? cluster + "_hive" : null;
                case "oidc.issuerUrl":          return oidcIssuer();
                case "oidc.realm":              return oidcRealm();
                case "oidc.adminRealm":         return cfg("oidc-env", "oidc_admin_realm");
                case "oidc.adminClientId":      return cfg("oidc-env", "oidc_admin_client_id");
                case "oidc.verifyTls":          return cfg("oidc-env", "oidc_verify_tls");
                case "oidc.principalDomain":    return cfg("oidc-env", "oidc_principal_domain");
                default:
                    return null;
            }
        } catch (Exception e) {
            LOG.warn("ManagedContextResolver: resolve('{}') failed: {}", key, e.toString());
            return null;
        }
    }

    // ----- Atlas -----

    private boolean atlasManaged() throws Exception {
        if (atlasResolved == null) {
            atlasHosts = ambari.getComponentHosts(cluster, "ATLAS", "ATLAS_SERVER");
            atlasResolved = atlasHosts != null && !atlasHosts.isEmpty();
        }
        return atlasResolved;
    }

    private String atlasUrl() throws Exception {
        if (!atlasManaged()) return null;
        String httpsPort = cfg("application-properties", "atlas.server.https.port");
        String httpPort  = cfg("application-properties", "atlas.server.http.port");
        String tlsRaw    = cfg("application-properties", "atlas.enableTLS");
        boolean tls = "true".equalsIgnoreCase(tlsRaw == null ? "" : tlsRaw.trim());
        String port = tls ? (blank(httpsPort) ? "21443" : httpsPort.trim())
                          : (blank(httpPort) ? "21000" : httpPort.trim());
        return (tls ? "https://" : "http://") + atlasHosts.get(0) + ":" + port;
    }

    private String atlasAuthMode() throws Exception {
        if (!atlasManaged()) return null;
        if ("true".equalsIgnoreCase(t(cfg("application-properties", "atlas.authentication.method.file")))) return "basic";
        if ("true".equalsIgnoreCase(t(cfg("application-properties", "atlas.authentication.method.ldap")))) return "ldap";
        if ("true".equalsIgnoreCase(t(cfg("application-properties", "atlas.authentication.method.kerberos")))) return "kerberos";
        return "none";
    }

    private String atlasAclMode() throws Exception {
        if (!atlasManaged()) return null;
        String impl = cfg("application-properties", "atlas.authorizer.impl");
        return impl != null && impl.trim().toLowerCase().contains("ranger") ? "ranger" : "simple";
    }

    // ----- Ranger -----

    private String rangerUrl() throws Exception {
        if (rangerResolved == null) {
            rangerHosts = ambari.getComponentHosts(cluster, "RANGER", "RANGER_ADMIN");
            rangerResolved = rangerHosts != null && !rangerHosts.isEmpty();
        }
        if (!rangerResolved) return null;
        String httpsPort = cfg("ranger-admin-site", "ranger.service.https.port");
        String httpPort  = cfg("ranger-admin-site", "ranger.service.http.port");
        String tlsRaw    = cfg("ranger-admin-site", "ranger.service.https.attrib.ssl.enabled");
        boolean tls = "true".equalsIgnoreCase(tlsRaw == null ? "" : tlsRaw.trim());
        String port = tls ? (blank(httpsPort) ? "6182" : httpsPort.trim())
                          : (blank(httpPort) ? "6080" : httpPort.trim());
        return (tls ? "https://" : "http://") + rangerHosts.get(0) + ":" + port;
    }

    // ----- Hive -----

    private String hs2JdbcUrl() throws Exception {
        if (hs2Resolved == null) {
            hs2 = ambari.getComponentHosts(cluster, "HIVE", "HIVE_SERVER");
            hs2Resolved = hs2 != null && !hs2.isEmpty();
        }
        if (!hs2Resolved) return null;
        String port = cfg("hive-site", "hive.server2.thrift.port");
        return "jdbc:hive2://" + hs2.get(0) + ":" + (blank(port) ? "10000" : port.trim()) + "/";
    }

    private boolean hs2Available() throws Exception {
        if (hs2Resolved == null) {
            hs2 = ambari.getComponentHosts(cluster, "HIVE", "HIVE_SERVER");
            hs2Resolved = hs2 != null && !hs2.isEmpty();
        }
        return hs2Resolved;
    }

    /** true when HiveServer2 uses the HTTP transport (hive.server2.transport.mode=http). */
    private boolean hiveHttpTransport() {
        String m = cfg("hive-site", "hive.server2.transport.mode");
        return m != null && m.trim().equalsIgnoreCase("http");
    }

    /**
     * HiveServer2 host:port the OM connector should hit. For the HTTP transport this is the
     * dedicated HTTP port (hive.server2.thrift.http.port, default 10001) — NOT the binary
     * thrift port — which is the bug that made the connector dial :10000 with a hive+https
     * scheme. Falls back to the binary port (thrift.port, default 10000) for the binary transport.
     */
    private String hs2HostPort() throws Exception {
        if (!hs2Available()) return null;
        String port;
        if (hiveHttpTransport()) {
            port = cfg("hive-site", "hive.server2.thrift.http.port");
            if (blank(port)) port = "10001";
        } else {
            port = cfg("hive-site", "hive.server2.thrift.port");
            if (blank(port)) port = "10000";
        }
        return hs2.get(0) + ":" + port.trim();
    }

    /**
     * OM Hive connector scheme, computed from HS2 transport + TLS: hive+https (HTTP transport
     * with use.SSL=true), hive+http (HTTP transport, no TLS), or hive (binary transport). This is
     * what "KDPS delivers the scheme, not the operator" means — derived from hive-site, not typed.
     */
    private String hiveScheme() throws Exception {
        if (!hs2Available()) return null;
        if (hiveHttpTransport()) {
            return "true".equalsIgnoreCase(t(cfg("hive-site", "hive.server2.use.SSL"))) ? "hive+https" : "hive+http";
        }
        return "hive";
    }

    /**
     * HS2 transport mode as a plain token: "http" or "binary". Read straight from
     * hive.server2.transport.mode; "all" and any non-http value resolve to "binary" (the
     * binary listener is always present in those modes, and binary → hive:// is the clearer
     * SQLAlchemy driver for Superset). Superset uses this to pick hive:// vs impala://.
     */
    private String hiveTransportMode() throws Exception {
        if (!hs2Available()) return null;
        return hiveHttpTransport() ? "http" : "binary";
    }

    /** OM Hive auth mode from hive.server2.authentication (KERBEROS/LDAP/NONE/NOSASL). */
    private String hiveAuthMode() throws Exception {
        if (!hs2Available()) return null;
        String a = cfg("hive-site", "hive.server2.authentication");
        if (a == null) return "none";
        switch (a.trim().toUpperCase()) {
            case "KERBEROS": return "kerberos";
            case "LDAP":     return "ldap";
            default:         return "none";
        }
    }

    // ----- OIDC (best-effort) -----

    private String oidcIssuer() {
        // KDPS clusters carry the OIDC issuer in oidc-env/oidc_issuer_url — the same source the
        // service dynamicValues (AMBARI_OIDC_ISSUER_URL) read, so the context stays consistent with
        // what services actually deploy with.
        String explicit = cfg("oidc-env", "oidc_issuer_url");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        // Many clusters set only oidc_admin_url + oidc_realm; compose the issuer exactly as the
        // service dynamicValues fallback does (CommandService.resolveOidcIssuerUrl), so the context
        // matches what services deploy with.
        String adminUrl = cfg("oidc-env", "oidc_admin_url");
        String realm    = cfg("oidc-env", "oidc_realm");
        if (adminUrl != null && !adminUrl.isBlank() && realm != null && !realm.isBlank()) {
            return adminUrl.replaceAll("/$", "") + "/realms/" + realm;
        }
        // Fall back to Ambari SSO config for non-KDPS clusters.
        return cfg("sso-configuration", "ambari.sso.provider.originalUrl");
    }

    private String oidcRealm() {
        return cfg("oidc-env", "oidc_realm");
    }

    // ----- helpers -----

    private String cfg(String type, String key) {
        try {
            return ambari.getDesiredConfigProperty(cluster, type, key);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String t(String s) { return s == null ? "" : s.trim(); }
}
