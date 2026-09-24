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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A release that asks for Ranger access control without Ranger wiring cannot install: the chart
 * requires a service name that KDPS fills in only while planning the policy repository, which it
 * plans only when the deploy carries the service definition's Ranger block. Left alone, that
 * surfaces a minute later as a Helm template error naming a value the operator never saw. These
 * tests pin the refusal, pin that it stays out of the way of every release that is fine, and pin
 * that the security profile plays no part in it.
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
    void refusesRangerAccessControlWithNoWiring() {
        String problem = CommandService.describeMissingWiring(request("ranger", null, false));
        assertNotNull(problem, "this is exactly the release that fails in the chart");
        assertTrue(problem.contains("ranger.serviceName"),
                "the operator should recognise the error they would otherwise have got: " + problem);
    }

    @Test
    void pointsAtThePlatformContextAndTheServiceDefinition() {
        String problem = CommandService.describeMissingWiring(request("ranger", null, false));
        assertTrue(problem.contains("platform context"),
                "the Ranger block rides on the context, so that is where to send the operator: " + problem);
        assertTrue(problem.contains("service definition"),
                "the other real cause is a chart whose definition declares no Ranger block: " + problem);
    }

    @Test
    void theSecurityProfileMakesNoDifference() {
        String withProfile = CommandService.describeMissingWiring(request("ranger", "keycloak", false));
        String withoutProfile = CommandService.describeMissingWiring(request("ranger", null, false));
        assertEquals(withoutProfile, withProfile,
                "the profile is irrelevant to Ranger wiring and must not steer the operator");
        assertFalse(withProfile.contains("security profile"),
                "the message must not mention the profile at all: " + withProfile);
    }

    @Test
    void allowsRangerWhenTheDeployCarriesItsWiring() {
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
