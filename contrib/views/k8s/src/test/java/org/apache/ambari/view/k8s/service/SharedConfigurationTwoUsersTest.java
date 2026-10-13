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

import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.KubeconfigGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * An administrator configures KDPS once for every user of the view instance: the cluster connection, a private
 * repository's credentials and an external context's secrets saved by one user are what an operator (another
 * Ambari user) and background deploy work read.
 */
class SharedConfigurationTwoUsersTest {

    private static final String KUBECONFIG = "apiVersion: v1\nkind: Config\ncurrent-context: prod\n"
            + "clusters:\n- name: prod\n  cluster:\n    server: https://api.prod:6443\n"
            + "contexts:\n- name: prod\n  context:\n    cluster: prod\n    user: kdps\n"
            + "users:\n- name: kdps\n  user:\n    token: t\n";

    @TempDir
    Path workDir;

    PerUserViewContext view;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("two-users", workDir);
    }

    /** User A's configuration: a kubeconfig, a private repository with its token, an external context with a Ranger password. */
    private void aliceConfiguresKdps() throws Exception {
        view.as("alice");
        new ViewConfigurationService(view.context).saveKubeconfigFile(
                new ByteArrayInputStream(KUBECONFIG.getBytes(StandardCharsets.UTF_8)), "kubeconfig.yaml");

        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId("harbor");
        repo.setType("OCI");
        repo.setName("harbor");
        repo.setUrl("harbor.example.com/kdps");
        repo.setAuthMode("basic");
        repo.setUsername("robot$kdps");
        new HelmRepositoryService(view.context, mock(HelmClient.class)).save(repo, "registry-token");

        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new java.util.LinkedHashMap<>(Map.of("rangerUrl", "https://ranger.ext:6182",
                "rangerAdminUsername", "admin"));
        context.secrets = new java.util.LinkedHashMap<>(Map.of("rangerAdminPassword", "ranger-secret"));
        new ContextService(view.context).save(context);
    }

    @Test
    void anotherUserSeesTheViewConfiguredWithTheSameConnectionAndSecrets() throws Exception {
        aliceConfiguresKdps();

        view.as("ops1");
        ViewConfigurationService configuration = new ViewConfigurationService(view.context);
        assertTrue(configuration.isConfigured(), "ops1 must not be sent to the configuration page");
        assertEquals(KubeconfigGuard.canonical(KUBECONFIG), configuration.getKubeconfigContents());

        HelmRepositoryService repositories = new HelmRepositoryService(view.context, mock(HelmClient.class));
        assertEquals("registry-token", repositories.readPlainSecret(repositories.get("harbor")),
                "the imagePullSecret of ops1's deploy gets the registry credentials");
        assertEquals("ranger-secret", new ContextService(view.context).readSecret("ext", "rangerAdminPassword"),
                "ops1's deploy can create the Ranger repository of the external context");
    }

    @Test
    void backgroundDeployWorkReadsTheSameConfigurationWhateverUserItInherited() throws Exception {
        aliceConfiguresKdps();

        for (String inherited : new String[] {null, "bob"}) {
            view.as(inherited);
            ViewConfigurationService configuration = new ViewConfigurationService(view.context);
            assertTrue(configuration.isConfigured(), "configured for " + inherited);
            assertEquals(KubeconfigGuard.canonical(KUBECONFIG), configuration.getKubeconfigContents());
            assertEquals("ranger-secret", new ContextService(view.context).readSecret("ext", "rangerAdminPassword"));
        }
    }

    @Test
    void anOpenShiftLoginSavedByOneUserIsUsedForEveryUser() {
        view.as("alice");
        new ViewConfigurationService(view.context)
                .saveOpenShiftLogin("https://api.ocp:6443", "kdps-deployer", "s3cret", null, true);

        view.as("ops1");
        ViewConfigurationService configuration = new ViewConfigurationService(view.context);
        assertTrue(configuration.isConfigured());
        assertTrue(configuration.isOpenShiftLogin());
        assertEquals("https://api.ocp:6443", configuration.getOpenShiftApiUrl());
        assertNotNull(configuration.getOpenShiftLoginProvider());
        assertEquals("s3cret", new InstanceSettings(view.context).getSecret("openshift.password.enc"),
                "ops1's token is minted with the saved password");
    }

    @Test
    void secretsRotatedByAnotherAdministratorReplaceTheOnesEveryoneUses() throws Exception {
        aliceConfiguresKdps();

        view.as("bob");
        HelmRepositoryService repositories = new HelmRepositoryService(view.context, mock(HelmClient.class));
        repositories.save(repositories.get("harbor"), "rotated-token");

        view.as("alice");
        HelmRepositoryService alicesView = new HelmRepositoryService(view.context, mock(HelmClient.class));
        assertEquals("rotated-token", alicesView.readPlainSecret(alicesView.get("harbor")));
    }

    @Test
    void nothingIsLeftInAmbarisPerUserInstanceData() throws Exception {
        aliceConfiguresKdps();

        assertTrue(view.instanceDataOf("alice").isEmpty(), "found: " + view.instanceDataOf("alice"));
    }
}
