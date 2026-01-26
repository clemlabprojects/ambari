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
