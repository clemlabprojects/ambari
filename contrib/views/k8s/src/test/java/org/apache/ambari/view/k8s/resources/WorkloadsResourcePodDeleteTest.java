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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.BadRequestException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deleting a pod from the workloads page leaves the shutdown time to the pod; a grace period of 0 force-deletes it
 * and is only accepted together with {@code force=true}.
 */
class WorkloadsResourcePodDeleteTest {

  private static final String INSTANCE = "workloads-pod-delete-test";

  private final KubernetesService k8s = mock(KubernetesService.class);

  @BeforeEach
  void registerKubernetesService() throws Exception {
    instances().put(INSTANCE, k8s);
  }

  @AfterEach
  void unregisterKubernetesService() throws Exception {
    instances().remove(INSTANCE);
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, KubernetesService> instances() throws Exception {
    Field f = KubernetesService.class.getDeclaredField("INSTANCES");
    f.setAccessible(true);
    return (ConcurrentMap<String, KubernetesService>) f.get(null);
  }

  private static WorkloadsResource operator() {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("view.operator.users", "ops"));
    when(ctx.getUsername()).thenReturn("ops");
    when(ctx.getLoggedinUser()).thenReturn("ops");
    when(ctx.getInstanceName()).thenReturn(INSTANCE);
    return new WorkloadsResource(ctx, null);
  }

  @Test
  void aDeleteWithoutAGracePeriodLeavesItToThePod() {
    operator().deletePod("analytics", "db-0", null, false);

    verify(k8s).deletePod("analytics", "db-0", null);
  }

  @Test
  void aGracePeriodOfZeroWithoutForceIsRefused() {
    BadRequestException e = assertThrows(BadRequestException.class,
        () -> operator().deletePod("analytics", "db-0", 0, false));

    assertEquals(400, e.getResponse().getStatus());
    verifyNoInteractions(k8s);
  }

  @Test
  void aGracePeriodOfZeroWithForceIsPassedOn() {
    operator().deletePod("analytics", "db-0", 0, true);

    verify(k8s).deletePod("analytics", "db-0", 0);
  }

  @Test
  void aPositiveGracePeriodNeedsNoForce() {
    operator().deletePod("analytics", "db-0", 60, false);

    verify(k8s).deletePod("analytics", "db-0", 60);
  }
}
