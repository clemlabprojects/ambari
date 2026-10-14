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

import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.serveraction.oidc.ConfigureOidcServerAction;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.commons.lang.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;

/**
 * REST service that synchronously tests connectivity to a Keycloak server.
 * <p/>
 * The test makes the Ambari server send Keycloak administrator credentials to a URL, so it is
 * reserved to cluster administrators. They only reach the identity providers configured for the
 * cluster: the hosts saved in {@code oidc-env}, plus those listed in
 * {@link Configuration#OIDC_CONNECTION_TEST_ALLOWED_HOSTS}. Ambari administrators may also test a
 * host that is not saved yet, which the OIDC wizard does before it saves the configuration of a
 * first enable or of a new identity provider. TLS certificates are verified unless the caller
 * explicitly turns verification off, and redirects are never followed.
 */
@StaticallyInject
public class ClusterOidcTestService {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterOidcTestService.class);

  /**
   * Setting up the cluster's single sign-on is part of its security configuration, like Kerberos,
   * so it requires the same authorization.
   */
  private static final Set<RoleAuthorization> REQUIRED_AUTHORIZATIONS =
      EnumSet.of(RoleAuthorization.CLUSTER_TOGGLE_KERBEROS);

  /**
   * Ambari administrators already control every cluster's security setup and the server itself, so
   * they may test an identity provider before it is saved for the cluster.
   */
  private static final Set<RoleAuthorization> ANY_HOST_AUTHORIZATIONS =
      EnumSet.of(RoleAuthorization.AMBARI_MANAGE_SETTINGS);

  /**
   * Realm names go into the token endpoint path unencoded, so they are kept to characters that
   * cannot change which path is requested.
   */
  private static final Pattern REALM_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

  /**
   * The admin URL may carry a context path (Keycloak's legacy {@code /auth}), nothing more exotic.
   */
  private static final Pattern PATH_PATTERN = Pattern.compile("^[A-Za-z0-9._~/-]*$");

  private static final String OIDC_ISSUER_URL = "oidc_issuer_url";

  private static final long CONNECT_TIMEOUT_SECONDS = 10;
  private static final long READ_TIMEOUT_SECONDS = 30;

  @Inject
  private static Clusters injectedClusters;

  @Inject
  private static Configuration injectedConfiguration;

  private final String clusterName;
  private final Clusters clusters;
  private final Configuration configuration;

  public ClusterOidcTestService(String clusterName) {
    this(clusterName, injectedClusters, injectedConfiguration);
  }

  ClusterOidcTestService(String clusterName, Clusters clusters, Configuration configuration) {
    this.clusterName = clusterName;
    this.clusters = clusters;
    this.configuration = configuration;
  }

  @POST
  @ApiIgnore
  @Produces("application/json")
  @Consumes("application/json")
  public Response testOidcConnection(@Context HttpHeaders headers, String body) {
    Cluster cluster;
    try {
      cluster = clusters.getCluster(clusterName);
    } catch (AmbariException e) {
      return jsonResponse(Response.Status.NOT_FOUND, "Cluster not found: " + clusterName);
    }

    // Authorization comes before the body is even read: nothing is parsed or contacted for a
    // caller who may not configure the cluster's security.
    if (!AuthorizationHelper.isAuthorized(ResourceType.CLUSTER, cluster.getResourceId(), REQUIRED_AUTHORIZATIONS)) {
      LOG.warn("Refused OIDC connection test on cluster {} for user {}: not a cluster administrator",
        clusterName, AuthorizationHelper.getAuthenticatedName());
      return jsonResponse(Response.Status.FORBIDDEN,
        "You are not authorized to test the OIDC connection of cluster '" + clusterName + "'.");
    }

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

    if (!REALM_PATTERN.matcher(realm).matches() || !REALM_PATTERN.matcher(adminRealm).matches()) {
      return badRequest("Realm names may only contain letters, digits, '.', '_' and '-'.");
    }

    URI adminUri;
    try {
      adminUri = parseAdminUrl(adminUrl);
    } catch (IllegalArgumentException e) {
      return badRequest(e.getMessage());
    }

    // The caller is a cluster administrator by now, and the OIDC wizard is open to them. The Ambari
    // web UI signs the user out on any 403, so a host outside the allowed list is refused like
    // any other unacceptable admin URL. That way the wizard shows the reason instead of
    // ending the session.
    String host = adminUri.getHost();
    if (!AuthorizationHelper.isAuthorized(ResourceType.AMBARI, null, ANY_HOST_AUTHORIZATIONS)
        && !isAllowedHost(host, allowedHosts(cluster))) {
      LOG.warn("Refused OIDC connection test on cluster {}: host {} is not a configured identity provider",
        clusterName, host);
      return badRequest(String.format(
        "The Keycloak host '%s' is not an identity provider configured for cluster '%s', so only an Ambari "
          + "administrator can test it before it is saved. An Ambari administrator can also allow it with %s "
          + "in ambari.properties.",
        host, clusterName, Configuration.OIDC_CONNECTION_TEST_ALLOWED_HOSTS.getKey()));
    }

    String baseUrl = normalizeBaseUrl(adminUri.getScheme() + "://" + adminUri.getRawAuthority()
      + StringUtils.defaultString(adminUri.getRawPath()));
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
        // Only Keycloak's own error fields are reported back, never the raw body: the caller learns
        // why the login failed, not what else the target answered.
        String keycloakError = keycloakError(payload);
        String detail = String.format("Keycloak token endpoint returned HTTP %d%s", status,
          keycloakError == null ? "" : ": " + keycloakError);
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

  /**
   * Accepts only a plain http(s) base URL, optionally with a context path such as {@code /auth}:
   * no credentials, query, fragment, encoded characters or dot segments, so that the request the
   * server sends always targets the token endpoint below that base.
   *
   * @param adminUrl the admin URL supplied by the caller
   * @return the parsed URL
   * @throws IllegalArgumentException with a message for the caller when the URL is not acceptable
   */
  static URI parseAdminUrl(String adminUrl) {
    URI uri;
    try {
      uri = new URI(adminUrl.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("admin_url is not a valid URL: " + e.getMessage());
    }

    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException("admin_url must be an http or https URL.");
    }
    if (StringUtils.isBlank(uri.getHost())) {
      throw new IllegalArgumentException("admin_url must name the Keycloak host.");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("admin_url must not contain credentials, a query or a fragment.");
    }

    String path = StringUtils.defaultString(uri.getRawPath());
    boolean dotSegment = false;
    for (String segment : path.split("/")) {
      if (".".equals(segment) || "..".equals(segment)) {
        dotSegment = true;
      }
    }
    if (!PATH_PATTERN.matcher(path).matches() || dotSegment) {
      throw new IllegalArgumentException("admin_url may only add a plain context path, such as /auth, to the host.");
    }
    return uri;
  }

  /**
   * Tells whether a host is one of the allowed identity provider hosts. An entry {@code *.domain}
   * matches every host below that domain.
   */
  static boolean isAllowedHost(String host, Set<String> allowedHosts) {
    if (StringUtils.isBlank(host)) {
      return false;
    }
    String candidate = host.toLowerCase(Locale.ROOT);
    for (String entry : allowedHosts) {
      if (entry.startsWith("*.")) {
        String domain = entry.substring(1);
        if (candidate.endsWith(domain) && candidate.length() > domain.length()) {
          return true;
        }
      } else if (entry.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The identity provider hosts of the cluster: those saved in {@code oidc-env} and those an
   * Ambari administrator allowed in ambari.properties.
   */
  private Set<String> allowedHosts(Cluster cluster) {
    Set<String> hosts = new HashSet<>();
    if (configuration != null) {
      hosts.addAll(configuration.getOidcConnectionTestAllowedHosts());
    }
    Config oidcEnv = cluster.getDesiredConfigByType(ConfigureOidcServerAction.OIDC_ENV);
    Map<String, String> properties = (oidcEnv == null) ? null : oidcEnv.getProperties();
    if (properties != null) {
      addHostOf(hosts, properties.get(ConfigureOidcServerAction.OIDC_ADMIN_URL));
      addHostOf(hosts, properties.get(OIDC_ISSUER_URL));
    }
    return hosts;
  }

  private static void addHostOf(Set<String> hosts, String url) {
    if (StringUtils.isBlank(url)) {
      return;
    }
    try {
      String host = new URI(url.trim()).getHost();
      if (StringUtils.isNotBlank(host)) {
        hosts.add(host.toLowerCase(Locale.ROOT));
      }
    } catch (URISyntaxException e) {
      LOG.debug("Ignoring unparseable OIDC URL in oidc-env: {}", e.getMessage());
    }
  }

  /**
   * Keycloak's {@code error_description} (or {@code error}) from a JSON error body, if any.
   */
  private static String keycloakError(String payload) {
    if (StringUtils.isBlank(payload)) {
      return null;
    }
    try {
      JsonObject json = new JsonParser().parse(payload).getAsJsonObject();
      String description = getString(json, "error_description");
      return (description != null) ? description : getString(json, "error");
    } catch (Exception e) {
      return null;
    }
  }

  private Client createClient(boolean verifyTls) throws KeyManagementException, NoSuchAlgorithmException {
    ClientBuilder builder = ClientBuilder.newBuilder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // A redirect would send the request to a host that was never checked against the allowed list.
      .property(ClientProperties.FOLLOW_REDIRECTS, Boolean.FALSE);

    if (verifyTls) {
      return builder.build();
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

    return builder
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

  private static String getString(JsonObject obj, String key) {
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
    return jsonResponse(Response.Status.BAD_REQUEST, message);
  }

  private Response jsonResponse(Response.Status status, String message) {
    return Response.status(status)
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
