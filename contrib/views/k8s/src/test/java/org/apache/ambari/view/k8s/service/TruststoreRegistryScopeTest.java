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

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.utils.TestCertificates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Shared and project truststores of the Truststores tab: where they are written, how the listing marks them, and that
 * neither replaces a release's own truststore.
 */
@EnableKubernetesMockClient(crud = true)
class TruststoreRegistryScopeTest {

    static KubernetesClient client;

    private static final String SHARED = "ambari-pki";
    private static final String PROJECT = "analytics";

    private static String caPem;

    private TruststoreRegistryService registry;

    @BeforeEach
    void setUp() {
        if (caPem == null) {
            caPem = TestCertificates.caPem("CN=Partner Root CA");
        }
        client.secrets().inNamespace(SHARED).delete();
        client.secrets().inNamespace(PROJECT).delete();
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(PROJECT).endMetadata().build())
                .createOr(existing -> existing.update());
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("test-instance");
        when(ctx.getUsername()).thenReturn("operator1");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                System.getProperty("java.io.tmpdir") + "/k8s-truststore-scope-ut"));
        KubernetesService kubernetes = spy(new KubernetesService(ctx, client, mock(HelmClient.class), true));
        registry = new TruststoreRegistryService(ctx, kubernetes, new PkiNamespaces(ctx, Map::of));
    }

    @Test
    void aProjectTruststoreIsWrittenInItsProject() {
        Map<String, Object> created = registry.create(PROJECT, "partner-ca", caPem, true, "Partner LDAPS");

        assertEquals(PROJECT, created.get("namespace"));
        assertEquals(TruststoreRegistryService.SCOPE_PROJECT, created.get("scope"));
        Secret secret = client.secrets().inNamespace(PROJECT).withName("partner-ca-truststore").get();
        assertNotNull(secret);
        assertTrue(TruststoreRegistryService.isManaged(secret));
        assertEquals("true", secret.getMetadata().getAnnotations().get(TruststoreRegistryService.ANNOTATION_TRUST_DEFAULT));
        assertNull(client.secrets().inNamespace(SHARED).withName("partner-ca-truststore").get());
    }

    @Test
    void withoutAProjectOrWithTheSharedOneTheTruststoreIsShared() {
        assertEquals(TruststoreRegistryService.SCOPE_SHARED,
                registry.create(null, "corp-ca", caPem, false, null).get("scope"));
        assertEquals(TruststoreRegistryService.SCOPE_SHARED,
                registry.create(SHARED, "corp-ca-2", caPem, false, null).get("scope"));
        assertNotNull(client.secrets().inNamespace(SHARED).withName("corp-ca-truststore").get());
        assertNotNull(client.secrets().inNamespace(SHARED).withName("corp-ca-2-truststore").get());
    }

    @Test
    void aProjectTruststoreNeverReplacesAReleaseTruststore() {
        client.secrets().inNamespace(PROJECT).resource(new SecretBuilder().withNewMetadata().withName("trino-truststore")
                .addToLabels(KdpsReleaseObjects.LABEL_RELEASE, "trino").endMetadata()
                .addToStringData("ca.crt", caPem).build()).create();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.create(PROJECT, "trino", caPem, false, null));

        assertTrue(refused.getMessage().contains("was not created in the Truststores tab"), refused.getMessage());
        Secret kept = client.secrets().inNamespace(PROJECT).withName("trino-truststore").get();
        assertFalse(TruststoreRegistryService.isManaged(kept), "the release truststore is untouched");
    }

    @Test
    void anInvalidProjectNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> registry.create("Not A Project", "x", caPem, false, null));
    }

    @Test
    void theListingMarksTheScopeOfTabTruststoresOnly() {
        List<KubernetesService.TruststoreSummary> listed = new ArrayList<>(List.of(
                summary(SHARED, "corp-ca-truststore", true),
                summary(PROJECT, "partner-ca-truststore", true),
                summary(PROJECT, "trino-truststore", false)));

        registry.markManaged(listed);

        assertEquals(TruststoreRegistryService.SCOPE_SHARED, listed.get(0).scope);
        assertEquals(TruststoreRegistryService.SCOPE_PROJECT, listed.get(1).scope);
        assertTrue(listed.get(1).managed, "a project truststore stays selectable");
        assertNull(listed.get(2).scope, "a release's own truststore has no scope");
    }

    @Test
    void aProjectTruststoreCanBeFlaggedDefaultAndDeletedInItsProject() {
        registry.create(PROJECT, "partner-ca", caPem, false, null);

        registry.setDefault(PROJECT, "partner-ca-truststore", true);
        assertEquals("true", client.secrets().inNamespace(PROJECT).withName("partner-ca-truststore").get()
                .getMetadata().getAnnotations().get(TruststoreRegistryService.ANNOTATION_TRUST_DEFAULT));

        registry.delete(PROJECT, "partner-ca-truststore");
        assertNull(client.secrets().inNamespace(PROJECT).withName("partner-ca-truststore").get());
    }

    @Test
    void whatIsNotDeployedToAProjectOnlyReadsSharedTruststores() {
        // A chart repository serves every project: a project truststore must not become the CA it is verified with.
        registry.create(PROJECT, "partner-ca", caPem, false, null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.caPemByName(PROJECT + "/partner-ca-truststore"));

        assertTrue(refused.getMessage().contains("only the shared truststores of project " + SHARED), refused.getMessage());
        assertFalse(registry.caPemByName(PROJECT + "/partner-ca-truststore", PROJECT).isBlank(),
                "a release of the project reads it");
    }

    private static KubernetesService.TruststoreSummary summary(String namespace, String name, boolean managed) {
        KubernetesService.TruststoreSummary t = new KubernetesService.TruststoreSummary();
        t.namespace = namespace;
        t.name = name;
        t.managed = managed;
        return t;
    }
}
