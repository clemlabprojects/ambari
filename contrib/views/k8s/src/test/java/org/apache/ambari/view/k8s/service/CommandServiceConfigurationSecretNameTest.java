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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.List;

import org.apache.ambari.view.k8s.model.stack.StackConfig;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * KDPS writes each service configuration to a Secret of the release before Helm runs. Superset's chart names its own
 * env Secret {@code <fullname>-env}, with {@code <fullname>} the release name when it contains "superset" and
 * {@code <release>-superset} otherwise: written as {@code <release>-superset-env}, KDPS's Secret took that name for a
 * release such as "ss484", and Helm refused to install it.
 */
class CommandServiceConfigurationSecretNameTest {

    private static StackConfig configuration(String path) throws Exception {
        try (InputStream in = CommandServiceConfigurationSecretNameTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the classpath");
            return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(in, StackConfig.class);
        }
    }

    /** The chart's superset.fullname (charts/superset/templates/_helpers.tpl), without a fullnameOverride. */
    private static String chartFullname(String release) {
        return release.contains("superset") ? release : release + "-superset";
    }

    @Test
    void supersetSettingsNeverTakeTheNameOfTheChartsEnvSecret() throws Exception {
        StackConfig env = configuration("/KDPS/services/SUPERSET/configurations/superset-env.json");

        for (String release : List.of("ss484", "bi", "superset484", "dashboarding-superset", "superset")) {
            String kdps = CommandService.configurationSecretName(release, env);
            assertNotEquals(chartFullname(release) + "-env", kdps, release);
            assertNotEquals(chartFullname(release) + "-config", kdps, release);
            assertNotEquals(chartFullname(release) + "-extra-config", kdps, release);
        }
        assertEquals("ss484-superset-settings", CommandService.configurationSecretName("ss484", env));
    }

    @Test
    void aConfigurationWithoutASuffixKeepsItsName() throws Exception {
        StackConfig files = configuration("/KDPS/services/SUPERSET/configurations/superset-files.json");

        assertEquals("ss484-superset-files", CommandService.configurationSecretName("ss484", files));
    }
}
