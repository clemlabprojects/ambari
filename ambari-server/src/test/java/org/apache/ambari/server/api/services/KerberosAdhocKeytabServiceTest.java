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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.ws.rs.core.Response;

import org.apache.ambari.server.audit.AuditLogger;
import org.apache.ambari.server.audit.event.AuditEvent;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.security.TestAuthenticationFactory;
import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.serveraction.kerberos.AdhocKeytabPrincipalPolicy;
import org.apache.ambari.server.serveraction.kerberos.KDCType;
import org.apache.ambari.server.serveraction.kerberos.KdcAccountDescriptions;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationException;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandler;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandlerFactory;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * POST /api/v1/kerberos/adhoc_keytab: a keytab from a platform context's KDC, for Ambari administrators, with the
 * administrator password read only from a KDPS context alias, and never a reset of an existing principal KDPS did not
 * create for the namespace.
 */
public class KerberosAdhocKeytabServiceTest {

  private static final String ALIAS = "kdps.context.cdp-prod.kdc.admin";

  /** An Active Directory: creates accounts, never exports an existing key. */
  private static class FakeAd extends KerberosOperationHandler implements KdcAccountDescriptions {
    final List<String> calls = new ArrayList<>();
    String description;
    PrincipalKeyCredential admin;
    String realm;
    Map<String, String> env;
    boolean exists;
    boolean closed;

    boolean refuseAccount;
    final List<String> lookups = new ArrayList<>();

