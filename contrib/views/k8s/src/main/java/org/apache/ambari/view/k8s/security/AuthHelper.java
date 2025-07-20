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

    public UserPermissions getPermissions() {
        if (isUserInList("view.admin.users")) {
            return new UserPermissions("ADMIN", true, true);
        }
        if (isUserInList("view.operator.users")) {
            return new UserPermissions("OPERATOR", false, true);
        }
        return new UserPermissions("VIEWER", false, false);
    }

    public void checkConfigurationPermission() {
        LOG.info("Performing configuration permission check for user '{}'", viewContext.getUsername());
        if (isUserInList("view.admin.users")) {
            LOG.info("Configuration permission GRANTED for user '{}' based on instance properties.", viewContext.getUsername());
            return;
        }
        LOG.warn("Configuration permission DENIED for user '{}'. User not in admin list.", viewContext.getUsername());
        throw new ForbiddenException("L'utilisateur n'a pas les droits pour configurer la vue.");
    }

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
