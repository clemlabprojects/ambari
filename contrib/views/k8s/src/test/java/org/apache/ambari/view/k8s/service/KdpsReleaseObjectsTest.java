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

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.store.K8sReleaseEntity;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The release selector KDPS stamps on what it creates for a release outside its chart, the objects that get it (and
 * those that must not: shared by the project, or provided by an administrator), and the report the uninstall answers
 * with.
 */
class KdpsReleaseObjectsTest {

    private static final String NS = "analytics";

    @Test
    void theReleaseSelectorIsTheManagedByAndReleaseLabelsWithTheNamespaceInAnAnnotation() {
        assertEquals(Map.of("app.kubernetes.io/managed-by", "ambari-k8s-view", "ambari.clemlab.com/release", "trino"),
                KdpsReleaseObjects.labels("trino"));
        assertEquals(Map.of("ambari.clemlab.com/release-namespace", NS), KdpsReleaseObjects.annotations(NS));
        assertEquals(Map.of(), KdpsReleaseObjects.labels(null), "no release: a shared object is never marked");
        assertEquals(Map.of(), KdpsReleaseObjects.labels(""));
        assertEquals(Map.of(), KdpsReleaseObjects.labels("not/a label"));
        assertEquals(Map.of("app.kubernetes.io/managed-by", "kdps", "ambari.clemlab.com/release", "trino"),
                KdpsReleaseObjects.withReleaseLabel(Map.of("app.kubernetes.io/managed-by", "kdps"), "trino"),
                "an object another step removes keeps its own managed-by value");
    }

