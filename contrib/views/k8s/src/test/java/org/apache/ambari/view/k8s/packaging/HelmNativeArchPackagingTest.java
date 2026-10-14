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
package org.apache.ambari.view.k8s.packaging;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards that the view ships the Helm native library of the architecture it is built on. The view calls Helm
 * in-process through a per-architecture jar; a view packaged with the other one still serves every Kubernetes
 * page and then fails each Helm action, so the build and the image check must not let it through.
 */
public class HelmNativeArchPackagingTest {

    private static final File SCRIPT = new File("../../../dev-support/ambari-kdps-ubi9/check-helm-native-arch.py");

    @Test
    void theHelmNativeArchFollowsTheBuildHostArchitecture() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        String[][] expected = {{"arch-amd64", "amd64", "amd64"}, {"arch-x86_64", "x86_64", "amd64"},
                {"arch-aarch64", "aarch64", "arm64"}, {"arch-arm64", "arm64", "arm64"}};
        for (String[] e : expected) {
            Element profile = profile(pom, e[0]);
            assertEquals(e[1], descendantText(profile, "value"), e[0] + " must activate on os.arch=" + e[1]);
            assertEquals(e[2], descendantText(profile, "ambari.grafana.arch"),
                    e[0] + " must package the " + e[2] + " Helm native library");
        }
        // The fallback for any other architecture matches ambari.arch and the rest of the build: x86.
        assertEquals("amd64", childText(propertiesOf(pom.getDocumentElement()), "ambari.grafana.arch"));
    }

    @Test
    void theBuildFailsWhenTheNativeJarIsMissing() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        Element step = null;
        NodeList executions = pom.getElementsByTagName("execution");
        for (int i = 0; i < executions.getLength(); i++) {
            Element execution = (Element) executions.item(i);
            if ("enforce-single-helm-native-arch".equals(childText(execution, "id"))) {
                step = execution;
            }
        }
        assertNotNull(step, "enforce-single-helm-native-arch execution not found");
        assertTrue(step.getElementsByTagName("available").getLength() >= 1, "the step must check the native jar is present");
        assertTrue(step.getElementsByTagName("fail").getLength() >= 1, "the step must fail the build when it is not");
    }

    @Test
    void theImageCheckAcceptsOnlyTheJarOfTheWantedArchitecture(@TempDir Path tmp) throws Exception {
        assumeTrue(SCRIPT.isFile(), "image check script not available from " + new File(".").getAbsolutePath());
        assumeTrue(run("python3", "--version") == 0, "python3 not available");

        File amd64 = viewJar(tmp, "amd64.jar", "WEB-INF/lib/linux-amd64-0.0.15.jar", "WEB-INF/lib/fabric8.jar");
        File arm64 = viewJar(tmp, "arm64.jar", "WEB-INF/lib/linux-arm64-0.0.15.jar");
        File both = viewJar(tmp, "both.jar", "WEB-INF/lib/linux-arm64-0.0.15.jar", "WEB-INF/lib/linux-amd64-0.0.15.jar");
        File none = viewJar(tmp, "none.jar", "WEB-INF/lib/fabric8.jar");
        File darwin = viewJar(tmp, "darwin.jar", "WEB-INF/lib/linux-amd64-0.0.15.jar", "WEB-INF/lib/darwin-arm64-0.0.15.jar");

        assertEquals(0, check("amd64", amd64));
        assertEquals(0, check("arm64", arm64));
        assertEquals(0, check("amd64", amd64, amd64));
        assertNotEquals(0, check("arm64", amd64), "an amd64 view must not pass on arm64");
        assertNotEquals(0, check("amd64", arm64), "an arm64 view must not pass on amd64");
        assertNotEquals(0, check("amd64", both), "two native jars must not pass");
        assertNotEquals(0, check("amd64", none), "a view without a native jar must not pass");
        assertNotEquals(0, check("amd64", darwin), "a stray native jar of another platform must not pass");
        assertNotEquals(0, check("amd64", amd64, arm64), "every jar given must pass");
        assertNotEquals(0, check("amd64", tmp.resolve("missing.jar").toFile()), "an unreadable jar must not pass");
    }

    private static int check(String arch, File... jars) throws Exception {
        String[] cmd = new String[4 + jars.length];
        cmd[0] = "python3";
        cmd[1] = SCRIPT.getPath();
        cmd[2] = "--arch";
        cmd[3] = arch;
        for (int i = 0; i < jars.length; i++) {
            cmd[4 + i] = jars[i].getPath();
        }
        return run(cmd);
    }

    private static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + String.join(" ", cmd));
        }
        return p.exitValue();
    }

    private static File viewJar(Path dir, String name, String... entries) throws Exception {
        File jar = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Element profile(Document pom, String id) {
        NodeList profiles = pom.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (id.equals(childText(profile, "id"))) {
                return profile;
            }
        }
        throw new AssertionError("profile " + id + " not found in pom.xml");
    }

    private static Element propertiesOf(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && "properties".equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        throw new AssertionError("no <properties> in <" + parent.getNodeName() + ">");
    }

    private static String descendantText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        assertEquals(1, nodes.getLength(), "exactly one <" + tag + "> expected in profile " + childText(parent, "id"));
        return nodes.item(0).getTextContent().trim();
    }

    private static String childText(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && tag.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }
}
