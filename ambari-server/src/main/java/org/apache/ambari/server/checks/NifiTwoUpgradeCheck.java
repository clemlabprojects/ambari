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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;
import org.apache.ambari.server.state.stack.upgrade.UpgradeType;
import org.apache.ambari.server.utils.VersionUtils;

import com.google.common.collect.Sets;
import com.google.inject.Singleton;

/**
 * ODP 1.3.2.0 ships NiFi 2, which only starts from a NiFi 1 cluster prepared on its current release: NiFi 1.27 or
 * later (ODP 1.3.1.0), a sensitive properties key of at least 12 characters and a NIFI_* algorithm already applied
 * to the flow (NiFi re-encrypts the flow when NiFi restarts), no NiFi Certificate Authority (NiFi 2 has no TLS
 * Toolkit), and a stop of every node (a NiFi 2 node cannot join a NiFi 1 cluster).
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.DEFAULT)
public class NifiTwoUpgradeCheck extends AbstractCheckDescriptor {

  static final String SERVICE = "NIFI";
  static final String NIFI_MASTER = "NIFI_MASTER";
  static final String NIFI_CA = "NIFI_CA";
  /** first ODP release with NiFi 2 */
  static final String NIFI_2_RELEASE = "1.3.2.0";
  /** first ODP release with NiFi 1.27 or later (NiFi 1.28.1) */
  static final String NIFI_1_28_RELEASE = "1.3.1.0";
  static final String KEY = "nifi.sensitive.props.key";
  static final String ALGORITHM = "nifi.sensitive.props.algorithm";
  static final int MIN_KEY_LENGTH = 12;
  /** NiFi 1 uses this algorithm when the property is empty */
  static final String NIFI_1_DEFAULT_ALGORITHM = "PBEWITHMD5AND256BITAES-CBC-OPENSSL";

  public NifiTwoUpgradeCheck() {
    super(CheckDescription.NIFI_2_UPGRADE);
  }

  @Override
  public Set<String> getApplicableServices() {
    return Sets.newHashSet(SERVICE);
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    Service nifi = cluster.getServices().get(SERVICE);
    RepositoryVersionEntity target = request.getTargetRepositoryVersion();
    if (nifi == null || target == null || nifi.getDesiredRepositoryVersion() == null) {
      return;
    }
    String source = nifi.getDesiredRepositoryVersion().getVersion();
    if (atLeast(source, NIFI_2_RELEASE) || !atLeast(target.getVersion(), NIFI_2_RELEASE)) {
      return;
    }

    List<String> reasons = new ArrayList<>();
    if (request.getUpgradeType() == UpgradeType.ROLLING) {
      reasons.add("A NiFi 2 node cannot join a NiFi 1 cluster: use Express mode.");
    }
    if (!atLeast(source, NIFI_1_28_RELEASE)) {
      reasons.add(String.format("NiFi 2 only migrates the flows of NiFi 1.27 and later: upgrade ODP %s to ODP %s "
          + "first.", source, NIFI_1_28_RELEASE));
    }
    Map<String, ServiceComponent> components = nifi.getServiceComponents();
    ServiceComponent ca = components.get(NIFI_CA);
    if (ca != null && !ca.getServiceComponentHosts().isEmpty()) {
      reasons.add("NiFi 2 has no TLS Toolkit: configure NiFi TLS with your own keystore and truststore "
          + "(nifi-ambari-ssl-config), restart NiFi, then delete the NiFi Certificate Authority component.");
    }
    reasons.addAll(sensitivePropertiesReasons(cluster));
    TreeSet<String> pendingRestart = new TreeSet<>();
    ServiceComponent master = components.get(NIFI_MASTER);
    if (master != null) {
      for (ServiceComponentHost host : master.getServiceComponentHosts().values()) {
        if (host.isRestartRequired()) {
          pendingRestart.add(host.getHostName());
        }
      }
    }
    if (!pendingRestart.isEmpty()) {
      reasons.add(String.format("Restart NiFi on %s: NiFi applies a new sensitive properties key or algorithm to "
          + "the flow when it restarts.", String.join(", ", pendingRestart)));
    }

    if (!reasons.isEmpty()) {
      prerequisiteCheck.getFailedOn().add(SERVICE);
      prerequisiteCheck.getFailedOn().addAll(pendingRestart);
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(String.format(getFailReason(prerequisiteCheck, request), String.join(" ", reasons)));
    }
  }

  private List<String> sensitivePropertiesReasons(Cluster cluster) {
    List<String> reasons = new ArrayList<>();
    Config ambariConfig = cluster.getDesiredConfigByType("nifi-ambari-config");
    String key = ambariConfig == null ? null : ambariConfig.getProperties().get(KEY);
    if (key == null || key.length() < MIN_KEY_LENGTH) {
      reasons.add(String.format("Set a sensitive properties key (%s in nifi-ambari-config) of at least %d "
          + "characters and restart NiFi: NiFi 2 refuses shorter keys.", KEY, MIN_KEY_LENGTH));
    }
    Config properties = cluster.getDesiredConfigByType("nifi-properties");
    String algorithm = properties == null ? null : properties.getProperties().get(ALGORITHM);
    if (algorithm == null || algorithm.trim().isEmpty()) {
      algorithm = NIFI_1_DEFAULT_ALGORITHM;
    }
    if (!algorithm.trim().startsWith("NIFI_")) {
      reasons.add(String.format("Set %s (nifi-properties) to NIFI_PBKDF2_AES_GCM_256 and restart NiFi: NiFi 2 "
          + "does not support %s.", ALGORITHM, algorithm.trim()));
    }
    return reasons;
  }

  private static boolean atLeast(String version, String release) {
    return VersionUtils.compareVersions(version, release, 4) >= 0;
  }
}
