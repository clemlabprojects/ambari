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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the move to several clusters: {@code KubernetesService.get(ctx)} reaches the default cluster without naming
 * it. The code not moved yet still calls it; new code names its cluster ({@code get(ctx, cluster)}). This fails when
 * the callers grow, and its baseline goes down as code moves, until no caller is left.
 */
class KubernetesServiceCallersTest {

  /** Calls of {@code KubernetesService.get} with a single argument in the main sources, on 2026-10-26. */
  static final int BASELINE = 11;

  private static final Pattern DEFAULT_CLUSTER_CALL = Pattern.compile("KubernetesService\\.get\\(\\s*[\\w.]+(\\(\\))?\\s*\\)");

  @Test
  void noNewCodeReachesTheDefaultClusterWithoutNamingIt() throws IOException {
    List<String> calls = new ArrayList<>();
    try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher m = DEFAULT_CLUSTER_CALL.matcher(Files.readString(source));
        while (m.find()) {
          calls.add(source.getFileName() + ": " + m.group());
        }
      }
    }
    assertTrue(calls.size() <= BASELINE, "KubernetesService.get(ctx) reaches the default cluster without naming it: "
        + "call get(ctx, cluster) instead. Calls found: " + calls);
    assertEquals(BASELINE, calls.size(), "Fewer calls than the baseline: lower BASELINE to " + calls.size()
        + " so that it keeps guarding. Calls found: " + calls);
  }
}
