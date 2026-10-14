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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.k8s.store.ProvisioningLedger;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The uninstall of a release takes back what KDPS registered for it outside Kubernetes, as its ledger records it, with
 * Keycloak, Ranger and the managed cluster's Ambari mocked: everything that gives access goes, what another release
 * still uses stays, the Ranger repository only goes when asked, and a failure is reported, never thrown.
 */
class ExternalCleanupTest {

    private static final String NS = "team-a";
    private static final String RELEASE = "om";

    private static final ProvisioningLedger.RangerGrant HIVE_READ = new ProvisioningLedger.RangerGrant(null, "c1_hive",
            "oma-ing-team-a", "select", CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-om-hive-read", true, true);
    private static final ProvisioningLedger.RangerGrant TRINO_SHOW = new ProvisioningLedger.RangerGrant(null,
            "trino-team-a", "trino", "show,use", "{\"catalog\":[\"*\"]}", "kdps-openmetadata-om-trino-catalogs", false);
    private static final ProvisioningLedger.OidcClient CLIENT =
            new ProvisioningLedger.OidcClient("om-team-a", "odp", null);
    private static final ProvisioningLedger.RangerRepository REPOSITORY =
            new ProvisioningLedger.RangerRepository(null, "om-team-a");
    private static final ProvisioningLedger.ViewInstance VIEW =
            new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "om");
    private static final String MAPPER = "ranger.tagsync.openmetadata.trino.instance.hive-trino.ranger.service";

    private final ExternalCleanup.Platform platform = mock(ExternalCleanup.Platform.class);

