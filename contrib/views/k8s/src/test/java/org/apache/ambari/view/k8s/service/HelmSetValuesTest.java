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
}
