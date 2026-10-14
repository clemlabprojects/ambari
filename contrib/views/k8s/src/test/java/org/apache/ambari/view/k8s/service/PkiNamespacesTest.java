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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The projects of the truststores and the Company CAs: set on the Configuration page for an account limited to its
 * own projects, shared by both registries, and never a workload project by default for the CA private keys.
 */
class PkiNamespacesTest {

    private ViewContext ctx;
    private Map<String, String> properties;

    @BeforeEach
    void setUp() {
        ctx = mock(ViewContext.class);
        properties = new HashMap<>();
        properties.put("view.admin.users", "lead");
        when(ctx.getProperties()).thenReturn(properties);
        when(ctx.getUsername()).thenReturn("lead");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());
    }

    private static Map<String, Object> settings(String truststoreNamespace, String pkiNamespace) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (truststoreNamespace != null) doc.put("truststore", Map.of("namespace", truststoreNamespace));
        if (pkiNamespace != null) doc.put("pki", Map.of("namespace", pkiNamespace));
        return doc;
    }

    @Test
    void nothingConfiguredKeepsTheDedicatedProject() {
        PkiNamespaces namespaces = new PkiNamespaces(ctx, Map::of);

        assertEquals("ambari-pki", namespaces.truststoreNamespace());
        assertEquals("ambari-pki", namespaces.caNamespace());
    }

    @Test
    void theTruststoreProjectNeverHoldsTheCompanyCas() {
        PkiNamespaces namespaces = new PkiNamespaces(ctx, () -> settings("analytics", null));

        assertEquals("analytics", namespaces.truststoreNamespace());
        assertEquals("ambari-pki", namespaces.caNamespace(), "CA private keys stay out of a workload project");
    }

    @Test
    void truststoresShareTheCompanyCaProjectUnlessTheyHaveTheirOwn() {
        assertEquals("kdps-pki", new PkiNamespaces(ctx, () -> settings(null, "kdps-pki")).truststoreNamespace());
        assertEquals("kdps-pki", new PkiNamespaces(ctx, () -> settings(null, "kdps-pki")).caNamespace());
        assertEquals("kdps-trust", new PkiNamespaces(ctx, () -> settings("kdps-trust", "kdps-pki")).truststoreNamespace());
    }

    @Test
    void theViewPropertiesOfEarlierVersionsAreHonouredUntilTheSettingsNameAProject() {
        properties.put("pki.namespace", "legacy-pki");
        properties.put("truststore.namespace", "legacy-trust");

        PkiNamespaces fromProperties = new PkiNamespaces(ctx, Map::of);
        assertEquals("legacy-trust", fromProperties.truststoreNamespace());
        assertEquals("legacy-pki", fromProperties.caNamespace());

        PkiNamespaces fromSettings = new PkiNamespaces(ctx, () -> settings(null, "kdps-pki"));
        assertEquals("kdps-pki", fromSettings.truststoreNamespace());
        assertEquals("kdps-pki", fromSettings.caNamespace());
    }

    @Test
    void settingsThatCannotBeReadFallBackToThePropertiesAndTheDefault() {
        properties.put("pki.namespace", "legacy-pki");
        PkiNamespaces namespaces = new PkiNamespaces(ctx, () -> {
            throw new IllegalStateException("The view settings could not be read");
        });

        assertEquals("legacy-pki", namespaces.caNamespace());
        assertEquals("legacy-pki", namespaces.truststoreNamespace());
    }

    @Test
    void theProjectsAreSavedWithTheViewSettingsAndReadBackByBothRegistries() {
        new ViewSettingsService(ctx).save(new LinkedHashMap<>(settings("kdps-trust", "kdps-pki")));

        assertEquals("kdps-trust", new TruststoreRegistryService(ctx, mock(KubernetesService.class)).managementNamespace());
        assertEquals("kdps-pki", new CaRegistryService(ctx, mock(KubernetesService.class)).registryNamespace());
    }

    @Test
    void aProjectNameKubernetesWouldRefuseIsNotSaved() {
        ViewSettingsService settings = new ViewSettingsService(ctx);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> settings.save(new LinkedHashMap<>(settings(null, "Corp_PKI"))));
        assertTrue(refused.getMessage().startsWith("The setting pki.namespace must be a project (namespace) name"),
                refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> settings.save(new LinkedHashMap<>(settings("-trust", null))));

        // Blank leaves it unset: the default project.
        settings.save(new LinkedHashMap<>(settings(" ", "")));
        assertEquals("ambari-pki", new PkiNamespaces(ctx).caNamespace());
    }

    @Test
    void theRefusalSaysWhatToDo() {
        assertEquals("KDPS cannot write Secrets in project kdps-pki; ask the platform team to grant the KDPS account edit "
                + "in that project, or set the namespace in Cluster Settings.",
                PkiNamespaces.forbiddenMessage("kdps-pki"));
    }
}
