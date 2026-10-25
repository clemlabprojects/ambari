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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.PrereqCheckRequest;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.stack.PrereqCheckStatus;
import org.apache.ambari.server.state.stack.PrerequisiteCheck;

import com.google.inject.Singleton;

/**
 * ODP (clemlab): fails while the deprecated LIVY2_SERVER component (ODP 1.0 legacy Livy, shared by Spark2 and Spark3)
 * is still installed. It is replaced by SPARK2_LIVY2_SERVER / SPARK3_LIVY2_SERVER and will be removed from the
 * stack; the operator migrates with resources/scripts/migrate_legacy_livy.py before upgrading.
 */
@Singleton
@UpgradeCheck(group = UpgradeCheckGroup.DEFAULT)
public class LegacyLivyCheck extends AbstractCheckDescriptor {

  static final String LEGACY_COMPONENT = "LIVY2_SERVER";
  static final List<String> SPARK_SERVICES = Arrays.asList("SPARK2", "SPARK3");

  public LegacyLivyCheck() {
    super(CheckDescription.LEGACY_LIVY_CHECK);
  }

  @Override
  public void perform(PrerequisiteCheck prerequisiteCheck, PrereqCheckRequest request) throws AmbariException {
    Cluster cluster = clustersProvider.get().getCluster(request.getClusterName());
    TreeSet<String> found = new TreeSet<>();
    Map<String, Service> services = cluster.getServices();
    for (String serviceName : SPARK_SERVICES) {
      Service service = services.get(serviceName);
      if (service == null) {
        continue;
      }
      ServiceComponent legacy = service.getServiceComponents().get(LEGACY_COMPONENT);
      if (legacy == null) {
        continue;
      }
      for (String host : legacy.getServiceComponentHosts().keySet()) {
        found.add(serviceName + "/" + LEGACY_COMPONENT + " on " + host);
      }
    }
    if (!found.isEmpty()) {
      prerequisiteCheck.getFailedOn().addAll(found);
      prerequisiteCheck.setStatus(PrereqCheckStatus.FAIL);
      prerequisiteCheck.setFailReason(String.format(getFailReason(prerequisiteCheck, request),
          String.join(", ", found)));
    }
  }
}
