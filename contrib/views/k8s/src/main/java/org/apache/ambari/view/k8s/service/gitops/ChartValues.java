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

package org.apache.ambari.view.k8s.service.gitops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The values.yaml of a chart as Helm installs it with a deploy's values: the chart's defaults with the values merged
 * in by Helm's rules ({@link #coalesce}). A profile with {@link GitOpsProfile#CHART_VALUES_MERGED} writes it in place of
 * the published one, so the chart folder deploys as KDPS would, with no other values file.
 */
public final class ChartValues {

    /** As {@link FileTemplate} writes {@code toYaml}. */
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());

    private ChartValues() {
    }

    /**
     * The chart's values.yaml with the deploy's values merged in.
     *
     * @param published the chart's values.yaml as published; {@code null} when the chart has none
     * @param values    the deploy's values, passwords and secrets left out
     * @param header    comment lines written first, each without its {@code #}
     * @throws IllegalArgumentException when the published values.yaml is not a YAML map
     */
    public static byte[] merged(byte[] published, Map<String, Object> values, String... header) {
        Map<String, Object> defaults = read(published);
        StringBuilder out = new StringBuilder();
        for (String line : header) {
            out.append("# ").append(line).append('\n');
        }
        Map<String, Object> merged = coalesce(defaults, values == null ? Map.of() : values);
        if (!merged.isEmpty()) {
            try {
                out.append(YAML.writeValueAsString(merged));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("The chart's values cannot be written: " + e.getOriginalMessage());
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Helm's merge of values given over a chart's defaults: maps are merged key by key, any other value given replaces
     * the default (a list included, as a whole), a key given as {@code null} removes the default, and keys the
     * defaults lack are added. The defaults keep their order, the keys they lack follow. Neither map is changed.
     */
    public static Map<String, Object> coalesce(Map<String, Object> defaults, Map<String, Object> given) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!given.containsKey(key)) {
                out.put(key, entry.getValue());
                continue;
            }
            Object value = given.get(key);
            if (value == null) {
                continue;
            }
            out.put(key, entry.getValue() instanceof Map<?, ?> d && value instanceof Map<?, ?> g
                    ? coalesce(asMap(d), asMap(g)) : value);
        }
        for (Map.Entry<String, Object> entry : given.entrySet()) {
            if (!defaults.containsKey(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> read(byte[] published) {
        if (published == null || published.length == 0) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> defaults = YAML.readValue(published, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return defaults == null ? new LinkedHashMap<>() : defaults;
        } catch (IOException e) {
            throw new IllegalArgumentException("The chart's values.yaml is not a YAML map, so the deploy's values cannot "
                    + "be merged into it: " + e.getMessage());
        }
    }
}
