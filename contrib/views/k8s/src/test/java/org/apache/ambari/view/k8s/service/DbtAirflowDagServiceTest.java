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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generated DAG is committed to a customer's repository, so two properties matter beyond it
 * being syntactically valid: re-publishing an unchanged release must produce identical bytes (or
 * every install leaves a commit), and the run definition must stay in the chart rather than being
 * copied into the DAG (or the two drift apart).
 */
class DbtAirflowDagServiceTest {

    @Test
    void theSameReleaseAlwaysRendersIdenticalText() {
        String a = DbtAirflowDagService.renderDag("dbt", "dbt-ns", "0 2 * * *", "", 60);
        String b = DbtAirflowDagService.renderDag("dbt", "dbt-ns", "0 2 * * *", "", 60);
        assertEquals(a, b, "an unchanged re-install must not produce a commit");
    }

    @Test
    void namesAreDerivedFromTheReleaseAndAreValidPythonIdentifiers() {
        assertEquals("kdps_dbt_lake_analytics.py", DbtAirflowDagService.dagFileName("lake", "analytics"));
        assertEquals("kdps_dbt_lake_analytics", DbtAirflowDagService.dagId("lake", "analytics"));
        // Dashes are legal in a release name but not in a DAG id.
        assertEquals("kdps_dbt_trino_prod_data_eng", DbtAirflowDagService.dagId("trino-prod", "data-eng"));
        assertTrue(DbtAirflowDagService.dagId("a.b-c", "x/y").matches("[a-z0-9_]+"));
    }

    @Test
    void theRunDefinitionStaysInTheChart() {
        String dag = DbtAirflowDagService.renderDag("dbt", "dbt-ns", null, "", 60);
        // The DAG creates a Job from the release's own template...
        assertTrue(dag.contains("read_namespaced_cron_job"), "the DAG should read the release's run template");
        assertTrue(dag.contains("CRON_JOB = \"dbt-run\""));
        // ...and must NOT restate how dbt runs, or the two definitions drift. Only the code is
        // checked: the docstring explains the design and mentions those words on purpose.
        String code = dag.substring(dag.indexOf("from datetime"));
        assertFalse(code.contains("image="), "the DAG must not pin an image");
        assertFalse(code.contains("DBT_PROFILES_DIR"), "the DAG must not carry the profile wiring");
        assertFalse(code.contains("volume"), "the DAG must not carry a pod spec");
        assertFalse(code.contains("KubernetesPodOperator"), "the run comes from the chart's template, not a pod built here");
    }

    @Test
    void aBlankScheduleMeansManualOnly() {
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "", 60).contains("schedule=None"));
        assertTrue(DbtAirflowDagService.renderDag("d", "n", "  ", "", 60).contains("schedule=None"));
        assertTrue(DbtAirflowDagService.renderDag("d", "n", "0 3 * * *", "", 60).contains("schedule=\"0 3 * * *\""));
    }

    @Test
    void theClusterConnectionIsOptional() {
        // Airflow inside the cluster uses its own service account.
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "", 60).contains("KUBE_CONN_ID = None"));
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "prod-k8s", 60).contains("KUBE_CONN_ID = \"prod-k8s\""));
    }

    @Test
    void aFailedRunSurfacesDbtsOwnOutput() {
        String dag = DbtAirflowDagService.renderDag("d", "n", null, "", 60);
        assertTrue(dag.contains("read_namespaced_pod_log"), "a failure should print the dbt log, not just an exit code");
    }

    @Test
    void theTimeoutIsAlwaysPositive() {
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "", 0).contains("minutes=1"));
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "", -5).contains("minutes=1"));
        assertTrue(DbtAirflowDagService.renderDag("d", "n", null, "", 90).contains("minutes=90"));
    }
}
