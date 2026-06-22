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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Registers an Apache Atlas federation in OpenMetadata via OM REST. Replaces
 * the {@code job-atlas-federation-wire.yaml} helm post-install hook that the
 * OM chart used to ship — the chart now defers this work to an Ambari step
 * ({@code OM_ATLAS_FEDERATION_REGISTER}) so the operation shows up as a
 * tracked, replayable command in the KDPS UI.
 *
 * <p>The four-call OM REST flow this client drives is unchanged from the
 * previous Job:
 * <ol>
 *   <li>{@code PUT /api/v1/services/metadataServices}  — the Atlas service
 *       (serviceType=Atlas, hostPort+credentials, the list of database
 *       services whose entities should land under Atlas-derived lineage).</li>
 *   <li>{@code PUT /api/v1/services/databaseServices}  — a CustomDatabase
 *       service ({@code atlas-<release>-hive}) where the Atlas-sourced
 *       hive_table entities are materialised, kept separate from the direct
 *       Hive/Trino ingestion so the lineage stays clear.</li>
 *   <li>{@code PUT /api/v1/services/ingestionPipelines} — a metadata
 *       ingestion pipeline with the operator-chosen schedule, executed by
 *       the airflow runtime deployed by the OM-deps sub-chart.</li>
 *   <li>{@code POST /api/v1/services/ingestionPipelines/trigger/{id}} —
 *       kicks off the first run so the operator sees activity in the OM
 *       UI immediately after the deploy.</li>
 * </ol>
 *
 * <p><b>Why exec-in-pod again?</b> The Ambari server runs outside the
 * Kubernetes cluster and OM is typically only exposed through an ingress
 * with TLS the operator manages externally. Exec-ing a scheduler pod that
 * already has python3 + urllib + a direct ClusterIP route to OM is simpler
 * and more reliable than threading certs through {@code java.net.HttpClient}
 * + trust store config in the view. See {@link OmBotJwtClient} for the same
 * decision applied to the JWT-mint step.
 */
public final class OmAtlasFederationClient {

    private static final Logger LOG = LoggerFactory.getLogger(OmAtlasFederationClient.class);

    private static final String EXEC_POD_SELECTOR_KEY   = "component";
    private static final String EXEC_POD_SELECTOR_VALUE = "scheduler";

    /** Result of a successful registration — the IDs OM minted for the new entities. */
    public static final class Result {
        public final String metadataServiceId;
        public final String databaseServiceName;
        public final String pipelineId;
        public final String triggerStatus;
        public Result(String metadataServiceId, String databaseServiceName,
                      String pipelineId, String triggerStatus) {
            this.metadataServiceId = metadataServiceId;
            this.databaseServiceName = databaseServiceName;
            this.pipelineId = pipelineId;
            this.triggerStatus = triggerStatus;
        }
    }

