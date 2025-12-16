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

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.beans.SimpleBeanInfo;

/**
 * Custom BeanInfo to explicitly control which properties Ambari's DataStore sees.
 *
 * Ambari's DataStore relies on java.beans.Introspector and counts each String
 * property as a 3000-char column when enforcing the 65k-per-entity limit.
 * Without a BeanInfo, even @Transient getters (createdAt/updatedAt/endpointsJson)
 * would be counted, pushing us over the limit. This BeanInfo keeps only the
 * persisted, short String properties and the non-string endpoints collection.
 */
public class K8sReleaseEntityBeanInfo extends SimpleBeanInfo {

    @Override
    public PropertyDescriptor[] getPropertyDescriptors() {
        try {
            return new PropertyDescriptor[]{
                    new PropertyDescriptor("id", K8sReleaseEntity.class, "getId", "setId"),
                    new PropertyDescriptor("namespace", K8sReleaseEntity.class, "getNamespace", "setNamespace"),
                    new PropertyDescriptor("releaseName", K8sReleaseEntity.class, "getReleaseName", "setReleaseName"),
                    new PropertyDescriptor("serviceKey", K8sReleaseEntity.class, "getServiceKey", "setServiceKey"),
                    new PropertyDescriptor("chartRef", K8sReleaseEntity.class, "getChartRef", "setChartRef"),
                    new PropertyDescriptor("repoId", K8sReleaseEntity.class, "getRepoId", "setRepoId"),
                    new PropertyDescriptor("version", K8sReleaseEntity.class, "getVersion", "setVersion"),
                    new PropertyDescriptor("deploymentId", K8sReleaseEntity.class, "getDeploymentId", "setDeploymentId"),
                    new PropertyDescriptor("deploymentMode", K8sReleaseEntity.class, "getDeploymentMode", "setDeploymentMode"),
                    new PropertyDescriptor("globalConfigVersion", K8sReleaseEntity.class, "getGlobalConfigVersion", "setGlobalConfigVersion"),
                    new PropertyDescriptor("securityProfile", K8sReleaseEntity.class, "getSecurityProfile", "setSecurityProfile"),
                    new PropertyDescriptor("securityProfileHash", K8sReleaseEntity.class, "getSecurityProfileHash", "setSecurityProfileHash"),
                    new PropertyDescriptor("gitCommitSha", K8sReleaseEntity.class, "getGitCommitSha", "setGitCommitSha"),
                    new PropertyDescriptor("gitBranch", K8sReleaseEntity.class, "getGitBranch", "setGitBranch"),
                    new PropertyDescriptor("gitRepoUrl", K8sReleaseEntity.class, "getGitRepoUrl", "setGitRepoUrl"),
                    new PropertyDescriptor("gitPath", K8sReleaseEntity.class, "getGitPath", "setGitPath"),
                    new PropertyDescriptor("gitCredentialAlias", K8sReleaseEntity.class, "getGitCredentialAlias", "setGitCredentialAlias"),
                    new PropertyDescriptor("gitCommitMode", K8sReleaseEntity.class, "getGitCommitMode", "setGitCommitMode"),
                    new PropertyDescriptor("gitPrUrl", K8sReleaseEntity.class, "getGitPrUrl", "setGitPrUrl"),
                    new PropertyDescriptor("gitPrNumber", K8sReleaseEntity.class, "getGitPrNumber", "setGitPrNumber"),
                    new PropertyDescriptor("gitPrState", K8sReleaseEntity.class, "getGitPrState", "setGitPrState"),
                    new PropertyDescriptor("managedByUi", K8sReleaseEntity.class, "isManagedByUi", "setManagedByUi")
            };
        } catch (IntrospectionException e) {
            // In practice this should never happen; fail fast to signal configuration issues.
            throw new RuntimeException("Failed to build K8sReleaseEntityBeanInfo", e);
        }
    }
}
