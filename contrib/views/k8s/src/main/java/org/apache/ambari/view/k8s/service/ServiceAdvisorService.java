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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs the KDPS "service advisor" — the analogue of the Ambari stack advisor — to recommend
 * default on/off values for a service's enable/disable toggles based on what the selected
 * platform context provides.
 *
 * <p>The engine is here (Java); the <em>rules</em> live in an operator-editable Python script
 * ({@code KDPS/advisors/stack_advisor.py}) that this class invokes — the same shape Ambari uses
 * for {@code stack_advisor.py}. Each service form field may carry an {@code advisor} key naming a
 * Python function; this class collects the detected context capabilities, hands them (plus the
 * field list) to the script, and returns its recommendations.
 *
 * <p><b>Security.</b> The script is executed with an explicit argument vector (no shell, so no
 * command injection), is fed its input on stdin (not argv, so nothing leaks to the process table),
 * and is given <em>only capability-presence booleans and field names</em> — never endpoints,
 * usernames, or secrets. The script path is the fixed bundled resource, never caller-supplied.
 *
 * <p><b>Resilience.</b> Advice is best-effort and never blocks a deploy: a missing {@code python3},
 * a missing/invalid script, a timeout, a non-zero exit, or malformed output all yield an empty
 * recommendation list and a warning log — the wizard simply falls back to the static defaults.
 */
public class ServiceAdvisorService {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceAdvisorService.class);
    private static final String SCRIPT_RESOURCE = "KDPS/advisors/stack_advisor.py";
    private static final String PYTHON = "python3";
    private static final long TIMEOUT_SECONDS = 10L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A single toggle recommendation returned to the wizard. */
    public static final class Recommendation {
        public String field;
        public boolean recommend;
        public String reason;
    }

    /**
     * Recommend on/off for each advisor-tagged field, given the resolved context's capabilities.
     *
     * @param serviceName   the service being configured (informational, passed to the script)
     * @param context       the resolved platform context (capabilities are read from it server-side)
     * @param advisorFields the form fields carrying an {@code advisor} key — each a map with at
     *                      least {@code name} and {@code advisor}
     * @return recommendations (possibly empty — never null, never throws)
     */
    public List<Recommendation> advise(String serviceName, ResolvedContext context,
                                       List<Map<String, String>> advisorFields) {
        if (advisorFields == null || advisorFields.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("service", serviceName == null ? "" : serviceName);
            request.put("capabilities", detectCapabilities(context));
            request.put("fields", advisorFields);

            String output = runScript(MAPPER.writeValueAsString(request));
            if (output == null) {
                return Collections.emptyList();
            }
            JsonNode root = MAPPER.readTree(output);
            List<Recommendation> recommendations = new ArrayList<>();
            for (JsonNode node : root.path("recommendations")) {
                String field = node.path("field").asText(null);
                if (field == null || field.isBlank()) {
                    continue;
                }
                Recommendation rec = new Recommendation();
                rec.field = field;
                rec.recommend = node.path("recommend").asBoolean(false);
                rec.reason = node.path("reason").asText("");
                recommendations.add(rec);
            }
            return recommendations;
        } catch (Exception e) {
            // Advice is advisory only — any failure must not surface to the operator.
            LOG.warn("ServiceAdvisorService: advice failed (non-fatal): {}", e.toString());
            return Collections.emptyList();
        }
    }

    /**
     * Capability-PRESENCE flags derived from the resolved context. Deliberately booleans only —
     * no endpoints, usernames, or secrets are placed in the advisor input.
     */
    Map<String, Boolean> detectCapabilities(ResolvedContext context) {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        if (context == null) {
            return caps;
        }
        Map<String, String> rf = context.getResolvedFields() != null
                ? context.getResolvedFields() : Collections.emptyMap();
        caps.put("atlas", context.isAtlasManaged() || notBlank(context.getAtlasUrl()) || notBlank(rf.get("atlas.url")));
        caps.put("ranger", context.isRangerManaged() || notBlank(context.getRangerUrl()) || notBlank(rf.get("ranger.url")));
        caps.put("hive", notBlank(rf.get("hive.hs2HostPort")) || notBlank(rf.get("hive.hs2JdbcUrl")));
        caps.put("kerberos", notBlank(context.getKerberosRealm()) || notBlank(rf.get("kerberos.realm")));
        caps.put("oidc", notBlank(rf.get("oidc.issuerUrl")));
        // Trino is discovered from Kubernetes pods (k8s-discovery), not the platform context, so it
        // is not derivable here yet; a future iteration can surface it as a context capability.
        caps.put("trino", false);
        return caps;
    }

    /**
     * Invoke {@code python3 stack_advisor.py}, writing {@code input} to stdin and returning stdout,
     * or {@code null} on any problem (missing python/script, timeout, non-zero exit). Payloads are
     * small (a handful of fields), so the write-then-read sequence cannot deadlock the OS pipe.
     */
    private String runScript(String input) throws InterruptedException {
        File script = resolveScript();
        if (script == null) {
            LOG.warn("ServiceAdvisorService: advisor script '{}' not found; skipping advice", SCRIPT_RESOURCE);
            return null;
        }
        Process process;
        try {
            process = new ProcessBuilder(PYTHON, script.getAbsolutePath()).start();
        } catch (IOException e) {
            LOG.warn("ServiceAdvisorService: '{}' not available; skipping advice ({})", PYTHON, e.toString());
            return null;
        }
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            String stdout;
            try (InputStream is = process.getInputStream()) {
                stdout = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("ServiceAdvisorService: advisor timed out after {}s; skipping advice", TIMEOUT_SECONDS);
                return null;
            }
            if (process.exitValue() != 0) {
                LOG.warn("ServiceAdvisorService: advisor exited {}; skipping advice", process.exitValue());
                return null;
            }
            return stdout;
        } catch (IOException e) {
            LOG.warn("ServiceAdvisorService: advisor I/O failed; skipping advice ({})", e.toString());
            return null;
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Resolve the bundled advisor script to a file on disk. The exploded view (Ambari work dir)
     * exposes it directly; when packaged in a jar it is extracted to a temp file.
     */
    private File resolveScript() {
        try {
            ClassLoader cl = getClass().getClassLoader();
            URL url = cl.getResource(SCRIPT_RESOURCE);
            if (url == null) {
                return null;
            }
            if ("file".equals(url.getProtocol())) {
                File f = new File(url.toURI());
                if (f.isFile()) {
                    return f;
                }
            }
            try (InputStream is = cl.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (is == null) {
                    return null;
                }
                File tmp = File.createTempFile("kdps-stack-advisor", ".py");
                tmp.deleteOnExit();
                Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return tmp;
            }
        } catch (Exception e) {
            LOG.warn("ServiceAdvisorService: could not locate advisor script: {}", e.toString());
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
