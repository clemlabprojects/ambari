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

import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.audit.AuditLogger;
import org.apache.ambari.server.audit.event.kerberos.CreateKeyTabKerberosAuditEvent;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.security.authorization.AuthorizationException;
import org.apache.ambari.server.security.authorization.AuthorizationHelper;
import org.apache.ambari.server.security.authorization.ResourceType;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabGenerator;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPrincipalPolicy;
import org.apache.ambari.server.serveraction.kerberos.KDCType;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandler;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandlerFactory;
import org.apache.ambari.server.utils.PasswordUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * Ad-hoc keytabs from a KDC no Ambari cluster manages: the KDC of a KDPS platform context, such as the Active
 * Directory of a CDP cluster, or a FreeIPA or MIT KDC while Ambari runs without a Kerberized cluster.
 * {@code POST /api/v1/kerberos/adhoc_keytab} creates the principal, or exports the key of one that exists, and answers
 * with its keytab. Unlike the cluster's {@code /clusters/{cluster}/adhoc_keytab}, it never resets the key of an
 * existing principal: in a KDC Ambari does not manage, that principal may be another platform's (409).
 * <p/>
 * The request names the principal, the namespace receiving the keytab and the platform context's credential alias
 * ({@code kdps.context.<id>.kdc.admin}). Everything about the KDC comes from the record KDPS stored under that alias in
 * Ambari's credential store ({@link KdcRecord}): its type, realm, address, administrator account and password. None
 * of it is taken from the request, so the stored password only ever goes to the KDC it was entered for, and nothing a
 * caller sends selects a program, a template or a server the KDC handlers use. Ambari administrators only; every
 * issue and refusal is audited.
 */
@Path("/kerberos/adhoc_keytab")
@StaticallyInject
public class KerberosAdhocKeytabService {

  private static final Logger LOG = LoggerFactory.getLogger(KerberosAdhocKeytabService.class);

  /** Creating principals in a KDC with a stored administrator account is the server's own security setup. */
  private static final Set<RoleAuthorization> REQUIRED_AUTHORIZATIONS =
      EnumSet.of(RoleAuthorization.AMBARI_MANAGE_SETTINGS);

  /** The aliases KDPS keeps a platform context's KDC administrator password under, as the store keeps them. */
  static final Pattern CONTEXT_CREDENTIAL_ALIAS =
      Pattern.compile("^kdps\\.context\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?\\.kdc\\.admin$");

  private static final Gson GSON = new Gson();

  @Inject
  private static KerberosOperationHandlerFactory injectedHandlerFactory;

  /**
   * Through a provider: the policy reads the cluster principals' DAO, which static injection would reach before the
   * persistence service has started.
   */
  @Inject
  private static Provider<AdhocKeytabPrincipalPolicy> injectedPrincipalPolicy;

  @Inject
  private static Configuration injectedConfiguration;

  @Inject
  private static AuditLogger injectedAuditLogger;

  /** Request fields that would choose the KDC: they come from the stored record only. */
  private static final String[] KDC_FIELDS = {"kerberos_env", "kdc_type", "realm", "admin_principal", "ldap_url",
      "container_dn", "kdc_hosts", "admin_server_host", "password"};

  private final KerberosOperationHandlerFactory handlerFactory;
  private final AdhocKeytabPrincipalPolicy principalPolicy;
  private final Function<String, String> secretForAlias;
  private final AuditLogger auditLogger;

  public KerberosAdhocKeytabService() {
    this(injectedHandlerFactory, injectedPrincipalPolicy.get(), alias -> PasswordUtils.getInstance().readPasswordFromStore(
        alias, injectedConfiguration.getMasterKeyLocation(), injectedConfiguration.isMasterKeyPersisted(),
        injectedConfiguration.getMasterKeyStoreLocation()), injectedAuditLogger);
  }

  KerberosAdhocKeytabService(KerberosOperationHandlerFactory handlerFactory, AdhocKeytabPrincipalPolicy principalPolicy,
                             Function<String, String> secretForAlias, AuditLogger auditLogger) {
    this.handlerFactory = handlerFactory;
    this.principalPolicy = principalPolicy;
    this.secretForAlias = secretForAlias;
    this.auditLogger = auditLogger;
  }