    private static ProvisioningLedger ledger() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addOidcClient(CLIENT);
        ledger.addRangerGrant(HIVE_READ);
        ledger.addRangerGrant(TRINO_SHOW);
        ledger.addRangerRepository(REPOSITORY);
        ledger.addPrincipal(new ProvisioningLedger.Principal("oma-ing-team-a@EXAMPLE.COM", "om-ingestion-keytab"));
        ledger.setAtlasFederation(new ProvisioningLedger.AtlasFederation("openmetadata-federation", true));
        ledger.setTagSyncSource(new ProvisioningLedger.TagSyncSource("openmetadatarest", "https://om.team-a/api", MAPPER));
        ledger.addViewInstance(VIEW);
        ledger.addVaultPath("secret/data/oidc/om");
        return ledger;
    }

    private ExternalCleanup.Report run(ExternalCleanup.Mode mode, boolean signInSecretsSeen,
                                       Map<String, ProvisioningLedger> others) {
        return ExternalCleanup.run(NS, RELEASE, ledger(), mode, signInSecretsSeen, others, platform);
    }

    private void managedClusterHasTheRelease() throws Exception {
        when(platform.managedConfig("atlas-env")).thenReturn(Map.of(
                "openmetadata.federation.username", "openmetadata-federation",
                "openmetadata.federation.password_hash", "abc", "openmetadata.federation.role", "ROLE_USER",
                "content", "export X=1"));
        Map<String, String> site = new LinkedHashMap<>();
        site.put("ranger.tagsync.source.atlas", "true");
        site.put("ranger.tagsync.source.openmetadatarest", "true");
        site.put("ranger.tagsync.source.openmetadatarest.endpoint", "https://om.team-a/api");
        site.put("ranger.tagsync.source.openmetadatarest.token", "SECRET:ranger-tagsync-site:3:x");
        site.put("ranger.tagsync.source.openmetadatarest.token.sha256", "d1");
        site.put(MAPPER, "trino-team-a");
        site.put("ranger.tagsync.sources", "atlas,openmetadatarest");
        when(platform.managedConfig("ranger-tagsync-site")).thenReturn(site);
        when(platform.deleteKeycloakClient(CLIENT)).thenReturn("Removed the single sign-on client 'om-team-a'.");
        when(platform.revokeRangerGrant(any())).thenReturn(1);
        when(platform.deleteViewInstance(VIEW)).thenReturn(true);
        when(platform.deleteVaultPath("secret/data/oidc/om")).thenReturn(true);
        when(platform.restartManagedComponent(anyString(), anyString(), anyString())).thenReturn(42);
    }

    @Test
    void everythingThatGivesAccessIsTakenBackAndTheRepositoryKept() throws Exception {
        managedClusterHasTheRelease();

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, false, Map.of());

        verify(platform).deleteKeycloakClient(CLIENT);
        verify(platform).revokeRangerGrant(HIVE_READ);
        verify(platform).revokeRangerGrant(TRINO_SHOW);
        verify(platform, never()).deleteRangerRepository(any());
        verify(platform).deleteViewInstance(VIEW);
        verify(platform).deleteVaultPath("secret/data/oidc/om");
        assertTrue(report.left().stream().anyMatch(l -> l.object().contains("oma-ing-team-a@EXAMPLE.COM")
                && l.command().contains("delprinc")), report.note());
        assertTrue(report.kept().stream().anyMatch(l -> l.object().contains("Ranger repository 'om-team-a'")));
        assertTrue(report.removed().stream().anyMatch(r -> r.contains("oma-ing-team-a")));
    }

    @Test
    void theAtlasFederationUserIsRemovedAndFileAuthenticationTurnedBackOff() throws Exception {
        managedClusterHasTheRelease();

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        verify(platform).updateManagedConfig(eq("atlas-env"), eq(Map.of("openmetadata.federation.username", "",
                "openmetadata.federation.password_hash", "", "openmetadata.federation.role", "")), anyString());
        verify(platform).updateManagedConfig(eq("application-properties"),
                eq(Map.of("atlas.authentication.method.file", "false")), anyString());
        // Restarting Atlas is the operator's call.
        verify(platform, never()).restartManagedComponent(eq("ATLAS"), anyString(), anyString());
        assertTrue(report.left().stream().anyMatch(l -> l.object().equals("Atlas restart")));
    }

    @Test
    void theOpenMetadataSourceOfRangerTagSyncIsRemovedAndTagSyncRestarted() throws Exception {
        managedClusterHasTheRelease();

        run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> props = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(platform).updateManagedConfig(eq("ranger-tagsync-site"), props.capture(), anyString());
        Map<String, String> written = props.getValue();
        assertEquals("", written.get("ranger.tagsync.source.openmetadatarest"));
        assertEquals("", written.get("ranger.tagsync.source.openmetadatarest.endpoint"));
        assertEquals("", written.get("ranger.tagsync.source.openmetadatarest.token"));
        assertEquals("", written.get("ranger.tagsync.source.openmetadatarest.token.sha256"));
        assertEquals("", written.get(MAPPER));
        assertEquals("atlas", written.get("ranger.tagsync.sources"));
        assertTrue(!written.containsKey("ranger.tagsync.source.atlas"), "the Atlas source is left as it is");
        verify(platform).restartManagedComponent(eq("RANGER"), eq("RANGER_TAGSYNC"), anyString());
    }

    @Test
    void aTagSyncSourceNowPointingAtAnotherOpenMetadataIsKept() throws Exception {
        managedClusterHasTheRelease();
        when(platform.managedConfig("ranger-tagsync-site")).thenReturn(Map.of(
                "ranger.tagsync.source.openmetadatarest.endpoint", "https://om.team-b/api"));

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        verify(platform, never()).updateManagedConfig(eq("ranger-tagsync-site"), anyMap(), anyString());
        verify(platform, never()).restartManagedComponent(eq("RANGER"), anyString(), anyString());
        assertTrue(report.kept().stream().anyMatch(l -> l.reason().contains("om.team-b")));
    }

    @Test
    void anAtlasUserThatIsNoLongerTheReleasesIsKept() throws Exception {
        managedClusterHasTheRelease();
        when(platform.managedConfig("atlas-env")).thenReturn(Map.of("openmetadata.federation.username", "other"));

        run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        verify(platform, never()).updateManagedConfig(eq("atlas-env"), anyMap(), anyString());
        verify(platform, never()).updateManagedConfig(eq("application-properties"), anyMap(), anyString());
    }

    @Test
    void theRepositoryGoesOnlyWhenEverythingIsAskedFor() throws Exception {
        managedClusterHasTheRelease();
        when(platform.deleteRangerRepository(REPOSITORY)).thenReturn(true);

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.ALL, true, Map.of());

        verify(platform).deleteRangerRepository(REPOSITORY);
        assertTrue(report.removed().contains("Ranger repository 'om-team-a'"));
    }

    @Test
    void whatAnotherReleaseStillUsesIsKept() throws Exception {
        managedClusterHasTheRelease();
        ProvisioningLedger other = new ProvisioningLedger();
        other.addRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_hive", "oma-ing-team-a", "select",
                CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-om2-hive-read", true));
        other.addPrincipal(new ProvisioningLedger.Principal("oma-ing-team-a@EXAMPLE.COM", "om2-ingestion-keytab"));
        other.setAtlasFederation(new ProvisioningLedger.AtlasFederation("openmetadata-federation", false));

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of("team-a/om2", other));

        verify(platform, never()).revokeRangerGrant(HIVE_READ);
        verify(platform).revokeRangerGrant(TRINO_SHOW);
        verify(platform, never()).updateManagedConfig(eq("atlas-env"), anyMap(), anyString());
        assertEquals(3, report.kept().stream().filter(l -> l.reason().contains("team-a/om2")).count(), report.note());
        ExternalCleanup.Report preview = ExternalCleanup.preview(ledger(), ExternalCleanup.Mode.CREDENTIALS,
                Map.of("team-a/om2", other));
        assertTrue(preview.kept().stream().anyMatch(l -> l.object().startsWith("Atlas federation user")
                && l.reason().contains("team-a/om2")), preview.note());
    }

    @Test
    void aReleaseOfTheNamespaceDeployedBeforeLedgersKeepsTheMintedAccountsAccessOutsideThisReleasesPolicy()
            throws Exception {
        managedClusterHasTheRelease();

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true,
                Map.of("team-a/om-old", ProvisioningLedger.unrecorded()));

        // Only the policy named for this release is changed: the earlier release may sign in as oma-ing-team-a.
        verify(platform).revokeRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_hive", "oma-ing-team-a",
                "select", CommandService.HIVE_ALL_RESOURCES, "kdps-openmetadata-om-hive-read", false, true));
        verify(platform, never()).revokeRangerGrant(HIVE_READ);
        verify(platform).revokeRangerGrant(TRINO_SHOW);
        assertTrue(report.kept().stream().anyMatch(l -> l.object().contains("oma-ing-team-a")
                && l.object().contains("other policies") && l.reason().contains("team-a/om-old")), report.note());
        assertTrue(report.kept().stream().anyMatch(l -> l.object().equals("Kerberos principal oma-ing-team-a@EXAMPLE.COM")
                && l.reason().contains("team-a/om-old")), report.note());
        ExternalCleanup.Report preview = ExternalCleanup.preview(NS, ledger(), ExternalCleanup.Mode.CREDENTIALS,
                Map.of("team-a/om-old", ProvisioningLedger.unrecorded()));
        assertTrue(preview.kept().stream().anyMatch(l -> l.reason().contains("team-a/om-old")), preview.note());
    }

    @Test
    void aReleaseOfAnotherNamespaceDeployedBeforeLedgersCannotUseTheNamespacesAccount() throws Exception {
        managedClusterHasTheRelease();

        run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of("team-b/om-old", ProvisioningLedger.unrecorded()));

        verify(platform).revokeRangerGrant(HIVE_READ);
    }

    @Test
    void aReleaseOfAnyNamespaceDeployedBeforeLedgersKeepsTheAtlasFederationUser() throws Exception {
        managedClusterHasTheRelease();
        // atlas-env holds one federation user for the whole cluster: the earlier release may sign in as it.
        Map<String, ProvisioningLedger> others = Map.of("team-b/om-old", ProvisioningLedger.unrecorded());

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, others);

        verify(platform, never()).updateManagedConfig(eq("atlas-env"), anyMap(), anyString());
        verify(platform, never()).updateManagedConfig(eq("application-properties"), anyMap(), anyString());
        assertTrue(report.kept().stream().anyMatch(l -> l.object().startsWith("Atlas federation user")
                && l.reason().contains("team-b/om-old")), report.note());
        ExternalCleanup.Report preview = ExternalCleanup.preview(NS, ledger(), ExternalCleanup.Mode.CREDENTIALS, others);
        assertTrue(preview.kept().stream().anyMatch(l -> l.object().startsWith("Atlas federation user")
                && l.reason().contains("team-b/om-old")), preview.note());
        assertTrue(preview.removed().stream().noneMatch(r -> r.startsWith("Atlas federation user")), preview.note());
    }

    @Test
    void aRepositoryAnotherReleaseRecordsIsNotDeleted() throws Exception {
        managedClusterHasTheRelease();
        ProvisioningLedger other = new ProvisioningLedger();
        other.addRangerRepository(REPOSITORY);

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.ALL, true, Map.of("team-a/om2", other));

        verify(platform, never()).deleteRangerRepository(any());
        assertTrue(report.kept().stream().anyMatch(l -> l.object().equals("Ranger repository 'om-team-a'")
                && l.reason().contains("team-a/om2")), report.note());
        assertTrue(ExternalCleanup.preview(NS, ledger(), ExternalCleanup.Mode.ALL, Map.of("team-a/om2", other)).kept()
                .stream().anyMatch(l -> l.object().equals("Ranger repository 'om-team-a'")));
    }

    @Test
    void otherReleasesThatCannotBeReadKeepEverythingThatMayBeShared() throws Exception {
        managedClusterHasTheRelease();

        run(ExternalCleanup.Mode.CREDENTIALS, true, null);

        verify(platform, never()).revokeRangerGrant(any());
        verify(platform, never()).updateManagedConfig(eq("atlas-env"), anyMap(), anyString());
        // A view instance or Vault path may be another release's too.
        verify(platform, never()).deleteViewInstance(any());
        verify(platform, never()).deleteVaultPath(anyString());
    }

    @Test
    void aClusterWideAccountWhoseNameHoldsTheNamespaceIsStillSharedWithEarlierReleasesOfEveryNamespace()
            throws Exception {
        managedClusterHasTheRelease();
        // The Atlas federation account is one for the whole cluster, whatever the namespace is called.
        ProvisioningLedger.RangerGrant atlasRead = new ProvisioningLedger.RangerGrant(null, "c1_atlas",
                "openmetadata-federation", "entity-read", "{\"entity\":[\"*\"]}", "kdps-openmetadata-om-atlas-entities",
                true);
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.addRangerGrant(atlasRead);
        Map<String, ProvisioningLedger> others = Map.of("team-b/om-old", ProvisioningLedger.unrecorded());

        ExternalCleanup.Report report = ExternalCleanup.run("data", RELEASE, ledger, ExternalCleanup.Mode.CREDENTIALS,
                true, others, platform);

        verify(platform).revokeRangerGrant(new ProvisioningLedger.RangerGrant(null, "c1_atlas",
                "openmetadata-federation", "entity-read", "{\"entity\":[\"*\"]}", "kdps-openmetadata-om-atlas-entities",
                false));
        verify(platform, never()).revokeRangerGrant(atlasRead);
        assertTrue(report.kept().stream().anyMatch(l -> l.object().contains("other policies")
                && l.reason().contains("team-b/om-old")), report.note());
        assertTrue(ExternalCleanup.preview("data", ledger, ExternalCleanup.Mode.CREDENTIALS, others).kept().stream()
                .anyMatch(l -> l.reason().contains("team-b/om-old")));
    }

    @Test
    void aViewInstanceAnotherReleaseOfTheSameNameUsesIsNotDeleted() throws Exception {
        managedClusterHasTheRelease();
        ProvisioningLedger other = new ProvisioningLedger();
        // Same release name in another namespace: the instance is named after the release alone, and that deploy
        // found it existing.
        other.addViewInstance(new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0", "om", false));

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of("team-b/om", other));

        verify(platform, never()).deleteViewInstance(any());
        assertTrue(report.kept().stream().anyMatch(l -> l.object().equals(ExternalCleanup.describe(VIEW))
                && l.reason().contains("team-b/om")), report.note());
        assertTrue(ExternalCleanup.preview(NS, ledger(), ExternalCleanup.Mode.CREDENTIALS, Map.of("team-b/om", other))
                .kept().stream().anyMatch(l -> l.object().equals(ExternalCleanup.describe(VIEW))));
    }

    @Test
    void aViewInstanceTheDeployFoundExistingIsNotDeleted() throws Exception {
        managedClusterHasTheRelease();
        ProvisioningLedger ledger = new ProvisioningLedger();
        ProvisioningLedger.ViewInstance adopted = new ProvisioningLedger.ViewInstance("SQL-ASSISTANT-VIEW", "1.0.0.0",
                "om", false);
        ledger.addViewInstance(adopted);

        ExternalCleanup.Report report = ExternalCleanup.run(NS, RELEASE, ledger, ExternalCleanup.Mode.CREDENTIALS, true,
                Map.of(), platform);

        verify(platform, never()).deleteViewInstance(any());
        assertTrue(report.kept().stream().anyMatch(l -> l.reason().contains("existed before the release")),
                report.note());
    }

    @Test
    void whatAReleaseDeployedBeforeLedgersMayUseIsNotDeleted() throws Exception {
        managedClusterHasTheRelease();

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true,
                Map.of("team-b/sqlbot", ProvisioningLedger.unrecorded()));

        verify(platform, never()).deleteViewInstance(any());
        verify(platform, never()).deleteVaultPath(anyString());
        assertEquals(2, report.kept().stream().filter(l -> (l.object().startsWith("Ambari view instance")
                || l.object().startsWith("Vault copy")) && l.reason().contains("team-b/sqlbot")).count(), report.note());
    }

    @Test
    void aVaultPathAnotherReleaseOfTheServiceWritesIsNotDeleted() throws Exception {
        managedClusterHasTheRelease();
        ProvisioningLedger other = new ProvisioningLedger();
        other.addVaultPath("secret/data/oidc/om");

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of("team-b/om2", other));

        verify(platform, never()).deleteVaultPath(anyString());
        verify(platform).deleteViewInstance(VIEW);
        assertTrue(report.kept().stream().anyMatch(l -> l.object().startsWith("Vault copy")
                && l.reason().contains("team-b/om2")), report.note());
        assertTrue(ExternalCleanup.preview(NS, ledger(), ExternalCleanup.Mode.CREDENTIALS, Map.of("team-b/om2", other))
                .kept().stream().anyMatch(l -> l.object().startsWith("Vault copy")));
    }

    @Test
    void whatMayBeSharedSaysTheOtherReleasesCouldNotBeRead() {
        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, null);

        assertTrue(report.kept().stream().filter(l -> l.object().startsWith("Ranger access"))
                .allMatch(l -> l.reason().equals("other releases may use it (they could not be read)")), report.note());
    }

    @Test
    void theKeycloakClientIsLeftToTheSignInCleanupWhenItsSecretsWereFound() throws Exception {
        managedClusterHasTheRelease();

        run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        verify(platform, never()).deleteKeycloakClient(any());
    }

    @Test
    void failuresAreReportedNotThrown() throws Exception {
        when(platform.deleteKeycloakClient(any())).thenThrow(new IllegalStateException("Keycloak is down"));
        when(platform.revokeRangerGrant(any())).thenThrow(new IllegalStateException("Ranger refused"));
        when(platform.managedConfig(anyString())).thenThrow(new IllegalStateException("no Ambari access"));
        when(platform.deleteViewInstance(any())).thenThrow(new IllegalStateException("HTTP 403"));
        when(platform.deleteVaultPath(anyString())).thenThrow(new IllegalStateException("Vault sealed"));

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, false, Map.of());

        assertTrue(report.removed().isEmpty(), report.note());
        assertTrue(report.left().stream().anyMatch(l -> l.reason().contains("Keycloak is down")
                && l.command().startsWith("kcadm.sh delete")));
        assertEquals(2, report.left().stream().filter(l -> l.reason().contains("Ranger refused")).count());
        assertTrue(report.left().stream().anyMatch(l -> l.reason().contains("no Ambari access")
                && l.command().contains("atlas-env")));
        assertTrue(report.left().stream().anyMatch(l -> l.reason().contains("HTTP 403")));
        assertTrue(report.left().stream().anyMatch(l -> l.reason().contains("Vault sealed")));
        assertTrue(report.toMap().get("note").toString().contains("Could not remove"));
    }

    @Test
    void anUnexpectedErrorEndsTheCleanupWithAReport() throws Exception {
        doThrow(new NullPointerException()).when(platform).deleteVaultPath(anyString());
        when(platform.managedConfig(anyString())).thenReturn(null);

        ExternalCleanup.Report report = run(ExternalCleanup.Mode.CREDENTIALS, true, Map.of());

        assertTrue(report.left().stream().anyMatch(l -> l.object().startsWith("Vault copy")), report.note());
    }

    @Test
    void anUninstallAskingToKeepEverythingChangesNothing() {
        ExternalCleanup.Report report = run(ExternalCleanup.Mode.NONE, false, Map.of());

        verifyNoInteractions(platform);
        assertTrue(report.removed().isEmpty());
        assertEquals(9, report.kept().size());
    }

    @Test
    void aLedgerMissingSomethingSaysWhatToLookFor() {
        ProvisioningLedger ledger = new ProvisioningLedger();
        ledger.markTruncated();

        ExternalCleanup.Report report = ExternalCleanup.run(NS, RELEASE, ledger, ExternalCleanup.Mode.CREDENTIALS, true,
                Map.of(), platform);

        assertEquals(1, report.left().size());
        assertTrue(report.left().get(0).command().contains("Keycloak and Ranger"));
    }

    @Test
    void thePreviewListsWhatTheUninstallWouldDoWithoutDoingIt() {
        ExternalCleanup.Report credentials = ExternalCleanup.preview(ledger(), ExternalCleanup.Mode.CREDENTIALS, Map.of());
        ExternalCleanup.Report all = ExternalCleanup.preview(ledger(), ExternalCleanup.Mode.ALL, Map.of());

        verifyNoInteractions(platform);
        assertTrue(credentials.removed().stream().anyMatch(r -> r.startsWith("Keycloak client 'om-team-a'")));
        assertTrue(credentials.kept().stream().anyMatch(k -> k.object().equals("Ranger repository 'om-team-a'")));
        assertTrue(all.removed().contains("Ranger repository 'om-team-a'"));
        assertTrue(ExternalCleanup.preview(ledger(), ExternalCleanup.Mode.NONE, Map.of()).removed().isEmpty());
    }

    @Test
    void theModeIsReadFromTheRequest() {
        assertEquals(ExternalCleanup.Mode.CREDENTIALS, ExternalCleanup.Mode.of(null));
        assertEquals(ExternalCleanup.Mode.CREDENTIALS, ExternalCleanup.Mode.of(" Credentials "));
        assertEquals(ExternalCleanup.Mode.ALL, ExternalCleanup.Mode.of("all"));
        assertEquals(ExternalCleanup.Mode.NONE, ExternalCleanup.Mode.of("NONE"));
        assertThrows(IllegalArgumentException.class, () -> ExternalCleanup.Mode.of("everything"));
    }

    @Test
    void theReportListsEachKind() {
        ExternalCleanup.Report report = new ExternalCleanup.Report(List.of("a"),
                List.of(new ExternalCleanup.Leftover("b", "why", "cmd")), List.of());

        Map<String, Object> map = report.toMap();

        assertEquals(List.of("a"), map.get("removed"));
        assertEquals(List.of(Map.of("object", "b", "reason", "why", "command", "cmd")), map.get("left"));
        assertEquals(List.of(), map.get("kept"));
    }
}
