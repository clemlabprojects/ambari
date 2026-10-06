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

import java.io.File;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Charts whose kinit sidecar derives its own principal must have the issued principal pinned by KDPS
 * (kerberos[].principalValuePath): KDPS shortens primaries above the FreeIPA limit, so a derived
 * name stops matching the keytab for long namespaces. Superset is the known case.
 */
public class KerberosPrincipalPinContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void supersetPinsTheIssuedPrincipalIntoTheKinitSidecarValue() throws Exception {
        File def = new File("src/main/resources/KDPS/services/SUPERSET/service.json");
        Map<String, Object> d = new ObjectMapper().readValue(def, Map.class);
        List<Map<String, Object>> kerberos = (List<Map<String, Object>>) d.get("kerberos");
        assertNotNull(kerberos);
        Map<String, Object> service = kerberos.stream().filter(e -> "service".equals(e.get("key"))).findFirst().orElseThrow();
        assertEquals("{{service}}-{{namespace}}@{{realm}}", service.get("principalTemplate"));
        assertEquals("global.security.kerberos.kinitSidecar.principal", service.get("principalValuePath"),
                "the Superset chart reads the pinned principal from global.security.kerberos.kinitSidecar.principal");
    }
}
