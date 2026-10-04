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
package org.apache.ambari.server.state;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.dao.HostRoleCommandDAO;
import org.apache.ambari.server.orm.dao.RequestDAO;
import org.apache.ambari.server.orm.dao.SettingDAO;
import org.apache.ambari.server.orm.dao.UpgradeDAO;
import org.apache.ambari.server.orm.entities.HostRoleCommandEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.RequestEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.serveraction.upgrades.SwitchJavaRuntimeAction;
import org.apache.ambari.server.state.UpgradeHelper.UpgradeGroupHolder;
import org.apache.ambari.server.state.stack.upgrade.Direction;
import org.apache.ambari.server.state.stack.upgrade.Grouping;
import org.apache.ambari.server.state.stack.upgrade.ServerActionTask;
import org.apache.ambari.server.state.stack.upgrade.StageWrapper;
import org.apache.ambari.server.state.stack.upgrade.TaskParameter;
import org.apache.ambari.server.state.stack.upgrade.TaskWrapper;
import org.apache.ambari.server.state.stack.upgrade.UpdateStackGrouping;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.ambari.server.utils.VersionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Plans an explicit, global stack JDK transition at the express upgrade boundary.
 * The plan is stored in the server action's execution command, including the
 * original paths, so retries, server restarts and downgrade do not lose it.
 */
@Singleton
public class UpgradeJavaRuntime {
  public static final String PLAN = "upgrade_java_runtime";
  public static final String PRIMARY = "Upgrade/primary_java_home";
  public static final String SECONDARY = "Upgrade/secondary_java_home";
  public static final String VALIDATION = "Upgrade/java_validation_request_id";
  private static final String FEATURE = "upgrade_java_home_selection";

  @Inject private Provider<AmbariMetaInfo> metaInfo;
  @Inject private Configuration configuration;
  @Inject private HostRoleCommandDAO commands;
  @Inject private RequestDAO requests;
  @Inject private UpgradeDAO upgrades;
  @Inject private Clusters clusters;
  @Inject private Gson gson;
  @Inject private SettingDAO settings;

  /** Retire preparation only after successful finalization; persisted execution plans remain. */
  public void retireDraft(Cluster cluster, RepositoryVersionEntity target) {
    try {
      settings.removeByName("upgrade-java-" + cluster.getClusterName() + "-" + target.getId());
    } catch (Exception e) {
      // Draft cleanup must not turn an already-finalized binary upgrade into a failure.
      LoggerFactory.getLogger(UpgradeJavaRuntime.class).warn("Unable to retire JDK preparation for cluster {}",
          cluster.getClusterName(), e);
    }
  }

  /**
   * Requirements come from target stack metadata, never a version constant in code.
   * Each feature range describes a JDK transition introduced at its min_version.
   * Non-overlapping ranges allow future transitions without changing Java code.
   */
  public JsonObject requirements(RepositoryVersionEntity repository) throws AmbariException {
    if (repository == null) {
      return null;
    }
    String stack = repository.getStackId().getStackName();
    for (PropertyInfo property : metaInfo.get().getStackProperties(stack, repository.getStackId().getStackVersion())) {
      if (!"stack_features".equals(property.getName())) {
        continue;
      }
      JsonObject document = gson.fromJson(property.getValue(), JsonObject.class);
      if (!document.has(stack)) {
        continue;
      }
      for (JsonElement element : document.getAsJsonObject(stack).getAsJsonArray("stack_features")) {
        JsonObject feature = element.getAsJsonObject();
        String version = StringUtils.substringBefore(repository.getVersion(), "-");
        if (FEATURE.equals(feature.get("name").getAsString())
            && (!feature.has("min_version") || VersionUtils.compareVersions(version, feature.get("min_version").getAsString()) >= 0)
            && (!feature.has("max_version") || VersionUtils.compareVersions(version, feature.get("max_version").getAsString()) < 0)) {
          return feature.has("enabled") && !feature.get("enabled").getAsBoolean() ? null : feature;
        }
      }
    }
    return null;
  }

  /**
   * Select JDKs only when crossing the target runtime's introduction boundary.
   * Desired service repositories are still the source repositories during
   * prechecks and upgrade planning. Ignore non-versioned services such as Metrics,
   * whose repository pointers do not describe the ODP binaries being upgraded.
   */
  public JsonObject transitionRequirements(Cluster cluster, RepositoryVersionEntity target) throws AmbariException {
    JsonObject required = requirements(target);
    if (required == null) {
      return null;
    }
    String boundary = required.get("min_version").getAsString();
    for (Service service : cluster.getServices().values()) {
      if (!service.getServiceComponents().values().stream().anyMatch(ServiceComponent::isVersionAdvertised)) {
        continue;
      }
      RepositoryVersionEntity source = service.getDesiredRepositoryVersion();
      if (source == null || StringUtils.isBlank(source.getVersion())) {
        throw new AmbariException("Cannot determine source repository for JDK transition: " + service.getName());
      }
      if (VersionUtils.compareVersions(StringUtils.substringBefore(source.getVersion(), "-"), boundary) < 0) {
        return required;
      }
    }
    return null;
  }

