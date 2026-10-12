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

package org.apache.ambari.view.k8s.resources;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.k8s.model.HelmReleaseDTO;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chart version the Releases page shows for a release, which Upgrade/Config starts from: the one running on the
 * cluster, not the one KDPS recorded at its last deploy, which goes stale after a Helm rollback or a resync that took
 * the newest chart. A GitOps release shows the one committed to Git, which Flux moves the cluster to.
 */
class HelmResourceChartVersionTest {

    /** The release as {@code helm list} reports it, parsed the way the Releases list parses it. */
    private static HelmReleaseDTO listed(String chart) {
        Release release = mock(Release.class);
        when(release.getName()).thenReturn("trino");
        when(release.getNamespace()).thenReturn("analytics");
        when(release.getChart()).thenReturn(chart);
        return HelmReleaseDTO.from(release);
    }

    /** Reads the release's Helm Secret, counting the reads. */
    private static Supplier<String> releaseSecret(String version, AtomicInteger reads) {
        return () -> {
            reads.incrementAndGet();
            return version;
        };
    }

    private static String runningVersion(HelmReleaseDTO dto, Supplier<String> releaseSecret, String recorded) {
        return HelmResource.releaseChartVersion(dto.chart, dto.version, releaseSecret, recorded, "DIRECT_HELM");
    }

    private static String gitOpsVersion(HelmReleaseDTO dto, Supplier<String> releaseSecret, String recorded) {
        return HelmResource.releaseChartVersion(dto.chart, dto.version, releaseSecret, recorded, "FLUX_GITOPS");
    }

    @Test
    void aStaleRecordedVersionGivesWayToTheOneRunning() {
        // Installed at 1.43.12, then moved to 1.43.14 without KDPS recording it.
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.14", runningVersion(listed("trino-1.43.14"), releaseSecret("1.43.14", reads), "1.43.12"));
        assertEquals(1, reads.get(), "Helm and the record disagree: the exact version is read from the release Secret");
    }

    @Test
    void afterARollbackTheRolledBackVersionIsShown() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.12", runningVersion(listed("trino-1.43.12"), releaseSecret("1.43.12", reads), "1.43.14"));
    }

    @Test
    void theParsedVersionStandsWhenTheReleaseSecretCannotBeRead() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.14", runningVersion(listed("trino-1.43.14"), releaseSecret(null, reads), "1.43.12"));
    }

    @Test
    void aRecordThatMatchesHelmNeedsNoSecretRead() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.14", runningVersion(listed("trino-1.43.14"), releaseSecret("9.9.9", reads), "1.43.14"));
        assertEquals(0, reads.get());
    }

    @Test
    void aVersionWithDashesIsKeptWhole() {
        AtomicInteger reads = new AtomicInteger();
        HelmReleaseDTO dto = listed("airflow-1.5.0-1.3.2");
        assertEquals("1.3.2", dto.version, "the helm list chart string alone cannot tell where the version starts");

        assertEquals("1.5.0-1.3.2", runningVersion(dto, releaseSecret("1.5.0-1.3.2", reads), "1.5.0-1.3.2"));
        assertEquals(0, reads.get());
        assertEquals("1.5.0-1.3.2", runningVersion(dto, releaseSecret("1.5.0-1.3.2", reads), "1.5.0-1.3.1"),
                "a stale record is replaced by the release Secret's exact version");
    }

    @Test
    void aReleaseKdpsNeverRecordedShowsWhatHelmReports() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.14", runningVersion(listed("trino-1.43.14"), releaseSecret("9.9.9", reads), null));
        assertEquals(0, reads.get(), "nothing to disagree with: no Secret read");
        assertEquals("2.1.0", runningVersion(listed("trino-nightly"), releaseSecret("2.1.0", reads), null),
                "an unparsable chart string is read from the release Secret");
    }

    @Test
    void theRecordedVersionIsTheLastResort() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.12", runningVersion(listed("trino-nightly"), releaseSecret(null, reads), "1.43.12"));
        assertNull(runningVersion(listed("trino-nightly"), releaseSecret(null, reads), "latest"),
                "'latest' names no chart version");
    }

    @Test
    void aGitOpsReleaseShowsTheVersionCommittedToGit() {
        // Upgraded to 1.43.14 in Git; Helm still runs 1.43.12 while the pull request is open or Flux catches up.
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.14", gitOpsVersion(listed("trino-1.43.12"), releaseSecret("1.43.12", reads), "1.43.14"),
                "Upgrade/Config must not start from the chart the commit replaces");
        assertEquals(0, reads.get());
    }

    @Test
    void aGitOpsReleaseWithNoRecordedVersionShowsWhatHelmRuns() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("1.43.12", gitOpsVersion(listed("trino-1.43.12"), releaseSecret("1.43.12", reads), "latest"));
        assertEquals("1.43.12", gitOpsVersion(listed("trino-1.43.12"), releaseSecret("1.43.12", reads), null));
    }
}
