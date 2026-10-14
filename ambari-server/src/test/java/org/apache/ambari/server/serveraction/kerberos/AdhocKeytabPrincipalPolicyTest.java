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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;

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
import org.apache.ambari.server.state.StackId;
import org.junit.Before;
import org.junit.Test;

/**
 * Ad-hoc keytabs are only issued for the principals KDPS gives the releases of the Kubernetes
 * namespace that receives the keytab, or for principals an Ambari administrator allowed explicitly,
 * and never for an identity the cluster itself relies on.
 */
public class AdhocKeytabPrincipalPolicyTest {

  private static final String REALM = "EXAMPLE.COM";
  private static final String NAMESPACE = "analytics";

  private KerberosPrincipalDAO kerberosPrincipalDAO;
  private KerberosHelper kerberosHelper;
  private ConfigHelper configHelper;
  private Cluster cluster;
  private Properties ambariProperties;

  @Before
  public void setUp() throws Exception {
    ambariProperties = new Properties();

    kerberosPrincipalDAO = createNiceMock(KerberosPrincipalDAO.class);
    expect(kerberosPrincipalDAO.findAll()).andReturn(Arrays.asList(
        new KerberosPrincipalEntity("hdfs-c1@" + REALM, false, null),
        new KerberosPrincipalEntity("nn/master01.example.com@" + REALM, true, null),
        new KerberosPrincipalEntity("HTTP/master01.example.com@" + REALM, true, null),
        new KerberosPrincipalEntity("ambari-server-c1@" + REALM, false, null))).anyTimes();

    kerberosHelper = createNiceMock(KerberosHelper.class);
    expect(kerberosHelper.getKDCAdministratorCredentials("c1"))
        .andReturn(new PrincipalKeyCredential("ops-kdc-admin", "secret")).anyTimes();

    Config kerberosEnv = createNiceMock(Config.class);
    expect(kerberosEnv.getProperties()).andReturn(Collections.singletonMap("realm", REALM)).anyTimes();

    cluster = createNiceMock(Cluster.class);
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnv).anyTimes();
    expect(cluster.getDesiredStackVersion()).andReturn(new StackId("ODP", "1.3")).anyTimes();
    expect(cluster.getDesiredConfigs()).andReturn(Collections.emptyMap()).anyTimes();

    configHelper = createNiceMock(ConfigHelper.class);
    expect(configHelper.getPropertyValuesWithPropertyType(anyObject(StackId.class), eq(PropertyType.USER),
        eq(cluster), anyObject(Map.class)))
        .andReturn(new HashSet<>(Arrays.asList("hdfs", "yarn", "hive", "ambari-qa"))).anyTimes();

