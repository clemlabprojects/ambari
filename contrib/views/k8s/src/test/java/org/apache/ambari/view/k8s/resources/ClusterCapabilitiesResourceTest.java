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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each cluster is answered with its own capabilities, however recently another was asked. */
class ClusterCapabilitiesResourceTest {

    @AfterEach
    void forget() {
        ClusterCapabilitiesResource.invalidate();
    }

    private static KubernetesService cluster(ClusterRef ref, boolean openShift) {
        KubernetesService service = mock(KubernetesService.class);
        when(service.cluster()).thenReturn(ref);
        when(service.isOpenShiftCluster()).thenReturn(openShift);
        when(service.crdExists(anyString())).thenReturn(false);
        when(service.listIngressClasses()).thenReturn(List.of());
        return service;
    }

    private static Object platformOf(KubernetesService service) {
        return ((Map<?, ?>) new ClusterCapabilitiesResource(service).getCapabilities().getEntity()).get("platform");
    }

    @Test
    void aKubernetesClusterIsNotAnsweredWithAnOpenShiftOne() {
        KubernetesService openShift = cluster(ClusterRef.DEFAULT, true);
        KubernetesService kubernetes = cluster(ClusterRef.of("k8s21"), false);

        assertEquals("openshift", platformOf(openShift));
        assertEquals("kubernetes", platformOf(kubernetes));
        // Each cached on its own: asked again, neither cluster is probed again.
        assertEquals("openshift", platformOf(openShift));
        assertEquals("kubernetes", platformOf(kubernetes));
        verify(openShift, times(1)).isOpenShiftCluster();
        verify(kubernetes, times(1)).isOpenShiftCluster();
    }
}