    @Override
    public void open(PrincipalKeyCredential administratorCredential, String defaultRealm, Map<String, String> kerberosConfiguration)
        throws KerberosOperationException {
      if (refuseAccount) {
        throw new KerberosOperationException("LDAP: error code 49 - invalid credentials");
      }
      admin = administratorCredential;
      realm = defaultRealm;
      env = kerberosConfiguration;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public boolean principalExists(String principal, boolean service) {
      lookups.add(principal);
      return exists;
    }

    @Override
    public boolean testAdministratorCredentials() {
      calls.add("test credentials");
      return true;
    }

    @Override
    public Integer createPrincipal(String principal, String password, boolean service) {
      calls.add("create " + principal);
      return 1;
    }

    @Override
    public Integer setPrincipalPassword(String principal, String password, boolean service) {
      calls.add("reset " + principal);
      return 2;
    }

    @Override
    public boolean removePrincipal(String principal, boolean service) {
      return false;
    }

    @Override
    public void ensureHostExists(String hostname) {
    }

    @Override
    public String getAccountDescription(String principal) {
      return description;
    }

    @Override
    public void setAccountDescription(String principal, String description) {
      calls.add("describe " + principal);
      this.description = description;
    }

    @Override
    public Integer createKeytabFileForExistingPrincipal(String principal, Set<EncryptionType> types, File destination)
        throws KerberosOperationException {
      throw new KerberosOperationException("Active Directory cannot export an existing key");
    }

    @Override
    protected boolean createKeytabFile(String principal, String password, Integer keyNumber, File destination) {
      try {
        Files.write(destination.toPath(), "keytab".getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return true;
    }
  }

  private FakeAd kdc;
  private final List<String> aliasesRead = new ArrayList<>();
  private final List<AuditEvent> audited = new ArrayList<>();
  private String storedRecord;
  private KerberosAdhocKeytabService service;

  /** The record KDPS stores for a platform context: the KDC, its account and password, bound together. */
  private static String record(String ldapUrl) {
    JsonObject record = new JsonObject();
    record.addProperty("kdc_type", "active-directory");
    record.addProperty("realm", "AD.EXAMPLE.COM");
    record.addProperty("admin_principal", "cm-kdc@AD.EXAMPLE.COM");
    record.addProperty("password", "cm-kdc-password");
    record.addProperty("ldap_url", ldapUrl);
    record.addProperty("container_dn", "OU=cloudera,DC=ad,DC=example,DC=com");
    record.addProperty("encryption_types", "aes256-cts-hmac-sha1-96");
    // Settings the record must never pass to the handler, whoever wrote them.
    record.addProperty("executable_search_paths", "/tmp/evil");
    record.addProperty("ad_create_attributes_template", "#set($x = $class.forName('java.lang.Runtime'))");
    record.addProperty("kdc_create_attributes", "-x evil");
    return record.toString();
  }

  @Before
  public void setUp() {
    kdc = new FakeAd();
    storedRecord = record("ldaps://dc01.ad.example.com");
    KerberosOperationHandlerFactory factory = new KerberosOperationHandlerFactory() {
      @Override
      public KerberosOperationHandler getKerberosOperationHandler(KDCType kdcType) {
        assertEquals(KDCType.ACTIVE_DIRECTORY, kdcType);
        return kdc;
      }
    };
    AuditLogger auditLogger = new AuditLogger() {
      @Override
      public void log(AuditEvent event) {
        audited.add(event);
      }

      @Override
      public boolean isEnabled() {
        return true;
      }
    };
    AdhocKeytabPrincipalPolicy policy = new AdhocKeytabPrincipalPolicy(null, null, null, new Configuration(new Properties()));
    service = new KerberosAdhocKeytabService(factory, policy, alias -> {
      aliasesRead.add(alias);
      return ALIAS.equals(alias) ? storedRecord : null;
    }, auditLogger);
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createAdministrator());
  }

  @After
  public void tearDown() {
    SecurityContextHolder.getContext().setAuthentication(null);
  }

  private static String request(String principal, String alias) {
    JsonObject body = new JsonObject();
    body.addProperty("principal", principal);
    body.addProperty("namespace", "analytics");
    body.addProperty("credential_alias", alias);
    return body.toString();
  }

  private static JsonObject json(Response response) {
    return JsonParser.parseString(String.valueOf(response.getEntity())).getAsJsonObject();
  }

  @Test
  public void anAdministratorGetsTheKeytabOfANewPrincipalFromTheContextsKdc() {
    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(200, response.getStatus());
    JsonObject answer = json(response);
    assertTrue(answer.get("created").getAsBoolean());
    assertEquals("keytab", new String(Base64.getDecoder().decode(answer.get("keytab").getAsString()), StandardCharsets.UTF_8));
    assertEquals("cm-kdc@AD.EXAMPLE.COM", kdc.admin.getPrincipal());
    assertEquals("cm-kdc-password", String.valueOf(kdc.admin.getKey()));
    assertEquals("AD.EXAMPLE.COM", kdc.realm);
    assertEquals("ldaps://dc01.ad.example.com", kdc.env.get("ldap_url"));
    assertEquals("OU=cloudera,DC=ad,DC=example,DC=com", kdc.env.get("container_dn"));
    assertTrue(kdc.closed);
    // Only the KDC settings reach the handler: no program path, no template or kadmin attributes from the record.
    assertFalse(kdc.env.containsKey("executable_search_paths"));
    assertFalse(kdc.env.containsKey("kdc_create_attributes"));
    String template = kdc.env.get("ad_create_attributes_template");
    assertTrue(template, template.contains("\"description\": \"Created by KDPS for namespace analytics of platform context cdp-prod\""));
    assertFalse(template, template.contains("forName"));
    assertEquals(1, audited.size());
    String audit = audited.get(0).getAuditMessage();
    assertTrue(audit, audit.contains("Principal(trino-analytics@AD.EXAMPLE.COM)"));
    assertTrue(audit, audit.contains("kdps.context.cdp-prod.kdc.admin"));
    assertFalse(audit, audit.contains("cm-kdc-password"));
  }

  @Test
  public void aRequestCannotChooseTheKdcItsSettingsOrItsAccount() {
    // Pointing the stored password at another server, or selecting a program or template, is not possible.
    for (String field : new String[]{"kerberos_env", "ldap_url", "kdc_type", "realm", "admin_principal"}) {
      JsonObject body = JsonParser.parseString(request("trino-analytics@AD.EXAMPLE.COM", ALIAS)).getAsJsonObject();
      body.addProperty(field, "ldaps://attacker.example.com");
      Response response = service.issue(body.toString());
      assertEquals(field, 400, response.getStatus());
    }
    assertTrue(aliasesRead.toString(), aliasesRead.isEmpty());
    assertNull("the KDC was not opened", kdc.admin);
  }

  @Test
  public void anActiveDirectoryRecordWithoutLdapsIsRefusedBeforeThePasswordIsSent() {
    storedRecord = record("ldap://dc01.ad.example.com");

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(400, response.getStatus());
    assertTrue(json(response).get("message").getAsString().contains("ldaps://"));
    assertNull("the KDC was not opened", kdc.admin);
  }

  @Test
  public void aStoredValueThatIsNotAKdcRecordIsSaid() {
    storedRecord = "just-a-password";

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(400, response.getStatus());
    assertTrue(json(response).get("message").getAsString().contains("is not a KDC record"));
    assertFalse(String.valueOf(response.getEntity()).contains("just-a-password"));
  }

  @Test
  public void anExistingPrincipalIsNotResetAndTheCallerIsToldWhy() {
    kdc.exists = true;

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(409, response.getStatus());
    assertTrue(json(response).get("message").getAsString().contains("neither exported nor changed"));
    assertTrue(kdc.calls.toString(), kdc.calls.isEmpty());
  }

  @Test
  public void anAccountKdpsCreatedForTheNamespaceGetsANewKeyWhenItsKeytabIsLost() {
    // Active Directory cannot give the key back: the account's description says KDPS created it for this namespace.
    kdc.exists = true;
    kdc.description = "Created by KDPS for namespace analytics of platform context cdp-prod";

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(200, response.getStatus());
    assertFalse(json(response).get("created").getAsBoolean());
    assertEquals(List.of("reset trino-analytics@AD.EXAMPLE.COM"), kdc.calls);
  }

  @Test
  public void anAccountKdpsCreatedForAnotherNamespaceIsNotReset() {
    kdc.exists = true;
    kdc.description = "Created by KDPS for namespace finance of platform context cdp-prod";

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(409, response.getStatus());
    assertTrue(kdc.calls.toString(), kdc.calls.isEmpty());
  }

  @Test
  public void anAccountKdpsCreatedForTheSameNamespaceOfAnotherContextIsNotReset() {
    // Another context's Kubernetes cluster may have its own namespace analytics, in the same Active Directory.
    kdc.exists = true;
    kdc.description = "Created by KDPS for namespace analytics of platform context cdp-test";

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(409, response.getStatus());
    assertTrue(kdc.calls.toString(), kdc.calls.isEmpty());
  }

  @Test
  public void aRecordWhoseSettingsCouldReachTheKdcToolsAsSyntaxIsRefused() {
    String[][] bad = {
        {"admin_principal", "-q delprinc@AD.EXAMPLE.COM"},
        {"realm", "AD.EXAMPLE.COM\n[plugins]"},
        {"container_dn", "OU=x\", \"servicePrincipalName\": \"y"},
        {"ldap_url", "ldaps://dc01.ad.example.com/?x"},
        {"kdc_hosts", "kdc01 -x"},
    };
    for (String[] setting : bad) {
      JsonObject record = JsonParser.parseString(record("ldaps://dc01.ad.example.com")).getAsJsonObject();
      record.addProperty(setting[0], setting[1]);
      storedRecord = record.toString();

      Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

      assertEquals(setting[0], 400, response.getStatus());
      assertNull("the KDC was not opened", kdc.admin);
    }
  }

  @Test
  public void thePrincipalsOfTheOtherPlatformAreRefusedBeforeTheKdcIsContacted() {
    Response response = service.issue(request("hive/cdp-master.ad.example.com@AD.EXAMPLE.COM", ALIAS));

    assertEquals(403, response.getStatus());
    assertNull("the KDC was not opened", kdc.admin);
    assertTrue(audited.get(0).getAuditMessage().contains("Refused"));
  }

  @Test
  public void onlyTheAliasOfAPlatformContextsKdcAccountIsRead() {
    for (String alias : new String[]{"kdc.admin.credential", "ambari.db.password", "kdps.git.release.x", null}) {
      Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", alias));
      assertEquals(String.valueOf(alias), 400, response.getStatus());
    }
    assertTrue(aliasesRead.toString(), aliasesRead.isEmpty());
    assertNull(kdc.admin);
  }

  @Test
  public void aMissingStoredPasswordIsSaid() {
    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", "kdps.context.other.kdc.admin"));

    assertEquals(400, response.getStatus());
    assertTrue(json(response).get("message").getAsString().contains("No KDC record is stored"));
  }

  @Test
  public void anOperatorWhoIsNotAnAmbariAdministratorIsRefused() {
    SecurityContextHolder.getContext().setAuthentication(TestAuthenticationFactory.createClusterAdministrator());

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(403, response.getStatus());
    assertFalse(String.valueOf(response.getEntity()).contains("keytab\""));
    assertTrue(aliasesRead.isEmpty());
  }

  private static String testRequest(String alias) {
    JsonObject body = new JsonObject();
    body.addProperty("credential_alias", alias);
    return body.toString();
  }

  @Test
  public void aStoredRecordIsTestedWithoutIssuingAnything() {
    Response response = service.test(testRequest(ALIAS));

    assertEquals(200, response.getStatus());
    JsonObject answer = json(response);
    assertTrue(answer.get("ok").getAsBoolean());
    assertTrue(answer.get("message").getAsString(), answer.get("message").getAsString()
        .contains("OU=cloudera,DC=ad,DC=example,DC=com can be searched"));
    assertTrue(kdc.calls.contains("test credentials"));
    assertEquals("the organizational unit is searched", List.of("kdps-connection-test@AD.EXAMPLE.COM"), kdc.lookups);
    assertFalse("nothing created", kdc.calls.stream().anyMatch(c -> c.startsWith("create")));
    assertTrue(kdc.closed);
  }

  @Test
  public void aRecordTheKdcRefusesSaysWhy() {
    kdc.refuseAccount = true;

    Response response = service.test(testRequest(ALIAS));

    assertEquals(502, response.getStatus());
    String message = json(response).get("message").getAsString();
    assertTrue(message, message.contains("did not accept cm-kdc@AD.EXAMPLE.COM"));
    assertTrue(message, message.contains("invalid credentials"));
    assertFalse(message, message.contains("cm-kdc-password"));
  }

  @Test
  public void theTestTakesNoKdcSettingFromTheRequestEither() {
    JsonObject body = JsonParser.parseString(testRequest(ALIAS)).getAsJsonObject();
    body.addProperty("ldap_url", "ldaps://attacker.example.com");

    assertEquals(400, service.test(body.toString()).getStatus());
    assertEquals(400, service.test(testRequest("kdc.admin.credential")).getStatus());
    assertTrue(aliasesRead.toString(), aliasesRead.isEmpty());
  }

  @Test
  public void aRecordNamingNoKnownKdcTypeIsRefused() {
    storedRecord = storedRecord.replace("active-directory", "ad");

    Response response = service.issue(request("trino-analytics@AD.EXAMPLE.COM", ALIAS));

    assertEquals(400, response.getStatus());
    assertNull(kdc.admin);
  }
}
