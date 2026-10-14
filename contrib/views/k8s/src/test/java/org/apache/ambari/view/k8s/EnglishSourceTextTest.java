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
package org.apache.ambari.view.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Messages the view returns to operators, and the comments next to them, are in English. The error text of a failed
 * deployment step or a refused request reaches the UI as is, so a French accented letter in a Java source is almost
 * certainly an untranslated message.
 */
class EnglishSourceTextTest {

    private static final Pattern FRENCH_ACCENT = Pattern.compile("[éèêëàâçùûôîïœ]");

    @Test
    void noJavaSourceCarriesFrenchText() throws IOException {
        Path java = Paths.get(System.getProperty("basedir", ".")).resolve("src/main/java");
        assertTrue(Files.isDirectory(java), "run from the view module: " + java.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(java)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (FRENCH_ACCENT.matcher(lines.get(i)).find()) {
                        offenders.add(java.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "Java sources with French accented letters");
    }
}
