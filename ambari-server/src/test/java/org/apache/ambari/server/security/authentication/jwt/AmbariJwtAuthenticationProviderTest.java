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
package org.apache.ambari.server.security.authentication.jwt;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.entities.GroupEntity;
import org.apache.ambari.server.orm.entities.MemberEntity;
import org.apache.ambari.server.orm.entities.PrincipalEntity;
import org.apache.ambari.server.orm.entities.UserAuthenticationEntity;
import org.apache.ambari.server.orm.entities.UserEntity;
import org.apache.ambari.server.security.authentication.AmbariAuthenticationException;
import org.apache.ambari.server.security.authentication.UserNotFoundException;
import org.apache.ambari.server.security.authorization.GroupType;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.UserAuthenticationType;
import org.apache.ambari.server.security.authorization.Users;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.Authentication;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Tests for {@link AmbariJwtAuthenticationProvider}, focused on the JIT (Just-In-Time)
 * user / group provisioning paths added by AMBARI-419.
 *
 * <p>Three properties drive the behavior under test:
 * <ul>
 *   <li>{@code ambari.sso.oidc.user.auto.create} — whether to create missing users from JWTs</li>
 *   <li>{@code ambari.sso.oidc.user.case.conversion} — none / lower / upper applied to displayName</li>
 *   <li>{@code ambari.sso.oidc.groups.claim} — JWT claim carrying group memberships (e.g. "groups")</li>
 *   <li>{@code ambari.sso.oidc.groups.auto.create} — whether to create missing Ambari groups</li>
 *   <li>{@code ambari.sso.oidc.groups.sync.on.login} — refresh memberships on every login vs. only at create</li>
 * </ul>
 *
 * <p>The provider re-parses the JWT (trusted — already signature-validated by the filter) to
 * read claims.  We build test JWTs with a static HMAC secret; signatures are not checked here.
 */
public class AmbariJwtAuthenticationProviderTest {

  /** 32+ byte secret required by MACSigner for HS256.  Value is irrelevant — sig isn't checked. */
  private static final byte[] HMAC_SECRET = "test-secret-test-secret-test-secret-test-secret".getBytes();

  private Users users;
  private Configuration configuration;
  private JwtAuthenticationPropertiesProvider propertiesProvider;
  private AmbariJwtAuthenticationProvider provider;

  @Before
  public void setUp() {
    users = createNiceMock(Users.class);
    configuration = createNiceMock(Configuration.class);
    propertiesProvider = createNiceMock(JwtAuthenticationPropertiesProvider.class);
  }

  private JwtAuthenticationProperties jitProperties(boolean autoCreate, String caseMode,
                                                    String groupsClaim, boolean groupsAutoCreate,
                                                    boolean groupsSyncOnLogin) {
    return jitProperties(autoCreate, caseMode, "", groupsClaim, groupsAutoCreate, groupsSyncOnLogin);
  }

  private JwtAuthenticationProperties jitProperties(boolean autoCreate, String caseMode,
                                                    String defaultRole, String groupsClaim,
                                                    boolean groupsAutoCreate, boolean groupsSyncOnLogin) {
    Map<String, String> raw = new HashMap<>();
    raw.put("ambari.sso.oidc.user.auto.create", Boolean.toString(autoCreate));
    raw.put("ambari.sso.oidc.user.case.conversion", caseMode == null ? "none" : caseMode);
    raw.put("ambari.sso.oidc.user.default.role", defaultRole == null ? "" : defaultRole);
    raw.put("ambari.sso.oidc.groups.claim", groupsClaim == null ? "" : groupsClaim);
    raw.put("ambari.sso.oidc.groups.auto.create", Boolean.toString(groupsAutoCreate));
    raw.put("ambari.sso.oidc.groups.sync.on.login", Boolean.toString(groupsSyncOnLogin));
    return new JwtAuthenticationProperties(raw);
  }

