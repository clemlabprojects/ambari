/*
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
package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What a GitOps deploy does in Git, for the operator. The deploy's answer carries it with the mode and the branch the
 * request names; the commit is made later by a step of the operation, whose result then carries it in full under
 * {@code gitOps}: the commit, the branch it was pushed to, the pull request and whether Git already held the change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitOpsDeployResult {

    /** The key of the result in the deploy's answer and in the operation's result. */
    public static final String KEY = "gitOps";

    /** Always {@code FLUX_GITOPS}. */
    @JsonProperty("mode")
    public String mode = DeploymentMode.FLUX_GITOPS.name();

    /** The commit holding the change; the commit Git already held when nothing was committed. */
    @JsonProperty("commit")
    public String commit;

    /** The branch the change was pushed to: the branch Flux reconciles, or the pull request's. */
    @JsonProperty("branch")
    public String branch;

    /** The pull request of the change, when one was opened. */
    @JsonProperty("prUrl")
    public String prUrl;

    /** The state of that pull request, when known (open, merged, closed). */
    @JsonProperty("prState")
    public String prState;

    /** Whether Git already held the change, so nothing was committed; {@code null} before the commit step ran. */
    @JsonProperty("noChanges")
    public Boolean noChanges;
}
