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

package org.apache.ambari.view.k8s.service.gitops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Fetches a chart package as its repository publishes it, for a GitOps profile that copies the complete chart into
 * the Git repository.
 *
 * <p>Helm itself downloads it: a throw-away chart naming it as its only dependency gets {@code helm dependency
 * update}, which reaches the repository the way installs do (an OCI registry with the login KDPS made, or an HTTP
 * repository). The package is then read into memory, its files keyed by their path inside the chart.</p>
 */
public class ChartFetcher {

    /** Longest chart package read; published charts are far smaller. */
    static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;

    private final Consumer<Path> dependencyUpdate;

    /**
     * @param dependencyUpdate runs {@code helm dependency update} in a chart directory
     */
    public ChartFetcher(Consumer<Path> dependencyUpdate) {
        this.dependencyUpdate = Objects.requireNonNull(dependencyUpdate, "dependencyUpdate");
    }

    /**
     * The files of a published chart.
     *
     * @param repositoryUrl the repository as Helm names a dependency's: {@code oci://host/path} or an HTTP(S) URL
     * @param chart         the chart's name in the repository
     * @param version       its version
     * @param workDir       an empty directory to work in
     * @return the chart's files by path inside the chart ({@code Chart.yaml}, {@code templates/...},
     *         {@code charts/<dependency>.tgz}...)
     * @throws IllegalStateException when the chart cannot be downloaded or read
     */
    public Map<String, byte[]> fetch(String repositoryUrl, String chart, String version, Path workDir) {
        try {
            Path wrapper = Files.createDirectories(workDir.resolve("fetch"));
            Files.writeString(wrapper.resolve("Chart.yaml"), "apiVersion: v2\nname: kdps-chart-fetch\nversion: 0.0.0\n"
                    + "dependencies:\n  - name: " + quoted(chart) + "\n    version: " + quoted(version)
                    + "\n    repository: " + quoted(repositoryUrl) + "\n", StandardCharsets.UTF_8);
            try {
                dependencyUpdate.accept(wrapper);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Chart " + chart + " " + version + " could not be downloaded from "
                        + repositoryUrl + ": " + e.getMessage(), e);
            }
            Path packageFile;
            try (Stream<Path> files = Files.list(wrapper.resolve("charts"))) {
                List<Path> packages = files.filter(f -> f.getFileName().toString().endsWith(".tgz")).toList();
                if (packages.size() != 1) {
                    throw new IllegalStateException("Chart " + chart + " " + version + " was not downloaded from "
                            + repositoryUrl + ".");
                }
                packageFile = packages.get(0);
            }
            if (Files.size(packageFile) > MAX_PACKAGE_BYTES) {
                throw new IllegalStateException("Chart " + chart + " " + version + " is larger than "
                        + MAX_PACKAGE_BYTES / (1024 * 1024) + " MiB.");
            }
            try (InputStream in = Files.newInputStream(packageFile)) {
                return chartFiles(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Chart " + chart + " " + version + " could not be read: " + e.getMessage(), e);
        }
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The regular files of a chart package (a gzipped tar whose entries all sit under the chart's directory), by path
     * inside the chart. Links and anything else that is not a regular file are left out; a path leaving the chart
     * directory is refused.
     */
    static Map<String, byte[]> chartFiles(InputStream gzipped) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream tar = new GZIPInputStream(gzipped)) {
            byte[] header = new byte[512];
            String longName = null;
            while (readFully(tar, header)) {
                if (isZeroBlock(header)) {
                    break;
                }
                String name = longName != null ? longName : entryName(header);
                longName = null;
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                byte[] data = readEntry(tar, size);
                if (type == 'x' || type == 'L') {
                    // A PAX header ("path=") or a GNU long name: the name of the next entry.
                    longName = type == 'L' ? new String(data, StandardCharsets.UTF_8).replace("\0", "")
                            : paxPath(data);
                    continue;
                }
                if (type != '0' && type != '\0') {
                    continue;
                }
                files.put(insideChart(name), data);
            }
        }
        if (!files.containsKey("Chart.yaml")) {
            throw new IOException("the package holds no Chart.yaml");
        }
        return files;
    }

    /** A path inside the chart: the entry's path without the chart's own directory, checked to stay inside it. */
    private static String insideChart(String entry) throws IOException {
        String p = entry.replace('\\', '/');
        int slash = p.indexOf('/');
        if (p.startsWith("/") || slash <= 0 || slash == p.length() - 1) {
            throw new IOException("unexpected entry " + entry);
        }
        String inside = p.substring(slash + 1);
        for (String segment : inside.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("entry " + entry + " leaves the chart directory");
            }
        }
        return inside;
    }

    private static String entryName(byte[] header) {
        String name = text(header, 0, 100);
        String magic = text(header, 257, 6);
        String prefix = magic.startsWith("ustar") ? text(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String paxPath(byte[] data) {
        // Records "<length> <key>=<value>\n".
        for (String record : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                return record.substring(space + 1 + "path=".length());
            }
        }
        return null;
    }

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size < 0 || size > MAX_PACKAGE_BYTES) {
            throw new IOException("entry too large");
        }
        byte[] data = new byte[(int) size];
        if (size > 0 && !readFully(in, data)) {
            throw new IOException("truncated package");
        }
        in.skipNBytes((512 - size % 512) % 512);
        return data;
    }

    /** Fills the buffer; false when the stream ended before its first byte, a failure when it ended inside it. */
    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                if (off == 0) {
                    return false;
                }
                throw new IOException("truncated package");
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] header, int offset, int length) {
        String s = text(header, offset, length);
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

}
