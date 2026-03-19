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

import com.google.gson.Gson;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.apache.ambari.view.k8s.utils.HadoopSiteXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles complex value resolution, templating, and config parsing.
 * Extracted from CommandService.
 */
public class ConfigResolutionService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigResolutionService.class);

    /**
     * Merge ranger overrides into the params map (later converted to Helm values).
     * This is driven by the ranger block from service.json (e.g., ranger-plugin-settings, ranger-security).
     */
    public void mergeRangerOverrides(Map<String, Object> params,
                                     Map<String, Map<String, Object>> rangerSpec,
                                     String namespace,
                                     String releaseName,
                                     String cluster) {
        if (rangerSpec == null || rangerSpec.isEmpty()) return;
        params.put("ranger", rangerSpec);
        params.put("_cluster", cluster);
        params.putIfAbsent("namespace", namespace);
        params.putIfAbsent("releaseName", releaseName);
    }

    /**
     * Computes additional Ranger service configuration entries from the
     * "extraConfigs" array defined under "ranger-plugin-settings" in charts.json.
     *
     * Resulting map is:
     *    propertyName -> value (always stored as String for Ranger service config)
     *
     * This is fully driven by JSON:
     *  - type=string   : literal string
     *  - type=template : template + properties
     *  - type=config   : value from Ambari config (core-site/... etc)
     *  - type=boolean  : literal or template, parsed as boolean, then rendered as "true"/"false"
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> computeExtraRangerConfigs(
            Map<String, Object> rangerPluginSettingsSpec,
            Map<String, Object> params,
            Map<String, Object> helmValues,
            AmbariActionClient ambariActionClient,
            String cluster) {

        Map<String, String> result = new LinkedHashMap<>();

        // 1) Extract the "extraConfigs" array from the spec
        Object extraCfgsObj = rangerPluginSettingsSpec.get("extraConfigs");
        if (!(extraCfgsObj instanceof List<?> list)) {
            // No extra configs defined for this chart, nothing to do.
            return result;
        }

        // 2) Build a small environment map used by templates:
        //    exposes well-known variables to {{ }} expressions.
        Map<String, Object> templateEnv = buildTemplateEnv(params, cluster);

        // 3) Iterate over each extra config definition
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                LOG.warn("Ignoring extraConfig entry because it is not an object: {}", item);
                continue;
            }
            Map<String, Object> cfg = (Map<String, Object>) raw;

            String property = asString(cfg.get("property"));
            String type     = asString(cfg.get("type"));

            if (property == null || property.isBlank() || type == null || type.isBlank()) {
                LOG.warn("Skipping extraConfig without property/type: {}", cfg);
                continue;
            }

            // 4) Resolve value based on its declared type
            String value = resolveExtraConfigValue(
                    property,
                    type,
                    cfg,
                    params,
                    helmValues,
                    ambariActionClient,
                    cluster,
                    templateEnv
            );

            if (value != null) {
                result.put(property, value);
            }
        }

        return result;
    }

    /**
     * Resolves a single extraConfig entry according to "type".
     *
     * Supported types:
     *   - "string"   : expects "value"
     *   - "template" : expects "template" + "properties"
     *   - "config"   : expects "source":"ambari-config" + "name":"type/property"
     *   - "boolean"  : either "value" or "template" + "properties"
     */
    @SuppressWarnings("unchecked")
    public String resolveExtraConfigValue(
            String property,
            String type,
            Map<String, Object> spec,
            Map<String, Object> params,
            Map<String, Object> helmValues,
            AmbariActionClient ambariActionClient,
            String cluster,
            Map<String, Object> templateEnv) {

        switch (type) {
            case "string": {
                // Literal value
                String literal = asString(spec.get("value"));
                if (literal == null) {
                    LOG.warn("extraConfig '{}' of type=string has no 'value' field", property);
                    return null;
                }
                return literal;
            }

            case "template": {
                // Template where placeholders are resolved from "properties"
                String template = asString(spec.get("template"));
                Object propsObj = spec.get("properties");

                if (template == null || !(propsObj instanceof Map<?, ?> rawProps)) {
                    LOG.warn("extraConfig '{}' of type=template must define 'template' and 'properties'", property);
                    return null;
                }

                Map<String, Object> props = (Map<String, Object>) rawProps;
                Map<String, Object> values = new LinkedHashMap<>();

                // Resolve each property used in the template
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    String name = e.getKey();
                    if (!(e.getValue() instanceof Map<?, ?> rawDef)) {
                        continue;
                    }
                    Map<String, Object> def = (Map<String, Object>) rawDef;

                    Object resolved = resolveInnerPropertyValue(
                            name,
                            def,
                            params,
                            helmValues,
                            ambariActionClient,
                            cluster,
                            templateEnv
                    );
                    if (resolved != null) {
                        values.put(name, resolved);
                    }
                }

                // Render final string value
                return renderTemplate(template, values);
            }

            case "config": {
                // Config values from Ambari (e.g. "core-site/hadoop.security.authentication")
                String source = asString(spec.get("source"));
                if (!"ambari-config".equals(source)) {
                    LOG.warn("extraConfig '{}' of type=config but unsupported source '{}'", property, source);
                    return null;
                }

                String name = asString(spec.get("name")); // "type/property"
                if (name == null || !name.contains("/")) {
                    LOG.warn("extraConfig '{}' of type=config must have name 'type/property'", property);
                    return null;
                }
                if (ambariActionClient == null || cluster == null) {
                    LOG.warn("extraConfig '{}' of type=config but Ambari client/cluster not available", property);
                    return null;
                }

                String[] parts = name.split("/", 2);
                try {
                    return ambariActionClient.getDesiredConfigProperty(cluster, parts[0], parts[1]);
                } catch (Exception ex) {
                    LOG.warn("Failed to read Ambari config {} for extraConfig '{}': {}", name, property, ex.toString());
                    return null;
                }
            }

            case "boolean": {
                // Boolean can be literal or template-based

                // Literal boolean: { "type": "boolean", "value": true }
                if (spec.containsKey("value")) {
                    Boolean b = asBoolean(spec.get("value"));
                    return (b == null) ? null : String.valueOf(b);
                }

                // Template-based: { "type": "boolean", "template": "{{ssl}}", "properties": { ... } }
                String template = asString(spec.get("template"));
                Object propsObj = spec.get("properties");
                if (template == null) {
                    LOG.warn("extraConfig '{}' of type=boolean needs either 'value' or 'template'", property);
                    return null;
                }

                Map<String, Object> values = new LinkedHashMap<>();
                if (propsObj instanceof Map<?, ?> rawProps) {
                    Map<String, Object> props = (Map<String, Object>) rawProps;
                    for (Map.Entry<String, Object> e : props.entrySet()) {
                        String name = e.getKey();
                        if (!(e.getValue() instanceof Map<?, ?> rawDef)) {
                            continue;
                        }
                        Map<String, Object> def = (Map<String, Object>) rawDef;
                        Object resolved = resolveInnerPropertyValue(
                                name,
                                def,
                                params,
                                helmValues,
                                ambariActionClient,
                                cluster,
                                templateEnv
                        );
                        if (resolved != null) {
                            values.put(name, resolved);
                        }
                    }
                }

                String rendered = renderTemplate(template, values);
                Boolean b = asBoolean(rendered);
                if (b == null) {
                    LOG.warn("extraConfig '{}' of type=boolean produced non-boolean value '{}'", property, rendered);
                    return null;
                }
                return String.valueOf(b);
            }

            default:
                LOG.warn("Unknown extraConfig type '{}' for property '{}'", type, property);
                return null;
        }
    }
    /**
     * Casts a raw value coming from Helm / params / Ambari into the requested type.
     * If type is null or "string", everything is converted with String.valueOf().
     */
    public Object castInnerValue(Object raw, String type, String name) {
        if (type == null || type.isBlank() || "string".equals(type)) {
            return String.valueOf(raw);
        }

        if ("integer".equals(type)) {
            if (raw instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                LOG.warn("Cannot cast value '{}' of '{}' to integer", raw, name);
                return null;
            }
        }

        if ("boolean".equals(type)) {
            Boolean b = asBoolean(raw);
            if (b == null) {
                LOG.warn("Cannot cast value '{}' of '{}' to boolean", raw, name);
            }
            return b;
        }

        LOG.warn("Unknown inner property type '{}' for '{}'", type, name);
        return String.valueOf(raw);
    }

    /**
     * Builds a small map used as context in template expansion.
     * Exposes well-known variables like:
     *   - releaseName
     *   - nameOverride
     *   - namespace
     *   - cluster
     *   - rangerRepository
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildTemplateEnv(Map<String, Object> params, String cluster) {
        Map<String, Object> env = new LinkedHashMap<>();

        if (params != null) {
            // Shallow copy of params: allows templates to refer directly to
            // "releaseName", "namespace", "_rangerRepositoryName", etc.
            env.putAll(params);
        }

        // Make sure cluster is explicitly available
        if (cluster != null) {
            env.put("cluster", cluster);
        }

        // Provide a friendlier name for repository
        Object repoName = (params != null) ? params.get("_rangerRepositoryName") : null;
        if (repoName != null) {
            env.put("rangerRepository", repoName);
        }

        return env;
    }

    /**
     * Reads a value from a nested Map using dotted path syntax, e.g. "server.https.port".
     * Returns null if anything is missing along the way.
     */
    @SuppressWarnings("unchecked")
    public static Object getByDottedPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;

        String[] parts = path.split("\\.");
        Object current = root;

        for (String p : parts) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = ((Map<String, Object>) m).get(p);
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    /** Safe conversion to String. */
    public static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    /**
     * Parses a boolean from various shapes:
     *   - Boolean instance
     *   - Number: 0 = false, else true
     *   - String: "true"/"false" (case-insensitive), "1"/"0"
     */
    public static Boolean asBoolean(Object raw) {
        if (raw == null) return null;

        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }

        String s = String.valueOf(raw).trim();
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        if (s.equals("1")) return Boolean.TRUE;
        if (s.equals("0")) return Boolean.FALSE;

        return null; // caller can log a warning
    }

    /**
     * Very small {{var}} template renderer.
     * This is intentionally dumb and predictable:
     *  - No conditionals
     *  - No loops
     *  - Just string replacement
     */
    public static String renderTemplate(String template, Map<String, Object> vars) {
        if (template == null) return null;
        if (vars == null || vars.isEmpty()) return template;

        String result = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            String key = e.getKey();
            String value = (e.getValue() == null) ? "" : String.valueOf(e.getValue());
            result = result.replace("{{" + key + "}}", value);
        }
        return result;
    }

    /**
     * Resolves a single property inside "properties" of a template/boolean extraConfig.
     *
     * Example of def:
     *   {
     *     "source": "helm",
     *     "path": "server.https.port",
     *     "type": "integer"
     *   }
     */
    @SuppressWarnings("unchecked")
    public Object resolveInnerPropertyValue(
            String name,
            Map<String, Object> def,
            Map<String, Object> params,
            Map<String, Object> helmValues,
            AmbariActionClient ambariActionClient,
            String cluster,
            Map<String, Object> templateEnv) {

        String source = asString(def.get("source"));
        String type   = asString(def.get("type")); // optional, default "string"

        Object raw = null;

        if ("helm".equals(source)) {
            String rawPath = asString(def.get("path"));
            // Read value from Helm values using dotted path "server.https.port"
            String path = resolveConditionalPath(rawPath, helmValues);
            raw = getByDottedPath(helmValues, path);
            if (raw == null) {
                LOG.warn("Resolving helm path '{}' (orig: '{}') returned NULL.", path, rawPath);
            }
        } else if ("helmCondition".equals(source)) {
            // New mode:
            // "path" is a conditional expression whose *condition* uses helm values,
            // and whose branches are *literal values* (not further helm paths).
            //
            // Example:
            //   "path": "{% if server.config.https.enabled %}https{% else %}http{% endif %}"
            //
            String expr = asString(def.get("path"));
            String literal = resolveConditionalLiteral(expr, helmValues);
            raw = literal;

        } else if ("embedded".equals(source)) {
            // Read from params / templateEnv. Path can be "releaseName" or "namespace" etc.
            String path = asString(def.get("path"));
            raw = getByDottedPath(params, path);
            if (raw == null && path != null) {
                // Also allow direct lookup in templateEnv for trivial names
                raw = templateEnv.get(path);
            }

        } else if ("template".equals(source)) {
            // Property defined as a small template itself, e.g. "{{releaseName}}-{{nameOverride}}-coordinator"
            Map<String, Object> combinedContext = new HashMap<>();

            // 1. Add Helm Values (Low priority)
            if (helmValues != null) {
                combinedContext.putAll(helmValues);
            }
            // 2. Add Params/Env (High priority - overwrite helm values if name collides)
            if (templateEnv != null) {
                combinedContext.putAll(templateEnv);
            }
            String tpl = asString(def.get("template"));
            raw = (tpl == null) ? null : renderTemplate(tpl, combinedContext);

        } else if ("ambari-config".equals(source)) {
            // Optional: allow Ambari config inside inner properties as well
            String confName = asString(def.get("name")); // "type/property"
            if (confName != null && confName.contains("/") && ambariActionClient != null && cluster != null) {
                String[] parts = confName.split("/", 2);
                try {
                    raw = ambariActionClient.getDesiredConfigProperty(cluster, parts[0], parts[1]);
                } catch (Exception ex) {
                    LOG.warn("Failed to load Ambari config {} for inner property '{}': {}", confName, name, ex.toString());
                    raw = null;
                }
            }
        } else {
            LOG.warn("Unknown inner property source '{}' for '{}'", source, name);
        }

        if (raw == null) {
            return null;
        }

        LOG.info("DEBUG: resolved property: source:{}, {}, {}, {}, from {}", source, raw, type, name, castInnerValue(raw, type, name));
        // Cast according to the declared type ("string", "integer", "boolean")
        return castInnerValue(raw, type, name);
    }

    /**
     * Safe conditional resolver.
     * Handles both:
     * 1. Simple strings: "trino.server.ssl.enabled"
     * 2. Jinja-style if/else: "{% if condition %}truePath{% else %}falsePath{% endif %}"
     */
    public String resolveConditionalPath(String pathExpression, Map<String, Object> helmValues) {
        // GUARD CLAUSE: If it's null or doesn't look like a conditional, return as is.
        if (pathExpression == null || !pathExpression.contains("{% if")) {
            return pathExpression;
        }

        // Regex to match: {% if key %} val1 {% else %} val2 {% endif %}
        // We use \\{% to match the literal "{%"
        String regex = "\\{%%\\s*if\\s+([\\w\\.]+)\\s*%%\\}(.*?)\\{%%\\s*else\\s*%%\\}(.*?)\\{%%\\s*endif\\s*%%\\}";

        // NOTE: If your input actually uses single braces like "{% if", remove the double %% below:
        // Correct Regex for "{% if ... %}":
        String standardRegex = "\\{%\\s*if\\s+([\\w\\.]+)\\s*%\\}(.*?)\\{%\\s*else\\s*%\\}(.*?)\\{%\\s*endif\\s*%\\}";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(standardRegex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(pathExpression);

        if (matcher.find()) {
            String conditionKey = matcher.group(1).trim(); // e.g. "trino.server.ssl.enabled"
            String truePath     = matcher.group(2).trim(); // e.g. "server.https.port"
            String falsePath    = matcher.group(3).trim(); // e.g. "server.http.port"

            // Check the condition
            Object conditionValue = getByDottedPath(helmValues, conditionKey);
            boolean isTrue = (conditionValue != null && Boolean.TRUE.equals(asBoolean(conditionValue)));

            return isTrue ? truePath : falsePath;
        }

        LOG.warn("Path looked like a conditional but failed regex parsing: {}", pathExpression);
        return pathExpression;
    }
    /**
     * Similar to resolveConditionalPath, but returns *literal* values instead of helm paths.
     *
     * Expression shape:
     *   "{% if some.helm.flag %}literalTrue{% else %}literalFalse{% endif %}"
     *
     * We evaluate the condition from helmValues["some.helm.flag"],
     * and return "literalTrue" or "literalFalse" as a raw string.
     */
    public String resolveConditionalLiteral(String expression, Map<String, Object> helmValues) {
        if (expression == null || !expression.contains("{% if")) {
            return expression;
        }

        String standardRegex = "\\{%\\s*if\\s+([\\w\\.]+)\\s*%\\}(.*?)\\{%\\s*else\\s*%\\}(.*?)\\{%\\s*endif\\s*%\\}";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(standardRegex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(expression);

        if (matcher.find()) {
            String conditionKey = matcher.group(1).trim(); // e.g. "server.config.https.enabled"
            String trueLiteral  = matcher.group(2).trim(); // e.g. "https" or "8443"
            String falseLiteral = matcher.group(3).trim(); // e.g. "http" or "8080"

            Object conditionValue = getByDottedPath(helmValues, conditionKey);
            boolean isTrue = (conditionValue != null && Boolean.TRUE.equals(asBoolean(conditionValue)));

            return isTrue ? trueLiteral : falseLiteral;
        }

        LOG.warn("Expression looked like a conditional but failed regex parsing: {}", expression);
        return expression;
    }
    /**
     * deeply merges "source" into "target".
     * New values overwrite old values.
     * Maps are merged recursively.
     * Lists and other types are overwritten (standard Helm behavior).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deepMerge(Map<String, Object> target, Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(target);

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = result.get(key);

            if (sourceValue instanceof Map && targetValue instanceof Map) {
                // Both are maps: recurse
                Map<String, Object> mergedSub = deepMerge(
                        (Map<String, Object>) targetValue,
                        (Map<String, Object>) sourceValue
                );
                result.put(key, mergedSub);
            } else {
                // Not both maps: overwrite (Helm behavior for lists/primitives)
                result.put(key, sourceValue);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> computeEndpointsForRelease(
            List<Map<String, Object>> endpointSpecs,
            Map<String, Object> allParams,
            Map<String, Object> helmValues,
            AmbariActionClient ambariActionClient,
            String cluster
//            ,            Map<String, Object> baseTemplateEnv
    ) {
        if (endpointSpecs == null || endpointSpecs.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> spec : endpointSpecs) {
            if (spec == null) {
                continue;
            }

            // id / label / kind / description are mostly static metadata
            String id = spec.get("id") != null ? String.valueOf(spec.get("id")) : null;
            String label = spec.get("label") != null ? String.valueOf(spec.get("label")) : id;
            String description = spec.get("description") != null ? String.valueOf(spec.get("description")) : null;
            String kind = spec.get("kind") != null ? String.valueOf(spec.get("kind")) : "http";

            // For this endpoint, start from base env and extend with its own properties
//            Map<String, Object> templateEnv = new LinkedHashMap<>(baseTemplateEnv);
            Map<String, Object> templateEnv = buildTemplateEnv(allParams, cluster);

            Object propsRaw = spec.get("properties");
            if (propsRaw instanceof Map<?, ?> propsMap) {
                for (Map.Entry<?, ?> e : propsMap.entrySet()) {
                    Object k = e.getKey();
                    Object v = e.getValue();
                    if (!(k instanceof String) || !(v instanceof Map)) {
                        continue;
                    }
                    String propName = (String) k;
                    Map<String, Object> propSpec = (Map<String, Object>) v;

                    // Reuse your existing ranger templating resolver
                    Object resolved = resolveInnerPropertyValue(
                            propName,            // name: "host", "port", "ssl", "scheme", ...
                            propSpec,            // the property spec from charts.json
                            allParams,           // full command params (like in ranger extraConfigs)
                            helmValues,          // merged helm values for this release
                            ambariActionClient,
                            cluster,         // or whatever you call it in that scope
                            templateEnv          // {releaseName, namespace, serviceKey, ...}
                    );
                    if (resolved != null) {
                        templateEnv.put(propName, resolved);
                    }
                }
            }

            // Find the template for the URL
            String urlTemplate = null;
            if (spec.get("urlTemplate") != null) {
                urlTemplate = String.valueOf(spec.get("urlTemplate"));
            } else if ("template".equals(spec.get("type")) && spec.get("template") instanceof String) {
                // superset-style endpoint config: type: template + template: "http://..."
                urlTemplate = String.valueOf(spec.get("template"));
            }

            if (urlTemplate == null || urlTemplate.isEmpty()) {
                continue;
            }

            String url = renderEndpointUrl(urlTemplate, templateEnv);
            if (url == null || url.isEmpty()) {
                continue;
            }

            Map<String, Object> endpoint = new LinkedHashMap<>();
            if (id != null) endpoint.put("id", id);
            if (label != null) endpoint.put("label", label);
            endpoint.put("url", url);
            if (description != null) endpoint.put("description", description);
            if (kind != null) endpoint.put("kind", kind);

            result.add(endpoint);
        }

        return result.isEmpty() ? null : result;
    }

    public String renderEndpointUrl(String template, Map<String, Object> env) {
        if (template == null) {
            return null;
        }
        String result = template;
        for (Map.Entry<String, Object> e : env.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || value == null) {
                continue;
            }
            String placeholder = "{{" + key + "}}";
            result = result.replace(placeholder, String.valueOf(value));
        }
        return result;
    }

    /**
     * Merge pre-resolved dynamic Helm overrides into the target override map.
     * <p>
     * The {@code childParams} may contain a map under the key {@code "_dynamicOverrides"}.
     * That map is expected to be {@code Map<String, String>} where:
     * <ul>
     *   <li><b>key</b>   — Helm values path (e.g. {@code "backend.krbRealm"})</li>
     *   <li><b>value</b> — The resolved value to inject</li>
     * </ul>
     * Each valid entry will be copied into {@code overrideProperties}, overriding any
     * existing value for the same key.
     *
     * @param childParams        The params attached to the command (may be {@code null}).
     * @param overrideProperties The map of Helm overrides to mutate (must not be {@code null}).
     */
    @SuppressWarnings("unchecked")
    public static void mergeDynamicOverrides(Map<String, Object> childParams,
                                              Map<String, String> overrideProperties) {
        if (childParams == null) {
            return;
        }

        Object rawOverrides = childParams.get("_dynamicOverrides");
        if (!(rawOverrides instanceof Map<?, ?>)) {
            return;
        }

        Map<?, ?> dynamicOverrides = (Map<?, ?>) rawOverrides;

        for (Map.Entry<?, ?> entry : dynamicOverrides.entrySet()) {
            String helmPath = entry.getKey() == null ? null : String.valueOf(entry.getKey()).trim();
            String value    = entry.getValue() == null ? null : String.valueOf(entry.getValue()).trim();

            if (helmPath == null || helmPath.isEmpty()) {
                continue; // skip invalid key
            }
            if (value == null || value.isEmpty()) {
                continue; // skip empty value
            }
            overrideProperties.put(helmPath, value);
        }
    }

}
