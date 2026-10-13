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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.utils.TestCertificates;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The truststore project is the view's own namespace, not a workload one: limiting the projects releases are deployed
 * to with {@code k8s.view.allowed.namespaces} does not stop a release from trusting the default truststores.
 */
@EnableKubernetesMockClient(crud = true)
class TrustBundleAllowedNamespacesTest {

    private static final String PKI = "ambari-pki";
    private static final String NS = "analytics";

    KubernetesClient client;

    private KubernetesService k8s;
    private TruststoreRegistryService registry;
    private TrustBundleService trustBundle;
    private String corporateCaPem;

    @BeforeEach
    void setUp() {
        corporateCaPem = TestCertificates.caPem("CN=Corporate Root CA");
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        doReturn("allowed-namespaces-truststore-test").when(ctx).getInstanceName();
        doReturn(null).when(ctx).getAmbariProperty(anyString());
        // The workload projects only; the truststore project is not listed.
        doReturn(Map.of("k8s.view.allowed.namespaces", NS,
                "k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut")).when(ctx).getProperties();
        k8s = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
        WebHookConfigurationService webhook = mock(WebHookConfigurationService.class);
        doThrow(new IllegalStateException("no internal CA in this test")).when(webhook).ensureAmbariCertificateAuthority();
        registry = new TruststoreRegistryService(ctx, k8s, new PkiNamespaces(ctx, Map::of));
        trustBundle = new TrustBundleService(ctx, k8s, webhook, registry);

        Secret defaultTruststore = new SecretBuilder().withNewMetadata().withNamespace(PKI).withName("corp-ca-truststore")
                .addToLabels(TruststoreRegistryService.LABEL_RESOURCE_TYPE, TruststoreRegistryService.RESOURCE_TYPE_TRUSTSTORE)
                .addToAnnotations(TruststoreRegistryService.ANNOTATION_TRUST_DEFAULT, "true")
                .endMetadata()
                .addToData("ca.crt", Base64.getEncoder().encodeToString(corporateCaPem.getBytes(StandardCharsets.UTF_8)))
                .build();
        client.secrets().inNamespace(PKI).resource(defaultTruststore).create();
    }

    @Test
    void defaultTruststoresAreListedOutsideTheAllowedNamespaces() {
        assertEquals(List.of(corporateCaPem), registry.defaultTruststorePems());
    }

    @Test
    void aReleaseOfAnAllowedNamespaceTrustsTheDefaultTruststores() {
        TrustBundleService.ProvisionResult result = trustBundle.provisionForDeploy(NS, "superset", null, null);

        assertTrue(result.provisioned());
        assertEquals(1, result.caCount());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("default truststores")),
                result.warnings().toString());
        Secret written = client.secrets().inNamespace(NS).withName("superset-truststore").get();
        assertNotNull(written);
        String bundle = new String(Base64.getDecoder().decode(written.getData().get("ca.crt")), StandardCharsets.UTF_8);
        String body = corporateCaPem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
        assertTrue(bundle.replaceAll("\\s", "").contains(body));
    }
}
