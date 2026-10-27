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

import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.cluster.ClusterRef;
import org.apache.ambari.view.k8s.service.cluster.ClusterRegistry;
import org.apache.ambari.view.k8s.service.cluster.KubernetesCluster;
import org.apache.ambari.view.k8s.service.cluster.ProtectedClusters;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An uninstall, a rollback or an upgrade of a release on a protected cluster needs the cluster's name, typed by the
 * operator; without it, or with another one, nothing is changed. Another cluster asks for nothing.
 */
class HelmResourceProtectedClusterTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private final FluxGitOpsBackend flux = mock(FluxGitOpsBackend.class);
    private final CommandService commandService = mock(CommandService.class);
    private final ReleaseMetadataService releaseMetadata = mock(ReleaseMetadataService.class);

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("protected", workDir).as("lead");
        when(view.context.getViewName()).thenReturn("K8S-VIEW");
        org.apache.ambari.view.ViewDefinition definition = mock(org.apache.ambari.view.ViewDefinition.class);
        when(definition.getVersion()).thenReturn("1.0.0.10");
        when(view.context.getViewDefinition()).thenReturn(definition);
        ClusterRegistry clusters = new ClusterRegistry(new InstanceSettings(view.context));
        clusters.save(new KubernetesCluster("prod", "Production", "", true));
        clusters.save(new KubernetesCluster("dev", "Development", "", false));
    }

    /** The resource of one request on {@code cluster}, the name typed being {@code confirm}. */
    private HelmResource resource(String cluster, String confirm) throws Exception {
        HelmResource resource = new ObjenesisStd().newInstance(HelmResource.class);
        set(resource, "viewContext", view.context);
        set(resource, "cluster", ClusterRef.of(cluster));
        set(resource, "confirmCluster", confirm);
        set(resource, "fluxGitOpsBackend", flux);
        set(resource, "commandService", commandService);
        set(resource, "releaseMetadataService", releaseMetadata);
        set(resource, "authHelper", mock(AuthHelper.class));
        return resource;
    }

    private static void set(HelmResource resource, String field, Object value) throws Exception {
        Field f = HelmResource.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(resource, value);
    }

    private static void assertRefused(Response response, String action) {
        assertEquals(409, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(ProtectedClusters.NotConfirmedException.CODE, body.get("code"));
        assertEquals(Map.of("id", "prod", "name", "Production"), body.get("cluster"));
        assertTrue(String.valueOf(body.get("error")).contains("type its name to confirm " + action), body.toString());
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        return headers;
    }

    private static UriInfo uri() {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getBaseUri()).thenReturn(URI.create("http://ambari:8080/api/v1/views/K8S-VIEW/"));
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uri;
    }

    @Test
    void withoutTheClustersNameNothingIsChanged() throws Exception {
        for (String typed : new String[] {null, "", "production", "dev"}) {
            HelmResource unconfirmed = resource("prod", typed);

            assertRefused(unconfirmed.uninstall(NS, RELEASE, null, null, null, uri(), headers()),
                    "the uninstall of analytics/trino");
            assertRefused(unconfirmed.rollbackReleaseToRevision(NS, RELEASE, 2), "the rollback of analytics/trino");
            assertRefused(unconfirmed.rollback(NS, RELEASE, 2), "the rollback of analytics/trino");
            assertRefused(unconfirmed.upgradeReleaseChart(NS, RELEASE, "2.0", headers(), uri()),
                    "the upgrade of analytics/trino");
        }
        Response mistyped = resource("prod", "production").uninstall(NS, RELEASE, null, null, null, uri(), headers());
        assertTrue(String.valueOf(((Map<?, ?>) mistyped.getEntity()).get("error"))
                .startsWith("The name typed is not the cluster's."));
        verifyNoInteractions(flux);
        verify(commandService, never()).destroyViaBackend(anyString(), anyString(), any());
        verify(commandService, never()).submitReleaseUpgrade(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void theClustersNameOrIdConfirmsTheAction() throws Exception {
        when(commandService.submitReleaseUpgrade(eq(NS), eq(RELEASE), eq("2.0"), any(), any())).thenReturn("op-1");

        assertEquals(202, resource("prod", " Production ").upgradeReleaseChart(NS, RELEASE, "2.0", headers(), uri())
                .getStatus());
        assertEquals(200, resource("prod", "prod").uninstall(NS, RELEASE, null, null, null, uri(), headers())
                .getStatus());
        verify(commandService).destroyViaBackend(NS, RELEASE, null);
    }

    @Test
    void anotherClusterAsksForNothing() throws Exception {
        when(commandService.submitReleaseUpgrade(eq(NS), eq(RELEASE), eq("2.0"), any(), any())).thenReturn("op-1");

        assertEquals(202, resource("dev", null).upgradeReleaseChart(NS, RELEASE, "2.0", headers(), uri()).getStatus());
        assertEquals(202, resource("default", null).upgradeReleaseChart(NS, RELEASE, "2.0", headers(), uri())
                .getStatus());
    }
}
