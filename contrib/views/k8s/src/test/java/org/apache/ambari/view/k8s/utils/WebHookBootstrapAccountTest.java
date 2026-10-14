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

package org.apache.ambari.view.k8s.utils;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.ViewDefinition;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.security.FileBasedCredentialStore;
import org.apache.ambari.view.k8s.security.MasterKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The keytab webhook's Ambari account gets what the webhook needs and no more: a password derived from its client key,
 * and access to the KDPS view instance only. Each view instance has an account of its own, since each issues its own
 * webhook key; one instance's setup or retirement leaves the other instances' accounts alone. The account earlier
 * versions shared between all the instances, an Ambari administrator, is deleted, and their stored password is no
 * longer kept. While the webhook is still installed, switching away from WEBHOOK mode keeps the account the webhook
 * signs in with.
 */
@EnableKubernetesMockClient
class WebHookBootstrapAccountTest {

  private static final String MWC_PATH = "/apis/admissionregistration.k8s.io/v1/mutatingwebhookconfigurations";

  @TempDir
  Path tmp;

  KubernetesMockServer server;
  KubernetesClient client;

  /** The view instance most tests run as. */
  private ViewContext ctx;
  /** Ambari's credential store, which the whole server shares between view instances. */
  private AmbariAliasResolver aliases;
  private final AmbariActionClient ambari = mock(AmbariActionClient.class);

  @BeforeEach
  void setUp() {
    ctx = instance("KDPS");
    FileBasedCredentialStore jceks = new FileBasedCredentialStore(tmp.resolve("credentials.jceks").toFile());
    jceks.setMasterKeyService(new MasterKeyService() {
      @Override
      public char[] getMasterSecret() {
        return "test-master-key".toCharArray();
      }

      @Override
      public boolean isMasterKeyInitialized() {
        return true;
      }
    });
    aliases = new AmbariAliasResolver(ctx, jceks);
  }

  private static ViewContext instance(String name) {
    ViewContext context = mock(ViewContext.class);
    when(context.getViewName()).thenReturn("K8S-VIEW");
    when(context.getInstanceName()).thenReturn(name);
    ViewDefinition definition = mock(ViewDefinition.class);
    when(definition.getVersion()).thenReturn("1.0.0.8");
    when(context.getViewDefinition()).thenReturn(definition);
    return context;
  }

  private String recorded(String alias) throws Exception {
    return aliases.listAliases().contains(alias) ? new String(aliases.getPasswordForAlias(alias)) : null;
  }

  @Test
  void eachViewInstanceHasItsOwnAccountName() {
    assertEquals("webhook-user-kdps", WebHookBootstrap.webhookUserName(ctx, WebHookBootstrap.KEYTAB_WEBHOOK));
    assertEquals("webhook-user-kdps_prod-2",
        WebHookBootstrap.webhookUserName(instance("KDPS_PROD.2"), WebHookBootstrap.KEYTAB_WEBHOOK));
    assertEquals("ambari.k8s.webhook.user.kdps", WebHookBootstrap.accountAlias(ctx));

    ViewContext custom = instance("KDPS");
    when(custom.getAmbariProperty("k8s.view.webhooks.keytab-webhook.backend.webhookUsername")).thenReturn("Kdps-Hook");
    assertEquals("kdps-hook-kdps", WebHookBootstrap.webhookUserName(custom, WebHookBootstrap.KEYTAB_WEBHOOK));
  }

  @Test
  void theAccountMayOnlyOpenTheViewInstance() throws Exception {
    when(ambari.revokeAmbariAdministrator("webhook-user-kdps")).thenReturn(true);

    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, "webhook-user-kdps", "derived-password");

