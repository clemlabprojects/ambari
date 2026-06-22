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

import org.apache.ambari.view.k8s.model.stack.ExternalServiceTarget;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExternalCredentialResolver} — the template engine that
 * resolves {@code externalServiceTargets.<key>.authModes.<mode>.applyTo} entries
 * against a Secret + form-values context.
 *
 * <p>Coverage targets:
 *   <ul>
 *     <li>Each ref type ({@code secret.name}, {@code secret.data.<key>}, {@code form.<path>})</li>
 *     <li>Each filter ({@code b64decode}, {@code trim}) — alone and chained</li>
 *     <li>Literal text + multiple tokens in one template</li>
 *     <li>Declared-keys check on {@code secret.data.X} catches typo-class errors</li>
 *     <li>Unknown ref/filter gracefully WARN-and-pass-through (no silent corruption)</li>
 *     <li>Real-world Kerberos applyTo for OM-hive (sanity-check the full happy path)</li>
 *   </ul>
 */
class ExternalCredentialResolverTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ExternalCredentialResolver.ResolverContext ctx(String name, Map<String, String> data, Map<String, Object> form) {
        return new ExternalCredentialResolver.ResolverContext(name, data, form);
    }

    // -------------------------------------------------------------------------
    // interpolate() — ref + filter chain
    // -------------------------------------------------------------------------

    @Test
    void resolvesSecretNameRef() {
        var c = ctx("creds-foo", null, null);
        assertEquals("creds-foo", ExternalCredentialResolver.interpolate("{{secret.name}}", c, null));
    }

    @Test
    void resolvesSecretDataKeyVerbatim() {
        // No filter → returns the base64-encoded value (chart consumes b64 directly,
        // e.g. when mounting via valueFrom.secretKeyRef).
        Map<String, String> data = Map.of("password", b64("s3cret"));
        var c = ctx("creds", data, null);
        assertEquals(b64("s3cret"),
                ExternalCredentialResolver.interpolate("{{secret.data.password}}", c, List.of("password")));
    }

    @Test
    void b64decodeFilterReturnsCleartext() {
        Map<String, String> data = Map.of("principal", b64("om-hive@FOREIGN.REALM\n"));
        var c = ctx("k", data, null);
        // Trailing newline preserved without trim filter
        assertEquals("om-hive@FOREIGN.REALM\n",
                ExternalCredentialResolver.interpolate("{{secret.data.principal | b64decode}}", c, List.of("principal")));
    }

    @Test
    void filterChainTrimsAfterDecode() {
        Map<String, String> data = Map.of("principal", b64("  om-hive@REALM \n"));
        var c = ctx("k", data, null);
        assertEquals("om-hive@REALM",
                ExternalCredentialResolver.interpolate(
                        "{{secret.data.principal | b64decode | trim}}",
                        c, List.of("principal")));
    }

    @Test
    void resolvesFormDottedPath() {
        Map<String, Object> form = new HashMap<>();
        Map<String, Object> hive = new HashMap<>();
        hive.put("host", "metastore.dev.example");
        form.put("hive", hive);
        var c = ctx(null, null, form);
        assertEquals("metastore.dev.example",
                ExternalCredentialResolver.interpolate("{{form.hive.host}}", c, null));
    }

    @Test
    void preservesLiteralTextAroundTokens() {
        Map<String, String> data = Map.of("host", b64("hms.dev"));
        var c = ctx("k", data, null);
        // A template that builds a JDBC URL by interpolating into the middle of a
        // larger string — common shape in real applyTo blocks.
        assertEquals("thrift://hms.dev:9083",
                ExternalCredentialResolver.interpolate(
                        "thrift://{{secret.data.host | b64decode}}:9083",
                        c, List.of("host")));
    }

    @Test
    void handlesMultipleTokensInOneTemplate() {
        Map<String, String> data = Map.of("user", b64("alice"), "pwd", b64("hunter2"));
        var c = ctx("k", data, null);
        assertEquals("alice:hunter2",
                ExternalCredentialResolver.interpolate(
                        "{{secret.data.user | b64decode}}:{{secret.data.pwd | b64decode}}",
                        c, List.of("user", "pwd")));
    }

    @Test
    void undeclaredSecretKeyThrowsToCatchTypoEarly() {
        Map<String, String> data = Map.of("keytab", b64("abc"));
        var c = ctx("k", data, null);
        // applyTo references `secret.data.principal` but the mode's secretKeys
        // declared only `keytab` — this is exactly the typo class the check
        // exists to catch (operator renamed the key in service.json but forgot
        // to update applyTo, or vice versa).
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ExternalCredentialResolver.interpolate(
                        "{{secret.data.principal | b64decode}}",
                        c, List.of("keytab")));
        assertTrue(ex.getMessage().contains("principal"));
        assertTrue(ex.getMessage().contains("secretKeys"));
    }

    @Test
    void undeclaredSecretKeyCheckSkippedWhenSecretKeysIsNull() {
        // Free-form templates outside an authMode's applyTo (e.g. future debug
        // tools) pass null for secretKeys to disable the check.
        Map<String, String> data = Map.of("anything", b64("ok"));
        var c = ctx("k", data, null);
        assertEquals("ok",
                ExternalCredentialResolver.interpolate(
                        "{{secret.data.anything | b64decode}}",
                        c, null));
    }

    @Test
    void unknownRefBecomesEmptyStringAndDoesNotCrash() {
        // A typo'd ref like `{{secret.foo}}` shouldn't blow up the deploy — log
        // and continue. The empty string in the resulting helm value is the
        // operator's signal to investigate.
        var c = ctx("k", Collections.emptyMap(), Collections.emptyMap());
        assertEquals("prefix--suffix",
                ExternalCredentialResolver.interpolate("prefix-{{secret.foo}}-suffix", c, null));
    }

    @Test
    void unknownFilterPassesValueThrough() {
        Map<String, String> data = Map.of("x", b64("plain"));
        var c = ctx("k", data, null);
        // `| upper` doesn't exist; resolver logs WARN and passes the value
        // unchanged. Forward-compat: future filters can be added without breaking
        // older deploys.
        assertEquals(b64("plain"),
                ExternalCredentialResolver.interpolate(
                        "{{secret.data.x | upper}}",
                        c, List.of("x")));
    }

    @Test
    void nullTemplateReturnsNull() {
        var c = ctx("k", null, null);
        assertNull(ExternalCredentialResolver.interpolate(null, c, null));
    }

    // -------------------------------------------------------------------------
    // resolveApplyTo() — the per-mode aggregation
    // -------------------------------------------------------------------------

    @Test
    void resolveApplyToWithNullApplyToReturnsEmptyMap() {
        ExternalServiceTarget.AuthMode mode = new ExternalServiceTarget.AuthMode();
        mode.applyTo = null;
        Map<String, String> result = ExternalCredentialResolver.resolveApplyTo(
                mode, ctx(null, null, Collections.emptyMap()));
        assertTrue(result.isEmpty(),
                "A mode with no applyTo (e.g. `none` sentinel) returns no overrides — caller skips the loop");
    }

    @Test
    void resolveApplyToEndToEndForHiveKerberosMode() {
        // The exact shape OM's externalServiceTargets.hive.authModes.kerberos uses.
        ExternalServiceTarget.AuthMode mode = new ExternalServiceTarget.AuthMode();
        mode.secretField = "hive.externalKeytabSecret";
        mode.secretKeys = Arrays.asList("keytab", "principal");
        Map<String, String> applyTo = new LinkedHashMap<>();
        applyTo.put("global.security.kerberos.keytab.secretName", "{{secret.name}}");
        applyTo.put("global.security.kerberos.keytab.secretDataKey", "keytab");
        applyTo.put("global.security.kerberos.principal", "{{secret.data.principal | b64decode | trim}}");
        mode.applyTo = applyTo;

        Map<String, String> data = Map.of(
                "keytab", b64("BINARY_KEYTAB"),
                "principal", b64("om-hive@FOREIGN.REALM  \n")
        );
        Map<String, String> result = ExternalCredentialResolver.resolveApplyTo(
                mode, ctx("external-hive-keytab", data, Collections.emptyMap()));

        assertEquals("external-hive-keytab", result.get("global.security.kerberos.keytab.secretName"));
        assertEquals("keytab", result.get("global.security.kerberos.keytab.secretDataKey"));
        assertEquals("om-hive@FOREIGN.REALM", result.get("global.security.kerberos.principal"),
                "Principal must be b64-decoded AND trimmed");
        assertEquals(3, result.size());
    }

    @Test
    void resolveApplyToPreservesInsertionOrderForDiffStableOverrides() {
        // Same diff-stability contract as buildTagSyncRangerSiteOverrides — Ambari
        // logs the override map verbatim and re-deploys with no changes should
        // produce identical log output.
        ExternalServiceTarget.AuthMode mode = new ExternalServiceTarget.AuthMode();
        Map<String, String> applyTo = new LinkedHashMap<>();
        applyTo.put("z.last", "{{secret.name}}");
        applyTo.put("a.first", "{{secret.name}}");
        applyTo.put("m.middle", "{{secret.name}}");
        mode.applyTo = applyTo;
        Map<String, String> result = ExternalCredentialResolver.resolveApplyTo(
                mode, ctx("name", Collections.emptyMap(), Collections.emptyMap()));
        java.util.Iterator<String> it = result.keySet().iterator();
        assertEquals("z.last", it.next());
        assertEquals("a.first", it.next());
        assertEquals("m.middle", it.next());
    }

    @Test
    void resolveApplyToRejectsNullMode() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalCredentialResolver.resolveApplyTo(null,
                        ctx(null, null, Collections.emptyMap())));
    }

    @Test
    void resolveApplyToRejectsNullContext() {
        ExternalServiceTarget.AuthMode mode = new ExternalServiceTarget.AuthMode();
        assertThrows(IllegalArgumentException.class,
                () -> ExternalCredentialResolver.resolveApplyTo(mode, null));
    }
}
