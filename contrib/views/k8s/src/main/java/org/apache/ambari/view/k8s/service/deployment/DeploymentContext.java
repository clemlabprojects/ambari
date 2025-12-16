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
package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.utils.AmbariAliasResolver;

import javax.ws.rs.core.MultivaluedMap;
import java.net.URI;

/**
 * Immutable context passed to deployment backends so they have access
 * to caller headers, repo/version hints, and Ambari helpers without
 * relying on thread-local state.
 */
public class DeploymentContext {
    private final String repoId;
    private final String version;
    private final String kubeContext;
    private final String commandsUrl;
    private final MultivaluedMap<String, String> callerHeaders;
    private final URI baseUri;
    private final AmbariAliasResolver ambariAliasResolver;

    public DeploymentContext(String repoId,
                             String version,
                             String kubeContext,
                             String commandsUrl,
                             MultivaluedMap<String, String> callerHeaders,
                             URI baseUri,
                             AmbariAliasResolver ambariAliasResolver) {
        this.repoId = repoId;
        this.version = version;
        this.kubeContext = kubeContext;
        this.commandsUrl = commandsUrl;
        this.callerHeaders = callerHeaders;
        this.baseUri = baseUri;
        this.ambariAliasResolver = ambariAliasResolver;
    }

    public String getRepoId() {
        return repoId;
    }

    public String getVersion() {
        return version;
    }

    public String getKubeContext() {
        return kubeContext;
    }

    public String getCommandsUrl() {
        return commandsUrl;
    }

    public MultivaluedMap<String, String> getCallerHeaders() {
        return callerHeaders;
    }

    public URI getBaseUri() {
        return baseUri;
    }

    public AmbariAliasResolver getAmbariAliasResolver() {
        return ambariAliasResolver;
    }
}
