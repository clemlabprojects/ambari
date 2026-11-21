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
package org.apache.ambari.server.checks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.UpgradeJavaRuntime;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Advertises JDK requirements only when an express upgrade crosses a stack
 * runtime boundary. Later upgrades within the same runtime keep their paths.
 * Preliminary checks do not activate the selected runtimes. Upgrade creation
 * separately enforces successful host validation through {@link UpgradeJavaRuntime}.
 * Rolling upgrades retain the existing requirement for a configured secondary JDK.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.CONFIGURATION_WARNING, order = 1.0f,
    required = { UpgradeType.NON_ROLLING, UpgradeType.ROLLING })
public class SecondaryJavaHomeCheck extends AbstractCheckDescriptor {

  private static final Logger LOG = LoggerFactory.getLogger(SecondaryJavaHomeCheck.class);

  static final String PRIMARY_JAVA_KEY = "primary_java";

  private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?.*\"");
  private static final Pattern JAVA_VERSION_OUTPUT = Pattern.compile("(?:openjdk|java) version \"(\\d+)(?:\\.(\\d+))?.*\".*");

  @Inject
  private UpgradeJavaRuntime runtimes;

  /**
   * Default constructor.
   */
  public SecondaryJavaHomeCheck() {
    super(CheckDescription.SECONDARY_JAVA_HOME);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<CheckQualification> getQualifications() {
    return Collections.singletonList(new TargetVersionQualification());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request)
      throws AmbariException {
    if (request.getUpgradeType() == UpgradeType.NON_ROLLING) {
      JsonObject required = runtimes.transitionRequirements(
          clustersProvider.get().getCluster(request.getClusterName()), request.getTargetRepositoryVersion());
      if (required == null) {
        return;
      }
      JsonObject selection = required.deepCopy();
      selection.addProperty("primary_java_home", config.getJavaHome());
      selection.addProperty("secondary_java_home", StringUtils.defaultString(config.getSecondaryJavaHome()));
      prerequisiteCheck.getFailedDetail().add(gson.fromJson(selection, java.util.Map.class));
      // Selection happens after these preliminary checks. Upgrade creation separately
      // requires successful, recent host checks; a precheck bypass cannot skip them.
      return;
    }
    if (request.getUpgradeType() == UpgradeType.ROLLING) {
      // A rolling upgrade restarts the services one by one and cannot switch the stack JDKs (they are
      // server-level settings): the primary Java home must already be one the target stack accepts.
      JsonObject crossing = runtimes.transitionRequirements(
          clustersProvider.get().getCluster(request.getClusterName()), request.getTargetRepositoryVersion());
      if (crossing != null) {
        int fallback = UpgradeJavaRuntime.fallbackMajor(crossing);
        int minimum = fallback > 0 ? fallback : crossing.get("primary_java_major").getAsInt();
        Integer major = javaMajor(config.getJavaHome());
        if (major == null || major < minimum) {
          LOG.info("primary java.home {} is JDK {}: a rolling upgrade needs JDK {} or later", config.getJavaHome(), major, minimum);
          prerequisiteCheck.getFailedOn().add(request.getClusterName());
          prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
          prerequisiteCheck.setFailReason(getFailReason(PRIMARY_JAVA_KEY, prerequisiteCheck, request)
              .replace("{{home}}", StringUtils.defaultString(config.getJavaHome()))
              .replace("{{found}}", major == null ? "an unknown JDK" : "JDK " + major)
              .replace("{{minimum}}", String.valueOf(minimum)));
          return;
        }
      }
    }

    // The secondary JDK is a single server-level property, not a cluster config.
    if (StringUtils.isNotBlank(config.getSecondaryJavaHome())) {
      return;
    }

    LOG.info("secondary.java.home is not set while upgrading to a dual-JDK stack");

    prerequisiteCheck.getFailedOn().add(request.getClusterName());
    prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
    prerequisiteCheck.setFailReason(getFailReason(prerequisiteCheck, request));
  }

  /**
   * The JDK major version of a Java home, read from its "release" file (JAVA_VERSION="1.8.0_412", "17.0.13", ...).
   * The stack requires the same JDK path on every host, including the Ambari Server host.
   *
   * @return the major version, or {@code null} when it cannot be read
   */
  Integer javaMajor(String javaHome) {
    if (StringUtils.isBlank(javaHome)) {
      return null;
    }
    try {
      if (Files.isReadable(Paths.get(javaHome, "release"))) {
        for (String line : Files.readAllLines(Paths.get(javaHome, "release"), StandardCharsets.UTF_8)) {
          Integer major = major(JAVA_VERSION.matcher(line.trim()));
          if (major != null) {
            return major;
          }
        }
      }
      // distribution JDK 8 packages have no release file: ask the JVM
      File java = Paths.get(javaHome, "bin", "java").toFile();
      if (java.canExecute()) {
        Process process = new ProcessBuilder(java.getAbsolutePath(), "-version").redirectErrorStream(true).start();
        String output = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
        if (process.waitFor(20, TimeUnit.SECONDS)) {
          for (String line : output.split("\\R")) {
            Integer major = major(JAVA_VERSION_OUTPUT.matcher(line.trim()));
            if (major != null) {
              return major;
            }
          }
        } else {
          process.destroyForcibly();
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("Cannot read the JDK version of {}: {}", javaHome, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    LOG.warn("Cannot determine the JDK version of {}", javaHome);
    return null;
  }

  private static Integer major(Matcher matcher) {
    if (!matcher.matches()) {
      return null;
    }
    int first = Integer.parseInt(matcher.group(1));
    return first == 1 && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : first;
  }

  /**
   * Restricts express selection to source/target pairs crossing the boundary.
   * Rolling upgrades retain the target-only configured-secondary prerequisite.
   */
  final class TargetVersionQualification implements CheckQualification {
    @Override
    public boolean isApplicable(PrereqCheckRequest request) throws AmbariException {
      RepositoryVersionEntity targetRepositoryVersion = request.getTargetRepositoryVersion();
      if (null == targetRepositoryVersion) {
        return false;
      }

      if (request.getUpgradeType() == UpgradeType.NON_ROLLING) {
        return runtimes.transitionRequirements(
            clustersProvider.get().getCluster(request.getClusterName()), targetRepositoryVersion) != null;
      }
      return runtimes.requirements(targetRepositoryVersion) != null;
    }
  }
}