  public Map<String, String> currentHomes() {
    Map<String, String> values = new HashMap<>();
    values.put(Configuration.JAVA_HOME.getKey(), configuration.getJavaHome());
    values.put(Configuration.STACK_JAVA_HOME.getKey(), configuration.getStackJavaHome());
    values.put(Configuration.SECONDARY_JAVA_HOME.getKey(), configuration.getSecondaryJavaHome());
    return values;
  }

  /** Prepare and validate, but do not change any runtime while old services still run. */
  public Plan prepare(UpgradeContext context, Map<String, Object> input) throws AmbariException {
    if (context.getType() != UpgradeType.NON_ROLLING) {
      if (input.containsKey(PRIMARY) || input.containsKey(SECONDARY) || input.containsKey(VALIDATION)) {
        throw new AmbariException("JDK selection requires an express upgrade");
      }
      return null;
    }
    if (context.getDirection() == Direction.DOWNGRADE) {
      UpgradeEntity original = upgrades.findLastUpgradeForCluster(context.getCluster().getClusterId(), Direction.UPGRADE);
      if (original != null) {
        for (HostRoleCommandEntity task : commands.findByRequest(original.getRequestId())) {
          ExecutionCommand command = execution(task);
          if (command != null && command.getCommandParams().containsKey(PLAN)) {
            requireGlobalAccess();
            Plan plan = gson.fromJson(command.getCommandParams().get(PLAN), Plan.class);
            plan.restore = true;
            return plan;
          }
        }
      }
      return null;
    }
    JsonObject required = transitionRequirements(context.getCluster(), context.getRepositoryVersion());
    if (required == null) {
      if (input.containsKey(PRIMARY) || input.containsKey(SECONDARY) || input.containsKey(VALIDATION)) {
        throw new AmbariException("This upgrade does not cross an enabled stack JDK transition");
      }
      return null;
    }
    requireGlobalAccess();
    Plan plan = new Plan();
    plan.before = currentHomes();
    plan.after = new HashMap<>();
    String primary = path(input.get(PRIMARY));
    String secondary = path(input.get(SECONDARY));
    plan.after.put(Configuration.JAVA_HOME.getKey(), primary);
    plan.after.put(Configuration.STACK_JAVA_HOME.getKey(), primary);
    plan.after.put(Configuration.SECONDARY_JAVA_HOME.getKey(), secondary);
    plan.primaryMajor = required.get("primary_java_major").getAsInt();
    plan.secondaryMajor = required.get("secondary_java_major").getAsInt();
    try {
      plan.validationRequest = Long.parseLong(String.valueOf(input.get(VALIDATION)));
    } catch (NumberFormatException e) {
      throw new AmbariException("Validate the selected JDKs on every host before upgrading", e);
    }
    validate(context.getCluster(), plan, true);
    return plan;
  }

  public void requireGlobalAccess() throws AmbariException {
    if (!AuthorizationHelper.isAuthorized(ResourceType.AMBARI, null,
        EnumSet.of(RoleAuthorization.AMBARI_MANAGE_CONFIGURATION))) {
      throw new AmbariException("Changing the stack JDKs requires Ambari administrator privileges");
    }
    if (clusters.getClusters().size() != 1) {
      throw new AmbariException("A server-wide JDK transition requires exactly one managed cluster");
    }
  }

  /** Resolve the installed stack's policy, not a pending upgrade's target policy. */
  public JsonObject administrationRequirements(Cluster cluster) throws AmbariException {
    requireGlobalAccess();
    if (cluster.getUpgradeInProgress() != null) {
      throw new AmbariException("Finish or revert the active upgrade before changing stack JDKs");
    }
    JsonObject required = null;
    for (Service service : cluster.getServices().values()) {
      if (!service.getServiceComponents().values().stream().anyMatch(ServiceComponent::isVersionAdvertised)) {
        continue;
      }
      JsonObject candidate = requirements(service.getDesiredRepositoryVersion());
      if (candidate == null || (required != null && !required.equals(candidate))) {
        throw new AmbariException("Installed services must share an enabled stack JDK selection policy");
      }
      required = candidate;
    }
    if (required == null) {
      throw new AmbariException("JDK selection is not enabled for the installed stack");
    }
    return required;
  }

