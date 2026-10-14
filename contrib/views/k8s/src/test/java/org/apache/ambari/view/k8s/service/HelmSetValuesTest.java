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

package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Settings reach the chart typed exactly like {@code helm --set} would type them. */
class HelmSetValuesTest {

  @Test
  void versionsCodesAndDecimalsKeepTheirExactText() {
    for (String text : new String[]{"1.10", "0123", "007", "1.0e3", "1.5", "476.1.3.2.0-37", "3.10", "0x1F", "1_000",
        "12345678901234567890", "+", "-", "", "٣"}) {
      assertEquals(text, HelmSetValues.typed(text), text);
    }
  }

  @Test
  void booleansInAnyCase() {
    assertEquals(Boolean.TRUE, HelmSetValues.typed("true"));
    assertEquals(Boolean.TRUE, HelmSetValues.typed("TRUE"));
    assertEquals(Boolean.FALSE, HelmSetValues.typed("False"));
    assertEquals(Boolean.FALSE, HelmSetValues.typed("false "), "a stray space must not leave a truthy string");
  }

  @Test
  void wholeNumbersBecomeIntegers() {
    assertEquals(0, HelmSetValues.typed("0"));
    assertEquals(476, HelmSetValues.typed("476"));
    assertEquals(-5, HelmSetValues.typed("-5"));
    assertEquals(5, HelmSetValues.typed("+5"));
    assertEquals(12345678901L, HelmSetValues.typed("12345678901"));
    assertEquals(3, HelmSetValues.typed(" 3 "));
    assertEquals(-5, HelmSetValues.typed("-05"), "Helm only refuses a leading zero in the first character");
  }

  @Test
  void nullClearsTheSetting() {
    assertNull(HelmSetValues.typed("null"));
    assertNull(HelmSetValues.typed("NULL"));
    assertNull(HelmSetValues.typed(null));
  }

  @Test
  void passwordsAndSecretsStayTextAndAreHiddenInLogs() {
    for (String password : new String[]{"12345", "true", "null", "1.10", " spaced "}) {
      String marked = HelmSetValues.textIfSecret("ldap.bindPassword", password);
      assertEquals(password, HelmSetValues.typed(marked), password);
      assertEquals("<hidden>", HelmSetValues.forLog(marked));
    }
    assertEquals("007", HelmSetValues.typed(HelmSetValues.textIfSecret("oidc.clientSecret", "007")));
    assertEquals(Boolean.TRUE, HelmSetValues.typed(HelmSetValues.textIfSecret("ldap.enabled", "true")), "not a secret");
    assertEquals("ldap://x", HelmSetValues.forLog(HelmSetValues.textIfSecret("ldap.url", "ldap://x")));
  }

  @Test
  void textValuesKeepTheirSpaces() {
    assertEquals(" 1.10 ", HelmSetValues.typed(" 1.10 "));
    assertEquals(0, HelmSetValues.typed("-0"), "-0 is the integer 0, like Helm");
  }

  @Test
  void onlyPasswordFieldsOfTheSecurityProfileAreMarked() {
    assertTrue(HelmSetValues.passwordFields().containsAll(java.util.Set.of("ldap.bindPassword", "ldap.adBindPassword", "oidc.clientSecret")));
    assertEquals("ca-secret", HelmSetValues.textIfSecret("oidc.caSecret", "ca-secret"), "a Secret name, not a secret");
    assertEquals("truststore.password", HelmSetValues.textIfSecret("tls.truststorePasswordKey", "truststore.password"));
  }

  @Test
  void markedValuesAreHiddenInLoggedJson() {
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("_ov", java.util.Map.of("ldap.bindPassword", HelmSetValues.textIfSecret("ldap.bindPassword", "s3cr\"et\\x"),
        "ldap.url", "ldap://ipa"));
    String json = new com.google.gson.Gson().toJson(params);
    String logged = HelmSetValues.redactJson(json);
    assertFalse(logged.contains("s3cr"), logged);
    assertTrue(logged.contains("\"<hidden>\"") && logged.contains("ldap://ipa"), logged);
    assertNull(HelmSetValues.redactJson(null));
  }

  @Test
  void stringsUnderSecretKeysAreHiddenToo() {
    String json = new com.google.gson.Gson().toJson(java.util.Map.of(
        "oidcExternalClientSecret", "abc\"def", "repoPassword", "pw", "secretName", "my-secret",
        "truststorePasswordKey", "truststore.password", "nested", java.util.Map.of("apiToken", "t0k")));
    String logged = HelmSetValues.redactJson(json);
    for (String leaked : new String[]{"abc", "\"pw\"", "t0k"}) assertFalse(logged.contains(leaked), logged);
    assertTrue(logged.contains("my-secret") && logged.contains("truststore.password"), "names and keys stay visible: " + logged);
  }

