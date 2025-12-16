package org.apache.ambari.view.k8s.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Specification describing a "rendered config" resource to create in Kubernetes.
 * It can represent either a ConfigMap or a Secret, with multiple named entries (files).
 */
public class RenderConfigSpec {
    /**
     * Kubernetes resource name.
     */
    private final String resourceName;

    /**
     * Resource kind to create: CONFIG_MAP or SECRET.
     */
    private final RenderConfigType resourceType;

    /**
     * Key → file contents mapping.
     * Keys are the filenames you will later mount in the container; values are the full file content as a String.
     */
    private final Map<String, String> files;

    /**
     * Optional labels to attach to the resource.
     */
    private final Map<String, String> labels;

    /**
     * Optional annotations to attach to the resource.
     */
    private final Map<String, String> annotations;

    /**
     * For Secrets: whether the Secret should be immutable. null means "do not set".
     * (Kubernetes supports immutable both for ConfigMaps and Secrets; for ConfigMaps it's rarely used here, but supported.)
     */
    private final Boolean immutable;

    public RenderConfigSpec(String resourceName,
                            RenderConfigType resourceType,
                            Map<String, String> files,
                            Map<String, String> labels,
                            Map<String, String> annotations,
                            Boolean immutable) {
        this.resourceName = Objects.requireNonNull(resourceName, "resourceName");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.files = files == null ? Map.of() : new LinkedHashMap<>(files);
        this.labels = labels == null ? Map.of() : new LinkedHashMap<>(labels);
        this.annotations = annotations == null ? Map.of() : new LinkedHashMap<>(annotations);
        this.immutable = immutable;
    }

    public String getResourceName() {
        return resourceName;
    }

    public RenderConfigType getResourceType() {
        return resourceType;
    }

    public Map<String, String> getFiles() {
        return files;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public Boolean getImmutable() {
        return immutable;
    }

    @Override
    public String toString() {
        return "RenderConfigSpec{" +
                "resourceName='" + resourceName + '\'' +
                ", resourceType=" + resourceType +
                ", files=" + (files != null ? files.keySet() : List.of()) +
                ", labels=" + labels +
                ", annotations=" + annotations +
                ", immutable=" + immutable +
                '}';
    }
}