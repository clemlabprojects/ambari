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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandServiceRangerSpecTest {

    @Test
    void detectsKerberosHintsInRangerSpec() throws Exception {
        CommandService commandService = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Method method = CommandService.class.getDeclaredMethod("isKerberosRangerSpec", Map.class);
        method.setAccessible(true);

        Map<String, Map<String, Object>> rangerSpec = new LinkedHashMap<>();
        Map<String, Object> securityEntry = new HashMap<>();
        securityEntry.put("krb5_princ_srv", "trino");
        rangerSpec.put("ranger-security", securityEntry);

        boolean result = (boolean) method.invoke(commandService, rangerSpec);
        assertTrue(result, "Expected Kerberos hints to be detected");
    }

    @Test
    void returnsFalseWhenNoKerberosHintsPresent() throws Exception {
        CommandService commandService = Mockito.mock(CommandService.class, Mockito.CALLS_REAL_METHODS);
        Method method = CommandService.class.getDeclaredMethod("isKerberosRangerSpec", Map.class);
        method.setAccessible(true);

        Map<String, Map<String, Object>> rangerSpec = new LinkedHashMap<>();
        Map<String, Object> securityEntry = new HashMap<>();
        securityEntry.put("plugin_username", "ranger-trino");
        rangerSpec.put("ranger-security", securityEntry);

        boolean result = (boolean) method.invoke(commandService, rangerSpec);
        assertFalse(result, "Expected Kerberos hints to be absent");
    }
}
