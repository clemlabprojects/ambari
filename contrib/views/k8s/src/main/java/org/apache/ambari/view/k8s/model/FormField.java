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

package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Minimal form field descriptor used to deserialize service.json (KDPS) forms.
 * Mirrors the structure already consumed by the frontend dynamic form logic.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormField {
    public String name;
    public String label;
    public String type; // string, boolean, number, select, group, k8s-discovery, etc.
    public boolean required;
    public Object defaultValue;
    public String help;
    public boolean excludeFromValues;
    public String serviceType; // for service-select / discovery backed fields
    public String discoveryType; // e.g. monitoring-discovery
    /**
     * For {@code type: "context-resolved"} fields — the {@code <capability>.<field>} key
     * (e.g. {@code hive.hs2HostPort}) whose value the selected platform context supplies when
     * the operator does not override it.
     */
    public String contextField;

    /**
     * For {@code type: "external-auth-target"} fields — references a key in the
     * service def's {@code externalServiceTargets} map. The wizard's
     * ExternalAuthTargetField component reads the referenced entry to learn
     * which auth modes + Secret field paths to render.
     */
    public String target;

    // For discovery/selectors
    public String lookupLabel;
    // A discovery/selector field (k8s-discovery, service-select, hadoop-discovery) auto-fills these
    // OTHER form fields with the picked service's host/port. They MUST be declared here: FormField
    // uses @JsonIgnoreProperties(ignoreUnknown=true), so undeclared properties are silently dropped
    // on load and never reach the UI — which disabled Trino host auto-fill (ui_trino_host stayed
    // empty, so the Superset→Trino datasource import was skipped).
    public String targetHost;
    public String targetPort;
    public String placeholder;
    public String tooltip;

    // For select fields
    public List<Option> options;

    // For grouped fields
    public List<FormField> fields;

    // Validation constraints
    public Integer maxLength;
    public String validationHint;

    // Simple condition support: { field, value }
    public Map<String, Object> condition;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Option {
        public String label;
        public String value;
        /**
         * Optional capability gate read by the UI. Values today: "certManager",
         * "externalSecrets". When set, the wizard hides this option unless the
         * cluster's /cluster/capabilities probe reports the capability installed.
         * Backend ignores this field (Jackson tolerates it via @JsonIgnoreProperties).
         */
        public String capability;
    }
}
