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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.SecurityMappingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every deploy with a security profile reads the mapping of its fields to Helm paths. The read writes nothing once
 * the mapping row exists, and a store that fails cannot fail the deploy: the defaults come from the classpath.
 */
class SecurityMappingServiceTest {

    private DataStore store;
    private SecurityMappingService service;

    @BeforeEach
    void setUp() {
        ViewContext ctx = mock(ViewContext.class);
        store = mock(DataStore.class);
        doReturn(store).when(ctx).getDataStore();
        service = new SecurityMappingService(ctx);
    }

    private static SecurityMappingEntity row(String overridesJson) {
        SecurityMappingEntity e = new SecurityMappingEntity();
        e.setId(SecurityMappingEntity.SINGLETON_ID);
        e.setOverridesJson(overridesJson);
        return e;
    }

    @Test
    void readingTheMappingWritesNothingOnceTheRowExists() throws Exception {
        doReturn(row("{\"ldap\":{\"ldap.url\":\"custom.ldap.url\"}}")).when(store)
                .find(SecurityMappingEntity.class, SecurityMappingEntity.SINGLETON_ID);

        Map<String, Map<String, String>> merged = service.loadMergedMapping();
        service.loadMergedMapping();

        verify(store, never()).store(any());
        assertEquals("custom.ldap.url", merged.get("ldap").get("ldap.url"), "the overrides apply");
        assertFalse(merged.get("oidc").isEmpty(), "the defaults come from security.json");
    }

    @Test
    void theRowIsCreatedOnceWithoutACopyOfTheDefaults() throws Exception {
        doReturn(null).when(store).find(SecurityMappingEntity.class, SecurityMappingEntity.SINGLETON_ID);

        service.loadMergedMapping();

        ArgumentCaptor<SecurityMappingEntity> stored = ArgumentCaptor.forClass(SecurityMappingEntity.class);
        verify(store, times(1)).store(stored.capture());
        assertNull(stored.getValue().getDefaultJson(), "the defaults are never stored");
    }

    @Test
    void aStoreThatCannotBeWrittenOrReadDoesNotFailTheDeploy() throws Exception {
        doReturn(null).when(store).find(SecurityMappingEntity.class, SecurityMappingEntity.SINGLETON_ID);
        doThrow(new PersistenceException("value too long")).when(store).store(any());
        assertFalse(service.loadMergedMapping().get("oidc").isEmpty());

        doThrow(new PersistenceException("database down")).when(store)
                .find(eq(SecurityMappingEntity.class), any());
        assertFalse(service.loadMergedMapping().get("oidc").isEmpty());
    }

    @Test
    void overridesTooLongForTheStoreAreRefused() {
        SecurityMappingEntity e = new SecurityMappingEntity();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> e.setOverridesJson("x".repeat(SecurityMappingEntity.MAX_OVERRIDES_JSON_LENGTH + 1)));

        assertTrue(refused.getMessage().contains("too long"), refused.getMessage());
        assertNull(e.getOverridesJson());
    }
}
