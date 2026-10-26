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
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
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
    private ViewContext ctx;
    private KubernetesService kubernetes;

    @BeforeEach
    void setUp() {
        if (caPem == null) {
            caPem = TestCertificates.caPem("CN=Partner Root CA");
        }
        client.secrets().inNamespace(SHARED).delete();
        client.secrets().inNamespace(PROJECT).delete();
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(PROJECT).endMetadata().build())
                .createOr(existing -> existing.update());
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());
        when(ctx.getInstanceName()).thenReturn("test-instance");
        when(ctx.getUsername()).thenReturn("operator1");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                System.getProperty("java.io.tmpdir") + "/k8s-truststore-scope-ut"));
        kubernetes = spy(new KubernetesService(ctx, client, mock(HelmClient.class), true));
        registry = registry();
    }

    /** The registry of a new request, as each request makes one. */
    private TruststoreRegistryService registry() {
        return new TruststoreRegistryService(ctx, kubernetes, new PkiNamespaces(ctx, Map::of));
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
    void withoutAProjectOrWithTheSharedOneTheTruststoreIsSharedAndKeptByTheView() {
        assertEquals(TruststoreRegistryService.SCOPE_SHARED,
                registry.create(null, "corp-ca", caPem, false, null).get("scope"));
        assertEquals(TruststoreRegistryService.SCOPE_SHARED,
                registry.create(SHARED, "corp-ca-2", caPem, true, null).get("scope"));

        assertTrue(client.secrets().inAnyNamespace().list().getItems().isEmpty(), "nothing written to a cluster");
        assertEquals(List.of("corp-ca-2-truststore", "corp-ca-truststore"),
                registry().list(null).stream().map(t -> t.name).toList());
        assertEquals(List.of(caPem.trim()), registry().defaultTruststorePems().stream().map(String::trim).toList());
        // As the wizard names it, as earlier releases and chart repositories name it.
        assertEquals(caPem.trim(), registry().caPemByName(SHARED + "/corp-ca-truststore", PROJECT).trim());
        assertEquals(caPem.trim(), registry().caPemByName("corp-ca").trim());

        registry().setDefault(SHARED, "corp-ca-2-truststore", false);
        assertTrue(registry().defaultTruststorePems(PROJECT).isEmpty());
        registry().delete(null, "corp-ca-truststore");
        assertThrows(IllegalArgumentException.class, () -> registry().caPemByName("corp-ca"));
    }

    @Test
    void theSharedTruststoresOfAnEarlierVersionAreImportedOnceAndTheirSecretsLeftInPlace() {
        client.secrets().inNamespace(SHARED).resource(new SecretBuilder().withNewMetadata().withName("corp-ca-truststore")
                .addToLabels(TruststoreRegistryService.LABEL_RESOURCE_TYPE, TruststoreRegistryService.RESOURCE_TYPE_TRUSTSTORE)
                .addToAnnotations(TruststoreRegistryService.ANNOTATION_TRUST_DEFAULT, "true")
                .addToAnnotations(TruststoreRegistryService.ANNOTATION_DESCRIPTION, "Corporate root")
                .endMetadata().addToData("ca.crt", java.util.Base64.getEncoder().encodeToString(caPem.getBytes()))
                .build()).create();

        assertEquals(1, registry.defaultTruststorePems(PROJECT).size(), "trusted by every release, as before");
        assertEquals("true", new InstanceSettings(ctx).get(TruststoreRegistryService.IMPORTED_KEY));
        assertNotNull(client.secrets().inNamespace(SHARED).withName("corp-ca-truststore").get(), "left in place");
        List<KubernetesService.TruststoreSummary> listed = registry().list(null);
        assertEquals(1, listed.size(), "listed once, as the view's own");
        assertEquals(TruststoreRegistryService.SCOPE_SHARED, listed.get(0).scope);

        // Imported once: what is later written there is not a shared truststore.
        client.secrets().inNamespace(SHARED).resource(new SecretBuilder().withNewMetadata().withName("late-truststore")
                .addToLabels(TruststoreRegistryService.LABEL_RESOURCE_TYPE, TruststoreRegistryService.RESOURCE_TYPE_TRUSTSTORE)
                .endMetadata().addToData("ca.crt", java.util.Base64.getEncoder().encodeToString(caPem.getBytes()))
                .build()).create();
        assertThrows(IllegalArgumentException.class, () -> registry().caPemByName("late"));

        registry().delete(SHARED, "corp-ca-truststore");
        assertNull(client.secrets().inNamespace(SHARED).withName("corp-ca-truststore").get(), "its old Secret goes too");
    }

    @Test
    void whenTheDefaultClusterCannotBeReadTheImportIsTriedAgainLater() {
        doThrow(new io.fabric8.kubernetes.client.KubernetesClientException("unreachable")).when(kubernetes).getClient();

        registry.create(null, "corp-ca", caPem, false, null);

        assertNotEquals("true", new InstanceSettings(ctx).get(TruststoreRegistryService.IMPORTED_KEY));
        assertEquals(1, registry().list(SHARED).size(), "the view's own are listed all the same");
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
        registry.create(null, "corp-ca", caPem, false, null);
        doReturn(new ArrayList<>(List.of(
                summary(SHARED, "corp-ca-truststore", true),
                summary(PROJECT, "partner-ca-truststore", true),
                summary(PROJECT, "trino-truststore", false)))).when(kubernetes).listTruststores(null);
        doReturn(new ArrayList<>(List.of(
                summary(PROJECT, "partner-ca-truststore", true),
                summary(PROJECT, "trino-truststore", false)))).when(kubernetes).listTruststores(PROJECT);

        List<KubernetesService.TruststoreSummary> listed = registry().list(null);

        assertEquals(3, listed.size(), "the old Secret of the shared one is left out");
        assertEquals(TruststoreRegistryService.SCOPE_SHARED, listed.get(0).scope);
        assertEquals(1, listed.get(0).caCount);
        assertEquals(TruststoreRegistryService.SCOPE_PROJECT, listed.get(1).scope);
        assertTrue(listed.get(1).managed, "a project truststore stays selectable");
        assertNull(listed.get(2).scope, "a release's own truststore has no scope");
        assertEquals(List.of("partner-ca-truststore", "trino-truststore"),
                registry().list(PROJECT).stream().map(t -> t.name).toList(), "a project lists no shared one");
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

        assertTrue(refused.getMessage().contains("only the shared truststores (" + SHARED + "/...)"), refused.getMessage());
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
