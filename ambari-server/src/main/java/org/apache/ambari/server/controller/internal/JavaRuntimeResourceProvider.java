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
package org.apache.ambari.server.controller.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.agent.stomp.MetadataHolder;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.AmbariManagementControllerImpl;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.UpgradeJavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * Persists operator-selected stack JDKs after checking the agent-produced proof.
 * This is not an upgrade action: running services are marked for restart, never
 * stopped here. The Ambari Server JVM (ambari.java.home) is not changed.
 */
@StaticallyInject
public class JavaRuntimeResourceProvider extends AbstractControllerResourceProvider {
  private static final String PREFIX = "JavaRuntime/";
  private static final String CLUSTER = PREFIX + "cluster_name";
  private static final String NAME = PREFIX + "name";
  private static final Set<String> KEYS = new HashSet<>(Arrays.asList(CLUSTER, NAME));
  private static final Set<String> PROPERTIES = new HashSet<>(KEYS);
  private static final Map<Resource.Type, String> KEY_MAP = new HashMap<>();
  private static final Logger LOG = LoggerFactory.getLogger(JavaRuntimeResourceProvider.class);

  @Inject private static UpgradeJavaRuntime runtimes;
  @Inject private static Configuration configuration;
  // Do not initialize the controller and persistence services during static injection.
  @Inject private static Provider<MetadataHolder> metadata;

  static {
    for (String property : Arrays.asList("primary_java_home", "secondary_java_home", "java_home",
        "primary_java_major", "secondary_java_major", "min_version", "validation_request_id",
        "expected_java_home", "expected_primary_java_home", "expected_secondary_java_home")) {
      PROPERTIES.add(PREFIX + property);
    }
    KEY_MAP.put(Resource.Type.Cluster, CLUSTER);
    KEY_MAP.put(Resource.Type.JavaRuntime, NAME);
  }

  public JavaRuntimeResourceProvider(AmbariManagementController controller) {
    super(Resource.Type.JavaRuntime, PROPERTIES, KEY_MAP, controller);
  }

  @Override
  public Set<Resource> getResources(Request request, Predicate predicate) throws SystemException, NoSuchResourceException {
    Map<String, Object> identity = single(getPropertyMaps(predicate));
    try {
      Cluster cluster = cluster(identity);
      JsonObject required = runtimes.administrationRequirements(cluster);
      Resource resource = new ResourceImpl(Resource.Type.JavaRuntime);
      resource.setProperty(CLUSTER, cluster.getClusterName());
      resource.setProperty(NAME, "stack");
      resource.setProperty(PREFIX + "java_home", configuration.getJavaHome());
      resource.setProperty(PREFIX + "primary_java_home", configuration.getStackJavaHome());
      resource.setProperty(PREFIX + "secondary_java_home", configuration.getSecondaryJavaHome());
      for (String property : Arrays.asList("min_version", "primary_java_major", "secondary_java_major")) {
        resource.setProperty(PREFIX + property, property.endsWith("_major")
            ? required.get(property).getAsInt() : required.get(property).getAsString());
      }
      return Collections.singleton(resource);
    } catch (AmbariException e) {
      throw new SystemException(e.getMessage(), e);
    }
  }

  @Override
  public RequestStatus updateResources(Request request, Predicate predicate) throws SystemException, NoSuchResourceException {
    Map<String, Object> values = new HashMap<>(single(request.getProperties()));
    // The URL owns the identity; a request body must not redirect a global update.
    values.putAll(single(getPropertyMaps(predicate)));
    try {
      Cluster cluster = cluster(values);
      // Serialize administrative writers and reject a dialog opened against older paths.
      synchronized (configuration) {
        UpgradeJavaRuntime.Plan plan = runtimes.administrationPlan(cluster,
            values.get(PREFIX + "primary_java_home"), values.get(PREFIX + "secondary_java_home"),
            values.get(PREFIX + "validation_request_id"));
        Map<String, String> expected = new HashMap<>();
        expected.put(Configuration.JAVA_HOME.getKey(), (String) values.get(PREFIX + "expected_java_home"));
        expected.put(Configuration.STACK_JAVA_HOME.getKey(), (String) values.get(PREFIX + "expected_primary_java_home"));
        expected.put(Configuration.SECONDARY_JAVA_HOME.getKey(), (String) values.get(PREFIX + "expected_secondary_java_home"));
        if (!runtimes.currentHomes().equals(expected)) {
          throw new AmbariException("Java homes changed since this dialog opened. Reopen it and validate again.");
        }
        if (!expected.equals(plan.after)) {
          // Mark first: a publication/persistence failure must not hide the need to restart.
          for (Service service : cluster.getServices().values()) {
            for (ServiceComponent component : service.getServiceComponents().values()) {
              if (component.isVersionAdvertised()) {
                for (ServiceComponentHost host : component.getServiceComponentHosts().values()) {
                  host.setRestartRequired(true);
                }
              }
            }
          }
        }
        configuration.updateStackJavaHomes(plan.after);
        metadata.get().updateData(((AmbariManagementControllerImpl) getManagementController()).getClustersMetadata());
        LOG.info("Administrative stack JDK update for {}: {} -> {}", cluster.getClusterName(), expected, plan.after);
      }
      notifyUpdate(Resource.Type.JavaRuntime, request, predicate);
      return getRequestStatus(null);
    } catch (Exception e) {
      throw new SystemException(e.getMessage(), e);
    }
  }

  private Cluster cluster(Map<String, Object> values) throws AmbariException, NoSuchResourceException {
    runtimes.requireGlobalAccess();
    if (!"stack".equals(values.get(NAME)) || values.get(CLUSTER) == null) {
      throw new NoSuchResourceException("Specify the cluster's stack Java runtime");
    }
    return getManagementController().getClusters().getCluster(values.get(CLUSTER).toString());
  }

  private Map<String, Object> single(Set<Map<String, Object>> values) {
    if (values.size() != 1) {
      throw new IllegalArgumentException("Exactly one stack runtime is required");
    }
    return values.iterator().next();
  }

  @Override
  public RequestStatus createResources(Request request) {
    throw new UnsupportedOperationException("Use PUT to update the stack Java runtime");
  }

  @Override
  public RequestStatus deleteResources(Request request, Predicate predicate) {
    throw new UnsupportedOperationException("The stack Java runtime cannot be deleted");
  }

  @Override
  protected Set<String> getPKPropertyIds() {
    return KEYS;
  }
}
