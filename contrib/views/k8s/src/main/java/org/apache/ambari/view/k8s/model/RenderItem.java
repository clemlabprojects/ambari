package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One rendering instruction coming from charts.json.
 *
 * Example JSON:
 * {
 *   "sourceType": "core-site",
 *   "targetConfigMapName": "hadoop-conf",
 *   "k8sType": "secret",
 *   "additionalOpts": { "fileName": "core-site.xml" }
 * }
 */
public final class RenderItem {
    /** Arbitrary key that selects which site map to render (e.g. "core-site"). */
    public final String sourceType;

    /** Target Kubernetes object name to merge into. */
    public final String targetConfigMapName;

    /** "configmap" or "secret" (case-insensitive). */
    public final String k8sType;

    /** Optional extra options (currently only "fileName" is recognized). */
    public final Map<String, Object> additionalOpts;

    @JsonCreator
    public RenderItem(
            @JsonProperty(value = "sourceType", required = true) String sourceType,
            @JsonProperty(value = "targetConfigMapName", required = true) String targetConfigMapName,
            @JsonProperty(value = "k8sType", required = true) String k8sType,
            @JsonProperty("additionalOpts") Map<String, Object> additionalOpts
    ) {
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null").trim();
        if (this.sourceType.isEmpty()) throw new IllegalArgumentException("sourceType is empty");

        this.targetConfigMapName = Objects.requireNonNull(targetConfigMapName, "targetConfigMapName must not be null").trim();
        if (this.targetConfigMapName.isEmpty()) throw new IllegalArgumentException("targetConfigMapName is empty");

        String normalized = Objects.requireNonNull(k8sType, "k8sType must not be null").trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("configmap") && !normalized.equals("secret")) {
            throw new IllegalArgumentException("k8sType must be 'configmap' or 'secret' (got: " + k8sType + ")");
        }
        this.k8sType = normalized;

        this.additionalOpts = (additionalOpts == null) ? Map.of() : Map.copyOf(additionalOpts);
    }

    /** Returns the file name to write; default is {@code <sourceType>.xml}. */
    public String resolveFileName() {
        Object explicit = additionalOpts.get("fileName");
        if (explicit instanceof String && !((String) explicit).isBlank()) {
            return ((String) explicit).trim();
        }
        return sourceType + ".xml";
    }

    /** True if this item targets a Secret object. */
    public boolean isSecret() {
        return "secret".equals(k8sType);
    }
}