    replay(kerberosPrincipalDAO, kerberosHelper, kerberosEnv, cluster, configHelper);
  }

  @Test
  public void testAcceptsKdpsReleasePrincipals() throws Exception {
    // headless <service>-<namespace> for the services KDPS deploys
    assertAccepted("trino-analytics@" + REALM, NAMESPACE);
    assertAccepted("superset-dashboard-analytics@" + REALM, NAMESPACE);
    assertAccepted("superset-dashboard-team-a@" + REALM, "team-a");
    assertAccepted("oma-ing-analytics@" + REALM, NAMESPACE);
    // service principals on <service>.<namespace>.<suffix> or <pod>[.<subdomain>].<namespace>.<suffix>
    assertAccepted("jupyterhub/jupyterhub.analytics.k8s@" + REALM, NAMESPACE);
    assertAccepted("jupyterhub/jupyterhub.analytics.svc@" + REALM, NAMESPACE);
    assertAccepted("jupyterhub/jupyterhub.analytics.svc.cluster.local@" + REALM, NAMESPACE);
    assertAccepted("jupyterhub/hub-0.hub.analytics.svc.cluster.local@" + REALM, NAMESPACE);
    // the keytab webhook's synthetic host <service>-<namespace>.<suffix>
    assertAccepted("jupyterhub/jupyterhub-analytics.k8s@" + REALM, NAMESPACE);
    // a service name scoped to the namespace like a headless principal
    assertAccepted("trino-analytics/trino.analytics.k8s@" + REALM, NAMESPACE);
    // case does not matter
    assertAccepted("Trino-Analytics@" + REALM, NAMESPACE);
    assertAccepted("JupyterHub/JupyterHub.Analytics.k8s@" + REALM, NAMESPACE);
  }

  @Test
  public void testAcceptsNamesShortenedForFreeIpa() throws Exception {
    // What the KDPS view issues for superset-dashboard-very-long-namespace and
    // oma-ing-data-engineering-sandbox-eu, whose names exceed FreeIPA's 32 characters (the second
    // one is cut right after a dash, which the view drops).
    assertAccepted("superset-dashboard-very-l-744183@" + REALM, "very-long-namespace");
    assertAccepted("oma-ing-data-engineering-330c6a@" + REALM, "data-engineering-sandbox-eu");
    // a service that is not listed is not accepted in its shortened form either
    assertRefused("trino-coordinator-client-195638@" + REALM, "trino-ns", "is not named after the namespace 'trino-ns'");
  }

  @Test
  public void testDashesInServiceAndNamespaceNamesDoNotMixReleases() throws Exception {
    // trino-team-analytics is the Trino principal of namespace team-analytics: a workload of
    // namespace analytics must not obtain it by calling itself trino-team.
    assertRefused("trino-team-analytics@" + REALM, NAMESPACE, "is not named after the namespace 'analytics'");
    assertAccepted("trino-team-analytics@" + REALM, "team-analytics");
    // and the other way round: superset-dashboard-analytics belongs to namespace analytics only
    assertRefused("superset-dashboard-analytics@" + REALM, "dashboard-analytics",
        "is not named after the namespace 'dashboard-analytics'");
    assertAccepted("superset-dashboard-analytics@" + REALM, NAMESPACE);
    assertAccepted("superset-dashboard-very-l-744183@" + REALM, "very-long-namespace");
    // the same identities through a service principal: the cluster would treat them as that user
    assertRefused("trino-team-analytics/trino.analytics.k8s@" + REALM, NAMESPACE,
        "would treat it as the user 'trino-team-analytics'");
    assertRefused("jupyterhub/jupyterhub-team-analytics.k8s@" + REALM, NAMESPACE,
        "is not named after the namespace 'analytics'");
    assertAccepted("jupyterhub/jupyterhub-team-analytics.k8s@" + REALM, "team-analytics");
  }

  @Test
  public void testClusterNameAfterTheNamespaceIsNotAccepted() throws Exception {
    // trino-analytics-c1 is also the Trino principal of namespace analytics-c1.
    assertRefused("trino-analytics-c1@" + REALM, NAMESPACE, "is not named after the namespace 'analytics'");
    assertAccepted("trino-analytics-c1@" + REALM, "analytics-c1");

    // An Ambari administrator who chose such names for the keytab webhook allows them explicitly.
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS.getKey(),
        "trino-analytics-c1@" + REALM);
    assertAccepted("trino-analytics-c1@" + REALM, NAMESPACE);
  }

  @Test
  public void testAmbariAdministratorChoosesTheHeadlessServiceNames() throws Exception {
    assertRefused("airflow-analytics@" + REALM, NAMESPACE, "is not named after the namespace 'analytics'");

    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES.getKey(),
        "trino, Airflow, trino-coordinator-client");
    assertAccepted("airflow-analytics@" + REALM, NAMESPACE);
    assertAccepted("trino-coordinator-client-195638@" + REALM, "trino-ns");
    assertAccepted("airflow-analytics/airflow.analytics.k8s@" + REALM, NAMESPACE);
    // services left out of the list are no longer accepted
    assertRefused("superset-dashboard-analytics@" + REALM, NAMESPACE, "is not named after the namespace 'analytics'");
    // listing a service does not lift the namespace check
    assertRefused("airflow-analytics@" + REALM, "sandbox", "is not named after the namespace 'sandbox'");
  }

  @Test
  public void testLongerListedServiceDecidesTheNamespace() throws Exception {
    // With the keytab webhook's pod labels listed next to the services KDPS deploys, trino and
    // trino-worker both start trino-worker-analytics: it is read as the trino-worker principal of
    // namespace analytics, never as the trino principal of namespace worker-analytics.
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_HEADLESS_SERVICE_NAMES.getKey(),
        "trino,trino-worker");
    assertAccepted("trino-worker-analytics@" + REALM, NAMESPACE);
    assertRefused("trino-worker-analytics@" + REALM, "worker-analytics",
        "is not named after the namespace 'worker-analytics'");
    assertAccepted("trino-analytics@" + REALM, NAMESPACE);
    // the same holds for the shortened form of trino-worker-analytics-with-a-long-name
    assertAccepted("trino-worker-analytics-wi-4da9d0@" + REALM, "analytics-with-a-long-name");
    assertRefused("trino-worker-analytics-wi-4da9d0@" + REALM, "worker-analytics-with-a-long-name",
        "is not named after the namespace 'worker-analytics-with-a-long-name'");
  }

  @Test
  public void testRefusesHumanUsersAndForeignPrincipals() throws Exception {
    String reason = "is not named after the namespace 'analytics'";
    assertRefused("alice@" + REALM, NAMESPACE, reason);
    assertRefused("Administrator@" + REALM, NAMESPACE, reason);
    assertRefused("postgres/db01.example.com@" + REALM, NAMESPACE, reason);
    // Naming a namespace after the foreign host does not help: the host is not a Kubernetes name.
    assertRefused("postgres/db01.example.com@" + REALM, "example", "is not named after the namespace 'example'");
    assertRefused("jupyterhub/jupyterhub.example.com@" + REALM, NAMESPACE, reason);
  }

  @Test
  public void testRefusesPrincipalOfAnotherNamespace() throws Exception {
    String reason = "is not named after the namespace 'sandbox'";
    assertRefused("trino-analytics@" + REALM, "sandbox", reason);
    assertRefused("jupyterhub/jupyterhub.analytics.k8s@" + REALM, "sandbox", reason);
    assertRefused("jupyterhub/jupyterhub-analytics.k8s@" + REALM, "sandbox", reason);
    assertRefused("superset-dashboard-very-l-744183@" + REALM, "sandbox", reason);
  }

  @Test
  public void testNamespaceNamedLikeAServiceDoesNotMatchOtherReleases() throws Exception {
    assertRefused("trino-analytics@" + REALM, "trino", "is not named after the namespace 'trino'");
    assertRefused("jupyterhub/jupyterhub.analytics.k8s@" + REALM, "jupyterhub",
        "is not named after the namespace 'jupyterhub'");
    assertRefused("superset-dashboard-analytics@" + REALM, "dashboard", "is not named after the namespace 'dashboard'");
  }

  @Test
  public void testRefusesPartialAndForgedNames() throws Exception {
    String reason = "is not named after the namespace";
    assertRefused("trino-myanalytics@" + REALM, NAMESPACE, reason);
    assertRefused("analytics@" + REALM, NAMESPACE, reason);
    assertRefused("jupyterhub/analytics.k8s@" + REALM, NAMESPACE, reason);
    assertRefused("trino-analytics-c2@" + REALM, NAMESPACE, reason);
    assertRefused("jupyterhub/jupyterhub.analytics.k8s.example.com@" + REALM, NAMESPACE, reason);
    // shortened-looking, but not the shortened form of a name of the namespace
    assertRefused("superset-dashboard-very-l-000000@" + REALM, "very-long-namespace", reason);
  }

  @Test
  public void testHostMustEndWithAConfiguredKubernetesDnsSuffix() throws Exception {
    assertRefused("jupyterhub/jupyterhub.analytics.cluster.example@" + REALM, NAMESPACE,
        "is not named after the namespace");

    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_DNS_SUFFIXES.getKey(), ".cluster.example");
    assertAccepted("jupyterhub/jupyterhub.analytics.cluster.example@" + REALM, NAMESPACE);
    assertRefused("jupyterhub/jupyterhub.analytics.k8s@" + REALM, NAMESPACE, "is not named after the namespace");
  }

  @Test
  public void testServicePrincipalOfAnyUserIsRefused() throws Exception {
    // The cluster treats name/host@REALM as the user "name": a host named after the namespace must
    // not hand out the identity of a person or of a trusted account such as the Ranger KMS
    // administrator.
    assertRefused("alice/alice.analytics.k8s@" + REALM, NAMESPACE, "would treat it as the user 'alice'");
    assertRefused("keyadmin/x.analytics.k8s@" + REALM, NAMESPACE, "would treat it as the user 'keyadmin'");
    assertRefused("alice/alice-analytics.k8s@" + REALM, NAMESPACE, "would treat it as the user 'alice'");
    // the user trino would be shared with every namespace until an Ambari administrator lists it
    assertRefused("trino/trino.analytics.k8s@" + REALM, NAMESPACE, "would treat it as the user 'trino'");
    // a service name scoped to another namespace
    assertRefused("trino-sandbox/trino.analytics.k8s@" + REALM, NAMESPACE,
        "would treat it as the user 'trino-sandbox'");
    // the JupyterHub service principal KDPS deploys stays accepted by default
    assertAccepted("jupyterhub/jupyterhub.analytics.k8s@" + REALM, NAMESPACE);
  }

  @Test
  public void testAmbariAdministratorChoosesTheServiceNames() throws Exception {
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_SERVICE_NAMES.getKey(), "jupyterhub, Trino, hive");

    assertAccepted("trino/trino.analytics.k8s@" + REALM, NAMESPACE);
    assertAccepted("trino/trino-0.workers.analytics.svc.cluster.local@" + REALM, NAMESPACE);
    assertAccepted("trino/trino-analytics.k8s@" + REALM, NAMESPACE);
    assertRefused("alice/alice.analytics.k8s@" + REALM, NAMESPACE, "would treat it as the user 'alice'");
    // listing a service does not lift the namespace check, nor cover the cluster's service accounts
    assertRefused("trino/trino.sandbox.k8s@" + REALM, NAMESPACE, "is not named after the namespace 'analytics'");
    assertRefused("hive/hive.analytics.k8s@" + REALM, NAMESPACE, "service account of cluster 'c1'");

    // an empty list leaves only the service names scoped to the namespace
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_SERVICE_NAMES.getKey(), "");
    assertRefused("jupyterhub/jupyterhub.analytics.k8s@" + REALM, NAMESPACE,
        "would treat it as the user 'jupyterhub'");
    assertAccepted("jupyterhub-analytics/jupyterhub.analytics.k8s@" + REALM, NAMESPACE);
  }

  @Test
  public void testAmbariAdministratorCanAllowOtherPrincipals() throws Exception {
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS.getKey(),
        "jupyterhub/jupyterhub.example.com@EXAMPLE.COM, spark/*.batch.example.com@" + REALM);

    assertAccepted("jupyterhub/jupyterhub.example.com@" + REALM, NAMESPACE);
    assertAccepted("spark/node1.batch.example.com@" + REALM, NAMESPACE);
    assertRefused("spark/node1.example.com@" + REALM, NAMESPACE, "is not named after the namespace");
    assertRefused("jupyterhub/other.example.com@" + REALM, NAMESPACE, "is not named after the namespace");
  }

  @Test
  public void testExplicitAllowanceDoesNotCoverClusterIdentities() throws Exception {
    ambariProperties.setProperty(Configuration.KERBEROS_ADHOC_KEYTAB_ALLOWED_PRINCIPALS.getKey(), "*");

    assertAccepted("alice@" + REALM, NAMESPACE);
    assertAccepted("alice/alice.analytics.k8s@" + REALM, NAMESPACE);
    assertRefused("hdfs-c1@" + REALM, NAMESPACE, "Kerberos principal of cluster 'c1'");
    assertRefused("hdfs@" + REALM, NAMESPACE, "service account of cluster 'c1'");
    assertRefused("ops-kdc-admin@" + REALM, NAMESPACE, "KDC administrator account");
    assertRefused("krbtgt/" + REALM + "@" + REALM, NAMESPACE, "Kerberos or directory infrastructure");
  }

  @Test
  public void testRejectsMissingOrInvalidNamespace() throws Exception {
    for (String namespace : Arrays.asList(null, "", " ", "Analytics", "team_a", "-analytics", "analytics-", "a.b")) {
      try {
        policy().verify(cluster, "trino-analytics@" + REALM, namespace);
        fail("Expected namespace '" + namespace + "' to be rejected");
      } catch (IllegalArgumentException expected) {
        // rejected before any lookup
      }
    }
  }

  @Test
  public void testRefusesClusterPrincipal() throws Exception {
    assertRefused("hdfs-c1@" + REALM, "Kerberos principal of cluster 'c1'");
    assertRefused("ambari-server-c1@" + REALM, "Kerberos principal of cluster 'c1'");
  }

  @Test
  public void testRefusesClusterPrincipalSpelledDifferently() throws Exception {
    assertRefused("HDFS-C1@example.com", "Kerberos principal of cluster 'c1'");
  }

  @Test
  public void testRefusesNameSharedWithClusterServicePrincipals() throws Exception {
    // nn/<any host> maps to the HDFS superuser exactly like the NameNode's own principal.
    assertRefused("nn/evil.analytics.k8s@" + REALM, "used by the Kerberos principals of cluster 'c1'");
  }

  @Test
  public void testRefusesServiceAccountNames() throws Exception {
    assertRefused("hdfs@" + REALM, "service account of cluster 'c1'");
    assertRefused("ambari-qa@" + REALM, "service account of cluster 'c1'");
    assertRefused("hive/hive.analytics.k8s@" + REALM, "service account of cluster 'c1'");
  }

  @Test
  public void testRefusesKerberosAndDirectoryInfrastructurePrincipals() throws Exception {
    assertRefused("krbtgt/" + REALM + "@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("kadmin/admin@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("K/M@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("admin@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("trino/admin@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("host/master01.example.com@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("HTTP/ipa.example.com@" + REALM, "Kerberos or directory infrastructure");
    assertRefused("ldap/ipa.example.com@" + REALM, "Kerberos or directory infrastructure");
  }

  @Test
  public void testRefusesKdcAdministratorAccount() throws Exception {
    assertRefused("ops-kdc-admin@" + REALM, "KDC administrator account");
  }

  @Test
  public void testRefusesForeignRealm() throws Exception {
    assertRefused("trino-analytics@OTHER.COM", "is not the Kerberos realm 'EXAMPLE.COM'");
  }

  @Test
  public void testRejectsMalformedPrincipals() throws Exception {
    for (String principal : Arrays.asList(null, "", "trino-analytics", "trino analytics@" + REALM,
        "a/b/c@" + REALM, "trino@" + REALM + "\" -randkey", "-trino@" + REALM, "trino/@" + REALM)) {
      try {
        policy().verify(cluster, principal, NAMESPACE);
        fail("Expected '" + principal + "' to be rejected as malformed");
      } catch (IllegalArgumentException expected) {
        // rejected before any lookup
      }
    }
  }

  @Test(expected = SystemException.class)
  public void testFailsClosedWhenServiceAccountsCannotBeRead() throws Exception {
    ConfigHelper failingConfigHelper = createNiceMock(ConfigHelper.class);
    expect(failingConfigHelper.getPropertyValuesWithPropertyType(anyObject(StackId.class), eq(PropertyType.USER),
        eq(cluster), anyObject(Map.class))).andThrow(new AmbariException("stack not found")).anyTimes();
    replay(failingConfigHelper);

    policy(kerberosHelper, failingConfigHelper).verify(cluster, "trino-analytics@" + REALM, NAMESPACE);
  }

  @Test
  public void testUnreadableKdcCredentialsDoNotBlockOtherChecks() throws Exception {
    KerberosHelper noCredentials = createNiceMock(KerberosHelper.class);
    expect(noCredentials.getKDCAdministratorCredentials(anyString()))
        .andThrow(new AmbariException("credential store unavailable")).anyTimes();
    replay(noCredentials);

    AdhocKeytabPrincipalPolicy policy = policy(noCredentials, configHelper);
    policy.verify(cluster, "trino-analytics@" + REALM, NAMESPACE);
    try {
      policy.verify(cluster, "hdfs-c1@" + REALM, NAMESPACE);
      fail("Expected the cluster principal to be refused");
    } catch (AuthorizationException expected) {
      // still refused
    }
  }

  private AdhocKeytabPrincipalPolicy policy() {
    return policy(kerberosHelper, configHelper);
  }

  private AdhocKeytabPrincipalPolicy policy(KerberosHelper kerberosHelper, ConfigHelper configHelper) {
    return new AdhocKeytabPrincipalPolicy(kerberosPrincipalDAO, kerberosHelper, configHelper,
        new Configuration(ambariProperties));
  }

  private void assertAccepted(String principal, String namespace) throws Exception {
    policy().verify(cluster, principal, namespace);
  }

  private void assertRefused(String principal, String reason) throws Exception {
    assertRefused(principal, NAMESPACE, reason);
  }

  private void assertRefused(String principal, String namespace, String reason) throws Exception {
    try {
      policy().verify(cluster, principal, namespace);
      fail("Expected '" + principal + "' to be refused for namespace '" + namespace + "'");
    } catch (AuthorizationException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(reason));
    }
  }
}
