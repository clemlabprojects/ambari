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

package org.apache.ambari.view.k8s.model.stack;

import java.util.List;
import java.util.Map;

/**
 * Declarative schema for one outbound service-to-service connection that can be
 * either Ambari-managed (no URL override → existing internal plumbing fires) or
 * external (URL override set → operator picks an auth mode + Secret, KDPS reads
 * the Secret and writes helm overrides per the auth mode's {@code applyTo} block).
 *
 * <p>Full schema documentation lives at
 * {@code docs/EXTERNAL_SERVICE_TARGETS.md}. This class is what Jackson hydrates
 * the JSON into; the deploy pipeline reads it from {@link StackServiceDef#externalServiceTargets}.
 */
public class ExternalServiceTarget {

    /** Human-readable label shown next to the target's UI group in the wizard. */
    public String label;

    /**
     * Matches a {@code DiscoveryResource} branch (e.g. {@code RANGER}, {@code ATLAS},
     * {@code HIVE_METASTORE}). When the operator picks an auto-discovered entry, the
     * URL is filled in elsewhere (the service-select field) — this attribute exists
     * purely as documentation for the wizard's UI labels.
     */
    public String discoveryServiceType;

    /**
     * Dotted form-field name the operator types the external URL into. When this
     * field's value at submit time is BLANK the entry is treated as Ambari-managed
     * — none of the {@code authModes} pipeline fires. When NON-BLANK, the entry
     * is treated as external — the deploy step reads {@code modeField} for the
     * chosen mode and applies the auth-mode's {@code applyTo} block.
     */
    public String urlOverrideField;

    /**
     * Dotted form-field name that holds the operator's chosen mode (one of the
     * keys in {@link #authModes}). May be null when only one mode is supported
     * — the deploy step uses the single declared mode in that case.
     */
    public String modeField;

    /** Map of mode name → mode definition. Keys appear as values in the auth dropdown. */
    public Map<String, AuthMode> authModes;

    /** Per-mode auth declaration. See {@code docs/EXTERNAL_SERVICE_TARGETS.md}. */
    public static class AuthMode {

        /** Human-readable label rendered in the auth-mode dropdown. */
        public String label;

        /**
         * Dotted form-field name where the operator picks the K8s Secret carrying
         * credentials for this mode. Must be declared elsewhere in
         * {@code service.json#form} as a {@code type: "secret-discovery"} field
         * (the wizard handles the picker UI). Optional — {@code none} mode does
         * not need a secret.
         */
        public String secretField;

        /**
         * Required key names in the picked Secret. The deploy step validates each
         * is present before resolving any template; missing keys raise
         * {@code IllegalStateException} with a clear "Secret X is missing key Y"
         * error so the operator fixes the Secret and retries.
         */
        public List<String> secretKeys;

        /**
         * Helm-path → template map. The deploy step interpolates each template
         * using the resolver context (Secret name + Secret data + form values),
         * applies the registered filters ({@code b64decode}, {@code trim}), and
         * writes the result as a helm {@code --set} override. Optional — modes
         * that just toggle an enable-flag may have an empty or null applyTo.
         */
        public Map<String, String> applyTo;
    }
}
