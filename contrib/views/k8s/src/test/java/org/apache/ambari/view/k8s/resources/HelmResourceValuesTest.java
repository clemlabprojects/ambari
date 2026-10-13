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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /helm/{namespace}/{release}/values serves the values of the releases KDPS deployed only, to users who may
 * redeploy them, and never their secrets.
 */
class HelmResourceValuesTest {

    private static final String NS = "analytics";

    private final KubernetesService kubernetes = mock(KubernetesService.class);
    private final ReleaseMetadataService releaseMetadata = mock(ReleaseMetadataService.class);
    private final AuthHelper auth = mock(AuthHelper.class);
    private HelmResource resource;

    @BeforeEach
    void setUp() throws Exception {
        resource = new ObjenesisStd().newInstance(HelmResource.class);
        set("viewContext", mock(ViewContext.class));
        set("kubernetesService", kubernetes);
        set("releaseMetadataService", releaseMetadata);
        set("authHelper", auth);
        when(kubernetes.listKdpsManagedReleaseKeys(any())).thenReturn(Set.of());
    }

    private void set(String field, Object value) throws Exception {
        Field f = HelmResource.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(resource, value);
    }

    private void recorded(String release, String serviceKey) {
        K8sReleaseEntity meta = new K8sReleaseEntity();
        meta.setId(K8sReleaseEntity.idOf(NS, release));
        meta.setNamespace(NS);
        meta.setReleaseName(release);
        meta.setServiceKey(serviceKey);
        meta.setManagedByUi(true);
        when(releaseMetadata.find(NS, release)).thenReturn(meta);
    }

    @Test
    void theSecretsOfAReleaseKdpsDeployedAreHidden() throws Exception {
        recorded("superset", "SUPERSET");
        when(kubernetes.getHelmReleaseValues(NS, "superset")).thenReturn(new ObjectMapper().readValue(
                "{\"extraSecretEnv\":{\"SUPERSET_SECRET_KEY\":\"Superset-Key-764\"},"
                        + "\"hiveDb\":{\"ldapPassword\":\"Ldap-Pass-764\"},\"replicaCount\":2}", Map.class));

        Response r = resource.getValues(NS, "superset");

        assertEquals(200, r.getStatus());
        String body = new ObjectMapper().writeValueAsString(r.getEntity());
        assertFalse(body.contains("Superset-Key-764"), "the SECRET_KEY field of its definition reads it: " + body);
        assertFalse(body.contains("Ldap-Pass-764"), body);
        assertTrue(body.contains("\"replicaCount\":2"), body);
    }

    @Test
    void aReleaseLabelledByKdpsIsServedToo() throws Exception {
        when(kubernetes.listKdpsManagedReleaseKeys(Set.of(NS))).thenReturn(Set.of(NS + "/trino"));
        when(kubernetes.getHelmReleaseValues(NS, "trino")).thenReturn(Map.of("server", Map.of("workers", 2)));

        assertEquals(200, resource.getValues(NS, "trino").getStatus());
    }

    @Test
    void aReleaseKdpsDidNotDeployIsNotServed() throws Exception {
        Response r = resource.getValues("kube-system", "cilium");

        assertEquals(404, r.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) r.getEntity()).get("error")).contains("not deployed by KDPS"));
        verify(kubernetes, never()).getHelmReleaseValues(anyString(), anyString());
    }

    @Test
    void onlyUsersWhoMayRedeployReadThem() throws Exception {
        doThrow(new ForbiddenException("read-only")).when(auth).checkWritePermission();

        assertThrows(ForbiddenException.class, () -> resource.getValues(NS, "superset"));
        verify(kubernetes, never()).getHelmReleaseValues(anyString(), anyString());
    }
}
