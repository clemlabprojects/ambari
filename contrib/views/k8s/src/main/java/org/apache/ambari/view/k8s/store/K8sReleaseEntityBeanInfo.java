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
 *
 * HARD CEILING: 3000 chars per String property against a 65000 total means at most
 * **21 String properties** for this entity (21 x 3000 = 63000; a 22nd = 66000 and the
 * DataStore refuses to initialise at all — every view endpoint then 500s with
 * "Can't initialize data store", and saved contexts/kubeconfig merely LOOK lost).
 * @Column(length=...) is ignored; the DataStore forces 3000, so shortening a column buys
 * nothing — only removing a property does.
 *
 * This entity previously sat at exactly 21. The nine flat git columns (gitCommitSha,
 * gitBranch, gitRepoUrl, gitPath, gitCredentialAlias, gitCommitMode, gitPrUrl, gitPrNumber,
 * gitPrState) were folded into the single `gitMetaJson` column — they are FLUX_GITOPS-only
 * metadata and the entity keeps all nine getters/setters, backed by that JSON, so no call
 * site changed. That freed 8 slots, leaving room for `platformContextId` while KEEPING
 * `deploymentId` (the only queryable release -> deployment trace).
 * Current count: 14 String properties = 42000, i.e. 7 slots of headroom.
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
                    new PropertyDescriptor("platformContextId", K8sReleaseEntity.class, "getPlatformContextId", "setPlatformContextId"),
                    new PropertyDescriptor("chartRef", K8sReleaseEntity.class, "getChartRef", "setChartRef"),
                    new PropertyDescriptor("repoId", K8sReleaseEntity.class, "getRepoId", "setRepoId"),
                    new PropertyDescriptor("version", K8sReleaseEntity.class, "getVersion", "setVersion"),
                    new PropertyDescriptor("deploymentId", K8sReleaseEntity.class, "getDeploymentId", "setDeploymentId"),
                    new PropertyDescriptor("deploymentMode", K8sReleaseEntity.class, "getDeploymentMode", "setDeploymentMode"),
                    new PropertyDescriptor("globalConfigVersion", K8sReleaseEntity.class, "getGlobalConfigVersion", "setGlobalConfigVersion"),
                    new PropertyDescriptor("securityProfile", K8sReleaseEntity.class, "getSecurityProfile", "setSecurityProfile"),
                    new PropertyDescriptor("securityProfileHash", K8sReleaseEntity.class, "getSecurityProfileHash", "setSecurityProfileHash"),
                    new PropertyDescriptor("gitMetaJson", K8sReleaseEntity.class, "getGitMetaJson", "setGitMetaJson"),
                    new PropertyDescriptor("managedByUi", K8sReleaseEntity.class, "isManagedByUi", "setManagedByUi")
            };
        } catch (IntrospectionException e) {
            // In practice this should never happen; fail fast to signal configuration issues.
            throw new RuntimeException("Failed to build K8sReleaseEntityBeanInfo", e);
        }
    }
}
