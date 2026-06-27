/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service.om;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Registers a <b>base</b> database metadata ingestion (Hive today; Trino is a
 * straightforward extension) in OpenMetadata via OM REST. This is the first of
 * the two layers behind Atlas "federation":
 *
 * <ol>
 *   <li><b>Base ingestion (this client)</b> creates a real OM DatabaseService
 *       (e.g. {@code hive-clemlab}) plus a metadata ingestion pipeline that
 *       connects directly to HiveServer2 and <i>creates the table entities</i>
 *       in OM.</li>
 *   <li><b>Atlas federation</b> ({@link OmAtlasFederationClient}) then
 *       <i>enriches</i> those already-existing tables with Atlas lineage/tags.
 *       The Atlas connector is enrichment-only — it patches tables found in its
 *       {@code databaseServiceName}, it never creates them — so without this
 *       base step there is nothing for Atlas to enrich and OM shows no data.</li>
 * </ol>
 *
 * <p>For that enrichment to match, the Atlas service's {@code databaseServiceName}
 * must equal the {@code serviceName} created here, and the Atlas
 * {@code entity_type} must be {@code hive_table}.
 *
 * <p><b>Kerberos.</b> When {@code authMode=kerberos} the OM Hive connector uses
 * SPNEGO over the HiveServer2 HTTP transport (see the {@code requests-kerberos}
 * transport added to {@code custom_hive_connection.py}). That transport reads
 * the <i>ambient</i> Kerberos ccache, which the OM-deps Airflow chart maintains
 * via its built-in {@code airflow kerberos} renewer from the operator-provided
 * Hive keytab (wired through the {@code hive} external-service-target). No
 * keytab is passed in the connection itself.
 *
 * <p><b>Why exec-in-pod?</b> Same rationale as {@link OmAtlasFederationClient}
 * and {@link OmBotJwtClient}: the Ambari server runs outside the cluster and OM
 * is usually only reachable through an operator-managed ingress, so exec-ing a
 * scheduler pod that already has python3 + urllib + a ClusterIP route to OM is
 * simpler and more reliable than threading TLS trust through the view.
 */
public final class OmBaseIngestionClient {

    private static final Logger LOG = LoggerFactory.getLogger(OmBaseIngestionClient.class);

    private static final String EXEC_POD_SELECTOR_KEY   = "component";
    private static final String EXEC_POD_SELECTOR_VALUE = "scheduler";

    /** Result of a successful base-ingestion registration. */
    public static final class Result {
        public final String databaseServiceId;
        public final String databaseServiceName;
        public final String pipelineId;
        public final String deployStatus;
        public final String triggerStatus;
        public Result(String databaseServiceId, String databaseServiceName,
                      String pipelineId, String deployStatus, String triggerStatus) {
            this.databaseServiceId = databaseServiceId;
            this.databaseServiceName = databaseServiceName;
            this.pipelineId = pipelineId;
            this.deployStatus = deployStatus;
            this.triggerStatus = triggerStatus;
        }
    }

