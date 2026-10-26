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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Certificate Authorities page with an account that may not read Secrets in the Company CA project: the view keeps
 * the CAs itself, so the page still lists them; only the import of an earlier version's CAs waits for the access.
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

    @Test
    void theCompanyCasAreListedWhateverTheAccountMayRead() {
        Response listed = new CaRegistryResource(ctx, k8s, null).list();

        assertEquals(200, listed.getStatus());
        assertEquals(List.of(), ((Map<?, ?>) listed.getEntity()).get("items"));
        assertEquals(true, ((Map<?, ?>) listed.getEntity()).get("canWrite"), "an administrator");
    }

    @Test
    void anUnknownCaIsNotFound() {
        CaRegistryResource resource = new CaRegistryResource(ctx, k8s, null);

        assertEquals(404, resource.delete("corp-issuing-ca").getStatus());
        assertEquals(404, resource.promoteToClusterIssuer("corp-issuing-ca", null).getStatus());
        assertEquals(404, resource.promoteToClusterIssuer("corp-issuing-ca", "nope").getStatus(), "unknown cluster");
    }
}
