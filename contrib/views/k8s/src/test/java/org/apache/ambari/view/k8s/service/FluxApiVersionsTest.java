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

import io.fabric8.kubernetes.api.model.APIGroupBuilder;
import io.fabric8.kubernetes.api.model.GroupVersionForDiscoveryBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.FluxApiVersions.Kind;
import org.apache.ambari.view.k8s.service.FluxApiVersions.Selection;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The version at which KDPS reads the Flux objects on the cluster: the one Flux 2.3 and later serve, which KDPS writes
 * to Git, else the one of an older Flux, as API discovery tells; and a cluster that serves neither is said to, rather
 * than every HelmRelease being "not found".
 */
@EnableKubernetesMockClient
class FluxApiVersionsTest {

    KubernetesClient client;
    KubernetesMockServer server;

    private void serves(String group, String... versions) {
        server.expect().get().withPath("/apis/" + group).andReturn(200, new APIGroupBuilder()
                .withName(group)
                .withVersions(Arrays.stream(versions).map(v -> new GroupVersionForDiscoveryBuilder()
                        .withGroupVersion(group + "/" + v).withVersion(v).build()).toList())
                .build()).always();
    }

    @Test
    void theVersionKdpsWritesIsUsedWhereverTheClusterServesIt() {
        Selection both = FluxApiVersions.select(Kind.HELM_RELEASE, List.of("v2", "v2beta2", "v2beta1"));
        assertEquals("v2", both.version());
        assertTrue(both.served());
        assertEquals("v1", FluxApiVersions.select(Kind.HELM_REPOSITORY, List.of("v1", "v1beta2")).version());
    }

    @Test
    void aFluxOlderThan23IsReadAtItsBetaVersions() {
        Selection helmRelease = FluxApiVersions.select(Kind.HELM_RELEASE, List.of("v2beta2", "v2beta1"));
        assertEquals("v2beta2", helmRelease.version());
        assertTrue(helmRelease.served());
        assertEquals("v1beta2", FluxApiVersions.select(Kind.HELM_REPOSITORY, List.of("v1beta2")).version());
    }

    @Test
    void aClusterWithoutTheFluxApiIsSaidToBeSo() {
        Selection none = FluxApiVersions.select(Kind.HELM_RELEASE, null);
        assertFalse(none.served());
        assertEquals("v2", none.version());
        assertTrue(none.problem().startsWith("The cluster does not serve the Flux HelmRelease API"
                + " (helm.toolkit.fluxcd.io): Flux is not installed"), none.problem());

        Selection other = FluxApiVersions.select(Kind.HELM_RELEASE, List.of("v3"));
        assertFalse(other.served());
        assertTrue(other.problem().contains("only at helm.toolkit.fluxcd.io v3, not at v2 (Flux 2.3 or later) nor"
                + " v2beta2"), other.problem());
    }

    @Test
    void anUnknownAnswerFromDiscoveryKeepsTheCurrentVersion() {
        Selection unknown = FluxApiVersions.select(Kind.HELM_RELEASE, List.of());
        assertEquals("v2", unknown.version());
        assertTrue(unknown.served());
        assertEquals("v2", FluxApiVersions.resolve(null, Kind.HELM_RELEASE).version(), "no cluster to ask");
    }

    @Test
    void theVersionIsReadFromApiDiscovery() {
        serves("helm.toolkit.fluxcd.io", "v2beta2", "v2beta1");
        serves("source.toolkit.fluxcd.io", "v1", "v1beta2");

        Selection helmRelease = FluxApiVersions.resolve(client, Kind.HELM_RELEASE);
        CustomResourceDefinitionContext context = helmRelease.context();

        assertEquals("v2beta2", helmRelease.version());
        assertEquals("helm.toolkit.fluxcd.io", context.getGroup());
        assertEquals("v2beta2", context.getVersion());
        assertEquals("helmreleases", context.getPlural());
        assertEquals("HelmRelease", context.getKind());
        assertEquals("v1", FluxApiVersions.resolve(client, Kind.HELM_REPOSITORY).version());
    }

    @Test
    void aGroupTheClusterDoesNotServeIsReported() {
        Selection helmRelease = FluxApiVersions.resolve(client, Kind.HELM_RELEASE);

        assertFalse(helmRelease.served(), "discovery answers 404");
        assertEquals("v2", helmRelease.version());
    }

    @Test
    void aHelmReleaseReadWhereFluxIsNotServedIsAnApiProblemNotAMissingRelease() {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                System.getProperty("java.io.tmpdir") + "/k8s-ut"));
        KubernetesService service = new KubernetesService(ctx, client, mock(HelmClient.class), true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.getFluxHelmRelease("analytics", "trino"));
        assertTrue(e.getMessage().startsWith("The cluster does not serve the Flux HelmRelease API"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> service.requestFluxReset("analytics", "trino"));
    }
}
