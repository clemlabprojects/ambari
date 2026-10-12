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
    /**
     * Marks a text field that holds a secret although it is not typed as a password, e.g. an S3 access key or
     * free-text properties that may carry a connection password. Like a password or pasted content, its value is kept
     * out of the logs and out of what the view keeps of a deploy (see {@code SensitiveFields}).
     */
    public boolean sensitive;
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

    /**
     * Regular expression the whole value must match, such as Helm's rule for a release name; the wizard shows
     * {@link #validationHint} under the field while it does not, so a name Helm or Kubernetes would refuse is caught
     * as it is typed. Read by the UI only, declared here for the same reason as {@link #resourceCheck}. The server
     * checks the release, namespace and chart names on its own.
     */
    public String pattern;

    // Simple condition support: { field, value }
    public Map<String, Object> condition;

    /**
     * Wizard-side check of a resource value against the limit it must stay under:
     * {@code { "kind": "javaHeap" | "memoryRequest" | "cpuRequest", "limitField": "<form field>" }}.
     * The wizard refuses, for example, a Java heap too close to the pod memory limit, or a request
     * above its limit, before anything is deployed. Read by the UI only; it must be declared here
     * because properties this class does not declare never reach the UI.
     */
    public Map<String, Object> resourceCheck;

    /**
     * When true and the field is left blank, the wizard leaves the key out of the chart values so
     * the chart's own default applies, instead of sending an empty string an older chart might not
     * understand. Read by the UI only, declared here for the same reason as {@link #resourceCheck}.
     */
    public boolean omitWhenBlank;

    /**
     * Platform or cluster capability a field, or a whole group, needs to be shown: {@code "kubernetes"} (anything but
     * a confirmed OpenShift cluster) or {@code "openshift"}, e.g. the nginx Ingress settings or the kube-prometheus
     * monitoring group, which mean nothing on the other platform. The wizard hides such a field and leaves its value
     * out of the chart values (see {@link #keepWhenHidden}), so a hidden default never reaches the chart. Read by the
     * UI only, declared here for the same reason as {@link #resourceCheck}.
     */
    public String capability;

    /**
     * When true, the wizard still sends the value of this field on a platform where {@link #capability} hides it: for
     * a field the wizard fills in by itself, such as the ingress class it detects on the cluster, or a value the chart
     * uses on both platforms. Read by the UI only.
     */
    public boolean keepWhenHidden;

    /**
     * For a field kept out of the chart values ({@code excludeFromValues}) whose effect is a chart
     * value: the values path that holds it (e.g. {@code ranger.tagSync.enabled}). On an upgrade the
     * wizard fills the field from the deployed release's value at that path, so the form shows what
     * runs instead of the field's default and re-submitting it changes nothing.
     */
    public String seedFromValue;

    /**
     * With {@link #seedFromValue}: a {@code ${path}} template of the value the chart gets when the
     * field is left blank (e.g. {@code https://${ingress.host}/api}). When the deployed value equals
     * it, the field stays blank, so the derived value keeps following its inputs.
     */
    public String seedUnlessEquals;

    /**
     * Value paths an upgrade fills a blank field from, first non-blank wins: the place the release's values keep what
     * the field set, for a field kept out of the values ({@link #excludeFromValues}), e.g. the Superset SECRET_KEY,
     * kept in {@code extraSecretEnv.SUPERSET_SECRET_KEY}. Read by the UI only.
     */
    public List<String> seedFromValues;

    /** For {@code secret-discovery} fields: several Secrets may be picked, and the value is their list. Read by the UI only. */
    public boolean multiple;

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
        /**
         * Offered on Upgrade/Config of a deployed release only, e.g. the "Keep current certificate" TLS mode, which
         * has nothing to keep on a first install. Read by the UI only; declared here because properties this class
         * does not declare never reach the UI.
         */
        public boolean upgradeOnly;
    }
}