    /**
     * Registers an Atlas federation in OM. All inputs are required.
     *
     * @param client K8s client the view holds for the deploy
     * @param namespace release namespace
     * @param release helm release name
     * @param jwt Unlimited-TTL JWT for OM's ingestion-bot
     *            (typically obtained via {@link OmBotJwtClient#mintUnlimitedJwt})
     * @param atlasHostPort Atlas REST endpoint, e.g. {@code http://atlas.host:21000}
     * @param atlasUsername Atlas bot account
     * @param atlasPassword Atlas bot password (used in cleartext via env var
     *                      to the exec; OM stores it Fernet-encrypted)
     * @param databaseServiceNames OM database service names whose entities
     *                              Atlas should claim. Empty list means "all".
     * @param schedule cron-style schedule string for the metadata pipeline
     *                 (e.g. {@code 0 *&#47;6 * * *} for every 6 hours)
     * @param timeout how long to wait for the exec
     * @return the OM IDs created on success
     * @throws IllegalStateException on any failure (message contains captured stderr)
     */
    public static Result register(KubernetesClient client,
                                  String namespace,
                                  String release,
                                  String jwt,
                                  String atlasHostPort,
                                  String atlasUsername,
                                  String atlasPassword,
                                  List<String> databaseServiceNames,
                                  String schedule,
                                  java.time.Duration timeout) throws Exception {
        if (client == null) throw new IllegalArgumentException("client is required");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (release == null || release.isBlank()) throw new IllegalArgumentException("release is required");
        if (jwt == null || jwt.isBlank()) throw new IllegalArgumentException("jwt is required");
        if (atlasHostPort == null || atlasHostPort.isBlank())
            throw new IllegalArgumentException("atlasHostPort is required");
        if (atlasUsername == null || atlasUsername.isBlank())
            throw new IllegalArgumentException("atlasUsername is required");
        if (atlasPassword == null || atlasPassword.isBlank())
            throw new IllegalArgumentException("atlasPassword is required");
        if (schedule == null || schedule.isBlank())
            throw new IllegalArgumentException("schedule is required");

        // Pick a scheduler pod (same selection rules as OmBotJwtClient).
        List<Pod> pods = client.pods().inNamespace(namespace)
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
        String serviceName = "atlas-" + release;
        String dbServiceName = "atlas-" + release + "-hive";
        String pipelineName = "atlas-" + release + "-metadata-ingest";

        // databaseServiceNames is rendered to a JSON array; OM requires that shape.
        String dbsJson = buildDbsJson(databaseServiceNames);

        LOG.info("OmAtlasFederationClient: exec into pod '{}' to register Atlas federation "
                        + "(service='{}', dbService='{}', pipeline='{}', dbs={})",
                podName, serviceName, dbServiceName, pipelineName, dbsJson);

        String script =
            "set -eu\n" +
            "python3 - <<'PY'\n" +
            "import os, json, sys, urllib.request\n" +
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
            "dbs = json.loads(os.environ['DBS_JSON'])\n" +
            "# 1) metadataService for Atlas\n" +
            "code, body = om('PUT', '/services/metadataServices', {\n" +
            "    'name': os.environ['ATLAS_SERVICE_NAME'],\n" +
            "    'displayName': 'Atlas (federated)',\n" +
            "    'description': 'Federation of Apache Atlas — wired by KDPS.',\n" +
            "    'serviceType': 'Atlas',\n" +
            "    'connection': {'config': {\n" +
            "        'type': 'Atlas',\n" +
            "        'hostPort': os.environ['ATLAS_HOST_PORT'],\n" +
            "        'username': os.environ['ATLAS_USER'],\n" +
            "        'password': os.environ['ATLAS_PASSWORD'],\n" +
            "        'databaseServiceName': dbs,\n" +
            "        'messagingServiceName': [],\n" +
            "        'supportsMetadataExtraction': True\n" +
            "    }}\n" +
            "})\n" +
            "print('metadataService=' + str(code), file=sys.stderr)\n" +
            "if code not in (200, 201):\n" +
            "    print('metadataService PUT failed: ' + body, file=sys.stderr); sys.exit(2)\n" +
            "service_id = json.loads(body)['id']\n" +
            "# 2) databaseService landing zone\n" +
            "code, body = om('PUT', '/services/databaseServices', {\n" +
            "    'name': os.environ['ATLAS_DB_SERVICE_NAME'],\n" +
            "    'displayName': 'Atlas Hive entities (federated)',\n" +
            "    'description': 'Hive entities ingested from Atlas.',\n" +
            "    'serviceType': 'CustomDatabase',\n" +
            "    'connection': {'config': {\n" +
            "        'type': 'CustomDatabase',\n" +
            "        'sourcePythonClass': 'metadata.ingestion.source.database.atlas.metadata.AtlasSource'\n" +
            "    }}\n" +
            "})\n" +
            "print('databaseService=' + str(code), file=sys.stderr)\n" +
            "if code not in (200, 201):\n" +
            "    print('databaseService PUT failed: ' + body, file=sys.stderr); sys.exit(3)\n" +
            "# 3) ingestionPipeline\n" +
            "code, body = om('PUT', '/services/ingestionPipelines', {\n" +
            "    'name': os.environ['ATLAS_PIPELINE_NAME'],\n" +
            "    'displayName': 'Atlas metadata ingest',\n" +
            "    'pipelineType': 'metadata',\n" +
            "    'service': {'id': service_id, 'type': 'metadataService'},\n" +
            "    'sourceConfig': {'config': {\n" +
            "        'type': 'DatabaseMetadata',\n" +
            "        'markDeletedTables': False,\n" +
            "        'includeTables': True, 'includeViews': True, 'includeTags': True\n" +
            "    }},\n" +
            "    'airflowConfig': {'scheduleInterval': os.environ['ATLAS_SCHEDULE']}\n" +
            "})\n" +
            "print('ingestionPipeline=' + str(code), file=sys.stderr)\n" +
            "if code not in (200, 201):\n" +
            "    print('ingestionPipeline PUT failed: ' + body, file=sys.stderr); sys.exit(4)\n" +
            "pipe_id = json.loads(body)['id']\n" +
            "# 4) trigger first run\n" +
            "code, body = om('POST', '/services/ingestionPipelines/trigger/' + pipe_id)\n" +
            "print('trigger=' + str(code), file=sys.stderr)\n" +
            "trigger_status = 'queued' if code in (200, 201) else ('skip-' + str(code))\n" +
            "print(json.dumps({\n" +
            "    'metadataServiceId': service_id,\n" +
            "    'databaseServiceName': os.environ['ATLAS_DB_SERVICE_NAME'],\n" +
            "    'pipelineId': pipe_id,\n" +
            "    'triggerStatus': trigger_status\n" +
            "}))\n" +
            "PY\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        long timeoutMs = timeout == null ? 120_000L : timeout.toMillis();

        // The Atlas password is the only secret on the env list. Bash interpolation
        // here is safe because we always shell-quote (single-quote escape) below.
        String envExports =
                "OM_RELEASE=" + OmBotJwtClient.shellQuote(release)
              + " OM_NAMESPACE=" + OmBotJwtClient.shellQuote(namespace)
              + " OM_JWT=" + OmBotJwtClient.shellQuote(jwt)
              + " ATLAS_HOST_PORT=" + OmBotJwtClient.shellQuote(atlasHostPort)
              + " ATLAS_USER=" + OmBotJwtClient.shellQuote(atlasUsername)
              + " ATLAS_PASSWORD=" + OmBotJwtClient.shellQuote(atlasPassword)
              + " ATLAS_SERVICE_NAME=" + OmBotJwtClient.shellQuote(serviceName)
              + " ATLAS_DB_SERVICE_NAME=" + OmBotJwtClient.shellQuote(dbServiceName)
              + " ATLAS_PIPELINE_NAME=" + OmBotJwtClient.shellQuote(pipelineName)
              + " ATLAS_SCHEDULE=" + OmBotJwtClient.shellQuote(schedule)
              + " DBS_JSON=" + OmBotJwtClient.shellQuote(dbsJson);

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
                        "OmAtlasFederationClient: timed out after " + timeoutMs
                                + "ms waiting for exec to complete. stderr="
                                + stderr.toString(StandardCharsets.UTF_8));
            }
        }

        String out = stdout.toString(StandardCharsets.UTF_8).trim();
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "OmAtlasFederationClient: exec produced no output on stdout. stderr="
                            + stderr.toString(StandardCharsets.UTF_8));
        }
        String[] lines = out.split("\\R");
        String resultLine = lines[lines.length - 1].trim();
        try {
            com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString(resultLine).getAsJsonObject();
            Result r = new Result(
                    json.has("metadataServiceId") ? json.get("metadataServiceId").getAsString() : null,
                    json.has("databaseServiceName") ? json.get("databaseServiceName").getAsString() : dbServiceName,
                    json.has("pipelineId") ? json.get("pipelineId").getAsString() : null,
                    json.has("triggerStatus") ? json.get("triggerStatus").getAsString() : "unknown");
            LOG.info("OmAtlasFederationClient: wired Atlas federation — metadataServiceId={} pipelineId={} trigger={}",
                    r.metadataServiceId, r.pipelineId, r.triggerStatus);
            return r;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "OmAtlasFederationClient: could not parse result JSON '" + resultLine
                            + "'. Full stderr: " + stderr.toString(StandardCharsets.UTF_8), ex);
        }
    }

    /**
     * Renders the {@code databaseServiceName} list to a JSON array literal.
     * Skips blank/null entries (a trailing comma in the wizard CSV shouldn't
     * produce an empty array element) and quote-escapes service names that
     * happen to contain {@code "}. Visible for unit tests.
     */
    static String buildDbsJson(List<String> databaseServiceNames) {
        StringBuilder dbsJson = new StringBuilder("[");
        if (databaseServiceNames != null) {
            boolean first = true;
            for (String d : databaseServiceNames) {
                if (d == null || d.isBlank()) continue;
                if (!first) dbsJson.append(",");
                dbsJson.append("\"").append(d.trim().replace("\"", "\\\"")).append("\"");
                first = false;
            }
        }
        dbsJson.append("]");
        return dbsJson.toString();
    }

    private OmAtlasFederationClient() { /* utility */ }
}
