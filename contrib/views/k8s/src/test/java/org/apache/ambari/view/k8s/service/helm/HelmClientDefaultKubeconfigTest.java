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

package org.apache.ambari.view.k8s.service.helm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The helm binary runs the credential plugin of a kubeconfig's user, so it is never given one. */
class HelmClientDefaultKubeconfigTest {

    @Test
    void theKubeconfigFileGivenToTheHelmBinaryHasNoCredentialCommand() throws Exception {
        String kubeconfig = "apiVersion: v1\nkind: Config\ncurrent-context: a\n"
                + "clusters:\n- name: a\n  cluster: {server: 'https://a:6443'}\n"
                + "contexts:\n- name: a\n  context: {cluster: a, user: u}\n"
                + "users:\n- name: u\n  user:\n    token: t\n    exec: {command: /bin/sh, args: [-c, id]}\n"
                + "- name: g\n  user:\n    auth-provider: {name: gcp, config: {cmd-path: /bin/sh}}\n";

        Path file = HelmClientDefault.writeKubeconfigToTempFile(kubeconfig);
        try {
            String written = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(written.contains("exec"), written);
            assertFalse(written.contains("auth-provider"), written);
            assertFalse(written.contains("/bin/sh"), written);
            assertTrue(written.contains("https://a:6443"), "the rest is kept: " + written);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aCommandHiddenBehindAMergeKeyIsNotHandedToTheHelmBinary() throws Exception {
        String crafted = "x-hidden: &e {exec: {command: /bin/sh}}\nkind: Config\nusers:\n- name: u\n  user: {<<: *e}\n";

        Path file = HelmClientDefault.writeKubeconfigToTempFile(crafted);
        try {
            String written = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(written.contains("/bin/sh") || written.contains("<<") || written.contains("x-hidden"), written);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Uploads that make the helm binary create {@code marker}: a plain one, and each way of hiding the command. */
    private static java.util.Map<String, String> craftedKubeconfigs(Path marker) {
        String exec = "{exec: {apiVersion: client.authentication.k8s.io/v1, command: /bin/sh, "
                + "args: ['-c', 'touch " + marker + "'], interactiveMode: Never}}";
        String head = "apiVersion: v1\nkind: Config\ncurrent-context: a\n"
                + "clusters:\n- name: a\n  cluster: {server: 'https://127.0.0.1:1', insecure-skip-tls-verify: true}\n"
                + "contexts:\n- name: a\n  context: {cluster: a, user: u}\n";
        java.util.Map<String, String> crafted = new java.util.LinkedHashMap<>();
        crafted.put("exec", head + "users:\n- name: u\n  user: " + exec + "\n");
        // "dXNlcnM=" and "dXNlcg==" are base64 for "users" and "user", which Go's YAML decodes in a key.
        crafted.put("tagged users", head + "!!binary dXNlcnM=:\n- name: u\n  user: " + exec + "\n");
        crafted.put("tagged user", head + "users:\n- name: u\n  !!binary dXNlcg==: " + exec + "\n");
        crafted.put("merge key", "x-hidden: &e " + exec + "\n" + head + "users:\n- name: u\n  user: {<<: *e}\n");
        crafted.put("alias", "x-hidden: &e " + exec + "\n" + head + "users:\n- name: u\n  user: *e\n");
        return crafted;
    }

    private static boolean helmIsInstalled() {
        try {
            return new ProcessBuilder("helm", "version", "--short").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void helmList(Path kubeconfig) throws Exception {
        Process helm = new ProcessBuilder("helm", "list", "--kubeconfig", kubeconfig.toString())
            .redirectErrorStream(true).start();
        helm.getInputStream().readAllBytes();
        assertTrue(helm.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "helm finished");
    }

    @Test
    void theHelmBinaryRunsTheCommandOfEveryCraftedUploadItself(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(helmIsInstalled(), "helm is installed");
        Path marker = dir.resolve("ran");
        for (java.util.Map.Entry<String, String> crafted : craftedKubeconfigs(marker).entrySet()) {
            Path raw = dir.resolve("raw.yaml");
            Files.writeString(raw, crafted.getValue(), StandardCharsets.UTF_8);
            helmList(raw);
            assertTrue(Files.deleteIfExists(marker), "the premise: helm runs the command of the " + crafted.getKey() + " upload");
        }
    }

    @Test
    void theHelmBinaryRunsNoCommandFromTheKubeconfigKdpsWrites(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(helmIsInstalled(), "helm is installed");
        Path marker = dir.resolve("ran");
        for (java.util.Map.Entry<String, String> crafted : craftedKubeconfigs(marker).entrySet()) {
            Path file = HelmClientDefault.writeKubeconfigToTempFile(crafted.getValue());
            try {
                helmList(file);
                assertFalse(Files.exists(marker), "helm ran the command of the " + crafted.getKey() + " upload: "
                        + Files.readString(file, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void aTabIndentedJsonKubeconfigIsHandedToTheHelmBinary() throws Exception {
        String json = "{\n\t\"kind\": \"Config\",\n\t\"clusters\": [{\"name\": \"a\", \"cluster\": {\"server\": \"https://a:6443\"}}],\n"
                + "\t\"users\": [{\"name\": \"u\", \"user\": {\"token\": \"t\"}}]\n}\n";

        Path file = HelmClientDefault.writeKubeconfigToTempFile(json);
        try {
            var written = org.apache.ambari.view.k8s.utils.KubeconfigGuard.parse(Files.readString(file, StandardCharsets.UTF_8));
            assertEquals("https://a:6443", written.getClusters().get(0).getCluster().getServer());
            assertEquals("t", written.getUsers().get(0).getUser().getToken());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
