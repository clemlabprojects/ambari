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

package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Asking Flux to read a HelmRelease's values Secrets again. */
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceFluxReconcileTest {

    static KubernetesClient client;
    static KubernetesMockServer server;

    private static final ResourceDefinitionContext HELM_RELEASES = new ResourceDefinitionContext.Builder()
            .withGroup("helm.toolkit.fluxcd.io").withVersion("v2").withKind("HelmRelease")
            .withPlural("helmreleases").withNamespaced(true).build();

    private KubernetesService service() {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
        return new KubernetesService(ctx, client, mock(HelmClient.class), true);
    }

    @Test
    void anExistingHelmReleaseIsAnnotatedAndKeepsItsOtherAnnotations() {
        GenericKubernetesResource hr = new GenericKubernetesResourceBuilder()
                .withApiVersion("helm.toolkit.fluxcd.io/v2").withKind("HelmRelease")
                .withNewMetadata().withName("trino").withNamespace("analytics")
                .addToAnnotations("team", "data").endMetadata()
                .build();
        client.genericKubernetesResources(HELM_RELEASES).inNamespace("analytics").resource(hr).create();

        assertTrue(service().requestFluxReconcile("analytics", "trino"));

        Map<String, String> annotations = client.genericKubernetesResources(HELM_RELEASES).inNamespace("analytics")
                .withName("trino").get().getMetadata().getAnnotations();
        assertNotNull(annotations.get("reconcile.fluxcd.io/requestedAt"));
        assertEquals("data", annotations.get("team"));
    }

    @Test
    void aHelmReleaseNotOnTheClusterIsReported() {
        assertFalse(service().requestFluxReconcile("analytics", "not-applied-yet"));
        assertNull(service().requestFluxReset("analytics", "not-applied-yet"));
    }

    @Test
    void aResetIsAskedWithTheReconcileRequestOfTheSameValue() {
        GenericKubernetesResource hr = new GenericKubernetesResourceBuilder()
                .withApiVersion("helm.toolkit.fluxcd.io/v2").withKind("HelmRelease")
                .withNewMetadata().withName("superset").withNamespace("analytics")
                .addToAnnotations("team", "data").endMetadata()
                .build();
        client.genericKubernetesResources(HELM_RELEASES).inNamespace("analytics").resource(hr).create();

        String resetAt = service().requestFluxReset("analytics", "superset");

        Map<String, String> annotations = client.genericKubernetesResources(HELM_RELEASES).inNamespace("analytics")
                .withName("superset").get().getMetadata().getAnnotations();
        assertNotNull(resetAt);
        assertEquals(resetAt, annotations.get("reconcile.fluxcd.io/resetAt"));
        assertEquals(resetAt, annotations.get("reconcile.fluxcd.io/requestedAt"),
                "Flux handles a reset only with a reconcile request of the same value");
        assertEquals("data", annotations.get("team"));
    }

    @Test
    void secretsAreListedByAllTheirLabels() {
        client.secrets().inNamespace("analytics").resource(new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("trino-kdps-values-1a2b3c4d").withNamespace("analytics")
                .addToLabels("managed-by", "ambari-k8s-view").addToLabels("ambari.clemlab.com/release", "trino")
                .endMetadata().build()).create();
        client.secrets().inNamespace("analytics").resource(new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("superset-kdps-values").withNamespace("analytics")
                .addToLabels("managed-by", "ambari-k8s-view").addToLabels("ambari.clemlab.com/release", "superset")
                .endMetadata().build()).create();

        assertEquals(java.util.List.of("trino-kdps-values-1a2b3c4d"), service().listSecretNames("analytics",
                Map.of("managed-by", "ambari-k8s-view", "ambari.clemlab.com/release", "trino")));
    }
}
