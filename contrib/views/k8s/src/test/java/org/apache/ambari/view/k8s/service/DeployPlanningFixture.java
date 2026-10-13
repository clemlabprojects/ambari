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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.cluster.Cluster;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.apache.ambari.view.k8s.utils.FakeAmbariServer;

import javax.ws.rs.core.MultivaluedHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A view instance on which deploys are planned as the wizard submits them, without running: a {@link CommandService}
 * built on the test seam over a view whose Ambari is a {@link FakeAmbariServer} (cluster {@code c1}, reached through
 * the deploy's base URI), with the shipped service definitions and a chart repository {@code charts}. Its timer never
 * fires, so an operation submitted for execution stays as planned.
 */
final class DeployPlanningFixture implements AutoCloseable {

    static final String NS = "analytics";
    static final String REALM = "EXAMPLE.COM";
    /** Passwords the secure cluster holds; none may reach the view's database or a parameter file in clear. */
    static final String KEYCLOAK_ADMIN_SECRET = "Keycloak-Admin-Secret-7f3a";
    static final String RANGER_ADMIN_PASSWORD = "Ranger-Admin-Pass-9c1e";
    static final String KDC_ADMIN_PASSWORD = "Kdc-Admin-Pass-4b2d";

    final Gson gson = new Gson();
    final FakeAmbariServer ambari;
    final PerUserViewContext view;
    final KubernetesService kubernetes;
    final HelmService helm;
    /** Never runs what it is given: an operation submitted for execution stays as planned. */
    final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    final CommandService service;

    /**
     * @param workDir    the view's working directory
     * @param kubernetes builds the Kubernetes service of the view: on a mock API server, or a Mockito mock
     */
    DeployPlanningFixture(Path workDir, Function<ViewContext, KubernetesService> kubernetes) throws IOException {
        ambari = new FakeAmbariServer("c1");
        view = new PerUserViewContext("plan-" + Long.toHexString(System.nanoTime()), workDir).as("admin");
        when(view.context.getURLStreamProvider()).thenReturn(ambari.streamProvider());
        Cluster cluster = mock(Cluster.class);
        when(cluster.getName()).thenReturn("c1");
        when(view.context.getCluster()).thenReturn(cluster);
        when(view.context.getLoggedinUser()).thenReturn("admin");

        HelmRepoEntity charts = new HelmRepoEntity();
        charts.setId("charts");
        charts.setName("charts");
        charts.setType("HTTP");
        charts.setUrl("https://charts.example.org");
        charts.setAuthMode("anonymous");
        view.dataStore.store(charts);

        this.kubernetes = kubernetes.apply(view.context);
        helm = mock(HelmService.class, RETURNS_DEEP_STUBS);
        when(helm.getRepositoryService().showValuesAsMap(any(), any(), any())).thenAnswer(inv -> chartDefaults());
        CommandUtils commandUtils = spy(new CommandUtils(view.context, this.kubernetes));
        // The managed cluster's krb5.conf is read from this host's /etc/krb5.conf, which a build machine may lack.
        doAnswer(inv -> CommandUtils.krb5ConfigMapName(inv.getArgument(1)))
                .when(commandUtils).prepareKrb5ConfConfigMap(anyString(), anyString(), any());
        CommandPlanFactory planFactory = new CommandPlanFactory(view.context, gson,
                new WebHookConfigurationService(view.context, this.kubernetes), commandUtils);
        service = new CommandService(view.context, view.dataStore, helm, this.kubernetes, commandUtils, planFactory,
                timer, Executors.newCachedThreadPool());
    }

    @Override
    public void close() throws IOException {
        service.shutdown();
        ambari.close();
    }

    /** Kerberos on (realm EXAMPLE.COM), Ranger Admin and Keycloak (realm odp) configured in Ambari. */
    void secureCluster() {
        kerberos(true);
        ambari.config("oidc-env", Map.of("oidc_admin_url", "https://sso.example.com", "oidc_realm", "odp",
                "oidc_issuer_url", "", "oidc_admin_client_id", "kdps-admin",
                "oidc_admin_client_secret", KEYCLOAK_ADMIN_SECRET, "oidc_verify_tls", "true"));
        ranger();
    }

    /** Kerberos off, no Keycloak, no Ranger. */
    void plainCluster() {
        kerberos(false);
    }

    /** The cluster's Kerberos state, with its realm and KDC when on, and the Hadoop site files. */
    void kerberos(boolean on) {
        ambari.config("cluster-env", Map.of("security_enabled", String.valueOf(on)));
        if (on) {
            ambari.config("kerberos-env", Map.of("realm", REALM, "kdc_type", "ipa",
                    "admin_server_host", "kdc.example.com"));
            ambari.config("krb5-conf", Map.of("domains", "example.com"));
            ambari.config("kerberos-admin", Map.of("principal", "admin/admin", "password", KDC_ADMIN_PASSWORD));
        }
        ambari.config("core-site", Map.of("fs.defaultFS", "hdfs://nameservice1",
                "hadoop.security.authentication", on ? "kerberos" : "simple"));
        ambari.config("hdfs-site", Map.of("dfs.nameservices", "nameservice1"));
    }

    /** Ranger Admin installed on the cluster. */
    void ranger() {
        ambari.config("admin-properties", Map.of("policymgr_external_url", "https://ranger.example.com:6182"));
        ambari.config("ranger-env", Map.of("admin_username", "admin", "admin_password", RANGER_ADMIN_PASSWORD,
                "ranger_admin_username", "amb_ranger_admin"));
        ambari.component("RANGER/RANGER_ADMIN", "ranger.example.com");
    }

    /** A TRINO deploy as the wizard submits it; {@code withRanger} sends the definition's Ranger block. */
    HelmDeployRequest trino(boolean withRanger) throws IOException {
        Map<String, Object> def = serviceDefinition("TRINO");
        HelmDeployRequest request = request("TRINO", "trino", def, "trino.example.org");
        if (withRanger) {
            request.setRanger(def.get("ranger"));
        }
        return request;
    }

    /** A SUPERSET deploy as the wizard submits it. */
    HelmDeployRequest superset() throws IOException {
        return request("SUPERSET", "superset", serviceDefinition("SUPERSET"), "superset.example.org");
    }

    private static HelmDeployRequest request(String serviceKey, String release, Map<String, Object> def, String host) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setChart(String.valueOf(def.get("chart")));
        request.setReleaseName(release);
        request.setNamespace(NS);
        request.setServiceKey(serviceKey);
        request.setRepoId("charts");
        request.setVersion(String.valueOf(def.get("version")));
        request.setSecretName(def.get("secretName") == null ? null : String.valueOf(def.get("secretName")));
        if (def.get("dependencies") instanceof Map<?, ?> dependencies) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) dependencies;
            request.setDependencies(new LinkedHashMap<>(copy));
        }
        request.setRequiredConfigMaps(def.get("requiredConfigMaps"));
        request.setEndpoints(def.get("endpoints"));
        request.setKerberos(kerberosAsTheWizardSeedsIt(def, release));
        request.setTruststoreRefs(List.of());
        Map<String, Object> ingress = new LinkedHashMap<>(Map.of("enabled", true, "host", host));
        request.setValues(new LinkedHashMap<>(Map.of("ingress", ingress)));
        request.setFormValues(new LinkedHashMap<>(Map.of("ingress", new LinkedHashMap<>(ingress))));
        return request;
    }

    /** What the wizard sends in {@code kerberos}: one entry per {@code kerberos[]} of the definition. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> kerberosAsTheWizardSeedsIt(Map<String, Object> def, String release) {
        if (!(def.get("kerberos") instanceof List<?> entries) || entries.isEmpty()) {
            return null;
        }
        Map<String, Object> kerberos = new LinkedHashMap<>();
        for (Object raw : entries) {
            Map<String, Object> entry = (Map<String, Object>) raw;
            Map<String, Object> seeded = new LinkedHashMap<>();
            seeded.put("enabled", !Boolean.FALSE.equals(entry.get("enabled")));
            seeded.put("principalTemplate", entry.getOrDefault("principalTemplate", ""));
            seeded.put("serviceName", entry.getOrDefault("serviceName", ""));
            seeded.put("secretName", String.valueOf(entry.getOrDefault("secretNameTemplate", "{{releaseName}}-keytab"))
                    .replace("{{releaseName}}", release));
            seeded.put("keyNameInSecret", entry.getOrDefault("keyNameInSecret", "service.keytab"));
            seeded.put("mountPath", entry.getOrDefault("mountPath", "/etc/security/keytabs"));
            if (entry.get("principalValuePath") != null) seeded.put("principalValuePath", entry.get("principalValuePath"));
            kerberos.put(String.valueOf(entry.get("key")), seeded);
        }
        return kerberos;
    }

    /** The chart's default values, as {@code helm show values} gives them, reduced to what the plan reads. */
    private static Map<String, Object> chartDefaults() {
        Map<String, Object> https = new LinkedHashMap<>(Map.of("enabled", false, "port", 8443));
        Map<String, Object> config = new LinkedHashMap<>(Map.of("https", https));
        Map<String, Object> server = new LinkedHashMap<>(Map.of("config", config));
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("server", server);
        defaults.put("service", new LinkedHashMap<>(Map.of("port", 8080)));
        defaults.put("nameOverride", "trino");
        return defaults;
    }

    /** The operator's Ambari session. */
    static MultivaluedHashMap<String, String> adminHeaders() {
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Cookie", "AMBARISESSIONID=admin-session");
        return headers;
    }

    /** Plans a direct Helm deploy and returns its operation id; nothing runs. */
    String plan(HelmDeployRequest request) {
        return service.planReleaseDeploy(request, "charts", request.getVersion(), "http://ambari.test/commands",
                adminHeaders(), URI.create(ambari.baseUri()), null, false);
    }

    /** The steps of an operation, in plan order. */
    List<CommandEntity> steps(String rootId) {
        CommandEntity root = view.dataStore.find(CommandEntity.class, rootId);
        List<String> ids = gson.fromJson(root.getChildListJson(), new TypeToken<ArrayList<String>>() {}.getType());
        return ids.stream().map(id -> view.dataStore.find(CommandEntity.class, id)).collect(Collectors.toList());
    }

    static List<String> types(List<CommandEntity> steps) {
        return steps.stream().map(CommandEntity::getType).collect(Collectors.toList());
    }

    static CommandEntity step(List<CommandEntity> steps, String type) {
        return steps.stream().filter(s -> type.equals(s.getType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " step in " + types(steps)));
    }

    /** A command's parameters, read back from their encrypted file. */
    Map<String, Object> params(CommandEntity command) {
        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> params = gson.fromJson(CommandUtils.resolveParamsPayload(command.getParamsJson()), mapType);
        return params == null ? Map.of() : params;
    }

    /** The chart values a step's parameters set ({@code _ov}). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> overrides(Map<String, Object> params) {
        Object ov = params.get("_ov");
        return ov instanceof Map ? (Map<String, Object>) ov : Map.of();
    }

    static Map<String, Object> serviceDefinition(String serviceKey) throws IOException {
        String resource = "KDPS/services/" + serviceKey + "/service.json";
        try (InputStream in = DeployPlanningFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("No service definition " + resource);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
        }
    }
}
