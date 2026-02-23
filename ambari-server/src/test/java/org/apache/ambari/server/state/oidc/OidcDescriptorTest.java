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
package org.apache.ambari.server.state.oidc;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import junit.framework.Assert;

public class OidcDescriptorTest {

  private static final OidcDescriptorFactory OIDC_DESCRIPTOR_FACTORY = new OidcDescriptorFactory();

  private static final String JSON_VALUE =
    "{" +
      "\"services\": [" +
        "{" +
          "\"name\": \"POLARIS\"," +
          "\"enabled\": true," +
          "\"configurations\": [" +
            "{" +
              "\"polaris-application-properties\": {" +
                "\"polaris.authentication.type\": \"external\"" +
              "}" +
            "}" +
          "]," +
          "\"clients\": [" +
            "{" +
              "\"name\": \"polaris-oidc\"," +
              "\"client_id\": \"${cluster_name}-polaris\"," +
              "\"realm\": \"${oidc_realm}\"," +
              "\"public_client\": false," +
              "\"service_accounts_enabled\": true," +
              "\"direct_access_grants_enabled\": false," +
              "\"standard_flow_enabled\": false," +
              "\"redirect_uris\": [\"https://polaris.example/callback\"]," +
              "\"attributes\": {\"policy\": \"standard\"}," +
              "\"secret_alias\": \"polaris.oidc.client.secret\"," +
              "\"configurations\": [" +
                "{" +
                  "\"polaris-application-properties\": {" +
                    "\"quarkus.oidc.client-id\": \"${client_id}\"" +
                  "}" +
                "}" +
              "]" +
            "}" +
          "]" +
        "}" +
      "]" +
    "}";

  @Test
  public void testCreateFromJson() throws Exception {
    OidcDescriptor descriptor = OIDC_DESCRIPTOR_FACTORY.createInstance(JSON_VALUE);
    Assert.assertNotNull(descriptor);

    Map<String, OidcServiceDescriptor> services = descriptor.getServices();
    Assert.assertNotNull(services);
    Assert.assertEquals(1, services.size());

    OidcServiceDescriptor service = services.get("POLARIS");
    Assert.assertNotNull(service);
    Assert.assertEquals(Boolean.TRUE, service.isEnabled());

    List<Map<String, Map<String, String>>> serviceConfigs = service.getConfigurations();
    Assert.assertNotNull(serviceConfigs);
    Assert.assertEquals(1, serviceConfigs.size());
    Assert.assertEquals("external", serviceConfigs.get(0)
      .get("polaris-application-properties").get("polaris.authentication.type"));

    List<OidcClientDescriptor> clients = service.getClients();
    Assert.assertNotNull(clients);
    Assert.assertEquals(1, clients.size());

    OidcClientDescriptor client = clients.get(0);
    Assert.assertEquals("polaris-oidc", client.getName());
    Assert.assertEquals("${cluster_name}-polaris", client.getClientId());
    Assert.assertEquals("${oidc_realm}", client.getRealm());
    Assert.assertEquals(Boolean.FALSE, client.isPublicClient());
    Assert.assertEquals(Boolean.TRUE, client.isServiceAccountsEnabled());
    Assert.assertEquals(Boolean.FALSE, client.isDirectAccessGrantsEnabled());
    Assert.assertEquals(Boolean.FALSE, client.isStandardFlowEnabled());
    Assert.assertEquals(1, client.getRedirectUris().size());
    Assert.assertEquals("https://polaris.example/callback", client.getRedirectUris().get(0));
    Assert.assertEquals("standard", client.getAttributes().get("policy"));
    Assert.assertEquals("polaris.oidc.client.secret", client.getSecretAlias());

    List<Map<String, Map<String, String>>> clientConfigs = client.getConfigurations();
    Assert.assertNotNull(clientConfigs);
    Assert.assertEquals(1, clientConfigs.size());
    Assert.assertEquals("${client_id}", clientConfigs.get(0)
      .get("polaris-application-properties").get("quarkus.oidc.client-id"));
  }

  @Test
  public void testUpdate() throws Exception {
    OidcDescriptor base = OIDC_DESCRIPTOR_FACTORY.createInstance(
      "{" +
        "\"services\": [" +
          "{" +
            "\"name\": \"POLARIS\"," +
            "\"clients\": [" +
              "{" +
                "\"name\": \"client-a\"," +
                "\"client_id\": \"client-a\"" +
              "}" +
            "]" +
          "}" +
        "]" +
      "}");

    OidcDescriptor updates = OIDC_DESCRIPTOR_FACTORY.createInstance(
      "{" +
        "\"services\": [" +
          "{" +
            "\"name\": \"POLARIS\"," +
            "\"enabled\": false," +
            "\"clients\": [" +
              "{" +
                "\"name\": \"client-a\"," +
                "\"client_id\": \"client-a-updated\"" +
              "}," +
              "{" +
                "\"name\": \"client-b\"," +
                "\"client_id\": \"client-b\"" +
              "}" +
            "]" +
          "}" +
        "]" +
      "}");

    base.update(updates);

    OidcServiceDescriptor service = base.getService("POLARIS");
    Assert.assertNotNull(service);
    Assert.assertEquals(Boolean.FALSE, service.isEnabled());

    List<OidcClientDescriptor> clients = service.getClients();
    Assert.assertNotNull(clients);
    Assert.assertEquals(2, clients.size());

    String clientAId = null;
    for (OidcClientDescriptor client : clients) {
      if ("client-a".equals(client.getName())) {
        clientAId = client.getClientId();
      }
    }
    Assert.assertEquals("client-a-updated", clientAId);
  }
}
