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

package org.apache.ambari.server.serveraction.kerberos.stageutils;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ambari.server.orm.dao.KerberosKeytabDAO;
import org.apache.ambari.server.orm.dao.KerberosKeytabPrincipalDAO;
import org.apache.ambari.server.orm.entities.HostEntity;
import org.apache.ambari.server.orm.entities.KerberosKeytabEntity;
import org.apache.ambari.server.orm.entities.KerberosKeytabPrincipalEntity;
import org.apache.ambari.server.orm.entities.KerberosPrincipalEntity;
import org.junit.Before;
import org.junit.Test;

/**
 * A keytab file shared by several principals is selected with all of them, whatever the filter of the operation.
 */
public class KerberosKeytabControllerTest {

  private static final String SPNEGO = "/etc/security/keytabs/spnego.service.keytab";
  private static final String KUDU = "/etc/security/keytabs/kudu.service.keytab";
  private static final String IMPALA = "/etc/security/keytabs/impala.service.keytab";
  private static final String DATANODE = "/etc/security/keytabs/dn.service.keytab";

  private final Map<String, KerberosKeytabEntity> keytabs = new HashMap<>();
  private final Map<String, KerberosPrincipalEntity> principals = new HashMap<>();
  private final Map<Long, HostEntity> hosts = new HashMap<>();
  private final List<KerberosKeytabPrincipalEntity> records = new ArrayList<>();
  private final List<Long> hostsRead = new ArrayList<>();

  private List<KerberosKeytabPrincipalEntity> selectedByFilter;
  private KerberosKeytabController controller;

  @Before
  public void setUp() throws Exception {
    // host 1: Kudu and Impala both keep HTTP/host1 next to their own principal
    record(1L, SPNEGO, "HTTP/host1@EXAMPLE.COM");
    record(1L, KUDU, "HTTP/host1@EXAMPLE.COM");
    record(1L, KUDU, "kudu/host1@EXAMPLE.COM");
    record(1L, IMPALA, "HTTP/host1@EXAMPLE.COM");
    record(1L, IMPALA, "impala/host1@EXAMPLE.COM");
    record(1L, DATANODE, "dn/host1@EXAMPLE.COM");
    // host 2: the same files, other principals
    record(2L, SPNEGO, "HTTP/host2@EXAMPLE.COM");
    record(2L, KUDU, "HTTP/host2@EXAMPLE.COM");
    record(2L, KUDU, "kudu/host2@EXAMPLE.COM");

    controller = new KerberosKeytabController();
    set("kerberosKeytabDAO", new KerberosKeytabDAO() {
      @Override
      public KerberosKeytabEntity find(String keytabPath) {
        return keytabs.get(keytabPath);
      }
    });
    set("kerberosKeytabPrincipalDAO", new KerberosKeytabPrincipalDAO() {
      @Override
      public List<KerberosKeytabPrincipalEntity> findByFilters(Collection<KerberosKeytabPrincipalFilter> filters) {
        return selectedByFilter;
      }

      @Override
      public List<KerberosKeytabPrincipalEntity> findByHost(Long hostId) {
        hostsRead.add(hostId);
        List<KerberosKeytabPrincipalEntity> result = new ArrayList<>();
        for (KerberosKeytabPrincipalEntity record : records) {
          if (hostId.equals(record.getHostId())) {
            result.add(record);
          }
        }
        return result;
      }
    });
  }