  /**
   * A platform context's KDC as KDPS stores it under the context's credential alias: a JSON document, so the
   * administrator password is bound to the KDC it was entered for. Keys: {@code kdc_type} ({@code active-directory},
   * {@code ipa}, {@code mit-kdc}), {@code realm}, {@code admin_principal}, {@code password}, and the KDC settings
   * {@code ldap_url} (AD, {@code ldaps://} only), {@code container_dn} (AD), {@code encryption_types},
   * {@code kdc_hosts}, {@code admin_server_host} (IPA, MIT).
   */
  static final class KdcRecord {
    final KDCType kdcType;
    final String realm;
    final String administrator;
    final String password;
    final Map<String, String> settings = new LinkedHashMap<>();

    private KdcRecord(KDCType kdcType, String realm, String administrator, String password) {
      this.kdcType = kdcType;
      this.realm = realm;
      this.administrator = administrator;
      this.password = password;
    }

    /** The KDC settings the handlers may read, nothing else. */
    private static final String[] SETTINGS = {"ldap_url", "container_dn", "encryption_types", "kdc_hosts",
        "admin_server_host"};

    /** @throws IllegalArgumentException saying what is wrong with the stored record, never its password */
    static KdcRecord parse(String stored) {
      JsonObject json;
      try {
        JsonElement parsed = JsonParser.parseString(stored);
        json = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
      } catch (RuntimeException e) {
        json = null;
      }
      if (json == null) {
        throw new IllegalArgumentException("is not a KDC record");
      }
      KDCType type;
      try {
        type = KDCType.translate(text(json, "kdc_type"));
      } catch (IllegalArgumentException e) {
        type = KDCType.NONE;
      }
      if (type == KDCType.NONE) {
        throw new IllegalArgumentException("names no KDC type (active-directory, ipa or mit-kdc)");
      }
      String realm = text(json, "realm");
      String administrator = text(json, "admin_principal");
      String password = text(json, "password");
      if (StringUtils.isBlank(realm) || StringUtils.isBlank(administrator) || StringUtils.isEmpty(password)) {
        throw new IllegalArgumentException("lacks the realm, the administrator account or its password");
      }
      KdcRecord record = new KdcRecord(type, realm.trim(), administrator.trim(), password);
      for (String setting : SETTINGS) {
        String value = text(json, setting);
        if (StringUtils.isNotBlank(value)) {
          record.settings.put(setting, value.trim());
        }
      }
      if (type == KDCType.ACTIVE_DIRECTORY) {
        String url = record.settings.get("ldap_url");
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
          throw new IllegalArgumentException("needs an ldaps:// URL for Active Directory: the administrator password "
              + "is never sent over plain LDAP");
        }
        if (StringUtils.isBlank(record.settings.get("container_dn"))) {
          throw new IllegalArgumentException("needs the container DN (organizational unit) new accounts go to");
        }
      }
      return record;
    }

