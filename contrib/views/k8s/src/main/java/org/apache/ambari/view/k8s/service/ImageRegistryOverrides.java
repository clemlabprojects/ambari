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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The image settings a release gets from its chart repository, whichever way it is deployed (Helm install or GitOps
 * commit): the repository's image registry, written into the property the chart reads it from, the image paths of
 * charts that ignore that property, and the image pull Secret, written into the universal value paths and into those
 * the service definition adds. One implementation, so a GitOps commit writes what a Helm install would pass.
 */
public final class ImageRegistryOverrides {

    private static final Logger LOG = LoggerFactory.getLogger(ImageRegistryOverrides.class);

    /** The value path a chart reads its image registry from when its definition names none. */
    public static final String DEFAULT_REGISTRY_PROPERTY = "global.imageRegistry";

    /** Registry prefix every KDPS service definition uses for its default images. */
    static final String DEFAULT_IMAGE_REGISTRY = "registry.clemlab.com/clemlabprojects";

    /** Path segment {@code key} or {@code key[idx]}, as Helm value paths write list elements. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("^(?<key>[^\\[\\]]+)(\\[(?<idx>\\d+)])?$");

    private ImageRegistryOverrides() {
    }

    /**
     * Writes the image pull Secret of a main release: {@code imagePullSecrets[0].name}, {@code
     * global.imagePullSecrets[0]} as a plain name (Bitnami sub-charts, e.g. Redis inside GitLab, expect a list of
     * names, not {@code {name: ...}} maps), and the paths the service definition adds ({@link
     * #applyImagePullSecretTargets}). No-op without a secret name.
     */
    public static void addReleasePullSecret(ViewContext ctx, Object secretName, Object serviceKey,
                                            Map<String, String> overrides) {
        if (secretName == null || String.valueOf(secretName).isBlank()) return;
        String name = String.valueOf(secretName);
        overrides.put("imagePullSecrets[0].name", name);
        overrides.put("global.imagePullSecrets[0]", name);
        applyImagePullSecretTargets(ctx, name, serviceKey, overrides);
    }

    /**
     * Inject the image-pull secret name into the extra helm value paths declared by
     * the service definition's {@code imagePullSecretTargets} list. KDPS always
     * writes two universal paths ({@code imagePullSecrets[0].name} and {@code global.imagePullSecrets[0]});
     * this method handles the chart-specific carve-outs (e.g. the airflow sub-chart
     * inside OpenMetadata which reads {@code dependencies.airflow.registry.secretName}
     * rather than honoring {@code global.imagePullSecrets}).
     *
     * <p>No-op when secretName, serviceKey, or the service.json's
     * {@code imagePullSecretTargets} is absent — i.e. existing services unaffected.
     */
    public static void applyImagePullSecretTargets(ViewContext ctx, Object secretName, Object serviceKey,
                                                   Map<String, String> overrideProperties) {
        if (secretName == null) return;
        String svcKey = serviceKey == null ? null : String.valueOf(serviceKey);
        if (svcKey == null || svcKey.isBlank()) return;
        try {
            StackServiceDef def = new StackDefinitionService(ctx).getServiceDefinition(svcKey);
            if (def == null || def.imagePullSecretTargets == null) return;
            for (String path : def.imagePullSecretTargets) {
                if (path == null || path.isBlank()) continue;
                overrideProperties.put(path, String.valueOf(secretName));
                LOG.info("Image-pull-secret cascade: set {}='{}' from service.json imagePullSecretTargets ({})",
                        path, secretName, svcKey);
            }
        } catch (Exception ex) {
            LOG.warn("Could not load service def {} for pull-secret target injection: {}",
                    svcKey, ex.toString());
        }
    }

