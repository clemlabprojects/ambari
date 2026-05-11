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

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.entities.GroupEntity;
import org.apache.ambari.server.orm.entities.MemberEntity;
import org.apache.ambari.server.orm.entities.PermissionEntity;
import org.apache.ambari.server.orm.entities.ResourceEntity;
import org.apache.ambari.server.orm.entities.UserAuthenticationEntity;
import org.apache.ambari.server.orm.entities.UserEntity;
import org.apache.ambari.server.security.authentication.AccountDisabledException;
import org.apache.ambari.server.security.authentication.AmbariAuthenticationException;
import org.apache.ambari.server.security.authentication.AmbariAuthenticationProvider;
import org.apache.ambari.server.security.authentication.AmbariUserAuthentication;
import org.apache.ambari.server.security.authentication.AmbariUserDetails;
import org.apache.ambari.server.security.authentication.AmbariUserDetailsImpl;
import org.apache.ambari.server.security.authentication.TooManyLoginFailuresException;
import org.apache.ambari.server.security.authentication.UserNotFoundException;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.GroupType;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.UserAuthenticationType;
import org.apache.ambari.server.security.authorization.Users;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import com.google.inject.Inject;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * AmbariJwtAuthenticationProvider authenticates users from a validated JWT issued by Knox SSO
 * or an OIDC provider (Keycloak, Okta, Azure AD, etc.).  The JWT signature is validated upstream
 * by {@link AmbariJwtAuthenticationFilter}; this provider trusts the token string and resolves
 * it to an Ambari user record.
 *
 * <h3>JIT (Just-In-Time) provisioning</h3>
 *
 * <p>Historically, if the JWT subject was not present in Ambari's local user database, this
 * provider threw {@link UserNotFoundException} with the message "Cannot find user from JWT.
 * Please, ensure LDAP is configured and users are synced."  This required pre-sync of users
 * via {@code ambari-server sync-ldap}, which is awkward for pure-OIDC deployments without
 * any LDAP/AD source (Keycloak with social login, Okta, Azure AD without LDAP federation, ...).
 *
 * <p>When {@code ambari.sso.oidc.user.auto.create=true} is set, the provider auto-creates the
 * Ambari user record on first successful JWT presentation, with authentication type
 * {@link UserAuthenticationType#JWT}.  Subsequent logins find the user and proceed normally.
 *
 * <p>Group memberships can additionally be sourced from a JWT array claim
 * (e.g. {@code groups}) configured by {@code ambari.sso.oidc.groups.claim}.  See
 * {@link JwtAuthenticationProperties} for the full set of properties and their semantics.
 *
 * <h3>Production-management notes</h3>
 *
 * <ul>
 *   <li><b>Visibility:</b> JIT-created users / groups appear in {@code /api/v1/users} and
 *       {@code /api/v1/groups} like any other.  Filter by {@code UserAuthenticationType.JWT}
 *       (users) and {@code GroupType.JWT} (groups) to identify them.</li>
 *   <li><b>Revocation:</b> Prefer DISABLE over DELETE for JIT users.  Deletion is not durable
 *       against the JWT issuer — on the next login JIT will recreate the record.  Disabling
 *       sets {@code active=false}; {@code Users.validateLogin} rejects subsequent attempts
 *       even with a valid JWT.</li>
 *   <li><b>Audit:</b> Each auto-create writes one INFO log line tagged
 *       {@code [JIT-AUTO-CREATE]} so SIEM / log-aggregation can capture provisioning events.</li>
 *   <li><b>Case-conversion:</b> Ambari's UserDAO / GroupDAO always normalize the persistence-
 *       layer name to lowercase.  The case-conversion properties only affect the display name
 *       and the JWT-side normalization before the case-insensitive DAO lookup.</li>
 * </ul>
 */
public class AmbariJwtAuthenticationProvider extends AmbariAuthenticationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(AmbariJwtAuthenticationProvider.class);

  private final JwtAuthenticationPropertiesProvider jwtPropertiesProvider;
  private final com.google.inject.Provider<Clusters> clustersProvider;

  /**
   * Constructor.
   *
   * @param users                 the users helper
   * @param configuration         the configuration
   * @param jwtPropertiesProvider provider for the live OIDC / SSO properties (loaded lazily and
   *                              refreshed on config changes)
   * @param clustersProvider      lazily-resolved Clusters API.  Injected as a Provider to avoid a
   *                              circular Guice dependency during server bootstrap (Clusters is
   *                              expensive to initialize; JWT auth doesn't need it until first
   *                              JIT-create with a {@code CLUSTER.*} default role).
   */
  @Inject
  public AmbariJwtAuthenticationProvider(Users users, Configuration configuration,
                                         JwtAuthenticationPropertiesProvider jwtPropertiesProvider,
                                         com.google.inject.Provider<Clusters> clustersProvider) {
    super(users, configuration);
    this.jwtPropertiesProvider = jwtPropertiesProvider;
    this.clustersProvider = clustersProvider;
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    if (authentication.getName() == null) {
      LOG.info("Authentication failed: no username provided");
      throw new AmbariAuthenticationException(null, "Unexpected error due to missing username", false);
    }

    String userName = authentication.getName().trim();

    if (authentication.getCredentials() == null) {
      LOG.info("Authentication failed: no credentials provided: {}", userName);
      throw new AmbariAuthenticationException(userName, "Unexpected error due to missing JWT token", false);
    }

    Users users = getUsers();
    JwtAuthenticationProperties jwtProperties = jwtPropertiesProvider.get();

    // Apply case-conversion to the inbound username up front so subsequent DAO lookups and
    // JIT creates use a consistent form.  UserDAO additionally lowercases on lookup, so
    // 'none' / 'lower' / 'upper' all match an existing record with the same letters.
    String normalizedUserName = JwtAuthenticationProperties.applyCaseConversion(
        jwtProperties == null ? "none" : jwtProperties.getOidcUserCaseConversion(), userName);

    UserEntity userEntity = users.getUserEntity(normalizedUserName);
    boolean jitCreated = false;

    if (userEntity == null) {
      if (jwtProperties != null && jwtProperties.isOidcUserAutoCreate()) {
        userEntity = jitCreateUser(users, normalizedUserName, jwtProperties);
        jitCreated = true;
      } else {
        LOG.info("User not found: {}", normalizedUserName);
        throw new UserNotFoundException(normalizedUserName,
            "Cannot find user from JWT. Please, ensure LDAP is configured and users are synced.");
      }
    }

    // If the user was found and allowed to log in, make sure that user is allowed to authenticate using a JWT token.
    boolean authOK = false;
    UserAuthenticationEntity authenticationEntity = getAuthenticationEntity(userEntity, UserAuthenticationType.JWT);
    if (authenticationEntity != null) {
      authOK = true;
    } else {
      // TODO: Determine if LDAP users can authenticate using JWT - for now we assume yes.
      // If a JWT entity was not found, see if an LDAP entity exists. If so, this user was synced
      // with a remote server and this should be allowed to authenticate using JWT
      authenticationEntity = getAuthenticationEntity(userEntity, UserAuthenticationType.LDAP);

      if (authenticationEntity != null) {
        try {
          users.addJWTAuthentication(userEntity, normalizedUserName);
          authOK = true;
        } catch (AmbariException e) {
          LOG.error(String.format("Failed to add the JWT authentication method for %s: %s",
              normalizedUserName, e.getLocalizedMessage()), e);
          throw new AmbariAuthenticationException(normalizedUserName, "Unexpected error has occurred", false, e);
        }
      }
    }

    if (authOK) {
      // The user was authenticated, return the authenticated user object
      LOG.debug("Authentication succeeded - a matching user was found: {}", normalizedUserName);

      // Sync group memberships from the JWT claim on each login (default) or only on JIT
      // creation if syncOnLogin is disabled.  Errors do not fail authentication — the user
      // is already authenticated; group sync failures are logged but otherwise swallowed.
      if (jwtProperties != null
          && !jwtProperties.getOidcGroupsClaim().isEmpty()
          && (jitCreated || jwtProperties.isOidcGroupsSyncOnLogin())) {
        try {
          syncGroupsFromJwt(users, userEntity, authentication.getCredentials().toString(), jwtProperties);
        } catch (Exception e) {
          LOG.warn("OIDC group sync failed for user {}: {}", normalizedUserName, e.getMessage());
        }
      }

      // Ensure the user account is allowed to log in
      try {
        users.validateLogin(userEntity, normalizedUserName);
      } catch (AccountDisabledException | TooManyLoginFailuresException e) {
        if (getConfiguration().showLockedOutUserMessage()) {
          throw e;
        } else {
          // Do not give away information about the existence or status of a user
          throw new AmbariAuthenticationException(normalizedUserName, "Unexpected error due to missing JWT token", false);
        }
      }

      AmbariUserDetails userDetails = new AmbariUserDetailsImpl(users.getUser(userEntity), null, users.getUserAuthorities(userEntity));

      String jwtTokenName = userDetails.getUsername().trim();
      //If JwtToken Provided Username and authenticatedUsername is different Add it to Alias
      if (!normalizedUserName.equals(jwtTokenName)) {
        AuthorizationHelper.addLoginNameAlias(normalizedUserName, jwtTokenName);
      }
      return new AmbariUserAuthentication(authentication.getCredentials().toString(), userDetails, true);
    } else {
      // The user was not authenticated, fail
      LOG.debug("Authentication failed: password does not match stored value: {}", normalizedUserName);
      throw new UserNotFoundException(normalizedUserName,
          "Cannot find user from JWT. Please, ensure LDAP is configured and users are synced.");
    }
  }

  /**
   * Create a new Ambari user record from a JWT subject and attach the JWT authentication entity.
   * Production-grade: race-safe, forbidden-char-safe, optional default-role grant.
   *
   * <p>Persistence-layer userName is lowercased by Ambari's UserDAO; the displayName preserves
   * the case-converted form so the UI shows whatever the admin expects.  No password is set —
   * the user is JWT-only.  Triggers the user-creation hook so downstream sync (e.g. Hive HDFS
   * home-directory provisioning) fires normally.
   *
   * <p>Race-safety: when two browser tabs trigger first-login for the same new subject
   * simultaneously, the second call's {@code createUser} throws "User already exists" (a check
   * inside {@code Users.createUser}).  Rather than 403 the user, we catch the race and re-fetch
   * the entity the parallel thread just persisted, then proceed as if we had won.  Idempotent.
   *
   * <p>Forbidden chars: {@code UserName.fromString} rejects {@code <>&|\\\`}.  We surface the
   * issue as a clean 401 rather than letting the {@code IllegalArgumentException} bubble out
   * as a 500.  Some IdPs (notably AD with funky SAM names) emit these characters in claims.
   *
   * <p>Default role: when {@code ambari.sso.oidc.user.default.role} is non-empty, applies the
   * role to the new user.  Syntax:
   * <ul>
   *   <li>{@code AMBARI.ADMINISTRATOR} → global Ambari admin (cluster-independent)</li>
   *   <li>{@code CLUSTER.USER@my-cluster} → CLUSTER.USER scoped to "my-cluster"</li>
   *   <li>{@code CLUSTER.USER} → if exactly one cluster exists in this Ambari, apply to it; else log + skip</li>
   * </ul>
   * Granting is idempotent ({@code Users.grantPrivilegeToUser} contains-checks); failures are
   * logged but do not fail authentication — the user is already auto-created at this point.
   *
   * @return the persisted UserEntity (never {@code null}; the just-fetched one)
   * @throws AmbariAuthenticationException if create fails for non-race reasons
   */
  private UserEntity jitCreateUser(Users users, String userName, JwtAuthenticationProperties jwtProperties) {
    LOG.info("[JIT-AUTO-CREATE] Provisioning Ambari user from OIDC JWT: {}", userName);

    UserEntity userEntity;
    try {
      userEntity = users.createUser(userName, /*localUserName=*/null, /*displayName=*/userName, /*active=*/true);
    } catch (IllegalArgumentException invalidName) {
      // UserName.fromString rejected the username (forbidden chars or empty)
      LOG.warn("[JIT-AUTO-CREATE] Rejecting JWT subject containing forbidden characters: {}", userName);
      throw new AmbariAuthenticationException(userName,
          "JWT subject is not a legal Ambari username: " + invalidName.getMessage(), false, invalidName);
    } catch (AmbariException e) {
      // Race: another thread JIT-created the same user between our getUserEntity null check and createUser.
      // The error message from Users.createUser is the verbatim string "User already exists".
      if (e.getMessage() != null && e.getMessage().contains("User already exists")) {
        LOG.info("[JIT-AUTO-CREATE] Race detected for user {}; using record created by parallel thread", userName);
        UserEntity existing = users.getUserEntity(userName);
        if (existing != null) {
          return existing;
        }
        // Defensive fallback — if we still can't find the record, surface a real failure.
      }
      LOG.error("[JIT-AUTO-CREATE] Failed to provision user {} from JWT: {}", userName, e.getMessage(), e);
      throw new AmbariAuthenticationException(userName, "Failed to auto-create user from JWT", false, e);
    }

    try {
      users.addJWTAuthentication(userEntity, userName);
    } catch (AmbariException e) {
      LOG.error("[JIT-AUTO-CREATE] Failed to attach JWT auth for user {}: {}", userName, e.getMessage(), e);
      throw new AmbariAuthenticationException(userName, "Failed to attach JWT auth to JIT user", false, e);
    }

    grantDefaultRoleIfConfigured(users, userEntity, jwtProperties);

    // Re-fetch so the in-memory entity reflects the just-added authentication entity (otherwise
    // the immediately-following getAuthenticationEntity() call returns null on the stale reference).
    UserEntity refreshed = users.getUserEntity(userName);
    return refreshed != null ? refreshed : userEntity;
  }

  /**
   * Apply the configured default role to a freshly JIT-created user.
   *
   * <p>Three property values recognized:
   * <pre>
   *   ""                            → no role assignment (admin curates after first login)
   *   "AMBARI.ADMINISTRATOR"        → global, no cluster context needed
   *   "CLUSTER.USER@cluster-name"   → cluster-scoped, named cluster
   *   "CLUSTER.USER"                → cluster-scoped, single-cluster auto-resolution
   * </pre>
   *
   * <p>Errors here do NOT throw — auth is already complete; we just log and continue.  The admin
   * can grant manually if the auto-grant misfires.
   */
  private void grantDefaultRoleIfConfigured(Users users, UserEntity userEntity, JwtAuthenticationProperties jwtProperties) {
    String defaultRole = jwtProperties == null ? "" : jwtProperties.getOidcUserDefaultRole();
    if (defaultRole.isEmpty()) {
      return;
    }
    try {
      if (PermissionEntity.AMBARI_ADMINISTRATOR_PERMISSION_NAME.equals(defaultRole)) {
        users.grantAdminPrivilege(userEntity);
        LOG.info("[JIT-AUTO-CREATE] Granted AMBARI.ADMINISTRATOR to user {}", userEntity.getUserName());
        return;
      }
      // Cluster-scoped role.  Optional @cluster-name suffix; without it we require exactly one cluster.
      String permissionName;
      String clusterName;
      int at = defaultRole.indexOf('@');
      if (at > 0) {
        permissionName = defaultRole.substring(0, at);
        clusterName = defaultRole.substring(at + 1);
      } else {
        permissionName = defaultRole;
        clusterName = null;
      }
      Clusters clusters = clustersProvider.get();
      Cluster cluster;
      if (clusterName != null) {
        cluster = clusters.getCluster(clusterName);
      } else {
        java.util.Map<String, Cluster> all = clusters.getClusters();
        if (all == null || all.size() != 1) {
          int size = all == null ? 0 : all.size();
          LOG.warn("[JIT-AUTO-CREATE] Default role '{}' references no cluster but {} clusters exist; "
              + "skipping role grant for {} (specify CLUSTER.*@<cluster-name> to disambiguate)",
              defaultRole, size, userEntity.getUserName());
          return;
        }
        cluster = all.values().iterator().next();
      }
      if (cluster == null) {
        LOG.warn("[JIT-AUTO-CREATE] Default role '{}' could not resolve a cluster; skipping role grant for {}",
            defaultRole, userEntity.getUserName());
        return;
      }
      ResourceEntity resource = cluster.getResource();
      if (resource == null) {
        LOG.warn("[JIT-AUTO-CREATE] Cluster '{}' has no resource entity; cannot grant '{}' to {}",
            cluster.getClusterName(), permissionName, userEntity.getUserName());
        return;
      }
      users.grantPrivilegeToUser(userEntity.getUserId(), resource.getId(), ResourceType.CLUSTER, permissionName);
      LOG.info("[JIT-AUTO-CREATE] Granted {} on cluster {} to user {}",
          permissionName, cluster.getClusterName(), userEntity.getUserName());
    } catch (Exception e) {
      LOG.warn("[JIT-AUTO-CREATE] Failed to grant default role '{}' to user {}: {}",
          defaultRole, userEntity.getUserName(), e.getMessage());
    }
  }

  /**
   * Read the configured groups claim from the JWT and reconcile the user's group memberships.
   * <p>
   * Behavior:
   * <ul>
   *   <li>Groups in the JWT but missing in Ambari: created if {@code ambari.sso.oidc.groups.auto.create=true};
   *       otherwise silently skipped (allows the admin to constrain which groups JWT users can join).</li>
   *   <li>Groups in Ambari (already members) but no longer in the JWT: user is removed.  Only
   *       JWT-typed groups are affected — LDAP/LOCAL group memberships are preserved.</li>
   *   <li>Group name case-conversion is applied before lookup (see {@code ambari.sso.oidc.groups.case.conversion}).</li>
   * </ul>
   *
   * <p>The JWT signature was already validated by {@link AmbariJwtAuthenticationFilter}; we trust
   * the serialized token and parse it for claims only.
   */
  private void syncGroupsFromJwt(Users users, UserEntity userEntity, String serializedJwt,
                                 JwtAuthenticationProperties jwtProperties) throws ParseException {
    SignedJWT jwt = SignedJWT.parse(serializedJwt);
    JWTClaimsSet claims = jwt.getJWTClaimsSet();

    String claimName = jwtProperties.getOidcGroupsClaim();
    List<String> rawGroups = readStringListClaim(claims, claimName);
    if (rawGroups == null) {
      LOG.debug("OIDC groups claim '{}' not present in JWT for user {}; skipping group sync",
          claimName, userEntity.getUserName());
      return;
    }

    String caseMode = jwtProperties.getOidcGroupsCaseConversion();
    Set<String> desiredGroups = new HashSet<>();
    for (String g : rawGroups) {
      if (g == null) {
        continue;
      }
      // Keycloak often returns group paths like "/admins"; strip leading slashes so the
      // Ambari group name matches the admin's mental model.
      String stripped = g.startsWith("/") ? g.substring(1) : g;
      if (stripped.isEmpty()) {
        continue;
      }
      desiredGroups.add(JwtAuthenticationProperties.applyCaseConversion(caseMode, stripped));
    }

    // Compute current JWT-typed group memberships from the user's MemberEntity list.
    Set<String> currentJwtGroups = new HashSet<>();
    Collection<MemberEntity> members = userEntity.getMemberEntities();
    if (members != null) {
      for (MemberEntity m : members) {
        GroupEntity g = m.getGroup();
        if (g != null && g.getGroupType() == GroupType.JWT) {
          currentJwtGroups.add(g.getGroupName());
        }
      }
    }

    // Add memberships: groups in the JWT that the user isn't already in.
    for (String groupName : desiredGroups) {
      if (currentJwtGroups.contains(groupName)) {
        continue;
      }
      GroupEntity groupEntity = users.getGroupEntity(groupName, GroupType.JWT);
      if (groupEntity == null) {
        if (!jwtProperties.isOidcGroupsAutoCreate()) {
          LOG.debug("[JIT-GROUP] Skipping group '{}' for user {} — auto-create disabled and group does not exist",
              groupName, userEntity.getUserName());
          continue;
        }
        LOG.info("[JIT-GROUP-CREATE] Provisioning Ambari group from OIDC JWT: {}", groupName);
        groupEntity = users.createGroup(groupName, GroupType.JWT);
      }
      try {
        users.addMemberToGroup(groupEntity, userEntity);
        LOG.info("[JIT-GROUP-JOIN] Added user {} to group {}", userEntity.getUserName(), groupName);
      } catch (AmbariException e) {
        LOG.warn("Failed to add user {} to JWT group {}: {}", userEntity.getUserName(), groupName, e.getMessage());
      }
    }

    // Remove memberships: JWT-typed groups the user is in but the JWT no longer lists.
    // Skipped when syncOnLogin=false: in that mode JIT only assigns, never removes.
    if (jwtProperties.isOidcGroupsSyncOnLogin()) {
      for (String groupName : currentJwtGroups) {
        if (desiredGroups.contains(groupName)) {
          continue;
        }
        GroupEntity groupEntity = users.getGroupEntity(groupName, GroupType.JWT);
        if (groupEntity == null) {
          continue;
        }
        try {
          users.removeMemberFromGroup(groupEntity, userEntity);
          LOG.info("[JIT-GROUP-LEAVE] Removed user {} from group {} (no longer in JWT)",
              userEntity.getUserName(), groupName);
        } catch (AmbariException e) {
          LOG.warn("Failed to remove user {} from JWT group {}: {}", userEntity.getUserName(), groupName, e.getMessage());
        }
      }
    }
  }

  /**
   * Read a JWT claim that should be a JSON array of strings.  Tolerant of two encodings IdPs
   * commonly emit: a JSON array, or a comma-delimited string.  Returns {@code null} if the
   * claim is missing entirely (distinct from "empty list").
   */
  @SuppressWarnings("unchecked")
  private List<String> readStringListClaim(JWTClaimsSet claims, String claimName) {
    Object raw = claims.getClaim(claimName);
    if (raw == null) {
      return null;
    }
    if (raw instanceof List) {
      List<String> out = new ArrayList<>();
      for (Object o : (List<Object>) raw) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    if (raw instanceof String) {
      String s = (String) raw;
      if (s.isEmpty()) {
        return Collections.emptyList();
      }
      List<String> out = new ArrayList<>();
      for (String piece : s.split(",")) {
        String trimmed = piece.trim();
        if (!trimmed.isEmpty()) {
          out.add(trimmed);
        }
      }
      return out;
    }
    // Unexpected shape (number, boolean, object); treat as missing rather than crashing auth.
    LOG.warn("OIDC groups claim '{}' has unexpected JSON type {}; ignoring", claimName, raw.getClass().getName());
    return null;
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return JwtAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
