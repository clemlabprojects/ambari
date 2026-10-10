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

package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OpenShift's user workload monitoring setting, as read from cluster-monitoring-config. */
class UserWorkloadMonitoringTest {

    @Test
    void readsEnableUserWorkload() {
        assertTrue(KubernetesService.userWorkloadEnabled("enableUserWorkload: true\nprometheusK8s:\n  retention: 7d\n"));
        assertFalse(KubernetesService.userWorkloadEnabled("enableUserWorkload: false\n"));
        assertFalse(KubernetesService.userWorkloadEnabled("prometheusK8s:\n  retention: 7d\n"), "never set: off");
        assertFalse(KubernetesService.userWorkloadEnabled(""));
        assertFalse(KubernetesService.userWorkloadEnabled(null));
        assertFalse(KubernetesService.userWorkloadEnabled(": not yaml ["));
    }
}
