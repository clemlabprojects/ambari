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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The template syntax of GitOps output profiles: fields, filters, and what it refuses. */
class FileTemplateTest {

    private static Map<String, Object> ctx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("release", "analytics");
        c.put("empty", "");
        c.put("list", List.of("a", "b"));
        c.put("map", Map.of("k", "v"));
        c.put("multi", "line1\nline2");
        return c;
    }

    private static String render(String t) {
        return FileTemplate.render("t", t, ctx());
    }

    @Test
    void filters() {
        assertEquals("name: \"analytics\"", render("name: {{ release | quote }}"));
        assertEquals("[\"a\",\"b\"]", render("{{ list | toJson }}"));
        assertEquals("k: \"v\"", render("{{ map | toYaml }}"));
        assertEquals("block:\n  line1\n  line2", render("block:\n  {{ multi | indent 2 }}"));
        assertEquals("fallback", render("{{ empty | default fallback }}"));
        assertEquals("fallback", render("{{ missing | default \"fallback\" }}"));
        assertEquals("analytics", render("{{  release  |  trim }}"));
    }

    @Test
    void otherToolsPlaceholdersAndPlainTextPassThrough() {
        assertEquals("${IMAGE_TAG} $(cmd) {single}", render("${IMAGE_TAG} $(cmd) {single}"));
    }

    @Test
    void aMapOrListMustBeWrittenWithToYamlOrToJson() {
        assertTrue(assertThrows(FileTemplate.TemplateException.class, () -> render("{{ list }}"))
                .getMessage().contains("write it with toYaml or toJson"));
    }

    @Test
    void indentNeedsANumber() {
        assertThrows(FileTemplate.TemplateException.class, () -> render("{{ multi | indent x }}"));
    }
}
