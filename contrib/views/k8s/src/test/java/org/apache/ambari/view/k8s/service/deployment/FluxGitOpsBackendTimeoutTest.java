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
package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.model.stack.StackServiceDef;
import org.apache.ambari.view.k8s.service.StackDefinitionService;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** How long Flux lets Helm install a GitOps release: the 15 minutes of a direct deploy unless the service asks more. */
public class FluxGitOpsBackendTimeoutTest {

    private static StackServiceDef serviceWith(Map<String, Object> gitops) {
        StackServiceDef def = new StackServiceDef();
        def.name = "SOMESERVICE";
        def.gitops = gitops;
        return def;
    }

    @Test
    public void aServiceWithoutSettingsGetsTheDirectDeployTimeout() {
        assertEquals("15m", FluxGitOpsBackend.helmTimeout(serviceWith(null)));
        assertEquals("15m", FluxGitOpsBackend.helmTimeout(serviceWith(Map.of())));
        assertEquals("15m", FluxGitOpsBackend.helmTimeout(null));
    }

    @Test
    public void aServiceCanSetItsOwnTimeout() {
        assertEquals("20m", FluxGitOpsBackend.helmTimeout(serviceWith(Map.of("timeout", "20m"))));
        assertEquals("1h30m", FluxGitOpsBackend.helmTimeout(serviceWith(Map.of("timeout", " 1h30m "))));
        assertEquals("600s", FluxGitOpsBackend.helmTimeout(serviceWith(Map.of("timeout", "600s"))));
    }

    @Test
    public void aTimeoutFluxWouldRejectIsIgnored() {
        for (Object invalid : new Object[] {"20 minutes", "20", "-5m", "m", "", 20}) {
            assertEquals(String.valueOf(invalid), "15m",
                    FluxGitOpsBackend.helmTimeout(serviceWith(Map.of("timeout", invalid))));
        }
    }

    @Test
    public void gitlabAsksForTwentyMinutes() {
        StackServiceDef gitlab = new StackDefinitionService().getServiceDefinition("GITLAB");

        assertEquals("20m", FluxGitOpsBackend.helmTimeout(gitlab));
    }

    @Test
    public void everyShippedServiceGetsATimeoutFluxAccepts() {
        for (String key : new String[] {"GITLAB", "OPENMETADATA", "SUPERSET", "TRINO"}) {
            String timeout = FluxGitOpsBackend.helmTimeout(new StackDefinitionService().getServiceDefinition(key));
            assertTrue(key + ": " + timeout, timeout.matches("([0-9]+(\\.[0-9]+)?(ms|s|m|h))+"));
        }
    }

    @Test
    public void theWaitForFluxCoversEveryInstallAttemptAndAMargin() {
        // 15m for the first attempt and 3 retries, 10 minutes more for Flux to fetch the sources.
        assertEquals(4 * 900 + 600, FluxGitOpsBackend.readyTimeoutSeconds(serviceWith(null), 1200));
        assertEquals(4 * 1200 + 600, FluxGitOpsBackend.readyTimeoutSeconds(serviceWith(Map.of("timeout", "20m")), 1200));
        assertEquals(4 * 5400 + 600, FluxGitOpsBackend.readyTimeoutSeconds(serviceWith(Map.of("timeout", "1h30m")), 1200));
        assertEquals(4 * 90 + 600, FluxGitOpsBackend.readyTimeoutSeconds(serviceWith(Map.of("timeout", "1.5m")), 0));
    }

    @Test
    public void theWaitForFluxIsNeverShorterThanItsFloor() {
        assertEquals(100000, FluxGitOpsBackend.readyTimeoutSeconds(serviceWith(null), 100000));
    }

    @Test
    public void gitlabWaitsLongerThanItsOwnTimeout() {
        StackServiceDef gitlab = new StackDefinitionService().getServiceDefinition("GITLAB");

        assertTrue(FluxGitOpsBackend.readyTimeoutSeconds(gitlab, 1200) > 20 * 60 * 4);
    }
}
