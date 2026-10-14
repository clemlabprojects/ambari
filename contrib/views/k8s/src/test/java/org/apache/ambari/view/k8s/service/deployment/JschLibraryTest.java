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

import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Git over SSH runs on the maintained JSch fork only. The original library uses the same package, and the Ambari view
 * class loader reads WEB-INF/lib in no fixed order, so a second copy on the class path could serve any JSch class from
 * the old jar: no Ed25519, no OpenSSH-format private keys and SHA-1 RSA signatures only.
 */
class JschLibraryTest {

    @Test
    void onlyOneCopyOfJschIsOnTheClassPath() throws Exception {
        List<URL> copies = Collections.list(
                JSch.class.getClassLoader().getResources("com/jcraft/jsch/JSch.class"));

        assertEquals(1, copies.size(), "JSch is found more than once: " + copies);
    }

    @Test
    void jschComesFromTheMaintainedFork() throws Exception {
        assertTrue(JSch.VERSION.startsWith("0.2."), "JSch version " + JSch.VERSION);

        // The jar that serves JSch must be the fork's, whatever the classes report about themselves.
        URL jschClass = JSch.class.getResource("JSch.class");
        assertNotNull(jschClass);
        String location = jschClass.toString();
        String jarRoot = location.substring(0, location.length() - "com/jcraft/jsch/JSch.class".length());
        List<String> forkMarkers = new ArrayList<>();
        for (URL marker : Collections.list(JSch.class.getClassLoader()
                .getResources("META-INF/maven/com.github.mwiede/jsch/pom.properties"))) {
            forkMarkers.add(marker.toString());
        }
        assertTrue(forkMarkers.stream().anyMatch(m -> m.startsWith(jarRoot)),
                "JSch is loaded from " + location + ", not from com.github.mwiede:jsch");
    }
}
