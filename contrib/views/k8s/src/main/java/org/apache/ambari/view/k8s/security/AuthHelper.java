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

package org.apache.ambari.view.k8s.security;

import org.apache.ambari.view.AmbariStreamProvider;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.AmbariLoopbackUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves the KDPS role of the current user and enforces it on the REST endpoints.
 *
 * <p>Three roles exist: ADMIN may change the view configuration (kubeconfig, contexts, repositories,
 * security profiles, truststores, managed configurations) and do everything an OPERATOR does;
 * OPERATOR may deploy, upgrade, roll back and uninstall releases and act on their pods; VIEWER only
 * reads. The roles come from the {@code view.admin.users} and {@code view.operator.users} view
 * properties. When {@code view.admin.users} is empty, the Ambari administrators are the view admins,
 * so a view instance that was never configured stays closed to every other user granted access to it.
 * Ambari is asked with the caller's own credentials: build the helper with the request's headers.
 * The view's own keytab webhook has no role: its Ambari account only reaches the view, which
 * authenticates its keytab requests by its client certificate ({@link KeytabWebhookAuthenticator}).
 *
 * <p>Ambari itself only lets users that were granted the view instance reach these endpoints; the
 * {@code <roles>} of a non-automatic view instance are ignored, so nothing here can rely on Ambari
 * having limited access to administrators.
 */
public class AuthHelper {
    private static final Logger LOG = LoggerFactory.getLogger(AuthHelper.class);

    public static final String ADMIN_USERS_PROPERTY = "view.admin.users";
    public static final String OPERATOR_USERS_PROPERTY = "view.operator.users";

    static final String CONFIGURE_DENIED = "Your account cannot change the KDPS configuration. "
            + "Ask an Ambari administrator to add you to the view's admin users (view.admin.users).";
    static final String WRITE_DENIED = "Your account has read-only access to KDPS: it cannot deploy, change or "
            + "remove releases. Ask an Ambari administrator to add you to the view's operator users "
            + "(view.operator.users).";

    /**
     * User names that cannot change an Ambari API path. A name may hold single spaces, as directory-synced names such
     * as "John Smith" do; both lookups encode them.
     */
    private static final Pattern PLAIN_USER_NAME = Pattern.compile("[\\p{L}\\p{N}._@+\\-]+( [\\p{L}\\p{N}._@+\\-]+)*");

    private final ViewContext viewContext;
    /** Headers of the request being served, {@code null} when unknown; they carry the caller's Ambari credentials. */
    private final MultivaluedMap<String, String> callerHeaders;
    private final Predicate<String> ambariAdministrator;

    /** Whether the current user is an Ambari administrator, looked up at most once per helper (one request). */
    private Boolean ambariAdminCache;

    /**
     * Creates an AuthHelper bound to the given view context, for code that has no request headers at hand. Ambari is
     * then only asked through the caller's Ambari session, which a request authenticated with a user name and
     * password does not have yet.
     *
     * @param viewContext active Ambari view context used for username and property lookups
     */
    public AuthHelper(ViewContext viewContext) {
        this(viewContext, (HttpHeaders) null);
    }

    /**
     * Creates an AuthHelper for one request.
     *
     * @param viewContext   active Ambari view context used for username and property lookups
     * @param callerHeaders headers of the request; their credentials are forwarded when Ambari is asked whether the
     *                      caller is an Ambari administrator. {@code null} when unknown.
     */
    public AuthHelper(ViewContext viewContext, HttpHeaders callerHeaders) {
        this.viewContext = viewContext;
        this.callerHeaders = callerHeaders == null ? null : callerHeaders.getRequestHeaders();
        this.ambariAdministrator = this::lookUpAmbariAdministrator;
    }

    /**
     * Creates an AuthHelper with its own Ambari administrator lookup, so tests do not need an Ambari server.
     *
     * @param viewContext         active Ambari view context
     * @param ambariAdministrator tells whether the given Ambari user name is an Ambari administrator
     */
    AuthHelper(ViewContext viewContext, Predicate<String> ambariAdministrator) {
        this.viewContext = viewContext;
        this.callerHeaders = null;
        this.ambariAdministrator = ambariAdministrator;
    }

    private enum Role { ADMIN, OPERATOR, VIEWER }

