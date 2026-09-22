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

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A release that asks for Ranger access control without Ranger wiring cannot install: the chart
 * requires a service name that KDPS fills in only while planning the policy repository, which it
 * plans only when the deploy says which Ranger to use. Left alone, that surfaces a minute later as
 * a Helm template error naming a value the operator never saw. These tests pin the refusal — and,
 * just as importantly, pin that it stays out of the way of every release that is fine.
 */
class CommandServiceMissingWiringTest {

    private static HelmDeployRequest request(String accessControlType, String securityProfile, boolean withRangerSpec) {
        HelmDeployRequest r = new HelmDeployRequest();
        r.setReleaseName("trino-iceberg");
        r.setNamespace("trino-iceberg-ns");
        Map<String, Object> form = new LinkedHashMap<>();
        if (accessControlType != null) {
            Map<String, Object> ac = new LinkedHashMap<>();
            ac.put("type", accessControlType);
            form.put("accessControl", ac);
        }
        r.setFormValues(form);
        if (securityProfile != null) r.setSecurityProfile(securityProfile);
        if (withRangerSpec) {
            Map<String, Map<String, Object>> ranger = new HashMap<>();
            ranger.put("ranger-plugin-settings", Map.of("repository_name_property", "ranger.serviceName"));
            r.setRanger(ranger);
        }
        return r;
    }

    @Test
    void refusesRangerAccessControlWithNoWiringAndNoSecurityProfile() {
        String problem = CommandService.describeMissingWiring(request("ranger", null, false));
        assertNotNull(problem, "this is exactly the release that fails in the chart");
        assertTrue(problem.contains("no security profile is selected"), problem);
        assertTrue(problem.contains("ranger.serviceName"),
                "the operator should recognise the error they would otherwise have got: " + problem);
    }

    @Test
    void saysSomethingDifferentWhenAProfileIsSelectedButBringsNoRanger() {
        String problem = CommandService.describeMissingWiring(request("ranger", "keycloak", false));
        assertNotNull(problem);
        assertTrue(problem.contains("although a security profile is selected"),
                "a profile that carries no Ranger plugin settings is a different mistake: " + problem);
    }

    @Test
    void allowsRangerWhenTheDeployCarriesItsWiring() {
        // The case that must never be refused: 21 of the 22 past Ranger deploys look like this.
        assertNull(CommandService.describeMissingWiring(request("ranger", "keycloak", true)));
        assertNull(CommandService.describeMissingWiring(request("ranger", null, true)));
    }

    @Test
    void ignoresReleasesThatDoNotAskForRanger() {
        assertNull(CommandService.describeMissingWiring(request("allow-all", null, false)));
        assertNull(CommandService.describeMissingWiring(request(null, null, false)));
        assertNull(CommandService.describeMissingWiring(request("file", "keycloak", false)));
    }

    @Test
    void namesTheChartErrorTheOperatorWouldOtherwiseHaveSeen() {
        String problem = CommandService.describeMissingWiring(request("ranger", null, false));
        assertTrue(problem.contains("ranger.serviceName"), problem);
        assertTrue(problem.contains("set access control"), "it should say how to proceed without Ranger: " + problem);
    }

    @Test
    void survivesARequestWithNothingInIt() {
        assertNull(CommandService.describeMissingWiring(null));
        assertNull(CommandService.describeMissingWiring(new HelmDeployRequest()));
    }
}
