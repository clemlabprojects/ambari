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
package org.apache.ambari.view.k8s.service.helm;

import com.marcnuri.helm.jni.NativeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * KDPS {@link NativeLibrary} provider that extracts the helm-java native library into the view's
 * WORKING DIRECTORY instead of {@code java.io.tmpdir}.
 *
 * <p>Why: helm-java's default provider extracts {@code helm-linux-<arch>.so} under
 * {@code java.io.tmpdir} (usually {@code /tmp}) and {@code dlopen}s it there. On hardened hosts
 * {@code /tmp} is mounted {@code noexec}, so the load fails with
 * {@code UnsatisfiedLinkError: failed to map segment from shared object} — and because it fails in
 * {@code Helm$HelmLibHolder}'s static initializer, the class is poisoned for the JVM lifetime
 * ({@code NoClassDefFoundError: Could not initialize class}). The view working dir lives under
 * {@code /var/lib/ambari-server}, which is executable on standard installs.
 *
 * <p>How it wins: registered in this jar's {@code META-INF/services}. The Ambari view classloader
 * lists the exploded view root (classes) before {@code WEB-INF/lib} jars, so
 * {@code ServiceLoader} iterates this provider before the arch provider bundled in the
 * helm-java native jar — {@code NativeLibrary.getInstance()} takes the first one. The binary name
 * (and thus the {@code .so} resource) is still delegated to the real arch provider; only
 * {@link #createTempDirectory()} is overridden.
 *
 * <p>The extraction base dir is injected by {@code ViewConfigurationService} at view init via
 * {@link #setExtractionBaseDir(Path)}. Until (or if ever) set, behavior falls back to the stock
 * {@code java.io.tmpdir} extraction, so nothing regresses when the working dir is unavailable.
 */
public class KdpsHelmNativeLibrary implements NativeLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(KdpsHelmNativeLibrary.class);

    private static volatile Path extractionBaseDir;
    private static volatile boolean loggedChoice;

    private final NativeLibrary delegate;

    public KdpsHelmNativeLibrary() {
        this.delegate = resolveArchProvider();
    }

    /** Inject the exec-safe base dir (view working dir); idempotent, first caller wins per value. */
    public static void setExtractionBaseDir(Path baseDir) {
        extractionBaseDir = baseDir;
    }

    /**
     * The real per-arch provider bundled in the helm-java native jar — resolved by {@code os.arch}
     * instead of ServiceLoader order, so a jar that accidentally bundles BOTH arch jars still picks
     * the right binary deterministically.
     */
    private static NativeLibrary resolveArchProvider() {
        String arch = System.getProperty("os.arch", "");
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        String cls = arm
                ? "com.marcnuri.helm.jni.linux.arm64.LinuxArm64NativeLibrary"
                : "com.marcnuri.helm.jni.linux.amd64.LinuxAmd64NativeLibrary";
        try {
            return (NativeLibrary) Class.forName(cls).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("helm-java native provider for os.arch=" + arch
                    + " not on the classpath (" + cls + ") — was the view built for the right architecture"
                    + " (-Dambari.grafana.arch)?", e);
        }
    }

    @Override
    public String getBinaryName() {
        return delegate.getBinaryName();
    }

    @Override
    public Path createTempDirectory() {
        Path base = extractionBaseDir;
        if (base != null) {
            try {
                Files.createDirectories(base);
                Path dir = Files.createTempDirectory(base, "helm-java");
                if (!loggedChoice) {
                    loggedChoice = true;
                    LOG.info("helm-java native library ({}) extracting under the view working dir: {}",
                            getBinaryName(), dir);
                }
                return dir;
            } catch (Exception e) {
                LOG.error("Could not create helm-java extraction dir under {} ({}); falling back to java.io.tmpdir."
                        + " If java.io.tmpdir is mounted noexec the helm client will fail to load —"
                        + " set server.tmp.dir in ambari.properties to an exec-permitted directory.",
                        base, e.toString());
            }
        } else if (!loggedChoice) {
            loggedChoice = true;
            LOG.warn("helm-java extraction base dir not injected yet; using java.io.tmpdir ({})."
                    + " On hosts with a noexec /tmp the helm client cannot load from there.",
                    System.getProperty("java.io.tmpdir"));
        }
        return NativeLibrary.super.createTempDirectory();
    }
}