  @Test
  public void aPrincipalBringsThePrincipalsSharingItsKeytabFiles() {
    // an operation on HTTP/host1 only, for instance a new component using the SPNEGO identity
    selectedByFilter = recordsOf(1L, "HTTP/host1@EXAMPLE.COM");

    Map<String, Set<String>> selected = select();

    assertEquals(setOf("HTTP/host1@EXAMPLE.COM"), selected.get(SPNEGO));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "kudu/host1@EXAMPLE.COM"), selected.get(KUDU));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "impala/host1@EXAMPLE.COM"), selected.get(IMPALA));
    assertEquals("a keytab file none of the selected principals belongs to is left alone", null, selected.get(DATANODE));
    assertEquals("the other host is not touched", Collections.singletonList(1L), hostsRead);
  }

  @Test
  public void aPrincipalBringsTheOtherFilesOfThePrincipalsItSharesAFileWith() {
    // an operation on kudu/host1 only: its file also holds HTTP/host1, whose other files are then rebuilt too,
    // each of them complete
    selectedByFilter = recordsOf(1L, "kudu/host1@EXAMPLE.COM");

    Map<String, Set<String>> selected = select();

    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "kudu/host1@EXAMPLE.COM"), selected.get(KUDU));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM"), selected.get(SPNEGO));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "impala/host1@EXAMPLE.COM"), selected.get(IMPALA));
    assertEquals(null, selected.get(DATANODE));
  }

  @Test
  public void aKeytabFileWithASinglePrincipalIsSelectedAlone() {
    selectedByFilter = recordsOf(1L, "dn/host1@EXAMPLE.COM");

    Map<String, Set<String>> selected = select();

    assertEquals(Collections.singleton(DATANODE), selected.keySet());
    assertEquals(setOf("dn/host1@EXAMPLE.COM"), selected.get(DATANODE));
  }

  @Test
  public void hostsAreCompletedIndependently() {
    selectedByFilter = new ArrayList<>(recordsOf(1L, "kudu/host1@EXAMPLE.COM"));
    selectedByFilter.addAll(recordsOf(2L, "HTTP/host2@EXAMPLE.COM"));

    Map<String, Set<String>> selected = select();

    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "kudu/host1@EXAMPLE.COM", "HTTP/host2@EXAMPLE.COM", "kudu/host2@EXAMPLE.COM"),
        selected.get(KUDU));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "HTTP/host2@EXAMPLE.COM"), selected.get(SPNEGO));
    assertEquals(setOf("HTTP/host1@EXAMPLE.COM", "impala/host1@EXAMPLE.COM"), selected.get(IMPALA));
  }

  @Test
  public void nothingSelectedStaysEmpty() {
    selectedByFilter = Collections.emptyList();

    assertEquals(Collections.emptyMap(), select());
    assertEquals(Collections.emptyList(), hostsRead);
  }

  private Map<String, Set<String>> select() {
    Map<String, Set<String>> result = new HashMap<>();
    for (ResolvedKerberosKeytab keytab : controller.getFilteredKeytabs(null, Collections.singleton("host"), new HashSet<>(Arrays.asList("any")))) {
      Set<String> names = new TreeSet<>();
      for (ResolvedKerberosPrincipal principal : keytab.getPrincipals()) {
        names.add(principal.getPrincipal());
      }
      result.put(keytab.getFile(), names);
    }
    return result;
  }

  private List<KerberosKeytabPrincipalEntity> recordsOf(Long hostId, String principal) {
    List<KerberosKeytabPrincipalEntity> result = new ArrayList<>();
    for (KerberosKeytabPrincipalEntity record : records) {
      if (hostId.equals(record.getHostId()) && principal.equals(record.getPrincipalName())) {
        result.add(record);
      }
    }
    return result;
  }

  private void record(Long hostId, String keytabPath, String principal) {
    KerberosKeytabEntity keytab = keytabs.get(keytabPath);
    if (keytab == null) {
      keytab = new KerberosKeytabEntity(keytabPath);
      keytabs.put(keytabPath, keytab);
    }
    KerberosPrincipalEntity principalEntity = principals.get(principal);
    if (principalEntity == null) {
      principalEntity = new KerberosPrincipalEntity(principal, true, "/cache/" + principal);
      principals.put(principal, principalEntity);
    }
    HostEntity host = hosts.get(hostId);
    if (host == null) {
      host = new HostEntity();
      host.setHostId(hostId);
      host.setHostName("host" + hostId);
      hosts.put(hostId, host);
    }
    KerberosKeytabPrincipalEntity record = new KerberosKeytabPrincipalEntity(keytab, host, principalEntity);
    record.setKkpId((long) (records.size() + 1));
    records.add(record);
  }

  private static Set<String> setOf(String... values) {
    return new TreeSet<>(Arrays.asList(values));
  }

  private void set(String field, Object value) throws Exception {
    Field declared = KerberosKeytabController.class.getDeclaredField(field);
    declared.setAccessible(true);
    declared.set(controller, value);
  }
}
