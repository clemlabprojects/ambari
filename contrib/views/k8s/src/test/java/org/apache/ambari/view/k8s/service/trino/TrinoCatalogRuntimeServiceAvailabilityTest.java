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
package org.apache.ambari.view.k8s.service.trino;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Catalog management signs in to Trino as the KDPS service user ('Let KDPS manage catalogs'), a password login, which
 * Trino only accepts over HTTPS ('Enable internal HTTPS'). A release deployed with internal HTTPS off leaves the HTTPS
 * value out, at the chart's default (off), and the wizard hides 'Let KDPS manage catalogs' until internal HTTPS is on:
 * the operator is told about internal HTTPS first, with the options named as the wizard shows them.
 */
class TrinoCatalogRuntimeServiceAvailabilityTest {

    private static final String INSTANCE = "trino-catalogs-availability-test";
    private static final String NS = "analytics";
    private static final String RELEASE = "trino";

    private final KubernetesService k8s = mock(KubernetesService.class);

    @BeforeEach
    void registerKubernetesService() throws Exception {
        instances().put(INSTANCE, k8s);
    }

    @AfterEach
    void unregisterKubernetesService() throws Exception {
        instances().remove(INSTANCE);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, KubernetesService> instances() throws Exception {
        Field f = KubernetesService.class.getDeclaredField("INSTANCES");
        f.setAccessible(true);
        return (ConcurrentMap<String, KubernetesService>) f.get(null);
    }

    /** Release values with the option and internal HTTPS set as given, or left out when {@code null}. */
    private static Map<String, Object> values(Boolean serviceUser, Object https) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (serviceUser != null) {
            values.put("kdps", new LinkedHashMap<>(Map.of("serviceUser", new LinkedHashMap<>(Map.of("enabled", serviceUser)))));
        }
        if (https != null) {
            values.put("server", new LinkedHashMap<>(Map.of("config",
                    new LinkedHashMap<>(Map.of("https", new LinkedHashMap<>(Map.of("enabled", https)))))));
        }
        return values;
    }

    /** The view instance, with no release recorded. */
    private static ViewContext context() {
        ViewContext ctx = mock(ViewContext.class);
        when(ctx.getInstanceName()).thenReturn(INSTANCE);
        when(ctx.getDataStore()).thenReturn(mock(DataStore.class));
        return ctx;
    }

    private static String unavailable(Map<String, Object> values) {
        return TrinoCatalogRuntimeService.serviceUserUnavailable(values, "Catalog management", NS + "/" + RELEASE);
    }

    private static void assertAsksForInternalHttpsFirst(String message) {
        assertTrue(message.startsWith("Catalog management needs 'Enable internal HTTPS' and 'Let KDPS manage catalogs'"
                + " on Trino release analytics/trino"), message);
        assertTrue(message.contains("turn 'Enable internal HTTPS' on, then 'Let KDPS manage catalogs'"), message);
    }

    @Test
    void aReleaseThatLeavesInternalHttpsAtTheChartDefaultHasItOff() {
        // As the wizard deploys it with internal HTTPS unticked: the HTTPS value is left out, and the option is off.
        Map<String, Object> deployed = values(false, null);

        assertFalse(TrinoCatalogRuntimeService.serviceUserAvailable(deployed));
        assertAsksForInternalHttpsFirst(unavailable(deployed));
        assertAsksForInternalHttpsFirst(unavailable(values(true, null)));
        assertAsksForInternalHttpsFirst(unavailable(values(true, false)));
        assertAsksForInternalHttpsFirst(unavailable(null));
    }

    @Test
    void withInternalHttpsOnlyTheOptionIsAskedFor() {
        String message = unavailable(values(false, true));

        assertTrue(message.startsWith("Catalog management needs 'Let KDPS manage catalogs' on Trino release"
                + " analytics/trino, which was deployed without it."), message);
        assertFalse(message.contains("internal HTTPS"), message);
        assertFalse(TrinoCatalogRuntimeService.serviceUserAvailable(values(null, true)));
    }

    @Test
    void withBothOnKdpsCanSignIn() {
        assertNull(unavailable(values(true, true)));
        assertNull(unavailable(values(true, "true")));
        assertTrue(TrinoCatalogRuntimeService.serviceUserAvailable(values(true, true)));
    }

    @Test
    void theCatalogsActionOnAReleaseDeployedWithInternalHttpsOffAsksForInternalHttps() {
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(values(false, null));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new TrinoCatalogRuntimeService(context()).resolve(NS, RELEASE));

        assertAsksForInternalHttpsFirst(refused.getMessage());
        verify(k8s, never()).getClient();
    }

    @Test
    void theCatalogsActionOnAReleaseDeployedWithoutTheOptionAsksForIt() {
        when(k8s.getHelmReleaseValues(NS, RELEASE)).thenReturn(values(null, true));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new TrinoCatalogRuntimeService(context()).resolve(NS, RELEASE));

        assertTrue(refused.getMessage().contains("needs 'Let KDPS manage catalogs'"), refused.getMessage());
        verify(k8s, never()).getClient();
    }
}