  /** Use the same path and host-proof validation for administrative saves as for upgrades. */
  public Plan administrationPlan(Cluster cluster, Object primary, Object secondary, Object validation)
      throws AmbariException {
    JsonObject required = administrationRequirements(cluster);
    Plan plan = new Plan();
    plan.after = new HashMap<>();
    plan.after.put(Configuration.JAVA_HOME.getKey(), path(primary));
    plan.after.put(Configuration.STACK_JAVA_HOME.getKey(), path(primary));
    plan.after.put(Configuration.SECONDARY_JAVA_HOME.getKey(), path(secondary));
    plan.primaryMajor = required.get("primary_java_major").getAsInt();
    plan.secondaryMajor = required.get("secondary_java_major").getAsInt();
    try {
      plan.validationRequest = Long.parseLong(String.valueOf(validation));
    } catch (NumberFormatException e) {
      throw new AmbariException("Validate both JDKs on every host before saving", e);
    }
    validate(cluster, plan, true);
    return plan;
  }

  private String path(Object value) throws AmbariException {
    String path = value == null ? "" : value.toString();
    if (!path.startsWith("/") || path.matches(".*\\s.*") || path.indexOf('\0') >= 0) {
      throw new AmbariException("Select an absolute JDK path without whitespace");
    }
    return path;
  }

  /** Reject stale, partial, failed or unrelated check requests, including direct REST bypasses. */
  public void validate(Cluster cluster, Plan plan, boolean checkAge) throws AmbariException {
    RequestEntity request = requests.findByPK(plan.validationRequest);
    if (request == null || !Long.valueOf(cluster.getClusterId()).equals(request.getClusterId())) {
      throw new AmbariException("JDK validation does not belong to this cluster");
    }
    Set<String> checked = new HashSet<>();
    for (HostRoleCommandEntity task : commands.findByRequest(plan.validationRequest)) {
      ExecutionCommand command = execution(task);
      if (command == null || !"check_upgrade_java".equals(command.getRole())
          || task.getStatus() != HostRoleStatus.COMPLETED
          || task.getStructuredOut() == null
          || (checkAge && System.currentTimeMillis() - task.getEndTime() > 30 * 60 * 1000L)) {
        throw new AmbariException("JDK checks must succeed on all hosts within the last 30 minutes");
      }
      JsonObject result = gson.fromJson(new String(task.getStructuredOut(), StandardCharsets.UTF_8), JsonObject.class);
      JsonObject checkedHomes = result.getAsJsonObject("upgrade_java");
      if (!matches(checkedHomes, "primary", plan.after.get(Configuration.JAVA_HOME.getKey()), plan.primaryMajor)
          || !matches(checkedHomes, "secondary", plan.after.get(Configuration.SECONDARY_JAVA_HOME.getKey()), plan.secondaryMajor)) {
        throw new AmbariException("JDK selection changed or failed validation on " + task.getHostName());
      }
      checked.add(task.getHostName());
    }
    if (!checked.equals(cluster.getHostNames()) || checked.isEmpty()) {
      throw new AmbariException("Validate the selected JDKs on every current cluster host");
    }
  }

  private boolean matches(JsonObject results, String name, String home, int major) {
    if (results == null || !results.has(name)) {
      return false;
    }
    JsonObject result = results.getAsJsonObject(name);
    return result.has("valid") && result.get("valid").getAsBoolean()
        && result.has("home") && home.equals(result.get("home").getAsString())
        && result.has("major") && major == result.get("major").getAsInt();
  }

  private ExecutionCommand execution(HostRoleCommandEntity task) {
    return task.getExecutionCommand() == null ? null : gson.fromJson(
        new String(task.getExecutionCommand().getCommand(), StandardCharsets.UTF_8), ExecutionCommand.class);
  }

  /** Insert the step before repository/config migration, never during progress polling. */
  public void addStage(List<UpgradeGroupHolder> groups, Plan plan) throws AmbariException {
    if (plan == null) {
      return;
    }
    for (int i = 0; i < groups.size(); i++) {
      if (groups.get(i).groupClass == UpdateStackGrouping.class) {
        UpgradeGroupHolder group = new UpgradeGroupHolder();
        group.name = "SWITCH_STACK_JAVA";
        group.title = plan.restore ? "Restore original stack JDKs" : "Activate validated stack JDKs";
        group.groupClass = Grouping.class;
        group.skippable = false;
        group.supportsAutoSkipOnFailure = false;
        ServerActionTask action = new ServerActionTask();
        action.setImplClass(SwitchJavaRuntimeAction.class.getName());
        TaskParameter parameter = new TaskParameter();
        parameter.name = PLAN;
        parameter.value = new GsonBuilder().serializeNulls().create().toJson(plan);
        action.parameters = new ArrayList<>(Collections.singletonList(parameter));
        group.items.add(new StageWrapper(StageWrapper.Type.SERVER_SIDE_ACTION, group.title,
            new TaskWrapper(null, null, Collections.emptySet(), action)));
        groups.add(i, group);
        return;
      }
    }
    throw new AmbariException("No safe repository switch boundary in the express upgrade pack");
  }

  public static class Plan {
    public Map<String, String> before;
    public Map<String, String> after;
    public long validationRequest;
    public int primaryMajor;
    public int secondaryMajor;
    public boolean restore;
  }
}