    /**
     * Registers a base Hive (or Trino) database metadata ingestion in OM.
     *
     * @param client       K8s client the view holds for the deploy
     * @param namespace    release namespace
     * @param release      helm release name
     * @param jwt          Unlimited-TTL JWT for OM's ingestion-bot
     *                     (see {@link OmBotJwtClient#mintUnlimitedJwt})
     * @param serviceName  the OM DatabaseService name to create — this is the
     *                     name Atlas federation's {@code databaseServiceName}
     *                     must reference (e.g. {@code hive-clemlab})
     * @param serviceType  OM database serviceType, {@code "Hive"} or {@code "Trino"}
     * @param scheme       connection scheme, e.g. {@code hive+https} / {@code hive+http}
     *                     (Hive) — drives transport selection in the connector
     * @param hostPort     {@code host:port} of the HiveServer2 / Trino coordinator
     * @param authMode     {@code kerberos} | {@code ldap} | {@code basic} | {@code none}
     * @param kerberosServiceName SPN service name when {@code authMode=kerberos}
     *                            (e.g. {@code hive}); ignored otherwise
     * @param username     used when {@code authMode} is ldap/basic; may be null
     * @param password     used when {@code authMode} is ldap/basic; may be null
     * @param schedule     cron-style schedule for the metadata pipeline
     * @param schemaIncludesCsv optional comma-separated schema include patterns
     *                          (regex); empty/null means "all schemas"
     * @param timeout      how long to wait for the exec
     * @return the OM IDs created on success
     * @throws IllegalStateException on any failure (message contains captured stderr)
     */
    public static Result register(KubernetesClient client,
                                  String namespace,
                                  String release,
                                  String jwt,
                                  String serviceName,
                                  String serviceType,
                                  String scheme,
                                  String hostPort,
                                  String authMode,
                                  String kerberosServiceName,
                                  String username,
                                  String password,
                                  String schedule,
                                  String schemaIncludesCsv,
                                  java.time.Duration timeout) throws Exception {
        if (client == null) throw new IllegalArgumentException("client is required");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (release == null || release.isBlank()) throw new IllegalArgumentException("release is required");
        if (jwt == null || jwt.isBlank()) throw new IllegalArgumentException("jwt is required");
        if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName is required");
        if (serviceType == null || serviceType.isBlank()) throw new IllegalArgumentException("serviceType is required");
        if (scheme == null || scheme.isBlank()) throw new IllegalArgumentException("scheme is required");
        if (hostPort == null || hostPort.isBlank()) throw new IllegalArgumentException("hostPort is required");
        if (schedule == null || schedule.isBlank()) throw new IllegalArgumentException("schedule is required");
        String am = (authMode == null || authMode.isBlank()) ? "none" : authMode.trim().toLowerCase();

        // Pick a Running scheduler pod (same selection rules as OmAtlasFederationClient).
        java.util.List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabel(EXEC_POD_SELECTOR_KEY, EXEC_POD_SELECTOR_VALUE)
                .list().getItems();
        Pod target = pods.stream()
                .filter(p -> p.getMetadata() != null && p.getMetadata().getName() != null)
                .filter(p -> p.getMetadata().getName().startsWith(release + "-airflow-scheduler"))
                .filter(p -> "Running".equalsIgnoreCase(p.getStatus().getPhase()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find a Running airflow scheduler pod for release '" + release
                                + "' in namespace '" + namespace + "'. Make sure dependencies.enabled=true."));

        String podName = target.getMetadata().getName();
        String pipelineName = serviceName + "-metadata-ingest";
        String schemaIncludesJson = buildIncludesJson(schemaIncludesCsv);

        LOG.info("OmBaseIngestionClient: exec into pod '{}' to register base ingestion "
                        + "(service='{}', type='{}', scheme='{}', hostPort='{}', auth='{}', pipeline='{}')",
                podName, serviceName, serviceType, scheme, hostPort, am, pipelineName);

        // The connection 'config' is assembled in Python from AUTH_MODE so secrets
        // only ever travel as shell-quoted env vars (never interpolated into JSON here).
        String script =
            "set -eu\n" +
            "python3 - <<'PY'\n" +
            "import os, json, sys, urllib.request, urllib.error\n" +
            "OM = 'http://' + os.environ.get('OM_SVC_HOST', os.environ['OM_RELEASE']) + '.' + os.environ['OM_NAMESPACE'] + ':' + os.environ.get('OM_PORT', '8585')\n" +
            "HDR = {'Authorization': 'Bearer ' + os.environ['OM_JWT'], 'Content-Type': 'application/json'}\n" +
            "def om(method, path, body=None):\n" +
            "    req = urllib.request.Request(OM + '/api/v1' + path,\n" +
            "        data=json.dumps(body).encode() if body is not None else None,\n" +
            "        headers=HDR, method=method)\n" +
            "    try:\n" +
            "        resp = urllib.request.urlopen(req, timeout=30)\n" +
            "        return resp.status, resp.read().decode()\n" +
            "    except urllib.error.HTTPError as e:\n" +
            "        return e.code, e.read().decode()\n" +
            "# --- build the database connection config from the auth mode ---\n" +
            "conn = {'type': os.environ['SVC_TYPE'], 'scheme': os.environ['SCHEME'], 'hostPort': os.environ['HOST_PORT']}\n" +
            "am = os.environ['AUTH_MODE']\n" +
            "if am == 'kerberos':\n" +
            "    conn['auth'] = 'KERBEROS'\n" +
            "    if os.environ.get('KRB_SVC'): conn['kerberosServiceName'] = os.environ['KRB_SVC']\n" +
            "elif am in ('ldap', 'basic', 'knox', 'simple'):\n" +
            "    conn['username'] = os.environ.get('DB_USER', '')\n" +
            "    conn['password'] = os.environ.get('DB_PASS', '')\n" +
            "    if am == 'ldap': conn['auth'] = 'LDAP'\n" +
            "# am == 'none' -> no auth fields\n" +
            "# 1) databaseService (creates the tables' parent service in OM)\n" +
            "code, body = om('PUT', '/services/databaseServices', {\n" +
            "    'name': os.environ['SVC_NAME'],\n" +
            "    'displayName': os.environ['SVC_NAME'],\n" +
            "    'description': 'Base ' + os.environ['SVC_TYPE'] + ' metadata ingestion — wired by KDPS.',\n" +
            "    'serviceType': os.environ['SVC_TYPE'],\n" +
            "    'connection': {'config': conn}\n" +
            "})\n" +
            "print('databaseService=' + str(code), file=sys.stderr)\n" +
            "if code not in (200, 201):\n" +
            "    print('databaseService PUT failed: ' + body, file=sys.stderr); sys.exit(2)\n" +
            "service_id = json.loads(body)['id']\n" +
            "# 2) ingestionPipeline (DatabaseMetadata)\n" +
            "src = {'type': 'DatabaseMetadata', 'markDeletedTables': False,\n" +
            "       'includeTables': True, 'includeViews': True}\n" +
            "incl = json.loads(os.environ['SCHEMA_INCLUDES_JSON'])\n" +
            "if incl:\n" +
            "    src['schemaFilterPattern'] = {'includes': incl}\n" +
            "code, body = om('PUT', '/services/ingestionPipelines', {\n" +
            "    'name': os.environ['PIPELINE_NAME'],\n" +
            "    'displayName': os.environ['SVC_TYPE'] + ' metadata ingest',\n" +
            "    'pipelineType': 'metadata',\n" +
            "    'service': {'id': service_id, 'type': 'databaseService'},\n" +
            "    'sourceConfig': {'config': src},\n" +
            "    'airflowConfig': {'scheduleInterval': os.environ['SCHEDULE']}\n" +
            "})\n" +
            "print('ingestionPipeline=' + str(code), file=sys.stderr)\n" +
            "if code not in (200, 201):\n" +
            "    print('ingestionPipeline PUT failed: ' + body, file=sys.stderr); sys.exit(3)\n" +
            "pipe_id = json.loads(body)['id']\n" +
            "# 3) DEPLOY the pipeline — generates the Airflow DAG. Without it the pipeline\n" +
            "#    exists but no DAG runs, so nothing is ingested. Must run BEFORE trigger.\n" +
            "code, body = om('POST', '/services/ingestionPipelines/deploy/' + pipe_id)\n" +
            "print('deploy=' + str(code), file=sys.stderr)\n" +
            "deploy_status = 'deployed' if code in (200, 201) else ('failed-' + str(code))\n" +
            "if code not in (200, 201):\n" +
            "    print('deploy failed: ' + body, file=sys.stderr)\n" +
            "# 4) trigger first run\n" +
            "code, body = om('POST', '/services/ingestionPipelines/trigger/' + pipe_id)\n" +
            "print('trigger=' + str(code), file=sys.stderr)\n" +
            "trigger_status = 'queued' if code in (200, 201) else ('skip-' + str(code))\n" +
            "print(json.dumps({\n" +
            "    'databaseServiceId': service_id,\n" +
            "    'databaseServiceName': os.environ['SVC_NAME'],\n" +
            "    'pipelineId': pipe_id,\n" +
            "    'deployStatus': deploy_status,\n" +
            "    'triggerStatus': trigger_status\n" +
            "}))\n" +
            "PY\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        long timeoutMs = timeout == null ? 120_000L : timeout.toMillis();

        // Secrets (DB_PASS) only ever travel as shell-quoted env values.
        String envExports =
                "OM_RELEASE=" + OmBotJwtClient.shellQuote(release)
              + " OM_NAMESPACE=" + OmBotJwtClient.shellQuote(namespace)
              + " OM_JWT=" + OmBotJwtClient.shellQuote(jwt)
              + " SVC_NAME=" + OmBotJwtClient.shellQuote(serviceName)
              + " SVC_TYPE=" + OmBotJwtClient.shellQuote(serviceType)
              + " SCHEME=" + OmBotJwtClient.shellQuote(scheme)
              + " HOST_PORT=" + OmBotJwtClient.shellQuote(hostPort)
              + " AUTH_MODE=" + OmBotJwtClient.shellQuote(am)
              + " KRB_SVC=" + OmBotJwtClient.shellQuote(kerberosServiceName == null ? "" : kerberosServiceName)
              + " DB_USER=" + OmBotJwtClient.shellQuote(username == null ? "" : username)
              + " DB_PASS=" + OmBotJwtClient.shellQuote(password == null ? "" : password)
              + " PIPELINE_NAME=" + OmBotJwtClient.shellQuote(pipelineName)
              + " SCHEDULE=" + OmBotJwtClient.shellQuote(schedule)
              + " SCHEMA_INCLUDES_JSON=" + OmBotJwtClient.shellQuote(schemaIncludesJson);

        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer("scheduler")
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override public void onClose(int code, String reason) { done.countDown(); }
                    @Override public void onFailure(Throwable t, Response r) { done.countDown(); }
                })
                .exec("bash", "-c", envExports + " bash -c " + OmBotJwtClient.shellQuote(script))) {
            boolean finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                throw new IllegalStateException(
                        "OmBaseIngestionClient: timed out after " + timeoutMs
                                + "ms waiting for exec to complete. stderr="
                                + stderr.toString(StandardCharsets.UTF_8));
            }
        }

        String out = stdout.toString(StandardCharsets.UTF_8).trim();
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "OmBaseIngestionClient: exec produced no output on stdout. stderr="
                            + stderr.toString(StandardCharsets.UTF_8));
        }
        String[] lines = out.split("\\R");
        String resultLine = lines[lines.length - 1].trim();
        try {
            com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString(resultLine).getAsJsonObject();
            Result r = new Result(
                    json.has("databaseServiceId") ? json.get("databaseServiceId").getAsString() : null,
                    json.has("databaseServiceName") ? json.get("databaseServiceName").getAsString() : serviceName,
                    json.has("pipelineId") ? json.get("pipelineId").getAsString() : null,
                    json.has("deployStatus") ? json.get("deployStatus").getAsString() : "unknown",
                    json.has("triggerStatus") ? json.get("triggerStatus").getAsString() : "unknown");
            LOG.info("OmBaseIngestionClient: wired base ingestion — service='{}' id={} pipelineId={} deploy={} trigger={}",
                    r.databaseServiceName, r.databaseServiceId, r.pipelineId, r.deployStatus, r.triggerStatus);
            return r;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "OmBaseIngestionClient: could not parse result JSON '" + resultLine
                            + "'. Full stderr: " + stderr.toString(StandardCharsets.UTF_8), ex);
        }
    }

    /**
     * Renders a comma-separated list of schema include patterns to a JSON array
     * literal. Blank entries are skipped; an empty/blank input yields {@code []}
     * (interpreted as "all schemas"). Visible for unit tests.
     */
    static String buildIncludesJson(String schemaIncludesCsv) {
        StringBuilder sb = new StringBuilder("[");
        if (schemaIncludesCsv != null && !schemaIncludesCsv.isBlank()) {
            boolean first = true;
            for (String s : schemaIncludesCsv.split(",")) {
                if (s == null || s.isBlank()) continue;
                if (!first) sb.append(",");
                sb.append("\"").append(s.trim().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private OmBaseIngestionClient() { /* utility */ }
}
