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

import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A context saved with a KDC account keeps its password in Ambari's credential store only. */
class ContextServiceKdcAccountTest {

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private ContextKdcAccountTest.MapStore store;
    private boolean storeTouched;
    private ContextService contexts;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("kdps", workDir);
        view.as("admin");
        store = new ContextKdcAccountTest.MapStore();
        contexts = new ContextService(view.context) {
            @Override
            public ContextKdcAccount kdcAccount() {
                storeTouched = true;
                return new ContextKdcAccount(store);
            }
        };
    }

    private static ContextRequest cdp(Map<String, Object> config, String password) {
        ContextRequest request = new ContextRequest();
        request.id = "cdp-prod";
        request.name = "CDP production";
        request.kind = ContextService.KIND_EXTERNAL;
        request.config = new LinkedHashMap<>(config);
        request.secrets = new LinkedHashMap<>();
        if (password != null) request.secrets.put("kdcAdminPassword", password);
        return request;
    }

    private static Map<String, Object> activeDirectory() {
        return new LinkedHashMap<>(Map.of("realm", "CORP.EXAMPLE.COM", "kdcType", "active-directory",
                "kdcAdminPrincipal", "cm-kdc@CORP.EXAMPLE.COM", "kdcLdapUrl", "ldaps://dc01.corp.example.com",
                "kdcContainerDn", "OU=cloudera,DC=corp,DC=example,DC=com"));
    }

    @Test
    void theKdcPasswordGoesToTheCredentialStoreOnlyAndShowsAsSaved() {
        KdpsContextEntity saved = contexts.save(cdp(activeDirectory(), "s3cret"));

        assertTrue(store.records.get("kdps.context.cdp-prod.kdc.admin").contains("s3cret"));
        assertTrue(List.of(saved.getSecretKeys().split(",")).contains("kdcAdminPassword"), "shown as saved");
        assertNull(new InstanceSettings(view.context).getSecret("context.cdp-prod.kdcAdminPassword"),
                "never in the view's settings");
        assertFalse(saved.getConfigJson().contains("s3cret"));
    }

    @Test
    void aRefusedKdcAccountLeavesTheContextAsItWas() {
        contexts.save(cdp(activeDirectory(), "s3cret"));
        Map<String, Object> moved = activeDirectory();
        moved.put("kdcLdapUrl", "ldaps://attacker.example.com");
        moved.put("description", "changed");

        assertThrows(IllegalArgumentException.class, () -> contexts.save(cdp(moved, null)));

        assertFalse(contexts.get("cdp-prod").getConfigJson().contains("attacker"));
    }

    @Test
    void deletingTheContextRemovesItsKdcRecord() {
        contexts.save(cdp(activeDirectory(), "s3cret"));

        contexts.delete("cdp-prod");

        assertTrue(store.records.isEmpty());
    }

    @Test
    void aContextWithoutKdcAccountNeverTouchesTheCredentialStore() {
        contexts.save(cdp(Map.of("realm", "CORP.EXAMPLE.COM"), null));
        contexts.delete("cdp-prod");

        assertFalse(storeTouched);
    }
}