    /**
     * The kerberos-env the handler is opened with: the record's settings and, for Active Directory, a fixed account
     * template, Ambari's default plus a description naming the namespace, which tells a KDPS account apart.
     *
     * @param namespace a Kubernetes namespace name, already validated: it cannot carry template syntax
     */
    Map<String, String> kerberosEnv(String namespace) {
      Map<String, String> env = new LinkedHashMap<>(settings);
      if (kdcType == KDCType.ACTIVE_DIRECTORY) {
        env.put("ad_create_attributes_template", "{"
            + "\"objectClass\": [\"top\", \"person\", \"organizationalPerson\", \"user\"],"
            + "\"cn\": \"$principal_name\","
            + "#if( $is_service )"
            + "  \"servicePrincipalName\": \"$principal_name\","
            + "#end"
            + "\"userPrincipalName\": \"$normalized_principal\","
            + "\"description\": \"" + ownerOf(namespace) + "\","
            + "\"unicodePwd\": \"$password\","
            + "\"accountExpires\": \"0\","
            + "\"userAccountControl\": \"66048\""
            + "}");
      }
      return env;
    }
  }

  /** The description of the accounts KDPS creates for a namespace, which lets Ambari reset their key later. */
  static String ownerOf(String namespace) {
    return "Created by KDPS for namespace " + namespace;
  }

  /**
   * Issues the keytab of a principal of a platform context's KDC.
   * <p/>
   * Body: {@code principal}, {@code namespace} (the Kubernetes namespace that receives the keytab) and
   * {@code credential_alias} (the platform context's stored {@link KdcRecord}); a request naming the KDC or its
   * settings itself is refused. An existing principal's current key is exported when the KDC allows it; otherwise,
   * or with {@code new_key: true}, its key is reset, only when KDPS created its account for that namespace (see
   * {@link #ownerOf}). Answer: {@code principal}, {@code kvno}, {@code created}, {@code keytab} (base64),
   * {@code sha256}.
   */
  @POST
  @ApiIgnore
  @Produces(MediaType.APPLICATION_JSON)
  // ContentTypeOverrideFilter turns application/json into text/plain for every API request
  @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
  public Response issue(String body) {
    Checked checked = checkedRequest(body);
    if (checked.refusal != null) {
      return checked.refusal;
    }
    String principal = text(checked.request, "principal");
    String namespace = text(checked.request, "namespace");
    String alias = checked.alias;
    KdcRecord record = checked.record;

    try {
      principalPolicy.verifyForExternalKdc(principal, namespace, record.realm, record.administrator);
    } catch (IllegalArgumentException e) {
      audit(alias, principal, namespace, "Refused: " + e.getMessage());
      return error(Response.Status.BAD_REQUEST, e.getMessage());
    } catch (AuthorizationException e) {
      audit(alias, principal, namespace, "Refused: " + e.getMessage());
      return error(Response.Status.FORBIDDEN, e.getMessage());
    }

    KerberosOperationHandler handler = null;
    try {
      handler = handlerFactory.getKerberosOperationHandler(record.kdcType);
      handler.open(new PrincipalKeyCredential(record.administrator, record.password), record.realm,
          record.kerberosEnv(namespace.trim()));
      LOG.info("Issuing an ad-hoc keytab for '{}' (namespace {}) from the {} KDC of realm {}", principal, namespace,
          record.kdcType, record.realm);
      boolean newKey = checked.request.has("new_key") && checked.request.get("new_key").isJsonPrimitive()
          && checked.request.get("new_key").getAsBoolean();
      AdhocKeytabGenerator.Result issued = AdhocKeytabGenerator.issueOwned(handler, principal.trim(),
          ownerOf(namespace.trim()), newKey, LOG::info);
      audit(alias, principal, namespace, null);
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("principal", principal.trim());
      answer.put("kvno", issued.getKvno());
      answer.put("created", issued.isCreated());
      answer.put("keytab", Base64.getEncoder().encodeToString(issued.getKeytab()));
      answer.put("sha256", issued.getSha256());
      return Response.ok(GSON.toJson(answer), MediaType.APPLICATION_JSON_TYPE).build();
    } catch (AdhocKeytabGenerator.ExistingPrincipalException e) {
      LOG.warn("Refused an ad-hoc keytab for existing principal '{}': {}", principal, e.getMessage());
      audit(alias, principal, namespace, "Refused: " + e.getMessage());
      return error(Response.Status.CONFLICT, e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed issuing an ad-hoc keytab for '{}' from the {} KDC of realm {}", principal, record.kdcType,
          record.realm, e);
      String reason = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
      audit(alias, principal, namespace, "Failed: " + reason);
      return error(Response.Status.BAD_GATEWAY, "The KDC did not issue the keytab of '" + principal + "': " + reason);
    } finally {
      if (handler != null) {
        try {
          handler.close();
        } catch (Exception e) {
          LOG.warn("Error closing the KDC connection: {}", e.getMessage());
        }
      }
    }
  }

  /** A request that passed the checks every call shares, or the answer refusing it. */
  private static final class Checked {
    JsonObject request;
    String alias;
    KdcRecord record;
    Response refusal;

    static Checked refused(Response refusal) {
      Checked checked = new Checked();
      checked.refusal = refusal;
      return checked;
    }
  }

  /**
   * The checks every call shares: an Ambari administrator, a JSON object naming no KDC setting itself, the alias of a
   * platform context's KDC record, and that record.
   */
  private Checked checkedRequest(String body) {
    if (!AuthorizationHelper.isAuthorized(ResourceType.AMBARI, null, REQUIRED_AUTHORIZATIONS)) {
      return Checked.refused(error(Response.Status.FORBIDDEN, "Only an Ambari administrator may use a platform "
          + "context's KDC."));
    }

    JsonObject request;
    try {
      JsonElement parsed = JsonParser.parseString(StringUtils.defaultString(body));
      request = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    } catch (RuntimeException e) {
      request = null;
    }
    if (request == null) {
      return Checked.refused(error(Response.Status.BAD_REQUEST, "The request body must be a JSON object."));
    }

    for (String field : KDC_FIELDS) {
      if (request.has(field)) {
        return Checked.refused(error(Response.Status.BAD_REQUEST, "'" + field + "' is not taken from the request: the "
            + "KDC, its account and its settings come from the platform context's stored record (credential_alias)."));
      }
    }
    String alias = text(request, "credential_alias");
    if (alias == null || !CONTEXT_CREDENTIAL_ALIAS.matcher(alias.toLowerCase(Locale.ROOT)).matches()) {
      return Checked.refused(error(Response.Status.BAD_REQUEST, "credential_alias must be the alias KDPS stored a "
          + "platform context's KDC record under (kdps.context.<id>.kdc.admin)."));
    }
    alias = alias.toLowerCase(Locale.ROOT);

    String stored = secretForAlias.apply(alias);
    if (StringUtils.isEmpty(stored)) {
      return Checked.refused(error(Response.Status.BAD_REQUEST, "No KDC record is stored under '" + alias + "': save "
          + "the platform context's KDC account again."));
    }
    KdcRecord record;
    try {
      record = KdcRecord.parse(stored);
    } catch (IllegalArgumentException e) {
      return Checked.refused(error(Response.Status.BAD_REQUEST, "The KDC record stored under '" + alias + "' "
          + e.getMessage() + ": save the platform context's KDC account again."));
    }
    Checked checked = new Checked();
    checked.request = request;
    checked.alias = alias;
    checked.record = record;
    return checked;
  }

  /**
   * Tests a platform context's KDC record without issuing anything: the KDC answers and takes the administrator
   * account, and, for Active Directory, the organizational unit new accounts go to can be searched.
   * Body: {@code credential_alias}. Answer: {@code ok} and {@code message}; 502 with the KDC's reason otherwise.
   */
  @POST
  @ApiIgnore
  @Path("test")
  @Produces(MediaType.APPLICATION_JSON)
  // ContentTypeOverrideFilter turns application/json into text/plain for every API request
  @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
  public Response test(String body) {
    Checked checked = checkedRequest(body);
    if (checked.refusal != null) {
      return checked.refusal;
    }
    KdcRecord record = checked.record;
    KerberosOperationHandler handler = null;
    try {
      handler = handlerFactory.getKerberosOperationHandler(record.kdcType);
      handler.open(new PrincipalKeyCredential(record.administrator, record.password), record.realm,
          record.kerberosEnv("kdps-test"));
      handler.testAdministratorCredentials();
      if (record.kdcType == KDCType.ACTIVE_DIRECTORY) {
        // A search under the organizational unit: a container that does not exist fails here, not at the first deploy.
        handler.principalExists("kdps-connection-test@" + record.realm, false);
      }
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("ok", true);
      answer.put("message", "The " + record.kdcType + " KDC of realm " + record.realm + " accepts " + record.administrator
          + (record.kdcType == KDCType.ACTIVE_DIRECTORY ? ", and its organizational unit "
          + record.settings.get("container_dn") + " can be searched." : "."));
      return Response.ok(GSON.toJson(answer), MediaType.APPLICATION_JSON_TYPE).build();
    } catch (Exception e) {
      LOG.warn("The KDC record {} did not pass its test: {}", checked.alias, e.toString());
      return error(Response.Status.BAD_GATEWAY, "The " + record.kdcType + " KDC of realm " + record.realm
          + " did not accept " + record.administrator + ": "
          + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
    } finally {
      if (handler != null) {
        try {
          handler.close();
        } catch (Exception e) {
          LOG.warn("Error closing the KDC connection: {}", e.getMessage());
        }
      }
    }
  }

  /** An audit log entry for an issued or refused keytab, naming the Ambari user who asked. */
  private void audit(String alias, String principal, String namespace, String failure) {
    if (auditLogger == null || !auditLogger.isEnabled()) {
      return;
    }
    CreateKeyTabKerberosAuditEvent.CreateKeyTabKerberosAuditEventBuilder event = CreateKeyTabKerberosAuditEvent.builder()
        .withTimestamp(System.currentTimeMillis())
        .withOperation("Ad-hoc keytab from platform context KDC " + alias + ", requested by "
            + AuthorizationHelper.getAuthenticatedName())
        .withRequestId(-1L)
        .withTaskId(-1L)
        .withPrincipal(principal)
        .withHostName("-")
        .withKeyTabFilePath("returned for namespace " + namespace);
    if (failure != null) {
      event.withReasonOfFailure(failure);
    }
    auditLogger.log(event.build());
  }

  private static String text(JsonObject request, String field) {
    JsonElement value = request.get(field);
    return (value == null || value.isJsonNull() || !value.isJsonPrimitive()) ? null : value.getAsString();
  }

  private static Response error(Response.Status status, String message) {
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("status", status.getStatusCode());
    answer.put("message", message);
    return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(GSON.toJson(answer)).build();
  }
}
