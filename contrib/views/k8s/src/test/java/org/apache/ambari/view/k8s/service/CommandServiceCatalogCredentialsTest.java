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

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.TrinoCatalogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trino catalog credentials come from Kubernetes Secrets ("Secrets with catalog credentials"), which the chart
 * exposes to the pods as environment variables, and catalogs read them as ${ENV:KEY}. A credential written out in a
 * catalog would sit in the catalog ConfigMap and in KDPS's own database, so a deploy from the wizard refuses it (inline,
 * reusable, or kept from an earlier deploy), a redeploy without the wizard warns about it, and the deploy refuses a
 * selected Secret that does not exist or a variable no Secret provides, before Trino fails to start on them.
 */
class CommandServiceCatalogCredentialsTest {

    private static final String NS = "analytics";

    private ViewContext ctx;
    private DataStore ds;
    private KubernetesService k8s;
    private CommandService service;

    @BeforeEach
    void setUp() throws Exception {
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        ds = mock(DataStore.class);
        when(ctx.getDataStore()).thenReturn(ds);
        k8s = mock(KubernetesService.class);
        service = mock(CommandService.class, CALLS_REAL_METHODS);
        set("ctx", ctx);
        set("kubernetesService", k8s);
    }

    private void set(String field, Object value) throws Exception {
        Field f = CommandService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static HelmDeployRequest request(Map<String, Object> values, Map<String, Object> customCatalogs) {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName("trino");
        request.setNamespace(NS);
        request.setValues(values);
        Map<String, Object> form = new LinkedHashMap<>();
        if (customCatalogs != null) {
            form.put("customCatalogs", customCatalogs);
        }
        request.setFormValues(form);
        return request;
    }

    private static Map<String, Object> values(String... credentialSecrets) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(CommandService.CATALOG_CREDENTIAL_SECRETS, new ArrayList<>(List.of(credentialSecrets)));
        return values;
    }

    private void secret(String name, String... keys) {
        SecretBuilder secret = new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata();
        for (String key : keys) {
            secret.addToData(key, "eA==");
        }
        when(k8s.getSecret(NS, name)).thenReturn(secret.build());
    }

    private static final String PG_FROM_ENV = "[pg]\nconnector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/x\n"
            + "connection-user=trino\nconnection-password=${ENV:PG_PASSWORD}\n";

