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
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards what the Ambari + KDPS image build takes from the network. A customer rebuilds that image from its own
 * mirrors, so every download has to be checked against a value shipped with the build context rather than one the
 * mirror serves, and the published context has to hold everything the build needs. It also guards that neither
 * Helm the image carries (the CLI and the library embedded in the view) is older than the Helm security fixes.
 */
public class AmbariImageSupplyChainTest {

    private static final Path IMAGE_DIR = Path.of("../../../dev-support/ambari-kdps-ubi9");

    /** Helm 3.18.5 is the first release with every fix the image must carry (chart loading, index parsing). */
    private static final int[] FIRST_PATCHED_HELM = {3, 18, 5};

    private static String dockerfile;
    private static String jenkinsfile;
    private static String buildScript;
    private static String readme;

    @BeforeAll
    static void readImageFiles() throws Exception {
        assumeTrue(Files.isRegularFile(IMAGE_DIR.resolve("Dockerfile")),
                "image build context not available from " + new File(".").getAbsolutePath());
        dockerfile = read("Dockerfile");
        jenkinsfile = read("Jenkinsfile");
        buildScript = read("build-image.sh");
        readme = read("README.md");
    }

    @Test
    void theHelmCliIsAPatchedReleaseEverywhereItIsNamed() {
        String version = group(dockerfile, "(?m)^ARG HELM_VERSION=(\\S+)$");
        assertTrue(atLeast(version, FIRST_PATCHED_HELM), "the image ships Helm " + version + ", older than 3.18.5");
        assertEquals(version, group(buildScript, "HELM_VERSION=\"\\$\\{HELM_VERSION:-([^}]+)\\}\""),
                "build-image.sh must default to the Dockerfile's Helm");
        assertEquals(version, group(jenkinsfile, "name: 'HELM_VERSION', defaultValue: '([^']+)'"),
                "the image pipeline must default to the Dockerfile's Helm");
        assertTrue(readme.contains("helm-v" + version + "-linux-amd64.tar.gz"),
                "the README's mirror example must name the Helm the image ships");
    }

    @Test
    void theHelmTarballIsCheckedAgainstPinnedChecksums() {
        for (String arch : new String[] {"AMD64", "ARM64"}) {
            String pinned = group(dockerfile, "(?m)^ARG HELM_SHA256_" + arch + "=(\\S*)$");
            assertTrue(pinned.matches("[0-9a-f]{64}"), "HELM_SHA256_" + arch + " must be pinned in the Dockerfile");
            assertEquals(pinned, group(jenkinsfile, "name: 'HELM_SHA256_" + arch + "', defaultValue: '([^']*)'"),
                    "the image pipeline must default to the Dockerfile's checksum for " + arch);
            assertTrue(jenkinsfile.contains("export HELM_SHA256_" + arch + "='${params.HELM_SHA256_" + arch + "}'"),
                    "the pipeline must hand HELM_SHA256_" + arch + " to build-image.sh");
            assertTrue(jenkinsfile.contains("\nHELM_SHA256_" + arch + "=${params.HELM_SHA256_" + arch + "}\n"),
                    "BUILD-INFO must record HELM_SHA256_" + arch);
            assertTrue(buildScript.contains("--build-arg HELM_SHA256_" + arch + "=${HELM_SHA256_" + arch + "}"),
                    "build-image.sh must pass HELM_SHA256_" + arch + " on to the build");
        }
        String helmStep = runStepContaining(dockerfile, "/tmp/helm.tgz");
        assertTrue(helmStep.contains("sha256sum -c"), "the Helm tarball must be checked before it is unpacked");
        assertTrue(helmStep.indexOf("sha256sum -c") < helmStep.indexOf("tar -xzf"), "checked before it is unpacked");
        assertTrue(helmStep.contains("test -n \"$sha\""), "an empty checksum must fail the build");
        assertFalse(helmStep.contains(".sha256sum"), "the checksum must not come from the server of the tarball");
    }

