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

import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FluxGitOpsBackendAdditionalTest {

    @Test
    public void evaluateConditionsCapturesStatusAndSummary() {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        List<Map<String, Object>> conds = new ArrayList<>();
        conds.add(Map.of("type", "Ready", "status", "True", "message", "release ready", "reason", "Available"));
        conds.add(Map.of("type", "Progressing", "status", "True", "message", "still reconciling"));
        FluxGitOpsBackend.evaluateConditions(dto, conds);
        assertEquals("SUCCEEDED", dto.status);
        assertNotNull(dto.conditionSummary);
        assertTrue(dto.conditionSummary.contains("Ready=True"));
        assertTrue(dto.conditionSummary.contains("Progressing=True"));
        assertTrue(dto.message.contains("release ready"));
    }

    @Test
    public void evaluateGenerationStalenessFlagsProgressing() {
        HelmReleaseDTO dto = new HelmReleaseDTO();
        dto.status = "UNKNOWN";
        FluxGitOpsBackend.evaluateGenerationStaleness(dto, 5, 3);
        assertTrue(dto.staleGeneration);
        assertEquals("PROGRESSING", dto.status);
        assertTrue(dto.message.contains("Waiting for generation 5"));
    }

    @Test
    public void pullRequestParserParsesGitHubResponse() {
        String body = "{\"html_url\":\"https://github.com/owner/repo/pull/1\",\"state\":\"open\",\"number\":1,\"mergeable_state\":\"clean\"}";
        FluxGitOpsBackend.PullRequestInfo info = FluxGitOpsBackend.PullRequestParser.parseGithub(body, "1");
        assertEquals("open", info.state);
        assertEquals("https://github.com/owner/repo/pull/1", info.url);
        assertEquals("1", info.id);
    }

    @Test
    public void pullRequestParserParsesGitLabResponse() {
        String body = "{\"web_url\":\"https://gitlab.com/owner/repo/-/merge_requests/2\",\"state\":\"opened\",\"iid\":2}";
        FluxGitOpsBackend.PullRequestInfo info = FluxGitOpsBackend.PullRequestParser.parseGitlab(body, "2");
        assertEquals("opened", info.state);
        assertEquals("https://gitlab.com/owner/repo/-/merge_requests/2", info.url);
        assertEquals("2", info.id);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateRelativeRejectsTraversal() {
        FluxGitOpsBackend.validateRelative(Path.of("../outside"));
    }
}



