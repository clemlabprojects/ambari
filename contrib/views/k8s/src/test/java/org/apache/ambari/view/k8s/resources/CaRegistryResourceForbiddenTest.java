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

package org.apache.ambari.view.k8s.resources;

import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.PkiNamespaces;
import org.apache.ambari.view.k8s.service.ViewSettingsService;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Certificate Authorities page with an account that may not use Secrets in the Company CA project: a 403 that says
 * what to do, not a server error with the raw refusal of the Kubernetes API.
 */
class CaRegistryResourceForbiddenTest {

    private ViewContext ctx;
    private KubernetesService k8s;

    @BeforeEach
    void setUp() {
        ctx = mock(ViewContext.class);
        when(ctx.getProperties()).thenReturn(Map.of("view.admin.users", "lead"));
        when(ctx.getUsername()).thenReturn("lead");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getDataStore()).thenReturn(new InMemoryDataStore());
        k8s = mock(KubernetesService.class);
        when(k8s.getClient()).thenThrow(new KubernetesClientException("secrets is forbidden: User \"kdps\" cannot list "
                + "resource \"secrets\" in API group \"\" in the namespace \"kdps-pki\"", 403, null));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("pki", Map.of("namespace", "kdps-pki"));
        new ViewSettingsService(ctx).save(settings);
    }

    private static String error(Response response) {
        return String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    void listingTheCompanyCasSaysWhatToDo() {
        Response listed = new CaRegistryResource(ctx, k8s, null).list();

        assertEquals(403, listed.getStatus());
        assertEquals(PkiNamespaces.forbiddenMessage("kdps-pki"), error(listed));
    }

    @Test
    void changingTheCompanyCasSaysWhatToDo() {
        CaRegistryResource resource = new CaRegistryResource(ctx, k8s, null);

        Response deleted = resource.delete("corp-issuing-ca");
        assertEquals(403, deleted.getStatus());
        assertEquals(PkiNamespaces.forbiddenMessage("kdps-pki"), error(deleted));

        Response promoted = resource.promoteToClusterIssuer("corp-issuing-ca");
        assertEquals(403, promoted.getStatus());
        assertEquals(PkiNamespaces.forbiddenMessage("kdps-pki"), error(promoted));
    }
}
