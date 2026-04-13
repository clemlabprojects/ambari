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

package org.apache.ambari.server.serveraction.oidc;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class KeycloakOidcOperationHandler implements OidcOperationHandler {
  private Client client;
  private PrincipalKeyCredential adminCredential;
  private OidcProviderConfiguration configuration;

  @Override
  public void open(PrincipalKeyCredential administratorCredential, OidcProviderConfiguration configuration)
    throws OidcOperationException {
    if (administratorCredential == null) {
      throw new OidcOperationException("Missing OIDC administrator credential");
    }
    this.adminCredential = administratorCredential;
    this.configuration = configuration;
    this.client = createClient(configuration.isVerifyTls());
  }

  @Override
  public OidcClientResult ensureClient(OidcClientDescriptor descriptor, String realm) throws OidcOperationException {
    if (descriptor == null) {
      throw new OidcOperationException("Missing OIDC client descriptor");
    }
    if (descriptor.getClientId() == null) {
      throw new OidcOperationException("Missing OIDC client_id for descriptor");
    }

    String targetRealm = (realm == null || realm.isEmpty())
      ? configuration.getRealm()
      : realm;

    String token = getAdminAccessToken();
    String clientInternalId = getClientInternalId(token, targetRealm, descriptor.getClientId());
    if (clientInternalId == null) {
      createClient(token, targetRealm, descriptor);
      clientInternalId = getClientInternalId(token, targetRealm, descriptor.getClientId());
    }

    String secret = getClientSecret(token, targetRealm, clientInternalId);
    return new OidcClientResult(descriptor.getClientId(), clientInternalId, secret);
  }

  @Override
  public void deleteClient(OidcClientDescriptor descriptor, String realm) throws OidcOperationException {
    if (descriptor == null) {
      throw new OidcOperationException("Missing OIDC client descriptor");
    }
    if (descriptor.getClientId() == null) {
      throw new OidcOperationException("Missing OIDC client_id for descriptor");
    }

    String targetRealm = (realm == null || realm.isEmpty())
      ? configuration.getRealm()
      : realm;

    String token = getAdminAccessToken();
    String clientInternalId = getClientInternalId(token, targetRealm, descriptor.getClientId());
    if (clientInternalId == null) {
      return;
    }

    String endpoint = String.format("%s/admin/realms/%s/clients/%s",
      normalizeBaseUrl(configuration.getBaseUrl()), targetRealm, clientInternalId);

    Response response = client.target(endpoint)
      .request(MediaType.APPLICATION_JSON_TYPE)
      .header("Authorization", "Bearer " + token)
      .delete();

    if (response.getStatus() / 100 != 2 && response.getStatus() != 404) {
      String message = response.readEntity(String.class);
      throw new OidcOperationException(String.format("Failed to delete Keycloak client %s (status %s): %s",
        descriptor.getClientId(), response.getStatus(), message));
    }
  }

  @Override
  public void close() throws OidcOperationException {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  private String getAdminAccessToken() throws OidcOperationException {
    String baseUrl = normalizeBaseUrl(configuration.getBaseUrl());
    String tokenEndpoint = String.format("%s/realms/%s/protocol/openid-connect/token",
      baseUrl, configuration.getAdminRealm());

    Form form = new Form();
    form.param("grant_type", "password");
    form.param("client_id", configuration.getAdminClientId());
    if (configuration.getAdminClientSecret() != null && !configuration.getAdminClientSecret().isEmpty()) {
      form.param("client_secret", configuration.getAdminClientSecret());
    }
    form.param("username", adminCredential.getPrincipal());
    form.param("password", new String(adminCredential.getKey()));

    Response response = client.target(tokenEndpoint)
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.form(form));

    String payload = response.readEntity(String.class);
    if (response.getStatus() / 100 != 2) {
      throw new OidcOperationException(String.format("Failed to obtain Keycloak admin token (status %s): %s",
        response.getStatus(), payload));
    }

    JsonObject json = new JsonParser().parse(payload).getAsJsonObject();
    JsonElement token = json.get("access_token");
    if (token == null) {
      throw new OidcOperationException("Keycloak admin token response missing access_token");
    }

    return token.getAsString();
  }

  private String getClientInternalId(String token, String realm, String clientId) throws OidcOperationException {
    String endpoint = String.format("%s/admin/realms/%s/clients", normalizeBaseUrl(configuration.getBaseUrl()), realm);
    Response response = client.target(endpoint)
      .queryParam("clientId", clientId)
      .request(MediaType.APPLICATION_JSON_TYPE)
      .header("Authorization", "Bearer " + token)
      .get();

    String payload = response.readEntity(String.class);
    if (response.getStatus() / 100 != 2) {
      throw new OidcOperationException(String.format("Failed to query Keycloak client %s (status %s): %s",
        clientId, response.getStatus(), payload));
    }

    JsonArray results = new JsonParser().parse(payload).getAsJsonArray();
    if (results.size() == 0) {
      return null;
    }

    JsonObject client = results.get(0).getAsJsonObject();
    JsonElement id = client.get("id");
    return (id == null) ? null : id.getAsString();
  }

  private void createClient(String token, String realm, OidcClientDescriptor descriptor) throws OidcOperationException {
    String endpoint = String.format("%s/admin/realms/%s/clients", normalizeBaseUrl(configuration.getBaseUrl()), realm);

    JsonObject payload = new JsonObject();
    payload.addProperty("clientId", descriptor.getClientId());
    payload.addProperty("protocol", "openid-connect");
    payload.addProperty("publicClient", getBooleanOrDefault(descriptor.isPublicClient(), false));
    payload.addProperty("serviceAccountsEnabled", getBooleanOrDefault(descriptor.isServiceAccountsEnabled(), true));
    payload.addProperty("directAccessGrantsEnabled", getBooleanOrDefault(descriptor.isDirectAccessGrantsEnabled(), true));
    payload.addProperty("standardFlowEnabled", getBooleanOrDefault(descriptor.isStandardFlowEnabled(), false));

    if (descriptor.getRedirectUris() != null) {
      JsonArray redirectUris = new JsonArray();
      for (String uri : descriptor.getRedirectUris()) {
        redirectUris.add(uri);
      }
      payload.add("redirectUris", redirectUris);
    }

    if (descriptor.getAttributes() != null) {
      JsonObject attributes = new JsonObject();
      for (Map.Entry<String, String> entry : descriptor.getAttributes().entrySet()) {
        attributes.addProperty(entry.getKey(), entry.getValue());
      }
      payload.add("attributes", attributes);
    }

    Response response = client.target(endpoint)
      .request(MediaType.APPLICATION_JSON_TYPE)
      .header("Authorization", "Bearer " + token)
      .post(Entity.json(payload.toString()));

    if (response.getStatus() == 409) {
      return;
    }

    if (response.getStatus() / 100 != 2) {
      String message = response.readEntity(String.class);
      throw new OidcOperationException(String.format("Failed to create Keycloak client %s (status %s): %s",
        descriptor.getClientId(), response.getStatus(), message));
    }
  }

  private String getClientSecret(String token, String realm, String internalId) throws OidcOperationException {
    if (internalId == null) {
      throw new OidcOperationException("Missing Keycloak client internal id");
    }

    String endpoint = String.format("%s/admin/realms/%s/clients/%s/client-secret",
      normalizeBaseUrl(configuration.getBaseUrl()), realm, internalId);

    Response response = client.target(endpoint)
      .request(MediaType.APPLICATION_JSON_TYPE)
      .header("Authorization", "Bearer " + token)
      .get();

    String payload = response.readEntity(String.class);
    if (response.getStatus() / 100 != 2) {
      throw new OidcOperationException(String.format("Failed to read Keycloak client secret (status %s): %s",
        response.getStatus(), payload));
    }

    JsonObject json = new JsonParser().parse(payload).getAsJsonObject();
    JsonElement value = json.get("value");
    return (value == null) ? null : value.getAsString();
  }

  private Client createClient(boolean verifyTls) throws OidcOperationException {
    if (verifyTls) {
      return ClientBuilder.newClient();
    }

    try {
      TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }};

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustAllCerts, new SecureRandom());
      HostnameVerifier verifier = (hostname, session) -> true;

      return ClientBuilder.newBuilder()
        .sslContext(sslContext)
        .hostnameVerifier(verifier)
        .build();
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
      throw new OidcOperationException("Failed to create Keycloak HTTP client", e);
    }
  }

  private String normalizeBaseUrl(String baseUrl) {
    if (baseUrl == null) {
      return null;
    }
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl;
  }

  private boolean getBooleanOrDefault(Boolean value, boolean defaultValue) {
    return (value == null) ? defaultValue : value;
  }
}