    @Test
    void anObjectIsTheReleasesOnlyWithBothLabelsAndNoOtherNamespace() {
        assertTrue(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().withLabels(KdpsReleaseObjects.labels("trino"))
                .build(), NS, "trino"));
        assertTrue(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().withLabels(KdpsReleaseObjects.labels("trino"))
                .withAnnotations(KdpsReleaseObjects.annotations(NS)).build(), NS, "trino"));
        assertFalse(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().withLabels(KdpsReleaseObjects.labels("trino"))
                .withAnnotations(KdpsReleaseObjects.annotations("other")).build(), NS, "trino"));
        assertFalse(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().withLabels(KdpsReleaseObjects.labels("trino-b"))
                .build(), NS, "trino"));
        assertFalse(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().withLabels(KdpsReleaseObjects.withReleaseLabel(
                Map.of("app.kubernetes.io/managed-by", "kdps"), "trino")).build(), NS, "trino"));
        assertFalse(KdpsReleaseObjects.ofRelease(new ObjectMetaBuilder().build(), NS, "trino"));
    }

    @Test
    void theReportSaysWhatWasRemovedAndHowToRemoveWhatIsLeft() {
        KdpsReleaseObjects.Cleanup cleanup = new KdpsReleaseObjects.Cleanup(
                List.of("Secret/trino-keytab", "ConfigMap/trino-krb5-conf"),
                List.of(new KdpsReleaseObjects.Leftover("ServiceAccount/trino-thanos-token",
                        "this account may not delete it",
                        KdpsReleaseObjects.deleteCommand("ServiceAccount", "trino-thanos-token", NS))));

        assertEquals("Removed what KDPS created for the release outside its chart: Secret/trino-keytab,"
                + " ConfigMap/trino-krb5-conf.\n"
                + "Could not remove this object KDPS created for the release; delete it by hand:\n"
                + "  ServiceAccount/trino-thanos-token (this account may not delete it): oc delete serviceaccount"
                + " trino-thanos-token -n analytics", cleanup.note());
        Map<String, Object> report = cleanup.toMap();
        assertEquals(List.of("Secret/trino-keytab", "ConfigMap/trino-krb5-conf"), report.get("removed"));
        assertEquals(List.of(Map.of("object", "ServiceAccount/trino-thanos-token",
                "reason", "this account may not delete it",
                "command", "oc delete serviceaccount trino-thanos-token -n analytics")), report.get("left"));
        assertEquals("oc delete clusterrolebinding kdps-monview-analytics-x",
                KdpsReleaseObjects.deleteCommand("ClusterRoleBinding", "kdps-monview-analytics-x", NS));
        assertEquals("oc delete certificate.cert-manager.io trino-tls -n analytics",
                KdpsReleaseObjects.deleteCommand("Certificate", "trino-tls", NS));
    }

    // ------------------------------------------------------------------ the objects that get the labels

    @Test
    void onlyTheRangerObjectsNamedAfterTheReleaseAreTheReleases() {
        Map<String, String> own = CommandService.rangerObjectLabels("trino", "{{releaseName}}-ranger-trino-security");
        assertEquals("trino", own.get(KdpsReleaseObjects.LABEL_RELEASE));
        assertEquals("ambari-k8s-view", own.get(KdpsReleaseObjects.LABEL_MANAGED_BY));
        assertEquals("trino", own.get("app.kubernetes.io/instance"));
        // Shared by the releases of the namespace: removing it with one release would break the others.
        Map<String, String> shared = CommandService.rangerObjectLabels("om", "ranger-openmetadata-tagsync");
        assertFalse(shared.containsKey(KdpsReleaseObjects.LABEL_RELEASE), shared.toString());
        assertFalse(shared.containsKey(KdpsReleaseObjects.LABEL_MANAGED_BY), shared.toString());
    }

    @Test
    void aKeytabSecretKdpsWritesIsTheReleases() {
        Map<String, String> labels = CommandService.keytabSecretLabels("trino", "trino/_HOST@EXAMPLE.ORG");
        assertEquals("trino", labels.get(KdpsReleaseObjects.LABEL_RELEASE));
        assertEquals("ambari-k8s-view", labels.get(KdpsReleaseObjects.LABEL_MANAGED_BY));
        assertEquals("trino", labels.get(CommandService.KEYTAB_RELEASE_LABEL));
    }

    @Test
    void theSignInSecretsCarryTheReleaseLabelButAreLeftToTheirOwnCleanup() {
        Map<String, String> labels = CommandService.oidcSecretLabels("superset");
        assertEquals("superset", labels.get(KdpsReleaseObjects.LABEL_RELEASE));
        assertEquals("kdps", labels.get(KdpsReleaseObjects.LABEL_MANAGED_BY));
    }

    @Test
    void theKrb5ConfConfigMapOfAReleaseIsTheReleases() {
        KubernetesService k8s = mock(KubernetesService.class);
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        CommandUtils utils = new CommandUtils(ctx, k8s);

        assertEquals("trino-krb5-conf", utils.prepareKrb5ConfConfigMap(NS, "trino", "[libdefaults]\n"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> annotations = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateConfigMap(eq(NS), eq("trino-krb5-conf"), anyMap(), labels.capture(),
                annotations.capture());
        assertEquals("trino", labels.getValue().get(KdpsReleaseObjects.LABEL_RELEASE));
        assertEquals("ambari-k8s-view", labels.getValue().get(KdpsReleaseObjects.LABEL_MANAGED_BY));
        assertEquals(NS, annotations.getValue().get(KdpsReleaseObjects.ANNOTATION_NAMESPACE));

        // One shared by the namespace (no release) is never marked as a release's.
        clearInvocations(k8s);
        assertTrue(utils.ensureKrb5ConfConfigMap(NS, "krb5-conf", "[libdefaults]\n"));
        verify(k8s).createOrUpdateConfigMap(eq(NS), eq("krb5-conf"), anyMap(), labels.capture(), anyMap());
        assertFalse(labels.getValue().containsKey(KdpsReleaseObjects.LABEL_RELEASE));
    }

    // ------------------------------------------------------------------ the record and the uninstall step

    @Test
    void theRecordKeepsTheClusterScopedObjectsWhenTheColumnIsFull() {
        K8sReleaseEntity e = new K8sReleaseEntity();
        e.setKdpsObjects(List.of("Secret/trino-keytab", "ClusterRoleBinding/kdps-monview-analytics-trino"));
        assertEquals(List.of("Secret/trino-keytab", "ClusterRoleBinding/kdps-monview-analytics-trino"),
                e.getKdpsObjects());

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add("Secret/trino-some-long-object-name-" + i);
        many.add("ClusterRoleBinding/kdps-monview-analytics-trino");
        e.setKdpsObjects(many);
        assertEquals(List.of("ClusterRoleBinding/kdps-monview-analytics-trino"), e.getKdpsObjects(),
                "the namespaced ones are found by their labels");
        assertTrue(e.getGitMetaJson().length() <= InstanceSettings.MAX_VALUE_LENGTH);

        // A deploy that found none records that it looked, unlike one by a version that did not.
        e.setKdpsObjects(List.of());
        assertEquals(List.of(), e.getKdpsObjects());
        e.setKdpsObjects(null);
        assertNull(e.getKdpsObjects());
    }

    private CommandService commandService(KubernetesService k8s) throws Exception {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("kdps-objects-" + Long.toHexString(System.nanoTime()));
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                Files.createTempDirectory("kdps-objects").toString()));
        when(ctx.getDataStore()).thenReturn(mock(DataStore.class));
        return new CommandService(ctx, mock(HelmService.class), k8s);
    }

    @Test
    void theUninstallStepAnswersTheReportOrNothing() throws Exception {
        KubernetesService k8s = mock(KubernetesService.class);
        CommandService service = commandService(k8s);
        try {
            when(k8s.revokeKdpsObjectsForRelease(NS, "empty", List.of()))
                    .thenReturn(new KdpsReleaseObjects.Cleanup(List.of(), List.of()));
            assertNull(service.revokeKdpsObjectsForRelease(NS, "empty", List.of()), "nothing created, nothing to say");

            when(k8s.revokeKdpsObjectsForRelease(NS, "trino", List.of("Secret/trino-keytab")))
                    .thenReturn(new KdpsReleaseObjects.Cleanup(List.of("Secret/trino-keytab"), List.of()));
            Map<String, Object> report = service.revokeKdpsObjectsForRelease(NS, "trino",
                    List.of("Secret/trino-keytab"));
            assertEquals(List.of("Secret/trino-keytab"), report.get("removed"));

            when(k8s.revokeKdpsObjectsForRelease(NS, "broken", List.of()))
                    .thenThrow(new IllegalStateException("boom"));
            Map<String, Object> failed = service.revokeKdpsObjectsForRelease(NS, "broken", List.of());
            assertEquals(List.of(), failed.get("removed"));
            assertTrue(String.valueOf(failed.get("note")).contains("oc delete secret,configmap,serviceaccount,role,"
                    + "rolebinding -l app.kubernetes.io/managed-by=ambari-k8s-view,ambari.clemlab.com/release=broken"
                    + " -n analytics"), String.valueOf(failed.get("note")));
        } finally {
            service.shutdown();
        }
    }

    /**
     * A release deployed, and not since redeployed, by a version that neither labelled nor recorded what it created:
     * an empty report would read as a complete cleanup, so it says what to look for.
     */
    @Test
    void theUninstallOfAReleaseDeployedBeforeKdpsRecordedItsObjectsSaysWhatToLookFor() throws Exception {
        KubernetesService k8s = mock(KubernetesService.class);
        CommandService service = commandService(k8s);
        try {
            when(k8s.revokeKdpsObjectsForRelease(NS, "trino", null))
                    .thenReturn(new KdpsReleaseObjects.Cleanup(List.of(), List.of()));

            Map<String, Object> report = service.revokeKdpsObjectsForRelease(NS, "trino", null);

            assertNotNull(report);
            assertEquals(List.of(), report.get("left"), "nothing failed");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> kept = (List<Map<String, Object>>) report.get("kept");
            assertEquals(1, kept.size(), kept.toString());
            assertEquals("Objects an earlier KDPS version created for the release", kept.get(0).get("object"));
            assertTrue(String.valueOf(kept.get(0).get("command")).contains("-n analytics | grep trino"),
                    kept.get(0).toString());
            assertTrue(String.valueOf(report.get("note")).startsWith("Left in place:"), report.toString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void theConfirmationListsOnlyTheClusterBindingsTheRecordNames() throws Exception {
        KubernetesService k8s = mock(KubernetesService.class);
        CommandService service = commandService(k8s);
        try {
            when(k8s.listKdpsObjectsForRelease(NS, "trino")).thenReturn(List.of("Secret/trino-keytab",
                    "ClusterRoleBinding/kdps-monview-analytics-trino", "ClusterRoleBinding/unrecorded"));

            assertEquals(List.of("Secret/trino-keytab", "ClusterRoleBinding/kdps-monview-analytics-trino"),
                    service.kdpsObjectsOfRelease(NS, "trino",
                            List.of("ClusterRoleBinding/kdps-monview-analytics-trino")));
            assertEquals(List.of("Secret/trino-keytab"), service.kdpsObjectsOfRelease(NS, "trino", null));
        } finally {
            service.shutdown();
        }
    }
}