    @Test
    void anInlineCatalogWithAPasswordWrittenOutIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CommandService.parseCustomCatalogs(
                "[pg]\nconnector.name=postgresql\nconnection-password=hunter2\n"));
        assertTrue(e.getMessage().contains("connection-password"), e.getMessage());
        assertTrue(e.getMessage().contains("Secrets with catalog credentials"), e.getMessage());
        assertTrue(e.getMessage().contains("connection-password=${ENV:PG_CONNECTION_PASSWORD}"), e.getMessage());
        assertFalse(e.getMessage().contains("hunter2"), "the message must not repeat the credential");

        assertEquals(Map.of("pg", PG_FROM_ENV.substring("[pg]\n".length()).trim()), CommandService.parseCustomCatalogs(PG_FROM_ENV));
    }

    @Test
    void theInlineCatalogsAndTheCredentialSecretsAreAddedTogether() {
        secret("pg-credentials", "PG_PASSWORD");
        HelmDeployRequest request = request(values("pg-credentials"), Map.of("text", PG_FROM_ENV));

        Map<String, String> added = service.applyCustomCatalogs(request);
        service.checkCatalogCredentials(request, added);

        assertEquals(List.of("pg"), new ArrayList<>(added.keySet()));
        assertTrue(String.valueOf(((Map<?, ?>) request.getValues().get("catalogs")).get("pg")).contains("${ENV:PG_PASSWORD}"));
    }

    @Test
    void aSelectedSecretMissingFromTheNamespaceIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.checkCatalogCredentials(request(values("pg-credentials"), null), Collections.emptyMap()));
        assertTrue(e.getMessage().contains("'pg-credentials'"), e.getMessage());
        assertTrue(e.getMessage().contains("kubectl -n analytics create secret generic pg-credentials"), e.getMessage());
    }

    @Test
    void aVariableNoSelectedSecretProvidesIsRefused() {
        secret("pg-credentials", "PG_USER");
        Map<String, String> catalogs = CommandService.parseCustomCatalogs(PG_FROM_ENV);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.checkCatalogCredentials(request(values("pg-credentials"), null), catalogs));
        assertTrue(e.getMessage().contains("'pg' reads PG_PASSWORD"), e.getMessage());
        assertTrue(e.getMessage().contains("(pg-credentials)"), e.getMessage());

        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> service.checkCatalogCredentials(request(values(), null), catalogs));
        assertTrue(none.getMessage().contains("no Secret is selected"), none.getMessage());
    }

    @Test
    void variablesFromTheChartOrTheValuesEnvNeedNoSecret() {
        Map<String, String> catalogs = Map.of("lake",
                "connector.name=hive\nhive.metastore.thrift.client.ssl.trust-certificate-password=${ENV:TRUSTSTORE_PASSWORD}\n"
                        + "s3.aws-secret-key=${ENV:LAKE_SECRET_KEY}\n");
        Map<String, Object> values = values();
        values.put("env", List.of(Map.of("name", "LAKE_SECRET_KEY", "value", "from-the-values")));

        assertDoesNotThrow(() -> service.checkCatalogCredentials(request(values, null), catalogs));
    }

    @Test
    void variablesAreNotCheckedWhenKdpsCannotSeeEveryKey() {
        Map<String, String> catalogs = CommandService.parseCustomCatalogs(PG_FROM_ENV);
        Map<String, Object> withEnvFrom = values();
        withEnvFrom.put("envFrom", List.of(Map.of("configMapRef", Map.of("name", "extra-env"))));
        assertDoesNotThrow(() -> service.checkCatalogCredentials(request(withEnvFrom, null), catalogs));

        when(k8s.getSecret(eq(NS), eq("pg-credentials"))).thenThrow(new KubernetesClientException("forbidden"));
        assertDoesNotThrow(() -> service.checkCatalogCredentials(request(values("pg-credentials"), null), catalogs));
    }

    /**
     * A reusable catalog saved before KDPS refused credentials written out still holds one. Selected in the wizard, it
     * is refused like an inline one, with where to fix it, so it does not land in the catalog ConfigMap again.
     */
    @Test
    void aReusableCatalogSavedWithAPasswordIsRefusedFromTheWizard() throws Exception {
        TrinoCatalogEntity saved = new TrinoCatalogEntity();
        saved.setId("cat-1");
        saved.setName("legacy");
        saved.setPropertiesText("connector.name=mysql\nconnection-password=old-literal\n");
        when(ds.find(TrinoCatalogEntity.class, "cat-1")).thenReturn(saved);
        HelmDeployRequest request = request(values(), Map.of("refs", List.of("cat-1")));

        Map<String, String> added = service.applyCustomCatalogs(request);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.checkLiteralCatalogCredentials(request, null));

        assertEquals(List.of("legacy"), new ArrayList<>(added.keySet()));
        assertTrue(e.getMessage().contains("'legacy' writes connection-password out (a reusable catalog: edit it on the"
                + " Trino Catalogs page)"), e.getMessage());
        assertTrue(e.getMessage().contains("connection-password=${ENV:LEGACY_CONNECTION_PASSWORD}"), e.getMessage());
        assertFalse(e.getMessage().contains("old-literal"), "the message must not repeat the credential");
    }

    /** The release's values with the catalogs an earlier deploy wrote, the KDPS-built Hive catalog included. */
    private static Map<String, Object> valuesWithCatalogs(Map<String, String> catalogs) {
        Map<String, Object> values = values();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("hive", "connector.name=hive\nhive.metastore.client.keytab=/etc/security/keytabs/service.keytab\n"
                + "hive.metastore.thrift.client.ssl.trust-certificate-password=changeit\n");
        all.putAll(catalogs);
        values.put("catalogs", all);
        return values;
    }

    /**
     * Upgrade/Config starts from the release's values, so a catalog an earlier deploy wrote with a password (inline
     * text, which the wizard does not show again, or one saved from the release's Catalogs action) is sent again. From
     * the wizard it is refused, with how to replace it; the catalogs KDPS builds itself are not the operator's.
     */
    @Test
    void aCatalogKeptFromAnEarlierDeployIsRefusedFromTheWizard() {
        HelmDeployRequest request = request(valuesWithCatalogs(Map.of(
                "pg", "connector.name=postgresql\nconnection-user=trino\nconnection-password=hunter2\n",
                "kafka", "connector.name=kafka\nkafka.nodes=broker:9092\n")), Map.of("text", ""));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.checkLiteralCatalogCredentials(request, null));

        assertTrue(e.getMessage().startsWith("Catalog credentials written out: 'pg' writes connection-password out"
                + " (kept from an earlier deploy of the release: define it again under 'Additional Trino catalogs',"
                + " which replaces it)."), e.getMessage());
        assertTrue(e.getMessage().contains("namespace 'analytics'"), e.getMessage());
        assertFalse(e.getMessage().contains("hunter2"), "the message must not repeat the credential");
        assertFalse(e.getMessage().contains("'hive'"), "the Hive catalog is built by KDPS: " + e.getMessage());
        assertFalse(e.getMessage().contains("kafka"), e.getMessage());
    }

    /**
     * A resync, a security refresh or an autoscaling change carries no wizard: refusing would leave the release
     * without them, so the catalogs are named in a warning for the operation log instead.
     */
    @Test
    void aRedeployWithoutTheWizardIsWarnedAboutCredentialsWrittenOut() {
        K8sReleaseEntity recorded = new K8sReleaseEntity();
        recorded.setCatalogRefsJson("{\"legacy\":{\"id\":\"cat-1\",\"hash\":\"abc\"}}");
        HelmDeployRequest request = request(valuesWithCatalogs(Map.of(
                "legacy", "connector.name=mysql\nconnection-password=old-literal\n")), null);

        String warning = service.checkLiteralCatalogCredentials(request, recorded);

        assertTrue(warning.contains("'legacy' writes connection-password out (a reusable catalog: edit it on the Trino"
                + " Catalogs page)"), warning);
        assertTrue(warning.contains("Run Upgrade/Config on the release"), warning);
        assertFalse(warning.contains("old-literal"), warning);
    }

    @Test
    void catalogsThatReadTheirCredentialsFromTheEnvironmentRaiseNothing() {
        HelmDeployRequest request = request(valuesWithCatalogs(Map.of("pg", PG_FROM_ENV.substring("[pg]\n".length()))),
                Map.of("text", ""));

        assertNull(service.checkLiteralCatalogCredentials(request, null));
        assertNull(service.checkLiteralCatalogCredentials(request(values(), Map.of("text", "")), null));
    }

    @Test
    void aListValuedFeatureIsOnWhenItHasEntries() {
        assertTrue(CommandService.featureOn(List.of("pg-credentials")));
        assertFalse(CommandService.featureOn(List.of()));
        assertTrue(CommandService.featureOn("true"));
        assertTrue(CommandService.featureOn(Boolean.TRUE));
        assertFalse(CommandService.featureOn("false"));
        assertFalse(CommandService.featureOn(null));
    }
}
