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

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.CommandService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.ReleaseMetadataService;
import org.apache.ambari.view.k8s.service.deployment.FluxGitOpsBackend;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uninstalling a GitOps release uses the Git repository, branch and credential recorded for it, never ones the request
 * names: a stored Git token must not be sent to a server of the caller's choosing, nor travel in a URL.
 */
class HelmResourceUninstallGitOpsTest {

    private final FluxGitOpsBackend flux = mock(FluxGitOpsBackend.class);
    private final ReleaseMetadataService metadata = mock(ReleaseMetadataService.class);
    private final CommandService commands = mock(CommandService.class);
    private HelmResource resource;
    private K8sReleaseEntity trino;

    @BeforeEach
    void setUp() throws Exception {
        ViewContext ctx = mock(ViewContext.class);
        when(ctx.getProperties()).thenReturn(Map.of(AuthHelper.ADMIN_USERS_PROPERTY, "lead",
                AuthHelper.OPERATOR_USERS_PROPERTY, "ops"));
        when(ctx.getUsername()).thenReturn("ops");
        when(ctx.getLoggedinUser()).thenReturn("ops");
        resource = new ObjenesisStd().newInstance(HelmResource.class);
        set("viewContext", ctx);
        set("authHelper", new AuthHelper(ctx));
        set("fluxGitOpsBackend", flux);
        set("releaseMetadataService", metadata);
        set("commandService", commands);

        trino = new K8sReleaseEntity();
        trino.setId(K8sReleaseEntity.idOf("analytics", "trino-uninstall-test"));
        trino.setNamespace("analytics");
        trino.setReleaseName("trino-uninstall-test");
        trino.setDeploymentMode("FLUX_GITOPS");
        trino.setGitRepoUrl("https://git.example.org/platform/gitops.git");
        trino.setGitBranch("main");
        trino.setGitPath("clusters/default/analytics/trino-uninstall-test");
        trino.setGitCredentialAlias("kdps.gitops.kdps.analytics.trino-uninstall-test");
        when(metadata.find("analytics", "trino-uninstall-test")).thenReturn(trino);
    }

    private void set(String name, Object value) throws Exception {
        Field field = HelmResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    private static UriInfo query(String... nameValues) {
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            parameters.add(nameValues[i], nameValues[i + 1]);
        }
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(parameters);
        return uriInfo;
    }

    @Test
    void aRequestNamingAGitRepositoryBranchOrCredentialIsRefusedWithNothingRemoved() throws Exception {
        for (String parameter : HelmResource.GIT_OVERRIDE_PARAMETERS) {
            Response response = resource.uninstall("analytics", "trino-uninstall-test", null, null,
                    query(parameter, "https://attacker.example/x.git"), null);

            assertEquals(400, response.getStatus(), parameter);
            assertTrue(String.valueOf(((Map<?, ?>) response.getEntity()).get("error")).contains("'" + parameter + "'"));
        }
        verifyNoInteractions(flux, metadata, commands);
    }

    @Test
    void aGitOpsReleaseIsRemovedWithWhatItsMetadataRecords() throws Exception {
        Response response = resource.uninstall("analytics", "trino-uninstall-test", null, null,
                query("repoId", "charts", "deploymentMode", "FLUX_GITOPS"), null);

        assertEquals(200, response.getStatus());
        verify(flux).checkRemovable(trino);
        verify(flux).destroy("analytics", "trino-uninstall-test");
        verify(flux, never()).destroy(any(), any(), any(), any(), any(), any(), any(), any());
        verify(metadata).delete("analytics", "trino-uninstall-test");
    }

    @Test
    void aGitOpsReleaseKdpsCannotRemoveFromGitIsKeptWithNothingRemoved() throws Exception {
        doThrow(new IllegalStateException("KDPS cannot remove analytics/trino-uninstall-test from Git"))
                .when(flux).checkRemovable(trino);

        Response response = resource.uninstall("analytics", "trino-uninstall-test", null, null, query(), null);

        assertEquals(409, response.getStatus());
        assertEquals("KDPS cannot remove analytics/trino-uninstall-test from Git",
                ((Map<?, ?>) response.getEntity()).get("error"));
        verify(flux, never()).destroy(anyString(), anyString());
        verify(metadata, never()).delete(anyString(), anyString());
        verifyNoInteractions(commands);
    }

    @Test
    void aGitOpsReleaseRecordingACredentialKdpsDidNotSaveIsKeptWithNothingRemoved(@TempDir Path tmp) throws Exception {
        ViewContext backendContext = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(backendContext.getInstanceName()).thenReturn("kdps");
        when(backendContext.getProperties()).thenReturn(Map.of("k8s.view.working.dir", tmp.toString()));
        set("fluxGitOpsBackend", new FluxGitOpsBackend(tmp.resolve("flux"), mock(KubernetesService.class), backendContext));
        // As earlier versions accepted it: an alias of Ambari's credential store an administrator named.
        trino.setGitCredentialAlias("corp-git-token");

        Response response = resource.uninstall("analytics", "trino-uninstall-test", null, null, query(), null);

        assertEquals(409, response.getStatus());
        String error = String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
        assertTrue(error.contains("'corp-git-token' does not hold a Git credential saved by KDPS"), error);
        assertTrue(error.endsWith("Deploy the release again once, giving its Git access token or SSH key, then "
                + "uninstall it."), error);
        verify(metadata, never()).delete(anyString(), anyString());
        verifyNoInteractions(commands);
    }
}