    /**
     * Applies {@link #rewriteImageRegistry} to every path the service definition lists in
     * {@code imageRegistryPaths}. The current value comes from the explicit overrides first, then
     * from the submitted values; paths without a value are left to the chart (nothing to rewrite).
     */
    public static void applyImageRegistryPaths(ViewContext ctx, Object serviceKeyObj, String effectiveRegistry,
                                               Map<String, Object> valuesMap, Map<String, String> overrideProperties) {
        if (serviceKeyObj == null || effectiveRegistry == null || effectiveRegistry.isBlank()) return;
        String serviceKey = String.valueOf(serviceKeyObj).trim();
        if (serviceKey.isEmpty() || "null".equals(serviceKey)) return;
        List<Object> entries;
        try {
            StackServiceDef def = new StackDefinitionService(ctx).getServiceDefinition(serviceKey);
            entries = (def == null) ? null : def.imageRegistryPaths;
        } catch (Exception e) {
            LOG.warn("imageRegistryPaths: cannot load the definition of {}: {}", serviceKey, e.toString());
            return;
        }
        if (entries == null || entries.isEmpty()) return;
        for (Object entry : entries) {
            String[] pd = imageRegistryEntry(entry);
            if (pd == null) continue;
            String path = pd[0], fallback = pd[1];
            Object current = overrideProperties.get(path);
            if (current == null && valuesMap != null) current = valueAt(valuesMap, path);
            String value = (current instanceof String s && !s.isBlank()) ? s : null;
            if (value == null) {
                if (fallback == null || fallback.isBlank()) {
                    LOG.debug("imageRegistryPaths: {} has no value in the request and no default; chart default stays", path);
                    continue;
                }
                value = fallback;
                LOG.info("imageRegistryPaths: {} not in the request; using the definition default {}", path, fallback);
            }
            String rewritten = rewriteImageRegistry(value, effectiveRegistry);
            if (!rewritten.equals(value)) {
                LOG.info("imageRegistryPaths: {} follows the repository registry: {} -> {}", path, value, rewritten);
            }
            if (current == null || !rewritten.equals(current)) {
                overrideProperties.put(path, rewritten);
            }
        }
    }

    /**
     * Moves an image reference onto {@code effectiveRegistry} (host or host/project, as the Helm
     * repository resolves it). The Clemlab default prefix is replaced as a whole; any other registry
     * host (quay.io, registry.k8s.io, ghcr.io, docker.io) is replaced by the target, Docker Hub's
     * {@code library/} is dropped, tags and digests are kept, and a reference already on the target
     * registry is returned unchanged. Mirrors therefore keep the upstream repository path, which is
     * how the Clemlab registry itself is laid out (kedacore/keda, prometheus/prometheus, ...).
     */
    public static String rewriteImageRegistry(String image, String effectiveRegistry) {
        if (image == null || image.isBlank() || effectiveRegistry == null || effectiveRegistry.isBlank()) return image;
        String ref = image.trim();
        String target = effectiveRegistry.trim().replaceAll("/+$", "");
        if (ref.equals(target) || ref.startsWith(target + "/")) return ref;
        String path = ref;
        if (path.startsWith(DEFAULT_IMAGE_REGISTRY + "/")) {
            path = path.substring(DEFAULT_IMAGE_REGISTRY.length() + 1);
        } else {
            int slash = path.indexOf('/');
            if (slash > 0) {
                String first = path.substring(0, slash);
                if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
                    path = path.substring(slash + 1);
                }
            }
            if (path.startsWith("library/")) path = path.substring("library/".length());
        }
        return target + "/" + path;
    }

    /**
     * Normalises one {@code imageRegistryPaths} entry: a bare path string, or a map with
     * {@code path} and an optional {@code default} image. Returns {path, default-or-null}, or null
     * for an unusable entry.
     */
    public static String[] imageRegistryEntry(Object entry) {
        if (entry instanceof String str) {
            return str.isBlank() ? null : new String[] {str.trim(), null};
        }
        if (entry instanceof Map<?, ?> m) {
            Object path = m.get("path");
            if (!(path instanceof String ps) || ps.isBlank()) return null;
            Object def = m.get("default");
            return new String[] {ps.trim(), (def instanceof String ds && !ds.isBlank()) ? ds.trim() : null};
        }
        return null;
    }

    /**
     * The value at a Helm value path ({@code a.b[0].c}) in nested values, or null. Values a GitOps commit has not
     * expanded yet may still hold the path as one dotted key, which is looked up as such first.
     */
    public static Object valueAt(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        if (root.containsKey(path)) return root.get(path);
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            Matcher m = PATH_SEGMENT.matcher(segment);
            if (!m.matches()) return null;
            current = map.get(m.group("key"));
            String idx = m.group("idx");
            if (idx != null) {
                if (!(current instanceof List<?> list)) return null;
                int i = Integer.parseInt(idx);
                if (i >= list.size()) return null;
                current = list.get(i);
            }
        }
        return current;
    }
}
