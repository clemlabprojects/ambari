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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * "Retry reconcile" asks Flux to try a GitOps release again: its HelmRelease gets the reset, force and reconcile
 * requests, and only an operator may ask.
 */
class HelmResourceRetryReconcileTest {

    private final KubernetesService kubernetes = mock(KubernetesService.class);
    private final ReleaseMetadataService metadata = mock(ReleaseMetadataService.class);
    private HelmResource resource;

    @BeforeEach
    void setUp() throws Exception {
        ViewContext ctx = mock(ViewContext.class);
        when(ctx.getProperties()).thenReturn(Map.of(AuthHelper.ADMIN_USERS_PROPERTY, "lead",
                AuthHelper.OPERATOR_USERS_PROPERTY, "ops"));
        when(ctx.getUsername()).thenReturn("ops");
        when(ctx.getLoggedinUser()).thenReturn("ops");
        resource = new ObjenesisStd().newInstance(HelmResource.class);
        set("viewContext", ctx);
        set("authHelper", new AuthHelper(ctx));
        set("kubernetesService", kubernetes);
        set("releaseMetadataService", metadata);
    }

    private void set(String name, Object value) throws Exception {
        Field field = HelmResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    private void recordedAs(String deploymentMode) {
        K8sReleaseEntity entity = new K8sReleaseEntity();
        entity.setNamespace("analytics");
        entity.setReleaseName("om");
        entity.setDeploymentMode(deploymentMode);
        when(metadata.find("analytics", "om")).thenReturn(entity);
    }

    @Test
    void aGitOpsReleaseIsAskedToBeTriedAgainWithoutACommit() {
        recordedAs("FLUX_GITOPS");
        when(kubernetes.requestFluxReset("analytics", "om")).thenReturn("2026-10-13T08:00:00Z");

        Response response = resource.retryReleaseReconcile("analytics", "om");

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(true, body.get("requested"));
        assertEquals("2026-10-13T08:00:00Z", body.get("requestedAt"));
        verify(kubernetes).requestFluxReset("analytics", "om");
    }

    @Test
    void aReleaseFluxDoesNotManageIsRefused() {
        recordedAs("DIRECT_HELM");

        Response response = resource.retryReleaseReconcile("analytics", "om");

        assertEquals(400, response.getStatus());
        verify(kubernetes, never()).requestFluxReset(anyString(), anyString());
    }

    @Test
    void aReleaseKdpsDoesNotKnowIsRefused() {
        Response response = resource.retryReleaseReconcile("analytics", "unknown");

        assertEquals(400, response.getStatus());
        verify(kubernetes, never()).requestFluxReset(anyString(), anyString());
    }

    @Test
    void aHelmReleaseNotOnTheClusterIsReported() {
        recordedAs("FLUX_GITOPS");
        when(kubernetes.requestFluxReset("analytics", "om")).thenReturn(null);

        Response response = resource.retryReleaseReconcile("analytics", "om");

        assertEquals(404, response.getStatus());
    }

    @Test
    void aFailureOfTheClusterIsReportedWithItsMessage() {
        recordedAs("FLUX_GITOPS");
        when(kubernetes.requestFluxReset("analytics", "om")).thenThrow(new IllegalStateException("forbidden: patch"));

        Response response = resource.retryReleaseReconcile("analytics", "om");

        assertEquals(500, response.getStatus());
        assertEquals("forbidden: patch", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    void aViewerMayNotAsk() throws Exception {
        ViewContext viewer = mock(ViewContext.class);
        when(viewer.getProperties()).thenReturn(Map.of(AuthHelper.ADMIN_USERS_PROPERTY, "lead",
                AuthHelper.OPERATOR_USERS_PROPERTY, "ops"));
        when(viewer.getUsername()).thenReturn("guest");
        when(viewer.getLoggedinUser()).thenReturn("guest");
        set("authHelper", new AuthHelper(viewer));
        recordedAs("FLUX_GITOPS");

        Response response = resource.retryReleaseReconcile("analytics", "om");

        assertEquals(403, response.getStatus());
        verifyNoInteractions(kubernetes);
    }
}
