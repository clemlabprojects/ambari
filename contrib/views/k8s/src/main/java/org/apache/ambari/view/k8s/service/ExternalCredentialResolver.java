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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-function template resolver for {@code externalServiceTargets.<key>.authModes.<mode>.applyTo}.
 *
 * <p>Given a {@link ResolverContext} (Secret name + Secret data map + form values),
 * interpolates each template in the {@code applyTo} block and returns the helm
 * override map ready for {@code commandUtils.addOverride}.
 *
 * <p>Template language (full spec in {@code docs/EXTERNAL_SERVICE_TARGETS.md}):
 * <ul>
 *   <li>{@code {{secret.name}}} → the picked Secret's metadata name</li>
 *   <li>{@code {{secret.data.<key>}}} → the base64-encoded Secret value at key</li>
 *   <li>{@code {{form.<dottedPath>}}} → the form value at path</li>
 *   <li>{@code | b64decode} filter → base64-decode the previous value</li>
 *   <li>{@code | trim} filter → strip leading/trailing whitespace</li>
 * </ul>
 *
 * <p>Errors raise {@link IllegalStateException} with messages the operator sees
 * in the wizard's deploy log — the resource layer catches them as HTTP 400.
 */
public final class ExternalCredentialResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalCredentialResolver.class);

    /**
     * Token format: {@code {{ ref [| filter [| filter ...]] }}}
     *   group(1) — the reference path (e.g. {@code secret.name}, {@code secret.data.principal})
     *   group(2) — the optional filter chain ({@code | b64decode | trim})
     */
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile(
            "\\{\\{\\s*([^|}]+?)\\s*((?:\\|[^|}]+)*)\\s*\\}\\}"
    );

    private ExternalCredentialResolver() { /* utility class */ }

    /**
     * Resolves the auth-mode's {@code applyTo} block into a helm-override map.
     *
     * <p>Returns an empty map when {@code applyTo} is null/empty (some modes
     * have no overrides, like a {@code none} mode that only acts as a sentinel).
     *
     * @param mode    the chosen auth mode (must be one of the entries in the
     *                target's {@code authModes} map). Caller validates this.
     * @param context the resolver context (Secret name + Secret data + form values).
     *                {@code secretName}/{@code secretData} may be null when the mode
     *                declares no {@code secretField}.
     * @return helm path → resolved value map (in declaration order)
     * @throws IllegalStateException when a {@code secret.data.<key>} reference
     *                               points at a key not declared in the mode's
     *                               {@code secretKeys} — caught early so the
     *                               operator doesn't end up with empty helm
     *                               overrides silently.
     */
    public static Map<String, String> resolveApplyTo(ExternalServiceTarget.AuthMode mode,
                                                     ResolverContext context) {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        if (context == null) throw new IllegalArgumentException("context is required");
        if (mode.applyTo == null || mode.applyTo.isEmpty()) return Collections.emptyMap();

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mode.applyTo.entrySet()) {
            String helmPath = entry.getKey();
            String template = entry.getValue();
            if (helmPath == null || helmPath.isBlank() || template == null) continue;
            String resolved = interpolate(template, context, mode.secretKeys);
            result.put(helmPath, resolved);
        }
        return result;
    }

    /**
     * Interpolates a single template string. Public so unit tests can target it
     * directly; the {@code resolveApplyTo} caller does the per-mode aggregation.
     *
     * @param template the template source (e.g. {@code "thrift://{{form.x}}:9083"})
     * @param ctx      resolver context
     * @param secretKeys declared keys for the current mode — references to
     *                   {@code secret.data.X} where X is not in this list
     *                   raise IllegalStateException. Pass null to skip the
     *                   declared-keys check (e.g. when resolving free-form
     *                   templates outside a mode's applyTo).
     */
    public static String interpolate(String template, ResolverContext ctx, List<String> secretKeys) {
        if (template == null) return null;
        Matcher m = TEMPLATE_TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1).trim();
            String filterChain = m.group(2) == null ? "" : m.group(2);
            String value = resolveRef(ref, ctx, secretKeys);
            for (String f : filterChain.split("\\|")) {
                String fName = f.trim();
                if (fName.isEmpty()) continue;
                value = applyFilter(fName, value, ref);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Resolves one ref path against the context. Unknown refs return empty
     * string + WARN — a typo in a single token shouldn't blow up the entire
     * deploy. Misuse of {@code secret.data.X} where X is not a declared key
     * DOES throw, however; that's a contract violation worth surfacing.
     */
    private static String resolveRef(String ref, ResolverContext ctx, List<String> secretKeys) {
        String[] parts = ref.split("\\.");
        if (parts.length == 0) return "";

        // {{secret.name}}
        if ("secret".equals(parts[0]) && parts.length == 2 && "name".equals(parts[1])) {
            return ctx.secretName == null ? "" : ctx.secretName;
        }

        // {{secret.data.<key>}}
        if ("secret".equals(parts[0]) && parts.length >= 3 && "data".equals(parts[1])) {
            String key = String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            // Enforce that the template only references keys the mode declared. This
            // catches "I renamed the key in the Secret but forgot to update applyTo"
            // before it produces a silent misconfiguration in the chart.
            if (secretKeys != null && !secretKeys.contains(key)) {
                throw new IllegalStateException(
                        "Template references {{secret.data." + key + "}} but '" + key
                                + "' is not in this auth mode's declared secretKeys " + secretKeys
                                + ". Update service.json so secretKeys lists every key referenced from applyTo.");
            }
            if (ctx.secretData == null) return "";
            // Secret values are base64-encoded; the b64decode filter is the operator's
            // responsibility to apply (we ship cleartext OR raw base64 to the chart
            // depending on what the chart expects).
            return ctx.secretData.getOrDefault(key, "");
        }

        // {{form.<dotted.path>}}
        if ("form".equals(parts[0]) && parts.length >= 2) {
            String dotted = String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            Object v = ConfigResolutionService.getByDottedPath(ctx.formValues, dotted);
            return v == null ? "" : String.valueOf(v);
        }

        LOG.warn("ExternalCredentialResolver: unknown template ref '{}' — substituting empty string", ref);
        return "";
    }

    /** Applies a single filter to a value. Unknown filter names log WARN + pass-through. */
    private static String applyFilter(String filter, String value, String refContext) {
        if (value == null) value = "";
        switch (filter) {
            case "b64decode":
                try {
                    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException(
                            "Cannot base64-decode value for '" + refContext + "': " + ex.getMessage(), ex);
                }
            case "trim":
                return value.trim();
            default:
                LOG.warn("ExternalCredentialResolver: unknown filter '|{}'  — pass-through", filter);
                return value;
        }
    }

    /**
     * Resolver context — the data sources templates can pull from. Constructed
     * by the caller (CommandService.applyExternalServiceCredentials).
     */
    public static final class ResolverContext {
        /** Picked Secret's metadata.name. Null when the mode declares no secretField. */
        public final String secretName;
        /** Picked Secret's data map (base64-encoded values). Null when no Secret picked. */
        public final Map<String, String> secretData;
        /** All form values (includeFromValues + excludeFromValues), dotted-path indexed. */
        public final Map<String, Object> formValues;

        public ResolverContext(String secretName, Map<String, String> secretData, Map<String, Object> formValues) {
            this.secretName = secretName;
            this.secretData = secretData;
            this.formValues = formValues;
        }
    }
}
