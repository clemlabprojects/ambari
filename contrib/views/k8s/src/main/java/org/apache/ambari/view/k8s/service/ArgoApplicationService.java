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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.dto.argocd.ArgoApplicationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds Argo CD Application manifests (Helm source) from deployment requests.
 */
public class ArgoApplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(ArgoApplicationService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Build a minimal Argo CD Application manifest as a YAML string.
     * Does not create the resource; caller can push to Git or apply via Argo API.
     */
    public String buildHelmApplication(ArgoApplicationRequest req) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "argoproj.io/v1alpha1");
        root.put("kind", "Application");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", req.releaseName);
        metadata.put("namespace", "argocd");
        root.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("repoURL", req.repoUrl);
        source.put("chart", req.chart);
        source.put("targetRevision", req.targetRevision != null ? req.targetRevision : "latest");
        if (req.values != null && !req.values.isEmpty()) {
            source.put("helm", Map.of("values", toYaml(req.values)));
        }
        spec.put("source", source);
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("server", "https://kubernetes.default.svc");
        destination.put("namespace", req.namespace);
        spec.put("destination", destination);
        spec.put("syncPolicy", Map.of("automated", Map.of("prune", true, "selfHeal", true)));
        root.put("spec", spec);

        try {
            // Produce clean YAML for readability
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            return yaml.dump(root);
        } catch (Exception ex) {
            LOG.warn("Failed to render Argo Application: {}", ex.toString());
            throw new IllegalStateException("Could not render Argo Application", ex);
        }
    }

    private String toYaml(Object obj) {
        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            return yaml.dump(obj);
        } catch (Exception ex) {
            LOG.warn("Failed to serialize values to YAML: {}", ex.toString());
            return "";
        }
    }
}
