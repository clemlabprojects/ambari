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

package org.apache.ambari.server.serveraction.kerberos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.junit.Test;

/**
 * The keytab of an ad-hoc principal: created when missing, exported without a new key when the KDC allows it, and
 * reset only when the caller allows it. A KDC no cluster manages may hold the principal for another platform.
 */
public class AdhocKeytabGeneratorTest {

  /** A KDC that records what was done to it. */
  static class FakeKdc extends KerberosOperationHandler {
    final List<String> calls = new ArrayList<>();
    boolean exists;
    boolean canExport;
    int kvno = 3;

    @Override
    public boolean principalExists(String principal, boolean service) {
      calls.add("exists");
      return exists;
    }

    @Override
    public Integer createPrincipal(String principal, String password, boolean service) {
      calls.add("create");
      exists = true;
      kvno = 1;
      return kvno;
    }

    @Override
    public Integer setPrincipalPassword(String principal, String password, boolean service) {
      calls.add("reset");
      return ++kvno;
    }

    @Override
    public boolean removePrincipal(String principal, boolean service) {
      calls.add("remove");
      return true;
    }

    @Override
    public void ensureHostExists(String hostname) {
      calls.add("host " + hostname);
    }

    @Override
    public Integer createKeytabFileForExistingPrincipal(String principal, Set<EncryptionType> types, File destination)
        throws KerberosOperationException {
      if (!canExport) {
        throw new KerberosOperationException("this KDC cannot export an existing key");
      }
      calls.add("export");
      write(destination, "exported key " + kvno);
      return kvno;
    }

    @Override
    protected boolean createKeytabFile(String principal, String password, Integer keyNumber, File destination) {
      calls.add("keytab from password");
      write(destination, "key from password " + keyNumber);
      return true;
    }

