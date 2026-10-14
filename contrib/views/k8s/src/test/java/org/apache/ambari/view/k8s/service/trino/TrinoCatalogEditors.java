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
package org.apache.ambari.view.k8s.service.trino;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Live catalog editors for the tests of the REST resources, which have no coordinator to reach: the release resolves
 * to the target given, and the statements go to the client given. Flux has applied what Git holds for the release.
 */
public final class TrinoCatalogEditors {

    private TrinoCatalogEditors() {
    }

    /**
     * @param target   the release, as resolution would find it
     * @param client   where the statements go
     * @param redeploy how the catalogs of a release deployed with Flux GitOps are committed
     */
    public static TrinoCatalogRuntimeService on(TrinoCatalogRuntimeService.Target target, TrinoStatementClient client,
                                                TrinoCatalogRuntimeService.GitOpsRedeploy redeploy) {
        KubernetesService kubernetes = mock(KubernetesService.class);
        doReturn(appliedHelmRelease(target.metadata == null ? null : target.metadata.getGitSpecDigest(), "True",
                "UpgradeSucceeded")).when(kubernetes).getFluxHelmRelease(target.namespace, target.release);
        TrinoCatalogRuntimeService service = spy(new TrinoCatalogRuntimeService(null, kubernetes, redeploy));
        doReturn(target).when(service).resolve(target.namespace, target.release);
        doReturn(client).when(service).clientFor(any());
        return service;
    }

    /**
     * A HelmRelease as Flux reports it once it has reconciled its latest generation.
     *
     * @param digest the spec digest annotation of the version applied, {@code null} for none
     * @param ready  the status of its Ready condition
     * @param reason the reason of its Ready condition
     */
    public static GenericKubernetesResource appliedHelmRelease(String digest, String ready, String reason) {
        Map<String, String> annotations = new LinkedHashMap<>();
        if (digest != null) {
            annotations.put(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION, digest);
        }
        GenericKubernetesResource hr = new GenericKubernetesResource();
        hr.setMetadata(new ObjectMetaBuilder().withGeneration(2L).withAnnotations(annotations).build());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("observedGeneration", 2);
        status.put("conditions", List.of(Map.of("type", "Ready", "status", ready, "reason", reason,
                "message", "Helm upgrade " + ("True".equals(ready) ? "succeeded" : "failed"),
                "observedGeneration", 2)));
        hr.setAdditionalProperty("status", status);
        return hr;
    }
}