  private String buildJwt(String subject, Object groupsClaim) throws Exception {
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
        .subject(subject)
        .claim("preferred_username", subject);
    if (groupsClaim != null) {
      builder.claim("groups", groupsClaim);
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
    jwt.sign(new MACSigner(HMAC_SECRET));
    return jwt.serialize();
  }

  private UserEntity userWithJwtAuth(String userName) {
    UserAuthenticationEntity jwtAuth = new UserAuthenticationEntity();
    jwtAuth.setAuthenticationType(UserAuthenticationType.JWT);
    jwtAuth.setAuthenticationKey(userName);

    UserEntity user = new UserEntity();
    user.setUserId(42);
    user.setUserName(userName);
    user.setActive(true);
    user.setPrincipal(new PrincipalEntity());
    user.setAuthenticationEntities(new ArrayList<>(Collections.singletonList(jwtAuth)));
    user.setMemberEntities(new HashSet<MemberEntity>());
    return user;
  }

  private Authentication tokenFor(String userName, String jwt) {
    return new JwtAuthenticationToken(userName, jwt, Collections.emptyList());
  }

  // ============================================================================================
  // Baseline (legacy) behavior — provider rejects unknown users when JIT is disabled
  // ============================================================================================

  @Test
  public void testUserExists_succeeds() throws Exception {
    UserEntity userEntity = userWithJwtAuth("hadoopadmin");
    expect(users.getUserEntity("hadoopadmin")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(/*autoCreate=*/false, "none", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("hadoopadmin", buildJwt("hadoopadmin", null)));

    assertNotNull(result);
    assertTrue(result.isAuthenticated());
    verify(users, propertiesProvider);
  }

  @Test
  public void testUserNotFound_jitDisabled_throws() throws Exception {
    expect(users.getUserEntity("ghost")).andReturn(null).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(/*autoCreate=*/false, "none", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    try {
      provider.authenticate(tokenFor("ghost", buildJwt("ghost", null)));
      fail("Expected UserNotFoundException — JIT disabled, user missing");
    } catch (UserNotFoundException expected) {
      assertTrue("Error message should reference LDAP",
          expected.getMessage().contains("LDAP is configured and users are synced"));
    }
    verify(users, propertiesProvider);
  }

  // ============================================================================================
  // JIT user auto-create
  // ============================================================================================

  @Test
  public void testJitAutoCreate_createsUserAndJwtAuth() throws Exception {
    // First lookup: user does not exist.  After createUser + addJWTAuthentication, the second
    // lookup (re-fetch inside jitCreateUser) returns the new entity with JWT auth attached.
    UserEntity created = userWithJwtAuth("alice");
    expect(users.getUserEntity("alice")).andReturn(null).once();
    expect(users.createUser(eq("alice"), anyObject(), eq("alice"), eq(true)))
        .andReturn(created).once();
    users.addJWTAuthentication(eq(created), eq("alice"));
    expectLastCall().once();
    expect(users.getUserEntity("alice")).andReturn(created).anyTimes();
    expect(users.getUser(created)).andReturn(new org.apache.ambari.server.security.authorization.User(created)).anyTimes();
    expect(users.getUserAuthorities(created)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(/*autoCreate=*/true, "none", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("alice", buildJwt("alice", null)));

    assertNotNull(result);
    assertTrue(result.isAuthenticated());
    verify(users, propertiesProvider);
  }

  // ============================================================================================
  // Case-conversion
  // ============================================================================================

  @Test
  public void testCaseConversionLower_normalizesBeforeLookup() throws Exception {
    // JWT subject is mixed-case "HadoopAdmin"; with case-conversion=lower the provider must
    // look up "hadoopadmin" — matching the lowercased persistence-layer name.
    UserEntity userEntity = userWithJwtAuth("hadoopadmin");
    expect(users.getUserEntity("hadoopadmin")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "lower", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("HadoopAdmin", buildJwt("HadoopAdmin", null)));

    assertNotNull(result);
    assertTrue(result.isAuthenticated());
    verify(users, propertiesProvider);
  }

  @Test
  public void testCaseConversionUpper_normalizesBeforeLookup() throws Exception {
    UserEntity userEntity = userWithJwtAuth("ALICE");
    expect(users.getUserEntity("ALICE")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "upper", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("alice", buildJwt("alice", null)));

    assertNotNull(result);
    assertTrue(result.isAuthenticated());
    verify(users, propertiesProvider);
  }

  // ============================================================================================
  // Group sync
  // ============================================================================================

  @Test
  public void testGroupSync_autoCreateEnabled_createsAndJoins() throws Exception {
    UserEntity userEntity = userWithJwtAuth("bob");
    GroupEntity engineering = new GroupEntity();
    engineering.setGroupName("engineering");
    engineering.setGroupType(GroupType.JWT);

    expect(users.getUserEntity("bob")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", /*groupsAutoCreate=*/true, true)).anyTimes();

    expect(users.getGroupEntity("engineering", GroupType.JWT)).andReturn(null).once();
    expect(users.createGroup("engineering", GroupType.JWT)).andReturn(engineering).once();
    users.addMemberToGroup(eq(engineering), eq(userEntity));
    expectLastCall().once();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(
        tokenFor("bob", buildJwt("bob", Collections.singletonList("engineering"))));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  @Test
  public void testGroupSync_autoCreateDisabled_skipsMissingGroups() throws Exception {
    UserEntity userEntity = userWithJwtAuth("bob");
    expect(users.getUserEntity("bob")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", /*groupsAutoCreate=*/false, true)).anyTimes();

    // Group is looked up but not found; auto-create disabled → no createGroup, no addMemberToGroup.
    expect(users.getGroupEntity("ops", GroupType.JWT)).andReturn(null).once();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(
        tokenFor("bob", buildJwt("bob", Collections.singletonList("ops"))));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  @Test
  public void testGroupSync_removesMembershipsNoLongerInJwt() throws Exception {
    // User is currently a member of two JWT-typed groups: "old-group" and "kept-group".
    // The JWT now only lists "kept-group" → "old-group" membership must be removed.
    GroupEntity oldGroup = new GroupEntity();
    oldGroup.setGroupName("old-group");
    oldGroup.setGroupType(GroupType.JWT);

    GroupEntity keptGroup = new GroupEntity();
    keptGroup.setGroupName("kept-group");
    keptGroup.setGroupType(GroupType.JWT);

    MemberEntity oldMember = new MemberEntity();
    oldMember.setGroup(oldGroup);
    MemberEntity keptMember = new MemberEntity();
    keptMember.setGroup(keptGroup);

    UserEntity userEntity = userWithJwtAuth("carol");
    Set<MemberEntity> members = new HashSet<>(Arrays.asList(oldMember, keptMember));
    userEntity.setMemberEntities(members);

    expect(users.getUserEntity("carol")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", true, /*syncOnLogin=*/true)).anyTimes();

    expect(users.getGroupEntity("old-group", GroupType.JWT)).andReturn(oldGroup).anyTimes();
    users.removeMemberFromGroup(eq(oldGroup), eq(userEntity));
    expectLastCall().once();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(
        tokenFor("carol", buildJwt("carol", Collections.singletonList("kept-group"))));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  @Test
  public void testGroupSync_keycloakSlashPrefixStripped() throws Exception {
    // Keycloak group-membership claim format: "/admins".  Provider must strip leading "/" so
    // it matches the Ambari group name conventions.
    UserEntity userEntity = userWithJwtAuth("dave");
    GroupEntity admins = new GroupEntity();
    admins.setGroupName("admins");
    admins.setGroupType(GroupType.JWT);

    expect(users.getUserEntity("dave")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", false, true)).anyTimes();
    expect(users.getGroupEntity("admins", GroupType.JWT)).andReturn(admins).once();
    users.addMemberToGroup(eq(admins), eq(userEntity));
    expectLastCall().once();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(
        tokenFor("dave", buildJwt("dave", Collections.singletonList("/admins"))));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  // ============================================================================================
  // applyCaseConversion — pure-function test (no mocking)
  // ============================================================================================

  // ============================================================================================
  // Hardened JIT — default-role grant, race-safety, forbidden chars
  // ============================================================================================

  @Test
  public void testJit_grantsAmbariAdministratorRole() throws Exception {
    UserEntity created = userWithJwtAuth("alice");
    expect(users.getUserEntity("alice")).andReturn(null).once();
    expect(users.createUser(eq("alice"), anyObject(), eq("alice"), eq(true))).andReturn(created).once();
    users.addJWTAuthentication(eq(created), eq("alice"));
    expectLastCall().once();
    // Default role = AMBARI.ADMINISTRATOR → goes through grantAdminPrivilege (not grantPrivilegeToUser).
    users.grantAdminPrivilege(eq(created));
    expectLastCall().once();
    expect(users.getUserEntity("alice")).andReturn(created).anyTimes();
    expect(users.getUser(created)).andReturn(new org.apache.ambari.server.security.authorization.User(created)).anyTimes();
    expect(users.getUserAuthorities(created)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "AMBARI.ADMINISTRATOR", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("alice", buildJwt("alice", null)));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  @Test
  public void testJit_grantsClusterUserRole_namedCluster() throws Exception {
    UserEntity created = userWithJwtAuth("bob");
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getResourceId()).andReturn(99L).anyTimes();
    expect(cluster.getClusterName()).andReturn("prod").anyTimes();
    Clusters clusters = createNiceMock(Clusters.class);
    expect(clusters.getCluster("prod")).andReturn(cluster).anyTimes();

    expect(users.getUserEntity("bob")).andReturn(null).once();
    expect(users.createUser(eq("bob"), anyObject(), eq("bob"), eq(true))).andReturn(created).once();
    users.addJWTAuthentication(eq(created), eq("bob"));
    expectLastCall().once();
    users.grantPrivilegeToUser(eq(42), eq(99L), eq(ResourceType.CLUSTER), eq("CLUSTER.USER"));
    expectLastCall().once();
    expect(users.getUserEntity("bob")).andReturn(created).anyTimes();
    expect(users.getUser(created)).andReturn(new org.apache.ambari.server.security.authorization.User(created)).anyTimes();
    expect(users.getUserAuthorities(created)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "CLUSTER.USER@prod", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider, cluster, clusters);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.of(clusters));
    Authentication result = provider.authenticate(tokenFor("bob", buildJwt("bob", null)));

    assertNotNull(result);
    verify(users, propertiesProvider, cluster, clusters);
  }

  @Test
  public void testJit_clusterRoleNoSuffix_singleClusterAutoResolves() throws Exception {
    UserEntity created = userWithJwtAuth("carol");
    Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getResourceId()).andReturn(77L).anyTimes();
    expect(cluster.getClusterName()).andReturn("only-cluster").anyTimes();
    Clusters clusters = createNiceMock(Clusters.class);
    Map<String, Cluster> singleClusterMap = new HashMap<>();
    singleClusterMap.put("only-cluster", cluster);
    expect(clusters.getClusters()).andReturn(singleClusterMap).anyTimes();

    expect(users.getUserEntity("carol")).andReturn(null).once();
    expect(users.createUser(eq("carol"), anyObject(), eq("carol"), eq(true))).andReturn(created).once();
    users.addJWTAuthentication(eq(created), eq("carol"));
    expectLastCall().once();
    users.grantPrivilegeToUser(eq(42), eq(77L), eq(ResourceType.CLUSTER), eq("CLUSTER.USER"));
    expectLastCall().once();
    expect(users.getUserEntity("carol")).andReturn(created).anyTimes();
    expect(users.getUser(created)).andReturn(new org.apache.ambari.server.security.authorization.User(created)).anyTimes();
    expect(users.getUserAuthorities(created)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "CLUSTER.USER", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider, cluster, clusters);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.of(clusters));
    Authentication result = provider.authenticate(tokenFor("carol", buildJwt("carol", null)));

    assertNotNull(result);
    verify(users, propertiesProvider, cluster, clusters);
  }

  @Test
  public void testJit_clusterRoleNoSuffix_multipleClustersSkipsGracefully() throws Exception {
    // No @cluster suffix + multiple clusters = ambiguous; provider must log a warning, skip the
    // role grant, but still complete authentication.
    UserEntity created = userWithJwtAuth("dave");
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster c1 = createNiceMock(Cluster.class);
    Cluster c2 = createNiceMock(Cluster.class);
    Map<String, Cluster> multi = new HashMap<>();
    multi.put("a", c1);
    multi.put("b", c2);
    expect(clusters.getClusters()).andReturn(multi).anyTimes();

    expect(users.getUserEntity("dave")).andReturn(null).once();
    expect(users.createUser(eq("dave"), anyObject(), eq("dave"), eq(true))).andReturn(created).once();
    users.addJWTAuthentication(eq(created), eq("dave"));
    expectLastCall().once();
    // No grantPrivilegeToUser call expected — provider must skip.
    expect(users.getUserEntity("dave")).andReturn(created).anyTimes();
    expect(users.getUser(created)).andReturn(new org.apache.ambari.server.security.authorization.User(created)).anyTimes();
    expect(users.getUserAuthorities(created)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "CLUSTER.USER", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider, c1, c2, clusters);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.of(clusters));
    Authentication result = provider.authenticate(tokenFor("dave", buildJwt("dave", null)));

    assertNotNull(result);  // auth still succeeds despite ambiguous role assignment
    verify(users, propertiesProvider);
  }

  @Test
  public void testJit_raceOnCreate_reusesParallelThreadsRecord() throws Exception {
    // Two browser tabs simultaneously trigger JIT-create.  The second thread sees the user did
    // not exist (its getUserEntity returned null) but createUser throws "User already exists"
    // because the first thread has just persisted.  Provider must recover by re-fetching and
    // proceed as if it had won the race.
    UserEntity raceWinner = userWithJwtAuth("eve");
    expect(users.getUserEntity("eve")).andReturn(null).once();
    expect(users.createUser(eq("eve"), anyObject(), eq("eve"), eq(true)))
        .andThrow(new AmbariException("User already exists")).once();
    // After the race, the provider re-fetches and finds the record persisted by the other thread.
    expect(users.getUserEntity("eve")).andReturn(raceWinner).anyTimes();
    expect(users.getUser(raceWinner)).andReturn(new org.apache.ambari.server.security.authorization.User(raceWinner)).anyTimes();
    expect(users.getUserAuthorities(raceWinner)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "", "", false, true)).anyTimes();
    // Note: NO addJWTAuthentication expectation — the winning thread already attached JWT auth.
    // This is the key invariant: jitCreateUser must NOT attempt addJWTAuthentication on the
    // race path (would throw "authentication type already exists").

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("eve", buildJwt("eve", null)));

    assertNotNull(result);
    assertTrue(result.isAuthenticated());
    verify(users, propertiesProvider);
  }

  @Test
  public void testJit_forbiddenCharsInJwtSubject_throwsAuthExceptionNotIllegalArg() throws Exception {
    // UserName.fromString rejects '<', '>', '&', '|', '\\', '`'.  An IdP that emits one of those
    // in the subject claim must yield a clean AmbariAuthenticationException — NOT a stack trace
    // out of IllegalArgumentException turning into a 500.
    expect(users.getUserEntity("bad<name>")).andReturn(null).once();
    expect(users.createUser(eq("bad<name>"), anyObject(), eq("bad<name>"), eq(true)))
        .andThrow(new IllegalArgumentException("Invalid username: bad<name> Avoid characters [<, >, &, |, \\, `]")).once();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(true, "none", "", "", false, true)).anyTimes();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<Clusters>of(null));
    try {
      provider.authenticate(tokenFor("bad<name>", buildJwt("bad<name>", null)));
      fail("Expected AmbariAuthenticationException for forbidden chars");
    } catch (AmbariAuthenticationException expected) {
      assertTrue("Error message should reference the rejection reason",
          expected.getMessage().contains("not a legal Ambari username")
            || expected.getMessage().contains("Invalid username"));
    }
    verify(users, propertiesProvider);
  }

  @Test
  public void testApplyCaseConversion_modes() {
    assertEquals("Foo",
        JwtAuthenticationProperties.applyCaseConversion("none", "Foo"));
    assertEquals("foo",
        JwtAuthenticationProperties.applyCaseConversion("lower", "Foo"));
    assertEquals("FOO",
        JwtAuthenticationProperties.applyCaseConversion("upper", "Foo"));
    assertEquals("Foo",
        JwtAuthenticationProperties.applyCaseConversion("BOGUS", "Foo")); // unrecognized → none
  }

  @Test
  public void testReadStringListClaim_commaDelimited() throws Exception {
    // Keycloak's "Group Membership" mapper with multivalued=false emits the claim as a comma-
    // delimited string instead of an array.  The provider must tolerate both encodings.
    UserEntity userEntity = userWithJwtAuth("eve");
    GroupEntity a = new GroupEntity();
    a.setGroupName("a");
    a.setGroupType(GroupType.JWT);
    GroupEntity b = new GroupEntity();
    b.setGroupName("b");
    b.setGroupType(GroupType.JWT);

    expect(users.getUserEntity("eve")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", false, true)).anyTimes();

    expect(users.getGroupEntity("a", GroupType.JWT)).andReturn(a).once();
    expect(users.getGroupEntity("b", GroupType.JWT)).andReturn(b).once();
    users.addMemberToGroup(eq(a), eq(userEntity));
    expectLastCall().once();
    users.addMemberToGroup(eq(b), eq(userEntity));
    expectLastCall().once();

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(tokenFor("eve", buildJwt("eve", "a,b")));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }

  @Test
  public void testGroupSync_noSyncOnLogin_skipsRemovals() throws Exception {
    // syncOnLogin=false: provider should ADD memberships from the JWT but NEVER remove them.
    // (Used when JIT is the source-of-truth for initial assignment but admin curates afterward.)
    GroupEntity oldGroup = new GroupEntity();
    oldGroup.setGroupName("old-group");
    oldGroup.setGroupType(GroupType.JWT);
    MemberEntity oldMember = new MemberEntity();
    oldMember.setGroup(oldGroup);

    UserEntity userEntity = userWithJwtAuth("frank");
    userEntity.setMemberEntities(new HashSet<>(Collections.singletonList(oldMember)));

    expect(users.getUserEntity("frank")).andReturn(userEntity).anyTimes();
    expect(users.getUser(userEntity)).andReturn(new org.apache.ambari.server.security.authorization.User(userEntity)).anyTimes();
    expect(users.getUserAuthorities(userEntity)).andReturn(Collections.emptyList()).anyTimes();
    // jitCreated=false (user already exists), syncOnLogin=false → group sync block is skipped entirely.
    expect(propertiesProvider.get()).andReturn(
        jitProperties(false, "none", "groups", true, /*syncOnLogin=*/false)).anyTimes();

    // No getGroupEntity / removeMemberFromGroup expectations — syncOnLogin=false means the
    // entire reconcile path is skipped on this login (the user wasn't just JIT-created).

    replay(users, configuration, propertiesProvider);

    provider = new AmbariJwtAuthenticationProvider(users, configuration, propertiesProvider,
        com.google.inject.util.Providers.<org.apache.ambari.server.state.Clusters>of(null));
    Authentication result = provider.authenticate(
        tokenFor("frank", buildJwt("frank", Collections.singletonList("new-group"))));

    assertNotNull(result);
    verify(users, propertiesProvider);
  }
}