    private static void write(File file, String content) {
      try {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** A KDC keeping who created each account, as Active Directory and FreeIPA users do. */
  static class DescribedKdc extends FakeKdc implements KdcAccountDescriptions {
    String description;

    @Override
    public String getAccountDescription(String principal) {
      return description;
    }

    boolean refuseDescription;

    @Override
    public void setAccountDescription(String principal, String description) throws KerberosOperationException {
      if (refuseDescription) {
        throw new KerberosOperationException("ipa: error: no such option");
      }
      calls.add("describe");
      this.description = description;
    }
  }

  private static final String OWNER = "Created by KDPS for namespace analytics";

  private static String text(AdhocKeytabGenerator.Result result) {
    return new String(result.getKeytab(), StandardCharsets.UTF_8);
  }

  @Test
  public void aMissingPrincipalIsCreatedWithItsKeytab() throws Exception {
    FakeKdc kdc = new FakeKdc();

    AdhocKeytabGenerator.Result result = AdhocKeytabGenerator.issue(kdc, "trino-analytics@AD.EXAMPLE.COM", false, line -> { });

    assertTrue(result.isCreated());
    assertEquals(1, result.getKvno());
    assertEquals("key from password 1", text(result));
    assertEquals(64, result.getSha256().length());
  }

  @Test
  public void anExistingPrincipalKeepsItsKeyWhenTheKdcExportsIt() throws Exception {
    FakeKdc kdc = new FakeKdc();
    kdc.exists = true;
    kdc.canExport = true;

    AdhocKeytabGenerator.Result result = AdhocKeytabGenerator.issue(kdc, "trino-analytics@IPA.EXAMPLE.COM", false, line -> { });

    assertFalse(result.isCreated());
    assertEquals("exported key 3", text(result));
    assertFalse(kdc.calls.toString(), kdc.calls.contains("reset"));
  }

  @Test
  public void anExistingPrincipalWhoseKeyCannotBeExportedIsNotResetUnlessAllowed() throws Exception {
    // Active Directory never exports a key: resetting it would break whoever holds the principal's keytab.
    FakeKdc kdc = new FakeKdc();
    kdc.exists = true;

    try {
      AdhocKeytabGenerator.issue(kdc, "hive/cdp-master.ad.example.com@AD.EXAMPLE.COM", false, line -> { });
      fail("An existing principal of a KDC no cluster manages must not be reset");
    } catch (AdhocKeytabGenerator.ExistingPrincipalException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("was not changed"));
    }
    assertFalse(kdc.calls.toString(), kdc.calls.contains("reset"));
    assertFalse(kdc.calls.toString(), kdc.calls.contains("keytab from password"));
  }

  @Test
  public void theClustersKdcResetsAnExistingPrincipalItCannotExport() throws Exception {
    // What the cluster's ad-hoc keytab has always done: the cluster's KDC, whose principals Ambari manages.
    FakeKdc kdc = new FakeKdc();
    kdc.exists = true;

    AdhocKeytabGenerator.Result result = AdhocKeytabGenerator.issue(kdc, "trino-analytics@EXAMPLE.COM", true, line -> { });

    assertTrue(kdc.calls.contains("reset"));
    assertEquals(4, result.getKvno());
    assertEquals("key from password 4", text(result));
  }

  @Test
  public void aServicePrincipalGetsItsHostObjectFirst() throws Exception {
    FakeKdc kdc = new FakeKdc();

    AdhocKeytabGenerator.issue(kdc, "jupyterhub/jupyterhub.analytics.svc@IPA.EXAMPLE.COM", false, line -> { });

    assertEquals("host jupyterhub.analytics.svc", kdc.calls.get(0));
  }

  @Test
  public void aNewAccountIsMarkedAsTheCallers() throws Exception {
    DescribedKdc kdc = new DescribedKdc();

    AdhocKeytabGenerator.issueOwned(kdc, "trino-analytics@IPA.EXAMPLE.COM", OWNER, false, line -> { });

    assertEquals(OWNER, kdc.description);
  }

  @Test
  public void anAccountMarkedAsTheCallersGetsANewKeyWhenItCannotBeExported() throws Exception {
    DescribedKdc kdc = new DescribedKdc();
    kdc.exists = true;
    kdc.description = OWNER;

    AdhocKeytabGenerator.Result result =
        AdhocKeytabGenerator.issueOwned(kdc, "trino-analytics@AD.EXAMPLE.COM", OWNER, false, line -> { });

    assertTrue(kdc.calls.toString(), kdc.calls.contains("reset"));
    assertEquals("key from password 4", text(result));
  }

  @Test
  public void aNewKeyIsGivenOnlyToAnAccountMarkedAsTheCallers() throws Exception {
    DescribedKdc kdc = new DescribedKdc();
    kdc.exists = true;
    kdc.canExport = true;
    kdc.description = OWNER;

    AdhocKeytabGenerator.Result regenerated =
        AdhocKeytabGenerator.issueOwned(kdc, "trino-analytics@MIT.EXAMPLE.COM", OWNER, true, line -> { });

    assertFalse("a new key is asked for, not the current one: " + kdc.calls, kdc.calls.contains("export"));
    assertEquals("key from password 4", text(regenerated));

    kdc.description = "Created by KDPS for namespace finance";
    try {
      AdhocKeytabGenerator.issueOwned(kdc, "trino-analytics@MIT.EXAMPLE.COM", OWNER, true, line -> { });
      fail("The account of another namespace must keep its key");
    } catch (AdhocKeytabGenerator.ExistingPrincipalException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("was not changed"));
    }
    assertEquals(4, kdc.kvno);
  }

  @Test
  public void aNewAccountThatCannotBeMarkedIsRemoved() throws Exception {
    // Unmarked, no later request could give it a key.
    DescribedKdc kdc = new DescribedKdc();
    kdc.refuseDescription = true;

    try {
      AdhocKeytabGenerator.issueOwned(kdc, "trino-analytics@IPA.EXAMPLE.COM", OWNER, false, line -> { });
      fail("The keytab of an unmarked account must not be issued");
    } catch (KerberosOperationException e) {
      assertTrue(kdc.calls.toString(), kdc.calls.contains("remove"));
    }
  }
}
