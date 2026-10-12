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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.dto.security.LdapConfig;
import org.apache.ambari.view.k8s.dto.security.OidcConfig;
import org.apache.ambari.view.k8s.dto.security.SecurityConfigDTO;
import org.apache.ambari.view.k8s.dto.security.SecurityProfilesDTO;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.SecurityProfileEntity;
import org.junit.jupiter.api.Test;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * /configurations/{namespace}/{name} only serves KDPS admins, and only Secrets the view manages as configuration
 * files: keytabs, passwords and Helm state can neither be read, deleted nor overwritten through it.
 */
@EnableKubernetesMockClient(crud = true)
class ConfigurationResourceManagedConfigTest {

  KubernetesClient client;

  private static final String NS = "analytics";

  private static ViewContext ctx(String user, String allowedNamespaces) {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    Map<String, String> props = new HashMap<>();
    props.put("view.admin.users", "lead");
    props.put("view.operator.users", "ops");
    props.put("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut");
    if (allowedNamespaces != null) props.put("k8s.view.allowed.namespaces", allowedNamespaces);
    when(ctx.getProperties()).thenReturn(props);
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);
    when(ctx.getInstanceName()).thenReturn("managed-config-test");
    return ctx;
  }

  private ConfigurationResource resource(ViewContext ctx) {
    return new ConfigurationResource(ctx, new KubernetesService(ctx, client, mock(HelmClient.class), true), null);
  }

  private ConfigurationResource admin() {
    return resource(ctx("lead", null));
  }

  private void createSecret(String name, Map<String, String> labels, Map<String, String> data) {
    Map<String, String> encoded = new HashMap<>();
    data.forEach((k, v) -> encoded.put(k, Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
    client.secrets().inNamespace(NS).resource(new SecretBuilder()
        .withNewMetadata().withName(name).withNamespace(NS).withLabels(labels).endMetadata()
        .withType("Opaque").withData(encoded).build()).create();
  }

  private Secret secret(String name) {
    return client.secrets().inNamespace(NS).withName(name).get();
  }

  private static ConfigurationResource.ManagedConfigDTO config(String name, String filename, String content) {
    ConfigurationResource.ManagedConfigDTO dto = new ConfigurationResource.ManagedConfigDTO();
    dto.namespace = NS;
    dto.name = name;
    dto.filename = filename;
    dto.content = content;
    dto.language = "python";
    dto.description = "test";
    return dto;
  }

  private static void assertForbidden(Supplier<Response> call) {
    WebApplicationException e = assertThrows(WebApplicationException.class, call::get);
    assertInstanceOf(ForbiddenException.class, e);
    assertEquals(403, e.getResponse().getStatus());
  }

  private static String error(Response r) {
    return String.valueOf(((Map<?, ?>) r.getEntity()).get("error"));
  }

  @Test
  void readOnlyUsersAndOperatorsAreRefusedEveryManagedConfigMethod() {
    for (String user : List.of("audit", "ops")) {
      KubernetesService k8s = mock(KubernetesService.class);
      ConfigurationResource api = new ConfigurationResource(ctx(user, null), k8s, null);

      assertForbidden(() -> api.getConfig(NS, "trino-keytab", "trino.keytab"));
      assertForbidden(() -> api.saveConfig(config("trino-keytab", "trino.keytab", "x")));
      assertForbidden(() -> api.deleteConfig(NS, "trino-keytab"));
      verifyNoInteractions(k8s);
    }
  }

  @Test
  void anAdminGets404ForASecretTheViewDoesNotManage() {
    createSecret("trino-keytab", Map.of("app", "trino"), Map.of("trino.keytab", "KEYTAB-BYTES"));

    Response r = admin().getConfig(NS, "trino-keytab", "trino.keytab");

    assertEquals(404, r.getStatus());
    assertFalse(String.valueOf(r.getEntity()).contains("KEYTAB-BYTES"));
  }

  @Test
  void anAdminCannotDeleteASecretTheViewDoesNotManage() {
    createSecret("trino-keytab", Map.of(), Map.of("trino.keytab", "KEYTAB-BYTES"));

    assertEquals(404, admin().deleteConfig(NS, "trino-keytab").getStatus());
    assertNotNull(secret("trino-keytab"), "the Secret is still there");
  }

  @Test
  void savingNeverReplacesASecretTheViewDoesNotManage() {
    createSecret("db-credentials", Map.of(), Map.of("username", "app", "password", "s3cret"));

    Response r = admin().saveConfig(config("db-credentials", "superset_config.py", "print('x')"));

    assertEquals(409, r.getStatus());
    assertTrue(error(r).contains("not a managed configuration"));
    assertEquals(Map.of("username", "app", "password", "s3cret").keySet(), secret("db-credentials").getData().keySet());
  }

  @Test
  void anAdminCreatesReadsUpdatesAndDeletesAManagedConfig() {
    ConfigurationResource api = admin();

    assertEquals(200, api.saveConfig(config("superset-config", "superset_config.py", "A = 1")).getStatus());
    assertEquals("true", secret("superset-config").getMetadata().getLabels().get(KubernetesService.MANAGED_CONFIG_LABEL));
    assertEquals(200, api.saveConfig(config("superset-config", "superset_config.py", "A = 2")).getStatus());

    Response read = api.getConfig(NS, "superset-config", "superset_config.py");
    assertEquals(200, read.getStatus());
    assertEquals("A = 2", ((Map<?, ?>) read.getEntity()).get("content"));

    assertEquals(200, api.deleteConfig(NS, "superset-config").getStatus());
    assertNull(secret("superset-config"));
  }

  @Test
  void invalidCoordinatesAreRejectedBeforeReachingTheCluster() {
    ConfigurationResource api = admin();

    assertEquals(400, api.saveConfig(null).getStatus());
    assertEquals(400, api.saveConfig(config("Bad_Name", "f.py", "x")).getStatus());
    assertEquals(400, api.saveConfig(config("good-name", null, "x")).getStatus());
    assertEquals(400, api.saveConfig(config("good-name", "../etc/passwd", "x")).getStatus());
    assertEquals(400, api.saveConfig(config("good-name", "f.py", null)).getStatus());
    assertEquals(400, api.getConfig(NS, "good-name", null).getStatus());
    assertEquals(400, api.getConfig("Not A Namespace", "good-name", "f.py").getStatus());
    assertEquals(400, api.deleteConfig(NS, "").getStatus());
  }

  @Test
  void namespacesOutsideTheAllowListAreRefused() {
    createSecret("superset-config", Map.of(KubernetesService.MANAGED_CONFIG_LABEL, "true"), Map.of("f.py", "x"));
    ConfigurationResource api = resource(ctx("lead", "dashboarding"));

    assertEquals(403, api.getConfig(NS, "superset-config", "f.py").getStatus());
    assertEquals(403, api.deleteConfig(NS, "superset-config").getStatus());
    assertNotNull(secret("superset-config"));
  }

  @Test
  void operatorsReadTheSecurityProfilesWithoutTheirSecrets() throws Exception {
    SecurityConfigDTO profile = new SecurityConfigDTO();
    profile.mode = "ldap";
    profile.ldap = new LdapConfig();
    profile.ldap.url = "ldaps://ldap.example.com";
    profile.ldap.bindPassword = "bind-secret";
    profile.ldap.adBindPassword = "ad-secret";
    profile.oidc = new OidcConfig();
    profile.oidc.clientId = "trino";
    profile.oidc.clientSecret = "oidc-secret";
    profile.oidc.principalDomain = "example.com";
    profile.extraProperties = Map.of("token", "extra-secret");

    SecurityProfileEntity entity = new SecurityProfileEntity();
    entity.setId("corp");
    entity.setViewInstance("managed-config-test");
    entity.setDefault(true);
    entity.setProfileJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(profile));

    ViewContext ops = ctx("ops", null);
    DataStore store = mock(DataStore.class);
    when(ops.getDataStore()).thenReturn(store);
    when(store.findAll(eq(SecurityProfileEntity.class), any())).thenReturn(List.of(entity));

    Response r = new ConfigurationResource(ops, mock(KubernetesService.class), null).getSecurityConfig(null, null);
    assertEquals(200, r.getStatus());
    SecurityConfigDTO seen = ((SecurityProfilesDTO) r.getEntity()).profiles.get("corp");
    assertEquals("ldaps://ldap.example.com", seen.ldap.url);
    assertEquals("example.com", seen.oidc.principalDomain);
    assertNull(seen.ldap.bindPassword);
    assertNull(seen.ldap.adBindPassword);
    assertNull(seen.oidc.clientSecret);
    assertNull(seen.extraProperties);

    ViewContext lead = ctx("lead", null);
    when(lead.getDataStore()).thenReturn(store);
    SecurityConfigDTO full = ((SecurityProfilesDTO) new ConfigurationResource(lead, mock(KubernetesService.class), null)
        .getSecurityConfig(null, null).getEntity()).profiles.get("corp");
    assertEquals("bind-secret", full.ldap.bindPassword);
    assertEquals("oidc-secret", full.oidc.clientSecret);

    assertForbidden(() -> new ConfigurationResource(ctx("audit", null), mock(KubernetesService.class), null)
        .getSecurityConfig(null, null));
  }
}
