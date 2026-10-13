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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.NS;
import static org.apache.ambari.view.k8s.service.DeployPlanningFixture.step;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Ranger part of a Trino deploy, planned against a fake Ambari and a mock Kubernetes API: the plugin's
 * configuration objects written before the install, and the step that creates its repository in Ranger. With Kerberos
 * the plugin logs in with the release's keytab and Ranger lets that principal download the policies; without it, the
 * plugin logs in as a Ranger user whose password is kept in a Secret of the namespace.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceRangerPlanningTest {

    private static final String SECURITY_CONFIG_MAP = "trino-ranger-trino-security";
    private static final String PLUGIN_PASSWORD_SECRET = "ranger-plugin-rest-client-password";

    @TempDir
    Path workDir;

    KubernetesMockServer server;
    KubernetesClient client;

    private DeployPlanningFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new DeployPlanningFixture(workDir, ctx -> new KubernetesService(ctx, client, true));
        fixture.ranger();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    void withKerberosThePluginLogsInWithTheReleaseKeytab() throws Exception {
        fixture.kerberos(true);

        List<CommandEntity> steps = fixture.steps(fixture.plan(fixture.trino(true)));

        Map<String, String> security = pluginProperties(SECURITY_CONFIG_MAP);
        assertEquals("keytab", security.get("ranger.plugin.trino.ugi.login.type"), security::toString);
        assertEquals("trino-analytics@" + DeployPlanningFixture.REALM,
                security.get("ranger.plugin.trino.ugi.keytab.principal"), security::toString);
        assertEquals("/etc/security/keytabs/service.keytab", security.get("ranger.plugin.trino.ugi.keytab.file"));
        assertEquals("true", security.get("ranger.plugin.trino.ugi.initialize"));
        assertEquals("trino-analytics", security.get("ranger.plugin.trino.service.name"));
        assertEquals("https://ranger.example.com:6182", security.get("ranger.plugin.trino.policy.rest.url"));
        Map<String, Object> repository = fixture.params(step(steps, "RANGER_REPOSITORY_CREATION"));
        assertEquals("trino-analytics", repository.get("_rangerRepositoryName"));
        assertEquals("trino", repository.get("_rangerServiceType"));
        Map<?, ?> configs = (Map<?, ?>) repository.get("_rangerServiceConfigs");
        assertEquals("trino-analytics", configs.get("policy.download.auth.users"), configs::toString);
        assertEquals("trino-analytics", configs.get("tag.download.auth.users"), configs::toString);
        assertNull(client.secrets().inNamespace(NS).withName(PLUGIN_PASSWORD_SECRET).get(),
                "a Kerberized plugin has no password");
        assertRepositoryBeforeTheInstall(steps);
    }

    @Test
    void withoutKerberosThePluginLogsInAsARangerUserWhosePasswordStaysInASecret() throws Exception {
        fixture.kerberos(false);

        List<CommandEntity> steps = fixture.steps(fixture.plan(fixture.trino(true)));

        Map<String, String> security = pluginProperties(SECURITY_CONFIG_MAP);
        assertEquals("", security.getOrDefault("ranger.plugin.trino.ugi.keytab.principal", ""), security::toString);
        assertEquals("false", security.get("ranger.plugin.trino.ugi.initialize"), "no Kerberos login");
        assertEquals("trino_ranger_plugin_user", security.get("ranger.plugin.trino.policy.rest.client.username"));
        Map<String, Object> repository = fixture.params(step(steps, "RANGER_REPOSITORY_CREATION"));
        assertEquals("trino_ranger_plugin_user", repository.get("_rangerPluginUserName"));
        Map<?, ?> configs = (Map<?, ?>) repository.get("_rangerServiceConfigs");
        assertFalse(configs.containsKey("policy.download.auth.users"), configs::toString);
        String password = pluginPassword();
        assertTrue(password.length() >= 12, "a generated password");
        assertStoredNowhereInClear(password);
        assertRepositoryBeforeTheInstall(steps);
    }

    @Test
    void aRedeployKeepsThePluginPasswordRangerKnows() throws Exception {
        fixture.kerberos(false);
        fixture.plan(fixture.trino(true));
        String first = pluginPassword();

        fixture.plan(fixture.trino(true));

        assertEquals(first, pluginPassword(), "Ranger keeps the password its plugin user was created with");
    }

    @Test
    void withoutTheRangerBlockNothingOfRangerIsWritten() throws Exception {
        fixture.kerberos(true);

        List<CommandEntity> steps = fixture.steps(fixture.plan(fixture.trino(false)));

        assertTrue(steps.stream().noneMatch(s -> s.getType().startsWith("RANGER_")), DeployPlanningFixture.types(steps)::toString);
        assertNull(client.configMaps().inNamespace(NS).withName(SECURITY_CONFIG_MAP).get());
        assertFalse(DeployPlanningFixture.overrides(fixture.params(step(steps, "HELM_DEPLOY"))).containsKey("ranger.serviceName"));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The repository is created in Ranger before the chart is installed, which starts the plugin. */
    private static void assertRepositoryBeforeTheInstall(List<CommandEntity> steps) {
        List<String> types = DeployPlanningFixture.types(steps);
        assertTrue(types.indexOf("RANGER_REPOSITORY_CREATION") < types.indexOf("HELM_DEPLOY_DRY_RUN"), types::toString);
    }

    /** The Hadoop-style properties of the XML file a Ranger ConfigMap holds, by name. */
    private Map<String, String> pluginProperties(String configMapName) {
        ConfigMap configMap = client.configMaps().inNamespace(NS).withName(configMapName).get();
        assertNotNull(configMap, "ConfigMap " + configMapName + " in " + NS);
        Map<String, String> properties = new LinkedHashMap<>();
        Matcher property = Pattern.compile("<name>([^<]*)</name>\\s*<value>([^<]*)</value>")
                .matcher(String.join("\n", configMap.getData().values()));
        while (property.find()) {
            properties.put(property.group(1), property.group(2));
        }
        return properties;
    }

    private String pluginPassword() {
        Secret secret = client.secrets().inNamespace(NS).withName(PLUGIN_PASSWORD_SECRET).get();
        assertNotNull(secret, "Secret " + PLUGIN_PASSWORD_SECRET + " in " + NS);
        return new String(Base64.getDecoder().decode(secret.getData().get("password")), StandardCharsets.UTF_8);
    }

    /** Neither the view's database nor a parameter file (encrypted) holds the value in clear. */
    private void assertStoredNowhereInClear(String value) throws Exception {
        List<String> stored = new ArrayList<>();
        for (CommandEntity command : fixture.view.dataStore.findAll(CommandEntity.class, null)) {
            stored.add(command.getParamsJson());
            if (command.getParamsJson() != null
                    && command.getParamsJson().startsWith(CommandParamsStore.FILE_REFERENCE)) {
                stored.add(Files.readString(Path.of(command.getParamsJson()
                        .substring(CommandParamsStore.FILE_REFERENCE.length())), StandardCharsets.UTF_8));
            }
        }
        for (CommandStatusEntity status : fixture.view.dataStore.findAll(CommandStatusEntity.class, null)) {
            stored.add(status.getResultJson());
            stored.add(status.getMessage());
        }
        for (String text : stored) {
            assertFalse(text != null && text.contains(value), "stored in clear: " + text);
        }
    }
}