    verify(ambari).createWebhookAmbariUser("webhook-user-kdps", "derived-password");
    verify(ambari).revokeAmbariAdministrator("webhook-user-kdps");
    verify(ambari).grantViewInstanceUser("K8S-VIEW", "1.0.0.8", "KDPS", "webhook-user-kdps");
    verifyNoMoreInteractions(ambari);
    // The account is recorded as this instance's own, so that only it is ever removed; its password is not kept.
    assertEquals("webhook-user-kdps", recorded(WebHookBootstrap.accountAlias(ctx)));
    assertFalse(aliases.listAliases().contains(WebHookBootstrap.PASS_ALIAS));
    assertFalse(aliases.listAliases().contains(WebHookBootstrap.USER_ALIAS));
  }

  @Test
  void theAccountEarlierVersionsSharedBetweenInstancesIsDeleted() throws Exception {
    aliases.addAliasToCredentialStore(WebHookBootstrap.USER_ALIAS, "webhook-user");
    when(ambari.deleteUser("webhook-user")).thenReturn(true);

    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, "webhook-user-kdps", "derived-password");

    verify(ambari).deleteUser("webhook-user");
    verify(ambari, never()).deleteUser("webhook-user-kdps");
    assertNull(recorded(WebHookBootstrap.USER_ALIAS));
    assertEquals("webhook-user-kdps", recorded(WebHookBootstrap.accountAlias(ctx)));
  }

  @Test
  void oneInstanceLeavesTheAccountOfAnotherAlone() throws Exception {
    ViewContext other = instance("KDPS_PROD");
    String mine = WebHookBootstrap.webhookUserName(ctx, WebHookBootstrap.KEYTAB_WEBHOOK);
    String theirs = WebHookBootstrap.webhookUserName(other, WebHookBootstrap.KEYTAB_WEBHOOK);
    assertNotEquals(mine, theirs);

    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, mine, "password-from-my-key");
    WebHookBootstrap.ensureWebhookAccount(other, ambari, aliases, theirs, "password-from-their-key");

    // Each setup sets the password of its own account only.
    verify(ambari).createWebhookAmbariUser(mine, "password-from-my-key");
    verify(ambari).createWebhookAmbariUser(theirs, "password-from-their-key");
    verify(ambari, never()).createWebhookAmbariUser(eq(mine), eq("password-from-their-key"));
    verify(ambari).grantViewInstanceUser("K8S-VIEW", "1.0.0.8", "KDPS_PROD", theirs);

    // A third instance saved in PRE_PROVISIONED mode, which never ran WEBHOOK mode, removes nothing.
    assertNull(WebHookBootstrap.retireWebhookAccount(instance("KDPS_DEV"), ambari, aliases, false));
    verify(ambari, never()).deleteUser(anyString());

    // The other instance retires its own account; this instance's account stays.
    assertNull(WebHookBootstrap.retireWebhookAccount(other, ambari, aliases, false));
    verify(ambari).deleteUser(theirs);
    verify(ambari, never()).deleteUser(mine);
    assertNull(recorded(WebHookBootstrap.accountAlias(other)));
    assertEquals(mine, recorded(WebHookBootstrap.accountAlias(ctx)));
  }

  @Test
  void theAccountIsKeptWhileTheWebhookIsStillInstalled() throws Exception {
    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, "webhook-user-kdps", "derived-password");

    String installed = WebHookBootstrap.retireWebhookAccount(ctx, ambari, aliases, true);
    assertNotNull(installed);
    assertTrue(installed.contains("still installed"), installed);
    assertTrue(installed.contains("Redeploy those releases"), installed);

    String unknown = WebHookBootstrap.retireWebhookAccount(ctx, ambari, aliases, null);
    assertNotNull(unknown);
    assertTrue(unknown.contains("could not check"), unknown);

    verify(ambari, never()).deleteUser(anyString());
    assertEquals("webhook-user-kdps", recorded(WebHookBootstrap.accountAlias(ctx)));

    // Once the webhook is uninstalled, saving the settings again removes the account.
    assertNull(WebHookBootstrap.retireWebhookAccount(ctx, ambari, aliases, false));
    verify(ambari).deleteUser("webhook-user-kdps");
    assertNull(recorded(WebHookBootstrap.accountAlias(ctx)));
  }

  @Test
  void theSharedAdministratorAccountIsDeletedEvenWhileTheWebhookIsInstalled() throws Exception {
    aliases.addAliasToCredentialStore(WebHookBootstrap.USER_ALIAS, "webhook-user");

    assertNull(WebHookBootstrap.retireWebhookAccount(ctx, ambari, aliases, true));

    verify(ambari).deleteUser("webhook-user");
    assertNull(recorded(WebHookBootstrap.USER_ALIAS));
  }

  @Test
  void anAccountAmbariRefusedToDeleteIsNotForgotten() throws Exception {
    // A view administrator who is not an Ambari administrator saves the settings: Ambari refuses every change.
    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, "webhook-user-kdps", "derived-password");
    aliases.addAliasToCredentialStore(WebHookBootstrap.USER_ALIAS, "webhook-user");
    when(ambari.deleteUser(anyString())).thenThrow(new java.io.IOException("HTTP 403 from Ambari: Forbidden"));

    String warning = WebHookBootstrap.retireWebhookAccount(ctx, ambari, aliases, false);

    assertNotNull(warning);
    assertTrue(warning.contains("'webhook-user'"), warning);
    assertTrue(warning.contains("'webhook-user-kdps'"), warning);
    assertTrue(warning.contains("Ambari administrator"), warning);
    // Both accounts stay recorded, so that the next save by an Ambari administrator removes them.
    assertEquals("webhook-user", recorded(WebHookBootstrap.USER_ALIAS));
    assertEquals("webhook-user-kdps", recorded(WebHookBootstrap.accountAlias(ctx)));

    // The setup of a WEBHOOK deploy goes on, and keeps the record too.
    WebHookBootstrap.ensureWebhookAccount(ctx, ambari, aliases, "webhook-user-kdps", "derived-password");
    assertEquals("webhook-user", recorded(WebHookBootstrap.USER_ALIAS));
  }

  @Test
  void tellsWhetherTheKeytabWebhookIsInstalled() {
    KubernetesService kube = mock(KubernetesService.class);
    when(kube.getClient()).thenReturn(client);

    server.expect().get().withPath(MWC_PATH).andReturn(200, new MutatingWebhookConfigurationListBuilder()
        .addToItems(new MutatingWebhookConfigurationBuilder()
            .withNewMetadata().withName("other-webhook").endMetadata()
            .addNewWebhook().withName("other.example.com")
            .withNewClientConfig().withNewService().withName("other").withNamespace("other-ns").endService()
            .endClientConfig().endWebhook()
            .build())
        .build()).once();
    assertEquals(Boolean.FALSE, WebHookBootstrap.keytabWebhookInstalled(ctx, kube));

    server.expect().get().withPath(MWC_PATH).andReturn(200, new MutatingWebhookConfigurationListBuilder()
        .addToItems(new MutatingWebhookConfigurationBuilder()
            .withNewMetadata().withName("keytab-webhook").endMetadata()
            .addNewWebhook().withName("keytab.injector.clemlab.com")
            .withNewClientConfig().withNewService().withName("keytab-webhook")
            .withNamespace("ambari-mutating-webhooks").endService().endClientConfig().endWebhook()
            .build())
        .build()).once();
    assertEquals(Boolean.TRUE, WebHookBootstrap.keytabWebhookInstalled(ctx, kube));

    server.expect().get().withPath(MWC_PATH).andReturn(403, "{}").once();
    assertNull(WebHookBootstrap.keytabWebhookInstalled(ctx, kube), "cannot tell");
    assertNull(WebHookBootstrap.keytabWebhookInstalled(ctx, null), "no cluster");
  }
}
