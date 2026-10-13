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
package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;

/** Registry rewrite applied to the image paths a service definition lists in imageRegistryPaths (AMBARI-690). */
public class CommandServiceImageRegistryTest {

    private static final String MIRROR = "mirror.client.local/clemlab";

    @Test
    void replacesTheClemlabDefaultPrefixAsAWhole() {
        assertEquals(MIRROR + "/jupyterhub/hub",
                ImageRegistryOverrides.rewriteImageRegistry("registry.clemlab.com/clemlabprojects/jupyterhub/hub", MIRROR));
        assertEquals(MIRROR + "/jupyterhub/krb-renewer:1.0.5",
                ImageRegistryOverrides.rewriteImageRegistry("registry.clemlab.com/clemlabprojects/jupyterhub/krb-renewer:1.0.5", MIRROR));
    }

    @Test
    void replacesUpstreamHostsAndKeepsTheRepositoryPath() {
        assertEquals(MIRROR + "/jupyterhub/configurable-http-proxy",
                ImageRegistryOverrides.rewriteImageRegistry("quay.io/jupyterhub/configurable-http-proxy", MIRROR));
        assertEquals(MIRROR + "/pause:3.10.1",
                ImageRegistryOverrides.rewriteImageRegistry("registry.k8s.io/pause:3.10.1", MIRROR));
        assertEquals(MIRROR + "/kube-scheduler@sha256:abc",
                ImageRegistryOverrides.rewriteImageRegistry("registry.k8s.io/kube-scheduler@sha256:abc", MIRROR));
        assertEquals(MIRROR + "/busybox:1.36",
                ImageRegistryOverrides.rewriteImageRegistry("docker.io/library/busybox:1.36", MIRROR));
        assertEquals(MIRROR + "/busybox",
                ImageRegistryOverrides.rewriteImageRegistry("busybox", MIRROR));
        assertEquals(MIRROR + "/bitnami/postgresql:18",
                ImageRegistryOverrides.rewriteImageRegistry("localhost:5000/bitnami/postgresql:18", MIRROR));
    }

    @Test
    void leavesValuesAlreadyOnTheTargetAndEmptyInputsAlone() {
        assertEquals(MIRROR + "/jupyterhub/hub:1.0.0",
                ImageRegistryOverrides.rewriteImageRegistry(MIRROR + "/jupyterhub/hub:1.0.0", MIRROR));
        assertEquals("registry.clemlab.com/clemlabprojects/jupyterhub/hub",
                ImageRegistryOverrides.rewriteImageRegistry("registry.clemlab.com/clemlabprojects/jupyterhub/hub", "registry.clemlab.com/clemlabprojects/"));
        assertEquals("quay.io/jupyterhub/hub", ImageRegistryOverrides.rewriteImageRegistry("quay.io/jupyterhub/hub", ""));
        assertNull(ImageRegistryOverrides.rewriteImageRegistry(null, MIRROR));
        assertEquals("  ", ImageRegistryOverrides.rewriteImageRegistry("  ", MIRROR));
    }

    @Test
    void hostOnlyRegistryKeepsTheClemlabProjectOutOfThePath() {
        assertEquals("harbor.local/jupyterhub/hub",
                ImageRegistryOverrides.rewriteImageRegistry("registry.clemlab.com/clemlabprojects/jupyterhub/hub", "harbor.local"));
    }

    @Test
    void parsesPathEntriesWithAndWithoutDefaults() {
        assertArrayEquals(new String[] {"hub.image.name", null}, ImageRegistryOverrides.imageRegistryEntry(" hub.image.name "));
        assertArrayEquals(new String[] {"proxy.chp.image.name", "registry.clemlab.com/clemlabprojects/jupyterhub/configurable-http-proxy"},
                ImageRegistryOverrides.imageRegistryEntry(Map.of("path", "proxy.chp.image.name", "default", "registry.clemlab.com/clemlabprojects/jupyterhub/configurable-http-proxy")));
        assertArrayEquals(new String[] {"a.b", null}, ImageRegistryOverrides.imageRegistryEntry(Map.of("path", "a.b", "default", " ")));
        assertNull(ImageRegistryOverrides.imageRegistryEntry(Map.of("default", "x")));
        assertNull(ImageRegistryOverrides.imageRegistryEntry(""));
        assertNull(ImageRegistryOverrides.imageRegistryEntry(42));
    }
}
