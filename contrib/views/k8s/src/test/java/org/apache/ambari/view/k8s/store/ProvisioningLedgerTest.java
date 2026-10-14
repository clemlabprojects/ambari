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

package org.apache.ambari.view.k8s.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger of what KDPS registered for a release outside Kubernetes survives being stored with the release's record
 * and read back by its uninstall, never loses what it holds to an entry that does not fit, and says when it is
 * missing something.
 */
class ProvisioningLedgerTest {

    private static ProvisioningLedger full() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addOidcClient(new ProvisioningLedger.OidcClient("trino-team-a", "odp", "cdp-prod"));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_hive", "oma-ing-team-a", "select",
                "{\"database\":[\"*\"],\"table\":[\"*\"],\"column\":[\"*\"]}", "kdps-openmetadata-om-hive-read", true,
                true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant("cdp-prod", "trino-team-a", "trino", "show",
                "{\"catalog\":[\"*\"]}", "kdps-openmetadata-om-trino-catalogs", false));
        ledger.addRangerRepository(new ProvisioningLedger.RangerRepository(null, "trino-team-a"));
        ledger.addPrincipal(new ProvisioningLedger.Principal("oma-ing-team-a@EXAMPLE.COM", "om-ingestion-keytab"));
        ledger.setAtlasFederation(new ProvisioningLedger.AtlasFederation("openmetadata-federation", true));
        ledger.setTagSyncSource(new ProvisioningLedger.TagSyncSource("openmetadatarest", "https://om.example/api",
                "ranger.tagsync.openmetadata.trino.instance.hive-trino.ranger.service"));
        ledger.addViewInstance(new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "sqla"));
        ledger.addViewInstance(new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "shared", false));
        ledger.addVaultPath("secret/data/oidc/trino-team-a");
        return ledger;
    }

    @Test
    void everythingRecordedIsReadBack() {
        ProvisioningLedger ledger = full();

        ProvisioningLedger read = ProvisioningLedger.parse(ledger.toJson());

        assertEquals(ledger.oidcClients(), read.oidcClients());
        assertEquals(ledger.rangerGrants(), read.rangerGrants());
        assertEquals(ledger.rangerRepositories(), read.rangerRepositories());
        assertEquals(ledger.principals(), read.principals());
        assertEquals(ledger.atlasFederation(), read.atlasFederation());
        assertEquals(ledger.tagSyncSource(), read.tagSyncSource());
        assertEquals(ledger.viewInstances(), read.viewInstances());
        assertEquals(ledger.vaultPaths(), read.vaultPaths());
        assertFalse(read.isTruncated());
        assertEquals(ledger.toJson(), read.toJson());
    }

    @Test
    void theReleaseRecordKeepsTheLedgerInItsOwnColumn() {
        K8sReleaseEntity release = new K8sReleaseEntity();
        release.recordProvisioningLedger(full());

        K8sReleaseEntity copy = new K8sReleaseEntity();
        copy.setProvisioningJson(release.getProvisioningJson());

        assertEquals(full().toJson(), copy.provisioningLedger().toJson());
        assertNull(release.getGitMetaJson(), "the ledger does not use the Git metadata column");
    }

    @Test
    void anEmptyLedgerIsStoredAsNothing() {
        assertNull(new ProvisioningLedger().toJson());
        assertTrue(ProvisioningLedger.parse(null).isEmpty());
        assertTrue(ProvisioningLedger.parse("").isEmpty());
    }

    @Test
    void anUnreadableLedgerSaysItIsMissingSomething() {
        ProvisioningLedger read = ProvisioningLedger.parse("{not json");

        assertTrue(read.isTruncated());
        assertFalse(read.isEmpty());
    }

    @Test
    void aGrantRecordedAgainReplacesTheEarlierOne() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_hive", "svc", "read", "{}", "p", true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_hive", "svc", "select", "{}", "p", true));

        assertEquals(List.of("select"), ledger.rangerGrants().stream().map(ProvisioningLedger.RangerGrant::access).toList());
    }

    @Test
    void aRedeployFindingTheViewInstanceItCreatedStillRecordsItAsCreated() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addViewInstance(new ProvisioningLedger.ViewInstance("V", "1", "om", true));
        ledger.addViewInstance(new ProvisioningLedger.ViewInstance("V", "1", "om", false));

        assertEquals(List.of(new ProvisioningLedger.ViewInstance("V", "1", "om", true)), ledger.viewInstances());
    }

    @Test
    void whetherAnAccountIsMintedPerNamespaceIsReadBack() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "s", "oma-ing-a", "x", "{}", "p", true, true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "s2", "openmetadata-federation", "x", "{}", "p",
                true));

        List<ProvisioningLedger.RangerGrant> read = ProvisioningLedger.parse(ledger.toJson()).rangerGrants();

        assertTrue(read.get(0).perNamespace());
        assertFalse(read.get(1).perNamespace());
    }

    @Test
    void aRedeployDoesNotForgetThatKdpsTurnedAtlasFileAuthenticationOn() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.setAtlasFederation(new ProvisioningLedger.AtlasFederation("om", true));
        ledger.setAtlasFederation(new ProvisioningLedger.AtlasFederation("om", false));

        assertTrue(ledger.atlasFederation().fileAuthTurnedOn());
    }

    @Test
    void anEntryThatDoesNotFitKeepsWhatWasRecordedAndSaysSo() {
        K8sReleaseEntity release = new K8sReleaseEntity();
        release.recordProvisioningLedger(full());
        ProvisioningLedger grown = release.provisioningLedger();
        for (int i = 0; i < 40; i++) {
            grown.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_trino", "user-" + i, "select",
                    "{\"catalog\":[\"*\"],\"schema\":[\"*\"],\"table\":[\"*\"]}", "kdps-release-policy-" + i, true));
        }

        release.recordProvisioningLedger(grown);

        ProvisioningLedger stored = release.provisioningLedger();
        assertTrue(release.getProvisioningJson().length() <= InstanceSettings.MAX_VALUE_LENGTH);
        assertTrue(stored.isTruncated());
        assertEquals(full().rangerGrants(), stored.rangerGrants());
    }

    /**
     * What an OpenMetadata release wired to Trino, Hive and Atlas registers, on a platform context with a generated
     * id: the largest ledger KDPS writes today.
     */
    private static ProvisioningLedger openMetadataWithTrinoHiveAndAtlas() {
        String context = "3f6b2c1e-8a4d-4b7e-9c2f-1d5e6a7b8c9d";
        String release = "openmetadata-analytics";
        String namespace = "data-platform-production";
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addOidcClient(new ProvisioningLedger.OidcClient(release + "-" + namespace, "enterprise-realm", context));
        String[][] trino = {
            {"queryid", "{\"queryid\":[\"*\"]}", "execute"},
            {"catalogs", "{\"catalog\":[\"*\"]}", "show,use"},
            {"schemas", "{\"catalog\":[\"*\"],\"schema\":[\"*\"]}", "show"},
            {"tables", "{\"catalog\":[\"*\"],\"schema\":[\"*\"],\"table\":[\"*\"]}", "show"},
            {"columns", "{\"catalog\":[\"*\"],\"schema\":[\"*\"],\"table\":[\"*\"],\"column\":[\"*\"]}", "show"},
            {"information-schema", "{\"catalog\":[\"*\"],\"schema\":[\"information_schema\"],\"table\":[\"*\"],"
                    + "\"column\":[\"*\"]}", "select"},
            {"system-metadata", "{\"catalog\":[\"system\"],\"schema\":[\"metadata\",\"jdbc\"],\"table\":[\"*\"],"
                    + "\"column\":[\"*\"]}", "select"},
        };
        for (String[] g : trino) {
            ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(context, "trino-lakehouse-" + namespace,
                    "trino-lakehouse-" + namespace, g[2], g[1], "kdps-openmetadata-" + release + "-trino-" + g[0], false));
        }
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(context, "cluster-production_hive", "oma-ing-" + namespace,
                "select", "{\"database\":[\"*\"],\"table\":[\"*\"],\"column\":[\"*\"]}",
                "kdps-openmetadata-" + release + "-hive-read", true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(context, "cluster-production_atlas",
                "openmetadata-federation", "entity-read",
                "{\"entity-type\":[\"*\"],\"entity-classification\":[\"*\"],\"entity\":[\"*\"]}",
                "kdps-openmetadata-" + release + "-read-entities", true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(context, "cluster-production_atlas",
                "openmetadata-federation", "type-read", "{\"type-category\":[\"*\"],\"type\":[\"*\"]}",
                "kdps-openmetadata-" + release + "-read-types", true));
        ledger.addPrincipal(new ProvisioningLedger.Principal("oma-ing-" + namespace + "@ENTERPRISE.EXAMPLE.COM",
                release + "-ingestion-keytab"));
        ledger.setAtlasFederation(new ProvisioningLedger.AtlasFederation("openmetadata-federation", true));
        ledger.setTagSyncSource(new ProvisioningLedger.TagSyncSource("openmetadatarest",
                "https://" + release + "." + namespace + ".apps.enterprise.example.com/api",
                "ranger.tagsync.openmetadata.trino.instance.trino-lakehouse.ranger.service"));
        ledger.addViewInstance(new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0",
                release + "-" + namespace));
        ledger.addVaultPath("secret/data/kdps/oidc/" + namespace + "/" + release);
        return ledger;
    }

    @Test
    void theLargestLedgerKdpsWritesFitsTheRecordWithRoomToSpare() {
        ProvisioningLedger ledger = openMetadataWithTrinoHiveAndAtlas();
        K8sReleaseEntity release = new K8sReleaseEntity();

        release.recordProvisioningLedger(ledger);

        ProvisioningLedger stored = release.provisioningLedger();
        assertFalse(stored.isTruncated(), release.getProvisioningJson());
        // A quarter of the column is left for what a redeploy or a later version adds.
        assertTrue(release.getProvisioningJson().length() <= InstanceSettings.MAX_VALUE_LENGTH * 3 / 4,
                release.getProvisioningJson().length() + " characters: " + release.getProvisioningJson());
        assertEquals(java.util.Set.copyOf(ledger.rangerGrants()), java.util.Set.copyOf(stored.rangerGrants()));
        assertEquals(ledger.oidcClients(), stored.oidcClients());
        assertEquals(ledger.vaultPaths(), stored.vaultPaths());
    }

    @Test
    void entriesOfAnotherOrNoPlatformContextKeepTheirOwn() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant("ext", "s", "u", "a", "{}", "p1", true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant("ext", "s", "u", "b", "{}", "p2", true));
        ledger.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "s", "u", "a", "not json", null, false));
        ledger.addOidcClient(new ProvisioningLedger.OidcClient("c", "r", "other"));
        ledger.addRangerRepository(new ProvisioningLedger.RangerRepository(null, "repo"));

        ProvisioningLedger read = ProvisioningLedger.parse(ledger.toJson());

        assertEquals(java.util.Set.copyOf(ledger.rangerGrants()), java.util.Set.copyOf(read.rangerGrants()));
        assertEquals(ledger.oidcClients(), read.oidcClients());
        assertEquals(ledger.rangerRepositories(), read.rangerRepositories());
    }

    @Test
    void aReleaseRecordedSinceLedgersExistIsToldApartFromAnEarlierOne() {
        K8sReleaseEntity earlier = new K8sReleaseEntity();
        K8sReleaseEntity recorded = new K8sReleaseEntity();

        recorded.recordProvisioningLedger(new ProvisioningLedger());

        assertFalse(earlier.hasProvisioningLedger());
        assertTrue(recorded.hasProvisioningLedger());
        assertTrue(recorded.provisioningLedger().isEmpty());
    }

    @Test
    void aLedgerKeptBeforeTheRecordExistedIsMergedIntoIt() {
        ProvisioningLedger record = new ProvisioningLedger();
        record.addVaultPath("secret/data/a");
        ProvisioningLedger pending = new ProvisioningLedger();
        pending.addVaultPath("secret/data/a");
        pending.addOidcClient(new ProvisioningLedger.OidcClient("c", "r", null));
        pending.markTruncated();

        record.merge(pending);

        assertEquals(List.of("secret/data/a"), record.vaultPaths());
        assertEquals(1, record.oidcClients().size());
        assertTrue(record.isTruncated());
    }
}
