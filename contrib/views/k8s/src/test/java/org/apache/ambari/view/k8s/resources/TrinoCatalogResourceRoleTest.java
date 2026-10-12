/**
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

package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.TrinoCatalogDTO;
import org.apache.ambari.view.k8s.store.TrinoCatalogEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reusable Trino catalogs may hold their credentials in plain text: a read-only user sees which catalogs exist and how
 * they connect, never their passwords. Operators, who attach them to releases, get them whole.
 */
class TrinoCatalogResourceRoleTest {

  private static final String PROPERTIES = "connector.name=postgresql\n"
      + "connection-url=jdbc:postgresql://db:5432/app\n"
      + "connection-user=app\n"
      + "connection-password=s3cret";

  private static TrinoCatalogResource as(String user) throws Exception {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of("view.admin.users", "admin", "view.operator.users", "ops"));
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);
    TrinoCatalogEntity pg = new TrinoCatalogEntity();
    pg.setId("pg-1");
    pg.setName("postgres_app");
    pg.setConnectorName("postgresql");
    pg.setPropertiesText(PROPERTIES);
    DataStore store = mock(DataStore.class);
    when(store.findAll(eq(TrinoCatalogEntity.class), any())).thenReturn(List.of(pg));
    when(store.find(eq(TrinoCatalogEntity.class), eq("pg-1"))).thenReturn(pg);
    when(ctx.getDataStore()).thenReturn(store);
    return new TrinoCatalogResource(ctx, null);
  }

  @SuppressWarnings("unchecked")
  private static TrinoCatalogDTO listed(TrinoCatalogResource resource) {
    List<TrinoCatalogDTO> catalogs = (List<TrinoCatalogDTO>) resource.list().getEntity();
    assertEquals(1, catalogs.size());
    return catalogs.get(0);
  }

  @Test
  void aReadOnlyUserCannotReadACatalogPassword() throws Exception {
    for (TrinoCatalogDTO catalog : List.of(listed(as("audit")), (TrinoCatalogDTO) as("audit").get("pg-1").getEntity())) {
      assertFalse(catalog.propertiesText.contains("s3cret"), catalog.propertiesText);
      assertTrue(catalog.propertiesText.contains("connection-password=********"), catalog.propertiesText);
      // What the catalog is and where it connects stays visible.
      assertEquals("postgres_app", catalog.name);
      assertEquals("postgresql", catalog.connectorName);
      assertTrue(catalog.propertiesText.contains("connection-url=jdbc:postgresql://db:5432/app"), catalog.propertiesText);
      assertTrue(catalog.propertiesText.contains("connection-user=app"), catalog.propertiesText);
    }
  }

  @Test
  void operatorsAndAdminsGetTheCatalogWhole() throws Exception {
    for (String user : List.of("ops", "admin")) {
      assertEquals(PROPERTIES, listed(as(user)).propertiesText, user);
      assertEquals(PROPERTIES, ((TrinoCatalogDTO) as(user).get("pg-1").getEntity()).propertiesText, user);
    }
  }
}
