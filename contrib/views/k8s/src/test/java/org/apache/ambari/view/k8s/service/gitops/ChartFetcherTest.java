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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A chart package read the way a file profile copies it into the repository. */
class ChartFetcherTest {

    /** A gzipped tar of the given entries (path inside the archive, content). */
    static byte[] tgz(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[512];
                byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
                System.arraycopy(name, 0, header, 0, name.length);
                put(header, 100, "0000644");
                put(header, 108, "0000000");
                put(header, 116, "0000000");
                put(header, 124, String.format("%011o", data.length));
                put(header, 136, String.format("%011o", 0));
                header[156] = '0';
                put(header, 257, "ustar");
                put(header, 263, "00");
                Arrays.fill(header, 148, 156, (byte) ' ');
                int sum = 0;
                for (byte b : header) {
                    sum += b & 0xff;
                }
                put(header, 148, String.format("%06o", sum));
                header[154] = 0;
                gz.write(header);
                gz.write(data);
                gz.write(new byte[(512 - data.length % 512) % 512]);
            }
            gz.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    private static void put(byte[] header, int offset, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, header, offset, b.length);
    }

    @Test
    void theFilesOfAPackageAreKeyedByTheirPathInsideTheChart() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("trino/Chart.yaml", "name: trino\n");
        entries.put("trino/templates/deployment.yaml", "kind: Deployment\n");
        entries.put("trino/charts/dep-1.0.0.tgz", "binary");
        entries.put("trino/values.yaml", "");

        Map<String, byte[]> files = ChartFetcher.chartFiles(new ByteArrayInputStream(tgz(entries)));

        assertEquals(java.util.List.of("Chart.yaml", "templates/deployment.yaml", "charts/dep-1.0.0.tgz", "values.yaml"),
                java.util.List.copyOf(files.keySet()));
        assertEquals("kind: Deployment\n", new String(files.get("templates/deployment.yaml"), StandardCharsets.UTF_8));
        assertEquals(0, files.get("values.yaml").length);
    }

    @Test
    void anEntryLeavingTheChartDirectoryIsRefused() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("trino/Chart.yaml", "name: trino\n");
        entries.put("trino/../../etc/cron.d/x", "evil");
        assertThrows(IOException.class, () -> ChartFetcher.chartFiles(new ByteArrayInputStream(tgz(entries))));
    }

    @Test
    void aPackageWithoutChartYamlIsRefused() throws IOException {
        assertThrows(IOException.class, () -> ChartFetcher.chartFiles(
                new ByteArrayInputStream(tgz(Map.of("trino/values.yaml", "a: 1\n")))));
    }

    @Test
    void helmDownloadsTheChartAsTheOnlyDependencyOfAThrowAwayChart(@TempDir Path work) throws IOException {
        byte[] pkg = tgz(Map.of("trino/Chart.yaml", "name: trino\n"));
        String[] wrapperChart = new String[1];
        ChartFetcher fetcher = new ChartFetcher(dir -> {
            try {
                wrapperChart[0] = Files.readString(dir.resolve("Chart.yaml"));
                Files.createDirectories(dir.resolve("charts"));
                Files.write(dir.resolve("charts/trino-1.43.20.tgz"), pkg);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        Map<String, byte[]> files = fetcher.fetch("oci://registry.example.org/charts", "trino", "1.43.20", work);

        assertEquals("name: trino\n", new String(files.get("Chart.yaml"), StandardCharsets.UTF_8));
        assertTrue(wrapperChart[0].contains("  - name: \"trino\"\n    version: \"1.43.20\"\n"
                + "    repository: \"oci://registry.example.org/charts\"\n"), wrapperChart[0]);
    }

    @Test
    void aDownloadFailureNamesTheChartAndRepository(@TempDir Path work) {
        ChartFetcher fetcher = new ChartFetcher(dir -> {
            throw new IllegalStateException("401 Unauthorized");
        });
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> fetcher.fetch("oci://registry.example.org/charts", "trino", "1.43.20", work));
        assertTrue(failed.getMessage().contains("trino 1.43.20") && failed.getMessage().contains("401"),
                failed.getMessage());
    }
}