  @Test
  void sshAndPrivateKeysAreHidden() {
    String json = new com.google.gson.Gson().toJson(java.util.Map.of("git", java.util.Map.of(
        "sshKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3Bl\n-----END OPENSSH PRIVATE KEY-----",
        "credentialAlias", "gitops-1", "repoUrl", "git@git.example.org:platform/gitops.git"),
        "tls", java.util.Map.of("privateKey", "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----")));
    String logged = HelmSetValues.redactJson(json);
    assertFalse(logged.contains("b3Bl") || logged.contains("MIIE"), logged);
    assertTrue(logged.contains("gitops-1") && logged.contains("git@git.example.org"), "aliases and URLs stay visible: " + logged);
  }

  @Test
  void theWizardFormIsHiddenWholeWithEverySecretItCarries() {
    java.util.Map<String, Object> form = new java.util.LinkedHashMap<>();
    form.put("superset", java.util.Map.of("secretKey", "flask-secret-751"));
    form.put("ingress", java.util.Map.of("host", "superset.example.org", "tlsUpload",
        java.util.Map.of("key", "-----BEGIN PRIVATE KEY-----\nMIIE-ingress-751\n-----END PRIVATE KEY-----")));
    form.put("icebergCatalog", java.util.Map.of("s3SecretKey", "s3-secret-751", "s3AccessKey", "AKIA751"));
    form.put("git", java.util.Map.of("sshKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nb3Bl-751\n-----END OPENSSH PRIVATE KEY-----"));
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("releaseName", "superset");
    params.put("replicaCount", 2);
    params.put("formValues", form);

    String logged = HelmSetValues.redactJson(new com.google.gson.Gson().toJson(params));

    for (String leaked : new String[]{"flask-secret-751", "MIIE-ingress-751", "s3-secret-751", "AKIA751", "b3Bl-751",
        "superset.example.org"}) {
      assertFalse(logged.contains(leaked), leaked + " in " + logged);
    }
    assertTrue(logged.contains("\"formValues\":\"<hidden>\"") && logged.contains("\"releaseName\":\"superset\"")
        && logged.contains("\"replicaCount\":2"), logged);
  }

  @Test
  void secretsOutsideTheFormAreHiddenByTheirKeyOrByTheirValue() {
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("superset", java.util.Map.of("secretKey", "flask-1"));
    params.put("s3SecretAccessKey", "s3s-1");
    params.put("s3AccessKeyId", "akid-1");
    params.put("objectStore", java.util.Map.of("accessKey", "ak-1", "apiKey", "api-1", "credentials", "cred-1"));
    params.put("kerberos", java.util.Map.of("keytab", "BQIA-1", "keytabSecretName", "trino-keytab"));
    params.put("ingressTlsUpload", java.util.Map.of("secretName", "superset-ingress-tls",
        "keyPem", "-----BEGIN EC PRIVATE KEY-----\nMHcC-1\n-----END EC PRIVATE KEY-----",
        "certPem", "-----BEGIN CERTIFICATE-----\nMIIB-cert\n-----END CERTIFICATE-----"));
    params.put("entries", java.util.List.of(java.util.Map.of("key", "k-1", "name", "entry")));
    params.put("ldap", java.util.Map.of("password", 123456));
    params.put("secretName", "my-secret");
    params.put("truststorePasswordKey", "truststore.password");
    params.put("credentialAlias", "gitops-1");

    String logged = HelmSetValues.redactJson(new com.google.gson.Gson().toJson(params));

    for (String leaked : new String[]{"flask-1", "s3s-1", "akid-1", "ak-1", "api-1", "cred-1", "BQIA-1", "MHcC-1",
        "k-1", "123456"}) {
      assertFalse(logged.contains(leaked), leaked + " in " + logged);
    }
    for (String visible : new String[]{"trino-keytab", "superset-ingress-tls", "MIIB-cert", "my-secret",
        "truststore.password", "gitops-1", "entry"}) {
      assertTrue(logged.contains(visible), visible + " must stay readable: " + logged);
    }
  }

  @Test
  void aTextThatIsNotJsonIsHiddenByTheSameRules() {
    String reference = "@@file:/var/lib/ambari-server/resources/views/k8s-view-data/kdps/params/1-params.json";
    assertEquals(reference, HelmSetValues.redactJson(reference));

    String cut = "{\"password\":\"pw-751\",\"key\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIC-751";
    String logged = HelmSetValues.redactJson(cut);
    assertFalse(logged.contains("pw-751") || logged.contains("MIIC-751"), logged);
  }
}
