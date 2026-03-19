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

package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Loads and merges variables for a mutating webhook installation.
 * Adds automatic composition of the Docker image string:
 *   {{IMAGE}} = registry/repository/name:tag
 */
public class WebHookValuesLoader {

    private static final Logger LOG = LoggerFactory.getLogger(WebHookValuesLoader.class);

    private static final String GLOBAL_PREFIX = "k8s.view.webhooks.";
    private static final String GLOBAL_NAMESPACE_KEY = GLOBAL_PREFIX + "namespace";
    private static final String GLOBAL_DNS_SUFFIX_KEY = GLOBAL_PREFIX + "dns_suffix";

    private final ViewContext viewContext;

    public WebHookValuesLoader(ViewContext viewContext) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
    }

    /**
     * Loads and merges variables for a given webhook.
     * @param webhookName e.g. "kerberos-keytab-mutating-webhook"
     * @param version e.g. "v1.0"
     * @return merged variables including composed {{IMAGE}}
     */
    public Map<String, String> loadVariables(String webhookName, String version) {
        Map<String, String> vars = new LinkedHashMap<>();

        // Step 1: load default properties from classpath
        String path = "k8s/webhooks/" + webhookName + "/" + version + "/webhook.properties";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                LOG.warn("No webhook.properties found at {}", path);
            } else {
                Properties props = new Properties();
                props.load(is);
                props.forEach((k, v) -> vars.put(k.toString().trim(), v.toString().trim()));
                LOG.info("Loaded {} variables from {}", props.size(), path);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load webhook.properties for " + webhookName, ex);
        }

        // Step 2: global overrides (namespace, dns_suffix)
        overrideIfPresent(vars, "NAMESPACE", viewContext.getAmbariProperty(GLOBAL_NAMESPACE_KEY));
        overrideIfPresent(vars, "DNS_SUFFIX", viewContext.getAmbariProperty(GLOBAL_DNS_SUFFIX_KEY));

        // Step 3: per-webhook overrides (prefix: k8s.view.webhooks.<webhook>.)
        String prefix = GLOBAL_PREFIX + webhookName + ".";
        for (String key : new ArrayList<>(vars.keySet())) {
            String overrideKey = prefix + key.toLowerCase();
            String overrideVal = viewContext.getAmbariProperty(overrideKey);
            overrideIfPresent(vars, key, overrideVal);
        }

        // Step 4: compose IMAGE = registry/repository/name:tag
        composeImage(vars);

        LOG.info("Resolved variables for webhook '{}': {}", webhookName, vars);
        return vars;
    }

    // Compose the IMAGE variable
    private static void composeImage(Map<String, String> vars) {
        String registry = vars.getOrDefault("IMAGE_REGISTRY", "").trim();
        String repo = vars.getOrDefault("IMAGE_REPOSITORY", "").trim();
        String name = vars.getOrDefault("IMAGE_NAME", "").trim();
        String tag = vars.getOrDefault("IMAGE_TAG", "").trim();

        StringBuilder sb = new StringBuilder();
        if (!registry.isEmpty()) sb.append(registry).append("/");
        if (!repo.isEmpty()) sb.append(repo).append("/");
        sb.append(name);
        if (!tag.isEmpty()) sb.append(":").append(tag);

        String fullImage = sb.toString();
        vars.put("IMAGE", fullImage);

        LOG.debug("Composed IMAGE={}", fullImage);
    }

    private static void overrideIfPresent(Map<String, String> vars, String key, String newValue) {
        if (newValue != null && !newValue.isBlank()) {
            vars.put(key, newValue.trim());
        }
    }
    public static Map<String,String> collectHelmOverrides(ViewContext ctx, String webhookName) {
        final String prefix = "k8s.view.webhooks." + webhookName + ".";
        Map<String,String> out = new java.util.LinkedHashMap<>();
        // getAmbariProperties() returns all (if your ViewContext exposes it); else adapt to your API
        Map<String,String> all = ctx.getProperties();
        for (var e : all.entrySet()) {
            String k = e.getKey();
            if (k != null && k.startsWith(prefix)) {
                String helmPath = k.substring(prefix.length()); // keep dots as-is
                out.put(helmPath, e.getValue());
            }
        }
        return out;
    }
}
