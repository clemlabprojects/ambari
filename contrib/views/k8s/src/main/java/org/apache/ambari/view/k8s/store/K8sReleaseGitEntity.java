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

package org.apache.ambari.view.k8s.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapsId;
import javax.persistence.OneToOne;
import javax.persistence.Table;

/**
 * GitOps metadata for Flux/Git-based deployments.
 * Uses release_id as the primary key to maintain a 1:1 relationship.
 */
@Entity
@Table(name = "k8s_release_git")
public class K8sReleaseGitEntity {

    @Id
    @Column(name = "release_id", length = 512)
    private String releaseId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "release_id")
    private K8sReleaseEntity release;

    @Column(name = "commit_sha", length = 128)
    private String commitSha;

    @Column(length = 256)
    private String branch;

    @Column(length = 512)
    private String path;

    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    @Column(name = "credential_alias", length = 256)
    private String credentialAlias;

    @Column(name = "commit_mode", length = 64)
    private String commitMode;

    @Column(name = "pr_url", length = 512)
    private String prUrl;

    @Column(name = "pr_number", length = 64)
    private String prNumber;

    @Column(name = "pr_state", length = 64)
    private String prState;

    public String getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(String releaseId) {
        this.releaseId = releaseId;
    }

    public K8sReleaseEntity getRelease() {
        return release;
    }

    public void setRelease(K8sReleaseEntity release) {
        this.release = release;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getCredentialAlias() {
        return credentialAlias;
    }

    public void setCredentialAlias(String credentialAlias) {
        this.credentialAlias = credentialAlias;
    }

    public String getCommitMode() {
        return commitMode;
    }

    public void setCommitMode(String commitMode) {
        this.commitMode = commitMode;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getPrNumber() {
        return prNumber;
    }

    public void setPrNumber(String prNumber) {
        this.prNumber = prNumber;
    }

    public String getPrState() {
        return prState;
    }

    public void setPrState(String prState) {
        this.prState = prState;
    }
}
