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

import org.apache.ambari.view.k8s.model.FormField;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context-resolved wizard field (Superset's hiveDb.hostPort / hiveDb.authMode) stays blank in
 * the submitted form unless the operator overrides it; the deploy must read the value the
 * selected platform context resolves for it, gated by the same toggle the wizard uses.
 */
class CommandServiceEffectiveFormValueTest {

    private static FormField field(String name, String type, String contextField, Map<String, Object> condition) {
        FormField f = new FormField();
        f.name = name;
        f.type = type;
        f.contextField = contextField;
        f.condition = condition;
        return f;
    }

    private static Map<String, Object> cond(String field, Object value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", field);
        c.put("value", value);
        return c;
    }

    /** Superset's "Platform Hive database" group as service.json declares it. */
    private static StackServiceDef supersetDef() {
        FormField group = field("hiveDatabase", "group", null, null);
        group.fields = Arrays.asList(
                field("hiveDb.enabled", "boolean", null, null),
                field("hiveDb.hostPort", "context-resolved", "hive.hs2HostPort", cond("hiveDb.enabled", true)),
                field("hiveDb.authMode", "context-resolved", "hive.authMode", cond("hiveDb.enabled", true)),
                field("hiveDb.externalKeytabSecret", "secret-discovery", null, cond("hiveDb.enabled", true)));
        StackServiceDef def = new StackServiceDef();
        def.form = List.of(field("releaseName", "string", null, null), group);
        return def;
    }

    private static Map<String, String> cdpContext() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("hive.hs2HostPort", "master03.dev01.hadoop.clemlab.com:10001");
        ctx.put("hive.authMode", "kerberos");
        return ctx;
    }

    private static Map<String, Object> form(boolean hiveEnabled) {
        Map<String, Object> hiveDb = new LinkedHashMap<>();
        hiveDb.put("enabled", hiveEnabled);
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("platformContextId", "prod-vagrant");
        fv.put("hiveDb", hiveDb);
        return fv;
    }

    @Test
    void findsFieldsInsideGroups() {
        StackServiceDef def = supersetDef();
        assertSame(def.form.get(1).fields.get(1), CommandService.findFormField(def.form, "hiveDb.hostPort"));
        assertNull(CommandService.findFormField(def.form, "hiveDb.nope"));
        assertNull(CommandService.findFormField(null, "hiveDb.hostPort"));
    }

    @Test
    void contextValueIsUsedWhenTheToggleIsOnAndNothingIsOverridden() {
        StackServiceDef def = supersetDef();
        assertEquals("master03.dev01.hadoop.clemlab.com:10001",
                CommandService.effectiveFormValue(def, form(true), "hiveDb.hostPort", cdpContext()));
        assertEquals("kerberos",
                CommandService.effectiveFormValue(def, form(true), "hiveDb.authMode", cdpContext()));
    }

    @Test
    void operatorOverrideWins() {
        Map<String, Object> fv = form(true);
        ((Map<String, Object>) fv.get("hiveDb")).put("hostPort", "hs2.example.com:10000");
        assertEquals("hs2.example.com:10000",
                CommandService.effectiveFormValue(supersetDef(), fv, "hiveDb.hostPort", cdpContext()));
    }

    @Test
    void hiveOffMeansTheTargetIsNotInPlay() {
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(false), "hiveDb.hostPort", cdpContext()));
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(false), "hiveDb.authMode", cdpContext()));
    }

    @Test
    void fieldsWithoutAContextSourceStayBlank() {
        // A plain secret picker has no contextField: only the operator can fill it.
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(true), "hiveDb.externalKeytabSecret", cdpContext()));
        // Trino's gate is the keytab field itself and is not declared as context-resolved.
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(true), "hive.externalKeytabSecret", cdpContext()));
        // The managed context resolves nothing here.
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(true), "hiveDb.hostPort", Map.of()));
        assertEquals("", CommandService.effectiveFormValue(supersetDef(), form(true), "hiveDb.hostPort", null));
    }

    @Test
    void conditionsFollowTheWizardRules() {
        Map<String, Object> fv = form(true);
        assertTrue(CommandService.formConditionHolds(null, fv));
        assertTrue(CommandService.formConditionHolds(cond("hiveDb.enabled", true), fv));
        assertTrue(CommandService.formConditionHolds(cond("hiveDb.enabled", "true"), fv));
        assertFalse(CommandService.formConditionHolds(cond("hiveDb.enabled", false), fv));
        assertTrue(CommandService.formConditionHolds(cond("platformContextId", Arrays.asList("default", "prod-vagrant")), fv));
        assertFalse(CommandService.formConditionHolds(cond("platformContextId", Arrays.asList("default", "other")), fv));

        Map<String, Object> nonEmpty = new LinkedHashMap<>();
        nonEmpty.put("field", "hiveDb.externalKeytabSecret");
        nonEmpty.put("operator", "non-empty");
        assertFalse(CommandService.formConditionHolds(nonEmpty, fv));
        ((Map<String, Object>) fv.get("hiveDb")).put("externalKeytabSecret", "superset-cdp-keytab");
        assertTrue(CommandService.formConditionHolds(nonEmpty, fv));
    }
}
