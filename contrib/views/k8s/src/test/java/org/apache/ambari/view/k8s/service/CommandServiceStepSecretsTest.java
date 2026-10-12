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

import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The secrets a step needs from the encrypted context store or from a security profile are read there when the step
 * runs, not kept with its parameters. A step planned by an earlier version still carries them and uses them.
 */
class CommandServiceStepSecretsTest {

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private CommandService service;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("step-secrets", workDir);
        service = new CommandService(view.context, mock(HelmService.class), mock(KubernetesService.class));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void theRangerPasswordOfAnExternalContextIsReadFromTheContextWhenTheStepRuns() {
        view.as("admin");
        ContextRequest context = new ContextRequest();
        context.id = "ext";
        context.name = "External cluster";
        context.kind = ContextService.KIND_EXTERNAL;
        context.config = new LinkedHashMap<>(Map.of("rangerUrl", "https://ranger.ext:6182", "rangerAdminUsername", "admin"));
        context.secrets = new LinkedHashMap<>(Map.of("rangerAdminPassword", "Ranger-Pass-751"));
        new ContextService(view.context).save(context);
        view.as(null);

        assertEquals("Ranger-Pass-751", service.rangerAdminPasswordForStep(Map.of("_platformContextId", "ext",
                "_rangerUrl", "https://ranger.ext:6182", "_rangerAdminUsername", "admin")));
        assertEquals("planned-earlier", service.rangerAdminPasswordForStep(Map.of("_platformContextId", "ext",
                "_rangerAdminPassword", "planned-earlier")));
    }

    @Test
    void theClientSecretOfAnExternalOidcProviderIsReadFromTheProfileWhenTheStepRuns() {
        SecurityProfileEntity profile = new SecurityProfileEntity();
        profile.setId("corp-sso");
        profile.setViewInstance("step-secrets");
        profile.setProfileJson("{\"mode\":\"oidc\",\"oidc\":{\"source\":\"external\",\"clientId\":\"kdps\","
                + "\"clientSecret\":\"Oidc-Secret-751\"}}");
        view.dataStore.store(profile);

        assertEquals("Oidc-Secret-751", service.externalOidcClientSecret(
                Map.of(CommandService.OIDC_EXTERNAL_PROFILE_PARAM, "corp-sso", "oidcExternalClientId", "kdps")));
        assertEquals("planned-earlier", service.externalOidcClientSecret(
                Map.of("oidcExternalClientId", "kdps", "oidcExternalClientSecret", "planned-earlier")));

        IllegalStateException gone = assertThrows(IllegalStateException.class, () -> service.externalOidcClientSecret(
                Map.of(CommandService.OIDC_EXTERNAL_PROFILE_PARAM, "deleted-profile", "oidcExternalClientId", "kdps")));
        assertTrue(gone.getMessage().contains("'deleted-profile'"), gone.getMessage());
    }
}