    @Test
    void theAmbariPackagesAreCheckedAgainstTheShippedSigningKey() throws Exception {
        assertFalse(dockerfile.contains("gpgcheck=0"), "the Ambari repository must not turn the signature check off");
        String installStep = runStepContaining(dockerfile, "ambari.repo");
        assertTrue(installStep.contains("gpgcheck=1"), "the Ambari repository must check signatures");
        assertTrue(installStep.contains("gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-clemlab"),
                "the key must be the one shipped with the build context, not one a mirror serves");
        assertTrue(installStep.indexOf("rpm --import") < installStep.indexOf("dnf install"),
                "the key must be imported before the package is installed");
        assertTrue(dockerfile.contains("COPY RPM-GPG-KEY-clemlab /etc/pki/rpm-gpg/RPM-GPG-KEY-clemlab"));
        String key = read("RPM-GPG-KEY-clemlab");
        assertTrue(key.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----")
                && key.trim().endsWith("-----END PGP PUBLIC KEY BLOCK-----"), "the shipped key must be a public key");
    }

    @Test
    void theDistroModuleComesFromTheBaseImageRepositoriesNotFromAPackageIndex() {
        assertTrue(runStepContaining(dockerfile, "dnf install").contains(" python3-distro "),
                "the server's distro module must be the UBI package");
        assertFalse(dockerfile.contains("pip3 install") || dockerfile.contains("pip install"),
                "nothing may be installed from a Python package index, which an air-gapped rebuild cannot reach");
    }

    @Test
    void thePublishedContextHoldsEveryFileTheBuildCopies() {
        String publish = jenkinsfile.substring(jenkinsfile.indexOf("stage('Publish build context')"));
        Matcher copy = Pattern.compile("(?m)^COPY (\\S+) ").matcher(dockerfile);
        List<String> sources = new ArrayList<>();
        while (copy.find()) {
            sources.add(copy.group(1));
        }
        assertTrue(sources.contains("RPM-GPG-KEY-clemlab"), "unexpected COPY list " + sources);
        for (String source : sources) {
            assertTrue(publish.contains("dev-support/ambari-kdps-ubi9/" + source + " "),
                    "the published build context must hold " + source + ", which the Dockerfile copies");
        }
    }

    @Test
    void theHelmLibraryEmbeddedInTheViewIsAPatchedRelease() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        Node property = pom.getElementsByTagName("helm-java.version").item(0);
        assertNotNull(property, "pom.xml must name the helm-java version once, in <helm-java.version>");

        URL so = null;
        for (String arch : new String[] {"amd64", "arm64"}) {
            so = so != null ? so : getClass().getClassLoader().getResource("helm-linux-" + arch + ".so");
        }
        assumeTrue(so != null, "no helm-java native library on the test classpath");
        String embedded = embeddedHelmVersion(so);
        assertNotNull(embedded, "no Helm module version found in " + so);
        assertTrue(atLeast(embedded, FIRST_PATCHED_HELM), "helm-java " + property.getTextContent()
                + " embeds Helm " + embedded + ", older than 3.18.5");
    }

    /** The Helm module version recorded in the Go build info of the native library. */
    private static String embeddedHelmVersion(URL library) throws Exception {
        Pattern dep = Pattern.compile("helm\\.sh/helm/v3\tv(\\d+\\.\\d+\\.\\d+)");
        byte[] buffer = new byte[1 << 20];
        String carry = "";
        try (InputStream in = library.openStream()) {
            for (int n; (n = in.readNBytes(buffer, 0, buffer.length)) > 0; ) {
                String window = carry + new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
                Matcher m = dep.matcher(window);
                if (m.find()) {
                    return m.group(1);
                }
                carry = window.substring(Math.max(0, window.length() - 64));
            }
        }
        return null;
    }

    /** The RUN instruction of the Dockerfile that contains the given text, with its continuation lines. */
    private static String runStepContaining(String file, String text) {
        for (String step : file.split("(?m)^(?=RUN |COPY |ENV |ARG |FROM |LABEL |USER |EXPOSE |ENTRYPOINT )")) {
            if (step.startsWith("RUN ") && step.contains(text)) {
                return step;
            }
        }
        throw new AssertionError("no RUN step of the Dockerfile contains " + text);
    }

    private static boolean atLeast(String version, int[] minimum) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < minimum.length; i++) {
            int part = Integer.parseInt(parts[i]);
            if (part != minimum[i]) {
                return part > minimum[i];
            }
        }
        return true;
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "pattern not found: " + regex);
        return m.group(1);
    }

    private static String read(String name) throws Exception {
        return Files.readString(IMAGE_DIR.resolve(name), StandardCharsets.UTF_8);
    }
}
