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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The revisions of a release as {@code helm history -o json} lists them, read to decide how a failed upgrade may
 * be recovered: whether the release ever served (a revision was deployed or superseded), which revision is the
 * last one, and which earlier revision is the one to return to.
 */
public final class HelmReleaseHistory {

    public static final String DEPLOYED = "deployed";
    public static final String SUPERSEDED = "superseded";
    public static final String FAILED = "failed";
    public static final String PENDING_INSTALL = "pending-install";
    public static final String PENDING_UPGRADE = "pending-upgrade";
    public static final String PENDING_ROLLBACK = "pending-rollback";

    /** One revision of the release. */
    public static final class Revision {
        private final int number;
        private final String status;

        Revision(int number, String status) {
            this.number = number;
            this.status = status;
        }

        public int number() {
            return number;
        }

        /** Helm's status, lower case ({@code deployed}, {@code pending-upgrade}, ...). */
        public String status() {
            return status;
        }

        public boolean is(String expected) {
            return expected.equals(status);
        }

        @Override
        public String toString() {
            return "revision " + number + " (" + status + ")";
        }
    }

    private final List<Revision> revisions;

    private HelmReleaseHistory(List<Revision> revisions) {
        this.revisions = revisions;
    }

    /**
     * Reads the entries returned by {@link HelmClient#history}. Entries without a readable revision number are
     * left out; the rest are ordered by revision.
     */
    public static HelmReleaseHistory of(List<Map<String, Object>> entries) {
        List<Revision> revisions = new ArrayList<>();
        if (entries != null) {
            for (Map<String, Object> entry : entries) {
                if (entry == null) continue;
                Integer number = revisionNumber(entry.get("revision"));
                if (number == null) continue;
                Object status = entry.get("status");
                revisions.add(new Revision(number,
                        status == null ? "" : String.valueOf(status).trim().toLowerCase(Locale.ROOT)));
            }
        }
        revisions.sort(Comparator.comparingInt(Revision::number));
        return new HelmReleaseHistory(Collections.unmodifiableList(revisions));
    }

    private static Integer revisionNumber(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public List<Revision> revisions() {
        return revisions;
    }

    /** The newest revision, or {@code null} for an empty history. */
    public Revision last() {
        return revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
    }

    /** True when some revision was deployed at one point (it is deployed, or a later one superseded it). */
    public boolean everDeployed() {
        for (Revision r : revisions) {
            if (r.is(DEPLOYED) || r.is(SUPERSEDED)) return true;
        }
        return false;
    }

    /**
     * The revision to return to when the last one did not complete: the newest earlier revision that is deployed
     * (it is still serving), otherwise the newest earlier superseded one; {@code null} when there is none.
     */
    public Revision lastGood() {
        Revision last = last();
        if (last == null) return null;
        Revision superseded = null;
        for (int i = revisions.size() - 2; i >= 0; i--) {
            Revision r = revisions.get(i);
            if (r.is(DEPLOYED)) return r;
            if (superseded == null && r.is(SUPERSEDED)) superseded = r;
        }
        return superseded;
    }
}
