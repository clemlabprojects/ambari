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

package org.apache.ambari.view.k8s.service.gitops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the templates of a GitOps output profile: {@code {{ ref | filter | filter ... }}}, the syntax of the view's
 * other templates (see {@code ExternalCredentialResolver}), against a read-only context of maps, lists and scalars.
 *
 * <p>Strict where the credential templates are lenient: a field the context does not have, an unknown filter, or a
 * map or list written without {@code toYaml}/{@code toJson} fails the rendering with the template's name and line,
 * since a file written with a silent blank would reach Git. Nothing else is interpreted, so other tools'
 * placeholders such as {@code ${IMAGE_TAG}} pass through unchanged, and a template cannot run code.</p>
 *
 * <p>Filters: {@code toYaml}, {@code toJson}, {@code indent N} (prefixes every line but the first, for a block that
 * starts after a key), {@code quote}, {@code trim}, {@code default X} (used when the field is missing or blank).</p>
 */
public final class FileTemplate {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^|}]+?)\\s*((?:\\|[^|}]+)*)\\s*\\}\\}");

    /** Every string quoted: a value such as 1.10 or true keeps its type when the file is read back. */
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A template that cannot be rendered: which one, where, and why. */
    public static final class TemplateException extends IllegalArgumentException {
        public TemplateException(String message) {
            super(message);
        }
    }

    private FileTemplate() {
    }

    /**
     * @param name     the template, for the messages (e.g. the file rule's path)
     * @param template the template text
     * @param context  the fields it may use
     * @return the rendered text
     * @throws TemplateException when a field, a filter or a value cannot be rendered
     */
    public static String render(String name, String template, Map<String, Object> context) {
        if (template == null) {
            return "";
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1).trim();
            String[] filters = m.group(2) == null ? new String[0] : m.group(2).split("\\|");
            String where = name + " line " + lineOf(template, m.start());
            Object value = lookup(context, ref);
            for (String raw : filters) {
                String filter = raw.trim();
                if (!filter.isEmpty()) {
                    value = apply(filter, value, ref, where);
                }
            }
            if (value == MISSING) {
                throw new TemplateException(where + ": unknown field '" + ref + "'");
            }
            if (value instanceof Map || value instanceof List) {
                throw new TemplateException(where + ": '" + ref + "' is a " + (value instanceof Map ? "map" : "list")
                        + "; write it with toYaml or toJson");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A field the context does not have; distinct from a field set to null. */
    private static final Object MISSING = new Object();

    private static Object lookup(Map<String, Object> context, String ref) {
        Object current = context;
        for (String part : ref.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return MISSING;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Object apply(String filter, Object value, String ref, String where) {
        String[] parts = filter.split("\\s+", 2);
        String fn = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : null;
        if ("default".equals(fn)) {
            if (arg == null) {
                throw new TemplateException(where + ": 'default' needs a value");
            }
            boolean blank = value == MISSING || value == null || String.valueOf(value).isBlank();
            return blank ? unquote(arg) : value;
        }
        if (value == MISSING) {
            throw new TemplateException(where + ": unknown field '" + ref + "'");
        }
        switch (fn) {
            case "toYaml":
                return write(YAML, value, where).stripTrailing();
            case "toJson":
                return write(JSON, value, where);
            case "quote":
                return write(JSON, value == null ? "" : String.valueOf(value), where);
            case "trim":
                return value == null ? "" : String.valueOf(value).trim();
            case "indent":
                return indent(value, arg, where);
            default:
                throw new TemplateException(where + ": unknown filter '" + fn + "'");
        }
    }

    private static String indent(Object value, String arg, String where) {
        int n;
        try {
            n = Integer.parseInt(arg == null ? "" : arg);
        } catch (NumberFormatException e) {
            throw new TemplateException(where + ": 'indent' needs a number of spaces");
        }
        if (value instanceof Map || value instanceof List) {
            throw new TemplateException(where + ": write a map or list with toYaml before indenting it");
        }
        String pad = " ".repeat(Math.max(0, n));
        return String.valueOf(value == null ? "" : value).replace("\n", "\n" + pad);
    }

    private static String write(ObjectMapper mapper, Object value, String where) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new TemplateException(where + ": cannot write the value: " + e.getOriginalMessage());
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
