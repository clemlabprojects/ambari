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

package org.apache.ambari.view.k8s.service.deployment;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Flux manifests a GitOps deploy commits (HelmRelease, HelmRepository), built as maps and written by the YAML
 * serializer that writes the chart values. Whatever a name, chart, version or URL holds, it stays one YAML value: it
 * cannot end its line, add a key or start another document for Flux to apply.
 *
 * <p>The text is the one the view wrote from templates before: plain strings for the fields of the manifest, the chart
 * values written as {@link FluxGitOpsBackend#valuesYaml} writes them (every string double-quoted).
 *
 * <p>A HelmRelease reads its chart from a HelmRepository in its own namespace unless the platform keeps its chart
 * sources in one namespace ({@link FluxGitOpsBackend#SOURCE_NAMESPACE_PROPERTY}): a Flux that refuses references across
 * namespaces, or a tenant allowed in its own namespaces only, can then still apply it.
 */
final class FluxManifests {

    /** How often Flux checks a HelmRelease and a HelmRepository. */
    private static final String INTERVAL = "5m";

    /**
     * Annotation that keeps an object on the cluster when its manifest leaves Git: Flux does not prune it. Set on the
     * objects several releases share (a dependency such as KEDA, a chart source of the platform), whose manifest a
     * removal by hand or a change of layout must never turn into an uninstall for every release.
     */
    static final String PRUNE_ANNOTATION = "kustomize.toolkit.fluxcd.io/prune";
    static final String PRUNE_DISABLED = "disabled";

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .stringQuotingChecker(new PlainUnlessTyped())
            .build())
            .registerModule(new SimpleModule().addSerializer(DoubleQuoted.class, new DoubleQuotedSerializer()));

    private FluxManifests() {
    }

    /**
     * A Flux HelmRepository.
     *
     * @param name          its name
     * @param namespace     its namespace
     * @param url           the chart repository URL, with the {@code oci://} scheme for an OCI registry
     * @param oci           whether it is an OCI registry, which Flux reads only with {@code type: oci}
     * @param secretRef     the Secret holding the credentials Flux pulls charts with, {@code null} for none
     * @param certSecretRef the Secret holding the {@code ca.crt} Flux verifies the server with, {@code null} for none
     * @param shared        whether other releases read it too, so Flux never prunes it ({@link #PRUNE_ANNOTATION})
     */
    static String helmRepository(String name, String namespace, String url, boolean oci, String secretRef,
                                 String certSecretRef, boolean shared) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        if (shared) {
            metadata.put("annotations", Map.of(PRUNE_ANNOTATION, PRUNE_DISABLED));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interval", INTERVAL);
        if (oci) {
            spec.put("type", "oci");
        }
        spec.put("url", url);
        if (secretRef != null) {
            spec.put("secretRef", Map.of("name", secretRef));
        }
        if (certSecretRef != null) {
            spec.put("certSecretRef", Map.of("name", certSecretRef));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("apiVersion", FluxGitOpsBackend.FLUX_SRC_API_VERSION);
        document.put("kind", "HelmRepository");
        document.put("metadata", metadata);
        document.put("spec", spec);
        return write(document);
    }

    /**
     * The spec of a HelmRelease.
     *
     * @param chart           the chart as the Flux source knows it
     * @param version         the chart version, {@code null} to name none
     * @param source          the HelmRepository the chart comes from
     * @param sourceNamespace its namespace, {@code null} when it is the HelmRelease's own: the reference then names
     *                        none, which a Flux refusing references across namespaces accepts
     * @param dependsOn       the HelmReleases Flux installs first, may be empty
     * @param valuesSecret    the values Secret the HelmRelease reads, {@code null} for none
     * @param values          the chart values written to Git
     */
    static Map<String, Object> helmReleaseSpec(String chart, String version, String source, String sourceNamespace,
                                               List<FluxGitOpsBackend.DepRef> dependsOn, String valuesSecret,
                                               Map<String, Object> values) {
        Map<String, Object> sourceRef = new LinkedHashMap<>();
        sourceRef.put("kind", "HelmRepository");
        sourceRef.put("name", source);
        if (sourceNamespace != null) {
            sourceRef.put("namespace", sourceNamespace);
        }
        Map<String, Object> chartSpec = new LinkedHashMap<>();
        chartSpec.put("chart", chart);
        if (version != null) {
            chartSpec.put("version", version);
        }
        chartSpec.put("sourceRef", sourceRef);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interval", INTERVAL);
        spec.put("timeout", FluxGitOpsBackend.HELM_TIMEOUT);
        if (dependsOn != null && !dependsOn.isEmpty()) {
            spec.put("dependsOn", dependsOn(dependsOn));
        }
        spec.put("chart", Map.of("spec", chartSpec));
        if (valuesSecret != null) {
            spec.put("valuesFrom", valuesFrom(valuesSecret));
        }
        spec.put("values", new DoubleQuoted(values == null ? Map.of() : values));
        return spec;
    }

    /** The {@code dependsOn} list of a HelmRelease spec. */
    static List<Map<String, Object>> dependsOn(List<FluxGitOpsBackend.DepRef> deps) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (FluxGitOpsBackend.DepRef dep : deps) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("name", dep.name());
            ref.put("namespace", dep.namespace());
            list.add(ref);
        }
        return list;
    }

    /**
     * The {@code valuesFrom} list of a HelmRelease spec, reading a values Secret. Flux merges {@code spec.values} over
     * it.
     */
    static List<Map<String, Object>> valuesFrom(String secretName) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("kind", "Secret");
        ref.put("name", secretName);
        ref.put("valuesKey", GitOpsSecretValues.VALUES_KEY);
        return List.of(ref);
    }

    /** The {@code spec} of a HelmRelease as its manifest writes it: what its spec digest is computed from. */
    static String specYaml(Map<String, Object> spec) {
        return write(Map.of("spec", spec));
    }

    /**
     * A HelmRelease.
     *
     * @param specDigest the {@link FluxGitOpsBackend#SPEC_DIGEST_ANNOTATION} to annotate it with, {@code null} for none
     * @param spec       its spec ({@link #helmReleaseSpec})
     */
    static String helmRelease(String name, String namespace, String specDigest, Map<String, Object> spec) {
        return helmRelease(name, namespace, specDigest, spec, false);
    }

    /**
     * A HelmRelease, which Flux never prunes when {@code shared}: a dependency other releases rely on, such as KEDA.
     *
     * @param specDigest the {@link FluxGitOpsBackend#SPEC_DIGEST_ANNOTATION} to annotate it with, {@code null} for none
     * @param spec       its spec ({@link #helmReleaseSpec})
     * @param shared     whether other releases rely on it ({@link #PRUNE_ANNOTATION})
     */
    static String helmRelease(String name, String namespace, String specDigest, Map<String, Object> spec,
                              boolean shared) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        Map<String, Object> annotations = new LinkedHashMap<>();
        if (specDigest != null) {
            annotations.put(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION, new DoubleQuoted(specDigest));
        }
        if (shared) {
            annotations.put(PRUNE_ANNOTATION, PRUNE_DISABLED);
        }
        if (!annotations.isEmpty()) {
            metadata.put("annotations", annotations);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("apiVersion", FluxGitOpsBackend.FLUX_HR_API_VERSION);
        document.put("kind", "HelmRelease");
        document.put("metadata", metadata);
        document.put("spec", spec);
        return write(document);
    }

    /**
     * A manifest as it is, but annotated so Flux never prunes it ({@link #PRUNE_ANNOTATION}): an object an earlier
     * version wrote into one release's directory, moved where it belongs to no release.
     *
     * @param manifest the manifest, one document
     * @return the annotated manifest, or the manifest unchanged when it is not one readable document
     */
    @SuppressWarnings("unchecked")
    static String keptByFlux(String manifest) {
        try {
            Object document = new ObjectMapper(new YAMLFactory()).readValue(manifest, Object.class);
            if (!(document instanceof Map<?, ?> map) || !(map.get("metadata") instanceof Map<?, ?> metadata)) {
                return manifest;
            }
            Map<String, Object> annotations = metadata.get("annotations") instanceof Map<?, ?> existing
                    ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
            annotations.put(PRUNE_ANNOTATION, PRUNE_DISABLED);
            ((Map<String, Object>) metadata).put("annotations", annotations);
            return write(document);
        } catch (IOException | RuntimeException unreadable) {
            return manifest;
        }
    }

    private static String write(Object document) {
        try {
            return YAML.writeValueAsString(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the Flux manifest: " + e.getMessage(), e);
        }
    }

    /** A value whose strings are written double-quoted, as the chart values and the spec digest always were. */
    private record DoubleQuoted(Object value) {
    }

    private static final class DoubleQuotedSerializer extends StdSerializer<DoubleQuoted> {
        DoubleQuotedSerializer() {
            super(DoubleQuoted.class);
        }

        @Override
        public void serialize(DoubleQuoted quoted, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            YAMLGenerator yaml = (YAMLGenerator) generator;
            boolean minimized = yaml.isEnabled(YAMLGenerator.Feature.MINIMIZE_QUOTES);
            yaml.disable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
            try {
                provider.defaultSerializeValue(quoted.value(), generator);
            } finally {
                yaml.configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, minimized);
            }
        }
    }

    /**
     * Writes a string plain unless YAML would read it as something else: a boolean, a number, null or a date. A chart
     * version such as {@code 1.0} would otherwise reach Flux as a number. Jackson's own check covers the characters
     * that need quotes.
     */
    private static final class PlainUnlessTyped extends StringQuotingChecker.Default {
        private static final long serialVersionUID = 1L;
        private static final Resolver RESOLVER = new Resolver();

        @Override
        public boolean needToQuoteValue(String value) {
            return super.needToQuoteValue(value) || !Tag.STR.equals(RESOLVER.resolve(NodeId.scalar, value, true));
        }
    }
}
