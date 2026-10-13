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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.resources.ConfigurationResource;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The credentials of the Thanos object store are kept encrypted apart from the view settings document: the
 * Configuration page only learns whether they are set, and sending the mask back keeps them.
 */
class ViewSettingsServiceTest {

    private static final String ACCESS = "AKIA-764-ACCESS";
    private static final String SECRET = "S3-Secret-Key-764";

    private InMemoryDataStore rows;
    private ViewContext ctx;

    @BeforeEach
    void setUp() {
        rows = new InMemoryDataStore();
        ctx = mock(ViewContext.class);
        Map<String, String> props = new HashMap<>();
        props.put("view.admin.users", "lead");
        when(ctx.getProperties()).thenReturn(props);
        when(ctx.getUsername()).thenReturn("lead");
        when(ctx.getLoggedinUser()).thenReturn("lead");
        when(ctx.getInstanceName()).thenReturn("kdps");
        when(ctx.getDataStore()).thenReturn(rows);
    }

    private ViewSettingsService settings() {
        return new ViewSettingsService(ctx);
    }

    /** The payload of the Configuration page. */
    private static Map<String, Object> page(Object accessKey, Object secretKey) {
        Map<String, Object> thanos = new LinkedHashMap<>();
        thanos.put("enabled", true);
        thanos.put("bucket", "metrics");
        thanos.put("accessKey", accessKey);
        thanos.put("secretKey", secretKey);
        Map<String, Object> monitoring = new LinkedHashMap<>();
        monitoring.put("autoBootstrap", true);
        monitoring.put("thanos", thanos);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("monitoring", monitoring);
        doc.put("kerberos", Map.of("injectionMode", "WEBHOOK"));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> thanosOf(Map<String, Object> doc) {
        return (Map<String, Object>) ((Map<String, Object>) doc.get("monitoring")).get("thanos");
    }

    private String document() {
        return rows.find(KdpsSettingEntity.class, ViewSettingsService.SETTINGS_KEY).getValue();
    }

    @Test
    void theCredentialsAreNeitherInTheDocumentNorInClear() throws Exception {
        settings().save(page(ACCESS, SECRET));

        StringBuilder stored = new StringBuilder();
        rows.findAll(KdpsSettingEntity.class, null).forEach(r -> stored.append(r.getId()).append(r.getValue()));
        assertFalse(stored.toString().contains(ACCESS), stored.toString());
        assertFalse(stored.toString().contains(SECRET), stored.toString());
        assertTrue(document().contains("\"bucket\":\"metrics\""), document());

        Map<String, Object> thanos = thanosOf(settings().read());
        assertEquals(ACCESS, thanos.get("accessKey"), "the view itself gets them");
        assertEquals(SECRET, thanos.get("secretKey"));
    }

    @Test
    void thePageGetsMasksAndSendingThemBackKeepsTheCredentials() {
        settings().save(page(ACCESS, SECRET));

        Map<String, Object> read = settings().readMasked();
        Map<String, Object> thanos = thanosOf(read);
        assertEquals(ViewSettingsService.MASKED_SECRET, thanos.get("accessKey"));
        assertEquals(ViewSettingsService.MASKED_SECRET, thanos.get("secretKey"));
        assertEquals(true, thanos.get("secretKeySet"));
        assertEquals("metrics", thanos.get("bucket"));

        thanos.put("bucket", "metrics-2");
        thanos.put("accessKey", "");
        settings().save(read);
        Map<String, Object> after = thanosOf(settings().read());
        assertEquals(ACCESS, after.get("accessKey"), "sent back blank");
        assertEquals(SECRET, after.get("secretKey"), "sent back masked");
        assertEquals("metrics-2", after.get("bucket"));
        assertFalse(document().contains("Set\""), "the flags of a read are not saved: " + document());

        settings().save(page(null, "S3-Secret-Key-Rotated"));
        assertEquals("S3-Secret-Key-Rotated", thanosOf(settings().read()).get("secretKey"));
        assertEquals(ACCESS, thanosOf(settings().read()).get("accessKey"));
    }

    @Test
    void nothingStoredReadsAsNothing() {
        assertEquals(Map.of(), settings().readMasked());
        settings().save(page(null, null));
        assertEquals(false, thanosOf(settings().readMasked()).get("secretKeySet"));
        assertNull(thanosOf(settings().read()).get("secretKey"));
    }

    @Test
    void aDocumentAnEarlierVersionSavedHasItsCredentialsMovedOnTheFirstRead() throws Exception {
        new InstanceSettings(ctx).put(ViewSettingsService.SETTINGS_KEY,
                new ObjectMapper().writeValueAsString(page(ACCESS, SECRET)));

        assertEquals(ViewSettingsService.MASKED_SECRET, thanosOf(settings().readMasked()).get("secretKey"));

        assertFalse(document().contains(SECRET), document());
        assertFalse(document().contains(ACCESS), document());
        assertEquals(SECRET, thanosOf(settings().read()).get("secretKey"));
    }

    @Test
    void theSettingsEndpointsServeMasksAndKeepTheCredentials() throws Exception {
        ConfigurationResource api = new ConfigurationResource(ctx, mock(KubernetesService.class), null);
        assertEquals(200, api.saveSettings(page(ACCESS, SECRET)).getStatus());

        Response get = api.getSettings();
        assertEquals(200, get.getStatus());
        String body = new ObjectMapper().writeValueAsString(get.getEntity());
        assertFalse(body.contains(SECRET), body);
        assertFalse(body.contains(ACCESS), body);

        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>) get.getEntity();
        assertEquals(200, api.saveSettings(read).getStatus());
        assertEquals(SECRET, thanosOf(settings().read()).get("secretKey"));
    }

    @Test
    void aCopyOfTheDocumentLosesItsCredentialsFromEverySection() {
        String both = "{\"monitoring\":{\"thanos\":{\"bucket\":\"metrics\",\"accessKey\":\"" + ACCESS + "\","
                + "\"secretKeySet\":true}},\"thanos\":{\"secretKey\":\"" + SECRET + "\"}}";

        String without = ViewSettingsService.withoutCredentials(both);

        assertFalse(without.contains(ACCESS), without);
        assertFalse(without.contains(SECRET), without);
        assertFalse(without.contains("Set\""), without);
        assertTrue(without.contains("\"bucket\":\"metrics\""), without);
        assertNull(ViewSettingsService.withoutCredentials("{\"monitoring\":{\"thanos\":{\"bucket\":\"m\"}}}"),
                "nothing to remove");
        assertNull(ViewSettingsService.withoutCredentials("not json"));
        assertNull(ViewSettingsService.withoutCredentials(null));
    }

    @Test
    void credentialsLeftInASectionTheViewDoesNotReadAreDroppedOnTheFirstRead() throws Exception {
        new InstanceSettings(ctx).put(ViewSettingsService.SETTINGS_KEY,
                "{\"monitoring\":{\"thanos\":{\"bucket\":\"metrics\",\"secretKey\":\"" + SECRET + "\"}},"
                        + "\"thanos\":{\"accessKey\":\"" + ACCESS + "\"}}");

        settings().readMasked();

        assertFalse(document().contains(ACCESS), document());
        assertFalse(document().contains(SECRET), document());
        assertEquals(SECRET, thanosOf(settings().read()).get("secretKey"));
        assertNull(thanosOf(settings().read()).get("accessKey"), "only the section the view reads is kept");
    }
}
