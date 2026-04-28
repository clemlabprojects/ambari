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

package org.apache.ambari.server.api.services;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.annotations.ApiIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * REST service that synchronously tests connectivity to a Keycloak server.
 */
public class ClusterOidcTestService {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterOidcTestService.class);

  private final String clusterName;

  public ClusterOidcTestService(String clusterName) {
    this.clusterName = clusterName;
  }

  @POST
  @ApiIgnore
  @Produces("application/json")
  @Consumes("application/json")
  public Response testOidcConnection(@Context HttpHeaders headers, String body) {
    JsonObject request;
    try {
      request = new JsonParser().parse(body).getAsJsonObject();
    } catch (Exception e) {
      LOG.error("Failed to parse OIDC test request body for cluster {}: {}", clusterName, e.getMessage());
      return badRequest("Failed to parse request body: " + e.getMessage());
    }

    String adminUrl = getString(request, "admin_url");
    String realm = getString(request, "realm");

    if (adminUrl == null || adminUrl.isEmpty() || realm == null || realm.isEmpty()) {
      return badRequest("admin_url and realm are required");
    }

    String adminRealm = getStringOrDefault(request, "admin_realm", "master");
    String adminClientId = getStringOrDefault(request, "admin_client_id", "admin-cli");
    String adminClientSecret = getStringOrDefault(request, "admin_client_secret", "");
    String adminUsername = getStringOrDefault(request, "admin_username", "admin");
    String adminPassword = getStringOrDefault(request, "admin_password", "");
    boolean verifyTls = getBooleanOrDefault(request, "verify_tls", true);

    String baseUrl = normalizeBaseUrl(adminUrl);
    String tokenEndpoint = String.format("%s/realms/%s/protocol/openid-connect/token",
      baseUrl, adminRealm);

    Client client = null;
    try {
      client = createClient(verifyTls);

      Form form = new Form();
      form.param("grant_type", "password");
      form.param("client_id", adminClientId);
      if (adminClientSecret != null && !adminClientSecret.isEmpty()) {
        form.param("client_secret", adminClientSecret);
      }
      form.param("username", adminUsername);
      form.param("password", adminPassword);

      Response kcResponse = client.target(tokenEndpoint)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .post(Entity.form(form));

      String payload = kcResponse.readEntity(String.class);
      int status = kcResponse.getStatus();

      if (status / 100 != 2) {
        String detail = String.format("Keycloak token endpoint returned HTTP %d: %s", status, payload);
        LOG.warn("OIDC test failed for cluster {}: {}", clusterName, detail);
        return errorResponse("Failed to connect: " + detail);
      }

      JsonObject json;
      try {
        json = new JsonParser().parse(payload).getAsJsonObject();
      } catch (Exception e) {
        String detail = "Keycloak response was not valid JSON: " + e.getMessage();
        LOG.warn("OIDC test failed for cluster {}: {}", clusterName, detail);
        return errorResponse("Failed to connect: " + detail);
      }

      if (!json.has("access_token")) {
        String detail = "Keycloak response did not contain access_token";
        LOG.warn("OIDC test failed for cluster {}: {}", clusterName, detail);
        return errorResponse("Failed to connect: " + detail);
      }

      String successMessage = String.format(
        "Successfully connected to Keycloak. Realm '%s' is accessible.", realm);
      LOG.info("OIDC test succeeded for cluster {}: {}", clusterName, successMessage);
      return okResponse(successMessage);

    } catch (Exception e) {
      String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
      LOG.error("OIDC test error for cluster {}: {}", clusterName, detail, e);
      return errorResponse("Failed to connect: " + detail);
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (Exception e) {
          LOG.warn("Failed to close JAX-RS client after OIDC test for cluster {}", clusterName, e);
        }
      }
    }
  }

  private Client createClient(boolean verifyTls) throws KeyManagementException, NoSuchAlgorithmException {
    if (verifyTls) {
      return ClientBuilder.newClient();
    }

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

  private String getString(JsonObject obj, String key) {
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) {
      return null;
    }
    String val = el.getAsString();
    return val.isEmpty() ? null : val;
  }

  private String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) {
      return defaultValue;
    }
    return el.getAsString();
  }

  private boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) {
      return defaultValue;
    }
    return el.getAsBoolean();
  }

  private Response okResponse(String message) {
    return Response.ok(buildJson("OK", message)).build();
  }

  private Response errorResponse(String message) {
    return Response.ok(buildJson("ERROR", message)).build();
  }

  private Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST)
      .entity(buildJson("ERROR", message))
      .type(MediaType.APPLICATION_JSON)
      .build();
  }

  private String buildJson(String status, String message) {
    JsonObject obj = new JsonObject();
    obj.addProperty("status", status);
    obj.addProperty("message", message);
    return obj.toString();
  }
}
