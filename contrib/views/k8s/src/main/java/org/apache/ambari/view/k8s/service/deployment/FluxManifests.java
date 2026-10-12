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
 * values written as {@link FluxGitOpsBackend#valuesYaml} writes them (every string double-quoted). A release deployed
 * before gets the same manifest and the same spec digest, so redeploying it commits nothing new.
 */
final class FluxManifests {

    /** How often Flux checks a HelmRelease and a HelmRepository. */
    private static final String INTERVAL = "5m";

    private static final String FLUX_NAMESPACE = "flux-system";

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
     * A Flux HelmRepository in {@code flux-system}.
     *
     * @param name the KDPS chart repository id
     * @param url  its URL, with the {@code oci://} scheme for an OCI registry
     * @param oci  whether it is an OCI registry, which Flux reads only with {@code type: oci}
     */
    static String helmRepository(String name, String url, boolean oci) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", FLUX_NAMESPACE);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interval", INTERVAL);
        if (oci) {
            spec.put("type", "oci");
        }
        spec.put("url", url);
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
     * @param chart        the chart as the Flux source knows it
     * @param version      the chart version, {@code null} to name none
     * @param source       the HelmRepository in {@code flux-system} the chart comes from
     * @param dependsOn    the HelmReleases Flux installs first, may be empty
     * @param valuesSecret the values Secret the HelmRelease reads, {@code null} for none
     * @param values       the chart values written to Git
     */
    static Map<String, Object> helmReleaseSpec(String chart, String version, String source,
                                               List<FluxGitOpsBackend.DepRef> dependsOn, String valuesSecret,
                                               Map<String, Object> values) {
        Map<String, Object> sourceRef = new LinkedHashMap<>();
        sourceRef.put("kind", "HelmRepository");
        sourceRef.put("name", source);
        sourceRef.put("namespace", FLUX_NAMESPACE);
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        if (specDigest != null) {
            metadata.put("annotations", Map.of(FluxGitOpsBackend.SPEC_DIGEST_ANNOTATION, new DoubleQuoted(specDigest)));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("apiVersion", FluxGitOpsBackend.FLUX_HR_API_VERSION);
        document.put("kind", "HelmRelease");
        document.put("metadata", metadata);
        document.put("spec", spec);
        return write(document);
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
