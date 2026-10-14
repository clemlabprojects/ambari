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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.KerberosHelper;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.orm.dao.KerberosPrincipalDAO;
import org.apache.ambari.server.orm.entities.KerberosPrincipalEntity;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.PropertyInfo.PropertyType;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Decides which principals Ambari may issue an ad-hoc keytab for.
 * <p/>
 * Ad-hoc keytabs are meant for workloads Ambari does not manage itself (the KDPS releases on
 * Kubernetes). Each keytab lands in a Secret of one Kubernetes namespace, and the principals of a
 * release are named after that namespace. Issuing a keytab extracts or rotates the principal's
 * key, so handing one out for any other identity would either give the caller that identity or
 * break whoever uses it. A principal is therefore only accepted when it is named after the
 * namespace that receives the keytab:
 * <ul>
 *   <li>{@code <service>-<namespace>@REALM}, or the shortened form KDPS gives such a name when it
 *       exceeds FreeIPA's 32-character limit, where the service is listed in
 *       {@link Configuration#KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES}. Service and namespace
 *       names may both contain dashes, so only a known service tells where the namespace starts:
 *       {@code trino-team-analytics} is the Trino principal of namespace {@code team-analytics} and
 *       must not pass as a {@code trino-team} principal of namespace {@code analytics};</li>
 *   <li>{@code <service>/<name>.<namespace>.<suffix>@REALM} or
 *       {@code <service>/<service>-<namespace>.<suffix>@REALM}, where the suffix is one of the
 *       Kubernetes DNS suffixes of {@link Configuration#KERBEROS_ADHOC_KEYTAB_DNS_SUFFIXES} and the
 *       service is listed in {@link Configuration#KERBEROS_ADHOC_KEYTAB_SERVICE_NAMES} or is itself a
 *       {@code <service>-<namespace>} name as above. The host alone is not enough: the cluster's
 *       {@code auth_to_local} rules end with {@code DEFAULT}, which treats {@code alice/<host>@REALM}
 *       as the user {@code alice} whatever the host;</li>
 * </ul>
 * or when an Ambari administrator allowed it explicitly in
 * {@link Configuration#KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS}.
 * <p/>
 * Even then, a principal is refused when:
 * <ul>
 *   <li>it is not a plain {@code name@REALM} or {@code name/host@REALM} principal;</li>
 *   <li>its realm is not the cluster's Kerberos realm;</li>
 *   <li>it belongs to the Kerberos or directory infrastructure ({@code krbtgt}, {@code kadmin},
 *       {@code K/M}, {@code admin}, any {@code name/admin}, {@code host/...}, {@code HTTP/...},
 *       {@code ldap/...}, the KDC administrator Ambari uses, ...);</li>
 *   <li>it is one of the principals Ambari created for the cluster, or shares its name with one
 *       of them ({@code nn/any-host} maps to the same local account as the NameNode principal);</li>
 *   <li>its name is a service account of the cluster ({@code hdfs@REALM} maps to the
 *       {@code hdfs} superuser).</li>
 * </ul>
 */
@Singleton
public class AdhocKeytabPrincipalPolicy {

  private static final Logger LOG = LoggerFactory.getLogger(AdhocKeytabPrincipalPolicy.class);

  /**
   * {@code name[/instance]@REALM}, restricted to the characters KDC tooling accepts without quoting
   * so that the principal can never alter the commands Ambari runs against the KDC.
   */
  private static final Pattern PRINCIPAL_PATTERN = Pattern.compile(
      "^([A-Za-z0-9][A-Za-z0-9._-]*)(?:/([A-Za-z0-9][A-Za-z0-9.-]*))?@([A-Za-z0-9][A-Za-z0-9.-]*)$");

  /**
   * Primaries that belong to the KDC, its administrators or the directory and web services of an
   * identity server such as FreeIPA, compared in lower case. Rotating one of their keys would break
   * that infrastructure; extracting it would hand its identity out.
   */
  private static final Set<String> RESERVED_PRIMARIES = new HashSet<>(Arrays.asList(
      "krbtgt", "kadmin", "kiprop", "changepw", "admin", "root",
      "host", "http", "ldap", "dns", "cifs", "dogtag"));

  /**
   * Kubernetes namespace names (RFC 1123 labels).
   */
  private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

  /**
   * FreeIPA caps the name of a user principal at 32 characters; KDPS shortens longer headless
   * principals to a prefix of the name, a dash and up to six hex digits of the name's hash.
   */
  private static final int MAX_IPA_PRINCIPAL_NAME = 32;

  private final KerberosPrincipalDAO kerberosPrincipalDAO;
  private final KerberosHelper kerberosHelper;
  private final ConfigHelper configHelper;
  private final Configuration configuration;

  @Inject
  public AdhocKeytabPrincipalPolicy(KerberosPrincipalDAO kerberosPrincipalDAO,
                                    KerberosHelper kerberosHelper,
                                    ConfigHelper configHelper,
                                    Configuration configuration) {
    this.kerberosPrincipalDAO = kerberosPrincipalDAO;
    this.kerberosHelper = kerberosHelper;
    this.configHelper = configHelper;
    this.configuration = configuration;
  }

  /**
   * Verifies that an ad-hoc keytab may be issued for the principal on the cluster.
   *
   * @param cluster   the cluster whose KDC would issue the keytab
   * @param principal the requested principal
   * @param namespace the Kubernetes namespace that receives the keytab
   * @throws IllegalArgumentException if the principal is not a well-formed {@code name[/host]@REALM}
   *                                  or the namespace is missing or not a Kubernetes namespace name
   * @throws AuthorizationException   if the principal is one Ambari must not issue a keytab for
   * @throws SystemException          if the cluster's own identities could not be determined
   */
  public void verify(Cluster cluster, String principal, String namespace) throws SystemException {
    Matcher matcher = (principal == null) ? null : PRINCIPAL_PATTERN.matcher(principal.trim());
    if (matcher == null || !matcher.matches()) {
      throw new IllegalArgumentException(String.format(
          "'%s' is not a valid principal for an ad-hoc keytab; expected name@REALM or name/host@REALM.", principal));
    }
    if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace.trim()).matches()) {
      throw new IllegalArgumentException(String.format(
          "'%s' is not a valid Kubernetes namespace; an ad-hoc keytab request names the namespace that receives the keytab.",
          namespace));
    }

    String requested = principal.trim();
    String ns = namespace.trim();
    String primary = matcher.group(1).toLowerCase(Locale.ROOT);
    String instance = (matcher.group(2) == null) ? null : matcher.group(2).toLowerCase(Locale.ROOT);
    String realm = matcher.group(3);
    String clusterName = cluster.getClusterName();

    String clusterRealm = clusterRealm(cluster);
    if (StringUtils.isNotBlank(clusterRealm) && !clusterRealm.equalsIgnoreCase(realm)) {
      throw refused(requested, String.format("its realm '%s' is not the Kerberos realm '%s' of cluster '%s'",
          realm, clusterRealm, clusterName));
    }

    if (RESERVED_PRIMARIES.contains(primary) || "admin".equals(instance) || ("k".equals(primary) && "m".equals(instance))) {
      throw refused(requested, "it belongs to the Kerberos or directory infrastructure");
    }

    if (isKdcAdministrator(clusterName, requested, clusterRealm)) {
      throw refused(requested, "it is the KDC administrator account Ambari uses");
    }

    // Comparisons ignore case: Active Directory resolves principals case-insensitively, so a
    // different spelling of a cluster principal could still reach the same account.
    for (KerberosPrincipalEntity entity : kerberosPrincipalDAO.findAll()) {
      String managed = entity.getPrincipalName();
      if (managed == null) {
        continue;
      }
      if (managed.equalsIgnoreCase(requested)) {
        throw refused(requested, String.format("it is a Kerberos principal of cluster '%s'", clusterName));
      }
      if (primaryOf(managed).equals(primary)) {
        throw refused(requested, String.format(
            "its name '%s' is used by the Kerberos principals of cluster '%s', which would treat it as the same account",
            primary, clusterName));
      }
    }

    if (serviceAccounts(cluster).contains(primary)) {
      throw refused(requested, String.format(
          "its name '%s' is a service account of cluster '%s', which would treat it as that account",
          primary, clusterName));
    }

    if (isExplicitlyAllowed(requested)) {
      return;
    }

    if (!isNamedAfterNamespace(primary, instance, ns)) {
      throw refused(requested, String.format(
          "it is not named after the namespace '%s' that receives the keytab (expected <service>-%s@%s with a "
              + "service listed in %s, or <service>/<name>.%s.<suffix>@%s with a suffix listed in %s). An Ambari "
              + "administrator can allow it with %s in ambari.properties",
          ns, ns, realm, Configuration.KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES.getKey(), ns, realm,
          Configuration.KERBEROS_ADHOC_KEYTAB_DNS_SUFFIXES.getKey(),
          Configuration.KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS.getKey()));
    }

    // The cluster treats name/host@REALM as the user "name", so a namespace-named host would still
    // hand out any user's identity. The name must be a known workload service, or be scoped to the
    // namespace the same way a headless principal is.
    if (instance != null && !configuration.getKerberosAdhocKeytabServiceNames().contains(primary)
        && !isReleaseName(primary, ns)) {
      throw refused(requested, String.format(
          "the cluster would treat it as the user '%s', which is neither a service listed in %s nor <service>-%s "
              + "with a service listed in %s. An Ambari administrator can allow it with %s in ambari.properties",
          primary, Configuration.KERBEROS_ADHOC_KEYTAB_SERVICE_NAMES.getKey(), ns,
          Configuration.KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES.getKey(),
          Configuration.KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS.getKey()));
    }
  }

  /**
   * Tells whether a principal follows one of the shapes KDPS gives the principals of a release in
   * the namespace. The namespace is matched exactly: as the label right before the DNS suffix of
   * the host, or as the part of a name that follows a known service, so that a namespace named like
   * a service ({@code trino}) or ending like another one ({@code analytics} and
   * {@code team-analytics}) does not match the principals of other namespaces.
   *
   * @param primary   the lower-case part of the principal before any {@code /}
   * @param instance  the lower-case host part, or null for a headless principal
   * @param namespace the namespace receiving the keytab
   */
  private boolean isNamedAfterNamespace(String primary, String instance, String namespace) {
    if (instance == null) {
      return isReleaseName(primary, namespace);
    }

    for (String suffix : configuration.getKerberosAdhocKeytabDnsSuffixes()) {
      if (!instance.endsWith("." + suffix)) {
        continue;
      }
      String[] labels = instance.substring(0, instance.length() - suffix.length() - 1).split("\\.", -1);
      String last = labels[labels.length - 1];
      // A single label is the synthetic host <service>-<namespace>, built from the principal's own
      // service: matching only its end would let jupyterhub-team-analytics pass for namespace analytics.
      if ((labels.length > 1 && last.equals(namespace))
          || (labels.length == 1 && last.equals(primary + "-" + namespace))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tells whether a lower-case name is {@code <service>-<namespace>} for a service listed in
   * {@link Configuration#KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES}, or the shortened form the
   * KDPS view gives that name when it exceeds FreeIPA's limit.
   * <p/>
   * When one listed service starts with another one and a dash ({@code trino} and
   * {@code trino-worker}), {@code trino-worker-analytics} could be either service's principal of a
   * different namespace. The longer service decides, so the name belongs to one namespace only.
   * A cluster name appended after the namespace is not accepted for the same reason:
   * {@code trino-analytics-c1} is also the Trino principal of namespace {@code analytics-c1}.
   */
  private boolean isReleaseName(String name, String namespace) {
    Set<String> services = configuration.getKerberosAdhocKeytabHeadlessServiceNames();
    for (String service : services) {
      String fullName = service + "-" + namespace;
      if (!service.equals(owningService(fullName, services))) {
        continue;
      }
      if (name.equals(fullName)
          || (fullName.length() > MAX_IPA_PRINCIPAL_NAME && name.equals(shortenedName(fullName)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The longest of the services that {@code name} starts with, followed by a dash.
   */
  private static String owningService(String name, Set<String> services) {
    String owner = null;
    for (String service : services) {
      if (name.startsWith(service + "-") && (owner == null || service.length() > owner.length())) {
        owner = service;
      }
    }
    return owner;
  }

  /**
   * The name the KDPS view issues for a headless principal name longer than FreeIPA's limit.
   */
  private static String shortenedName(String name) {
    String hash = Integer.toHexString(name.hashCode() & 0x7fffffff);
    if (hash.length() > 6) {
      hash = hash.substring(0, 6);
    }
    String prefix = name.substring(0, MAX_IPA_PRINCIPAL_NAME - hash.length() - 1).replaceAll("-+$", "");
    return prefix + "-" + hash;
  }

  /**
   * Tells whether an Ambari administrator listed the principal in
   * {@link Configuration#KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS}, where {@code *} matches any
   * sequence of characters.
   */
  private boolean isExplicitlyAllowed(String principal) {
    String candidate = principal.toLowerCase(Locale.ROOT);
    for (String entry : configuration.getKerberosAdhocKeytabAllowedPrincipals()) {
      StringBuilder regex = new StringBuilder();
      for (String part : entry.split("\\*", -1)) {
        if (regex.length() > 0) {
          regex.append(".*");
        }
        regex.append(Pattern.quote(part));
      }
      if (Pattern.matches(regex.toString(), candidate)) {
        return true;
      }
    }
    return false;
  }

  private static AuthorizationException refused(String principal, String reason) {
    return new AuthorizationException(String.format(
        "Ambari does not issue an ad-hoc keytab for '%s': %s.", principal, reason));
  }

  /**
   * The lower-case first component of a principal: {@code nn/host@REALM} gives {@code nn}.
   */
  private static String primaryOf(String principal) {
    String value = principal;
    int at = value.indexOf('@');
    if (at >= 0) {
      value = value.substring(0, at);
    }
    int slash = value.indexOf('/');
    if (slash >= 0) {
      value = value.substring(0, slash);
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String clusterRealm(Cluster cluster) {
    Config kerberosEnv = cluster.getDesiredConfigByType(KerberosHelper.KERBEROS_ENV);
    Map<String, String> properties = (kerberosEnv == null) ? null : kerberosEnv.getProperties();
    return (properties == null) ? null : properties.get(KerberosHelper.DEFAULT_REALM);
  }

  private boolean isKdcAdministrator(String clusterName, String requested, String clusterRealm) {
    PrincipalKeyCredential credential;
    try {
      credential = kerberosHelper.getKDCAdministratorCredentials(clusterName);
    } catch (AmbariException e) {
      // Without stored credentials the keytab cannot be issued anyway; the other checks still apply.
      LOG.debug("KDC administrator credentials of cluster {} are not readable: {}", clusterName, e.getMessage());
      return false;
    }
    String administrator = (credential == null) ? null : credential.getPrincipal();
    if (StringUtils.isBlank(administrator)) {
      return false;
    }
    if (!administrator.contains("@") && StringUtils.isNotBlank(clusterRealm)) {
      administrator = administrator + "@" + clusterRealm;
    }
    return administrator.equalsIgnoreCase(requested);
  }

  /**
   * The local service accounts of the cluster (hdfs, yarn, hive, the smoke test user, ...), in
   * lower case. They are read from the same user-typed properties Ambari uses to create the
   * accounts on the hosts.
   */
  private Set<String> serviceAccounts(Cluster cluster) throws SystemException {
    Set<String> accounts = new HashSet<>();
    try {
      Set<String> users = configHelper.getPropertyValuesWithPropertyType(
          cluster.getDesiredStackVersion(), PropertyType.USER, cluster, cluster.getDesiredConfigs());
      if (users != null) {
        for (String user : users) {
          if (StringUtils.isNotBlank(user)) {
            accounts.add(user.trim().toLowerCase(Locale.ROOT));
          }
        }
      }
    } catch (AmbariException e) {
      throw new SystemException("Unable to read the service accounts of cluster '" + cluster.getClusterName()
          + "' to check the requested principal: " + e.getMessage(), e);
    }
    return accounts;
  }
}