    private List<String> userList(String propertyName) {
        Map<String, String> properties = viewContext.getProperties();
        String userListStr = properties == null ? null : properties.get(propertyName);
        if (userListStr == null || userListStr.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(userListStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private Role role() {
        String currentUser = viewContext.getUsername();
        List<String> admins = userList(ADMIN_USERS_PROPERTY);
        List<String> operators = userList(OPERATOR_USERS_PROPERTY);
        Role role;
        if (admins.isEmpty() ? isAmbariAdministrator() : admins.contains(currentUser)) {
            role = Role.ADMIN;
        } else if (operators.contains(currentUser)) {
            role = Role.OPERATOR;
        } else {
            role = Role.VIEWER;
        }
        LOG.debug("KDPS role of user '{}': {} (admin users {}, operator users {})", currentUser, role,
                admins.isEmpty() ? "<Ambari administrators>" : admins, operators);
        return role;
    }

    /** The caller's name as Ambari knows it. */
    private String ambariUserName() {
        String ambariUser = viewContext.getLoggedinUser();
        return ambariUser == null || ambariUser.isBlank() ? viewContext.getUsername() : ambariUser;
    }

    private boolean isAmbariAdministrator() {
        if (ambariAdminCache == null) {
            String ambariUser = ambariUserName();
            boolean admin = false;
            if (ambariUser != null && !ambariUser.isBlank()) {
                try {
                    admin = ambariAdministrator.test(ambariUser);
                } catch (RuntimeException e) {
                    LOG.warn("Could not tell whether '{}' is an Ambari administrator; treating the account as read-only: {}",
                            ambariUser, e.toString());
                }
            }
            ambariAdminCache = admin;
        }
        return ambariAdminCache;
    }

    /**
     * Asks Ambari whether the user holds the Ambari Administrator role, granted by name or through one of their
     * groups. The question goes to the local Ambari with the caller's own credentials, as every other call of the view
     * does: a request authenticated with a user name and password has no Ambari session until it completes. The
     * caller's Ambari session is the fallback. Any failure counts as "no", so an unreachable Ambari never widens access.
     */
    private boolean lookUpAmbariAdministrator(String ambariUser) {
        // A name that could change the API path is not looked up.
        if (!PLAIN_USER_NAME.matcher(ambariUser).matches()) {
            LOG.warn("Not looking up the unusual Ambari user name '{}'; treating the account as read-only", ambariUser);
            return false;
        }
        Map<String, String> credentials = AmbariActionClient.toAuthHeaders(callerHeaders);
        if (!credentials.isEmpty()) {
            try {
                return new AmbariActionClient(viewContext, AmbariLoopbackUrlResolver.resolveApiBase(viewContext), credentials)
                        .isAmbariAdministrator(ambariUser);
            } catch (Exception e) {
                LOG.info("Ambari user lookup for '{}' with the caller's credentials failed; asking with the Ambari "
                        + "session: {}", ambariUser, e.toString());
            }
        }
        AmbariStreamProvider ambari = viewContext.getAmbariStreamProvider();
        if (ambari == null) {
            return false;
        }
        // The provider builds and encodes the URL itself (a space becomes %20), so the name goes in as it is.
        String path = "/api/v1/users/" + ambariUser + AmbariActionClient.PRIVILEGES_QUERY;
        try (InputStream in = ambari.readFrom(path, "GET", (String) null, Map.of("X-Requested-By", "ambari"))) {
            return AmbariActionClient.grantsAmbariAdministrator(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("Ambari user lookup for '{}' failed; treating the account as read-only: {}", ambariUser, e.toString());
            return false;
        }
    }

    /**
     * Determine the effective permissions for the currently authenticated user.
     *
     * @return a {@link UserPermissions} instance reflecting the user's role and capability flags
     */
    public UserPermissions getPermissions() {
        switch (role()) {
            case ADMIN:
                return new UserPermissions("ADMIN", true, true);
            case OPERATOR:
                return new UserPermissions("OPERATOR", false, true);
            default:
                return new UserPermissions("VIEWER", false, false);
        }
    }

    /**
     * Whether the current user may change the view configuration and read the secrets it holds.
     *
     * @return true for the ADMIN role
     */
    public boolean canConfigure() {
        return role() == Role.ADMIN;
    }

    /**
     * Whether the current user may deploy, change or remove releases.
     *
     * @return true for the ADMIN and OPERATOR roles
     */
    public boolean canWrite() {
        return role() != Role.VIEWER;
    }

    /**
     * Assert that the current user is allowed to modify view configuration.
     *
     * @throws ForbiddenException (HTTP 403 with an {@code error} message) if the user is not a view admin
     */
    public void checkConfigurationPermission() {
        if (!canConfigure()) {
            LOG.warn("Configuration permission DENIED for user '{}'.", viewContext.getUsername());
            throw forbidden(CONFIGURE_DENIED);
        }
    }

    /**
     * Assert that the current user is allowed to perform write operations (deploy, upgrade, delete).
     *
     * @throws ForbiddenException (HTTP 403 with an {@code error} message) if the user is a viewer
     */
    public void checkWritePermission() {
        if (!canWrite()) {
            LOG.warn("Write permission DENIED for user '{}'.", viewContext.getUsername());
            throw forbidden(WRITE_DENIED);
        }
    }

    /**
     * A 403 whose body carries the reason, so the UI can show it instead of a bare status code.
     *
     * @param message the reason, shown to the user
     * @return the exception to throw
     */
    public static ForbiddenException forbidden(String message) {
        return new ForbiddenException(message, Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build());
    }
}
