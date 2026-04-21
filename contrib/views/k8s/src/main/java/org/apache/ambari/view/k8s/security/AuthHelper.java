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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ForbiddenException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AuthHelper {
    private final ViewContext viewContext;
    private static final Logger LOG = LoggerFactory.getLogger(AuthHelper.class);

    /**
     * Creates an AuthHelper bound to the given view context.
     *
     * @param viewContext active Ambari view context used for username and property lookups
     */
    public AuthHelper(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    private boolean isUserInList(String propertyName) {
        String userListStr = viewContext.getProperties().get(propertyName);
        if (userListStr == null || userListStr.trim().isEmpty()) {
            return false;
        }
        List<String> userList = Arrays.stream(userListStr.split(","))
                                      .map(String::trim)
                                      .collect(Collectors.toList());
        
        String currentUser = viewContext.getUsername();
        boolean isPresent = userList.contains(currentUser);
        LOG.info("Checking if user '{}' is in list '{}' (from property '{}'). Result: {}", currentUser, userList, propertyName, isPresent);
        return isPresent;
    }

    /**
     * Determine the effective permissions for the currently authenticated user.
     * Checks the {@code view.admin.users} and {@code view.operator.users} property lists in order.
     *
     * @return a {@link UserPermissions} instance reflecting the user's role and capability flags
     */
    public UserPermissions getPermissions() {
        if (isUserInList("view.admin.users")) {
            return new UserPermissions("ADMIN", true, true);
        }
        if (isUserInList("view.operator.users")) {
            return new UserPermissions("OPERATOR", false, true);
        }
        return new UserPermissions("VIEWER", false, false);
    }

    /**
     * Assert that the current user is allowed to modify view configuration.
     * When {@code view.admin.users} is configured, only listed users are permitted;
     * otherwise the Ambari view ACL is sufficient.
     *
     * @throws ForbiddenException if the user does not hold configuration permission
     */
    public void checkConfigurationPermission() {
        String username = viewContext.getUsername();
        LOG.info("Performing configuration permission check for user '{}'", username);

        // If view.admin.users is explicitly configured, enforce it strictly.
        String adminListRaw = viewContext.getProperties().get("view.admin.users");
        if (adminListRaw != null && !adminListRaw.trim().isEmpty()) {
            if (isUserInList("view.admin.users")) {
                LOG.info("Configuration permission GRANTED for user '{}' via view.admin.users property.", username);
                return;
            }
            LOG.warn("Configuration permission DENIED for user '{}'. User not in view.admin.users.", username);
            throw new ForbiddenException("L'utilisateur n'a pas les droits pour configurer la vue.");
        }

        // Property not configured: fall back to Ambari's own view ACL.
        // Access to the view itself is already restricted to CLUSTER.ADMINISTRATOR
        // and above by the view instance roles — any authenticated view user may configure.
        LOG.info("view.admin.users not configured — configuration permission GRANTED for user '{}' via Ambari view ACL.", username);
    }

    /**
     * Assert that the current user is allowed to perform write operations (deploy, upgrade, delete).
     * Users must appear in either {@code view.admin.users} or {@code view.operator.users}.
     *
     * @throws ForbiddenException if the user does not hold write permission
     */
    public void checkWritePermission() {
        LOG.info("Performing write permission check for user '{}'", viewContext.getUsername());
        if (isUserInList("view.admin.users") || isUserInList("view.operator.users")) {
            LOG.info("Write permission GRANTED for user '{}' based on instance properties.", viewContext.getUsername());
            return;
        }
        LOG.warn("Write permission DENIED for user '{}'. User not in admin or operator list.", viewContext.getUsername());
        throw new ForbiddenException("L'utilisateur n'a pas les droits pour effectuer des actions d'écriture.");
    }
}
