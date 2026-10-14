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

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * What KDPS creates for a release outside its chart carries the release's labels, and the uninstall of the release
 * removes it by them: never an object without them (one an administrator provided), one of another release, or one
 * another uninstall step removes; and what the account may not delete is reported with the command that deletes it,
 * never as a failure.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesServiceKdpsReleaseObjectsTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "trino";

    private static final ResourceDefinitionContext CERTIFICATES = new ResourceDefinitionContext.Builder()
            .withGroup("cert-manager.io").withVersion("v1").withKind("Certificate").withPlural("certificates")
            .withNamespaced(true).build();
    private static final ResourceDefinitionContext EXTERNAL_SECRETS = new ResourceDefinitionContext.Builder()
            .withGroup("external-secrets.io").withVersion("v1beta1").withKind("ExternalSecret")
            .withPlural("externalsecrets").withNamespaced(true).build();

    KubernetesMockServer server;
    KubernetesClient client;
    KubernetesService svc;

    @BeforeEach
    void setUp() {
        ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("kdps-objects-test");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir",
                System.getProperty("java.io.tmpdir") + "/k8s-ut"));
        svc = new KubernetesService(ctx, client, mock(HelmClient.class), /*isConfigured=*/true);
    }

    private static Map<String, String> labelsOf(String release) {
        return KdpsReleaseObjects.labels(release);
    }

    private void secret(String name, Map<String, String> labels, Map<String, String> annotations) {
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(NS).withLabels(labels).withAnnotations(annotations)
                .endMetadata().addToData("k", "dg==").build()).create();
    }

    private boolean secretExists(String name) {
        return client.secrets().inNamespace(NS).withName(name).get() != null;
    }

    /** What KDPS created for the release, as it is on the cluster after a deploy. */
    private void releaseObjects() {
        secret(RELEASE + "-keytab", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        secret(RELEASE + "-truststore", labelsOf(RELEASE), Map.of());
        secret(RELEASE + "-thanos-token", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(RELEASE + "-krb5-conf").withNamespace(NS).withLabels(labelsOf(RELEASE))
                .endMetadata().addToData("krb5.conf", "[libdefaults]").build()).create();
        client.serviceAccounts().inNamespace(NS).resource(new ServiceAccountBuilder()
                .withNewMetadata().withName(RELEASE + "-thanos-token").withNamespace(NS).withLabels(labelsOf(RELEASE))
                .endMetadata().build()).create();
        client.rbac().roles().inNamespace(NS).resource(new RoleBuilder()
                .withNewMetadata().withName(RELEASE + "-thanos-token-thanos-tenancy").withNamespace(NS)
                .withLabels(labelsOf(RELEASE)).endMetadata().build()).create();
        client.rbac().roleBindings().inNamespace(NS).resource(new RoleBindingBuilder()
                .withNewMetadata().withName(RELEASE + "-thanos-token-thanos-tenancy").withNamespace(NS)
                .withLabels(labelsOf(RELEASE)).endMetadata()
                .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("Role")
                .withName(RELEASE + "-thanos-token-thanos-tenancy").endRoleRef().build()).create();
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName(RELEASE + "-ingress-tls").withNamespace(NS)
                .withLabels(new HashMap<>(labelsOf(RELEASE))).endMetadata().build()).create();
    }

    @Test
    void theReleasesLabelledObjectsAreRemovedAndNothingElse() {
        releaseObjects();
        // Provided by an administrator for the release: no KDPS labels.
        secret(RELEASE + "-external-keytab", Map.of("app.kubernetes.io/instance", RELEASE), Map.of());
        // Another release of the project, and a release of the same name in another namespace.
        secret("trino-b-keytab", labelsOf("trino-b"), Map.of());
        secret("trino-elsewhere", labelsOf(RELEASE), KdpsReleaseObjects.annotations("other-project"));
        // Removed by the single sign-on step, which reads it first: the same release label, its own managed-by.
        secret(RELEASE + "-oidc-client", KdpsReleaseObjects.withReleaseLabel(
                Map.of("app.kubernetes.io/managed-by", "kdps"), RELEASE), Map.of());
        // Shared by the releases of the project.
        secret("hadoop-conf", Map.of("managed-by", "ambari-k8s-view"), Map.of());

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, null);

        assertEquals(List.of(), cleanup.left());
        assertTrue(cleanup.removed().containsAll(List.of("Certificate/trino-ingress-tls", "Secret/trino-keytab",
                "Secret/trino-truststore", "Secret/trino-thanos-token", "ConfigMap/trino-krb5-conf",
                "RoleBinding/trino-thanos-token-thanos-tenancy", "Role/trino-thanos-token-thanos-tenancy",
                "ServiceAccount/trino-thanos-token")), cleanup.removed().toString());
        assertEquals(8, cleanup.removed().size(), cleanup.removed().toString());
        assertEquals("Certificate/trino-ingress-tls", cleanup.removed().get(0),
                "the Certificate goes first, or cert-manager would issue its Secret again");
        assertTrue(client.secrets().inNamespace(NS).withLabels(KdpsReleaseObjects.selector(RELEASE)).list().getItems()
                .stream().allMatch(s -> s.getMetadata().getName().equals("trino-elsewhere")));
        assertNull(client.configMaps().inNamespace(NS).withName("trino-krb5-conf").get());
        assertNull(client.serviceAccounts().inNamespace(NS).withName("trino-thanos-token").get());
        for (String kept : List.of("trino-external-keytab", "trino-b-keytab", "trino-elsewhere", "trino-oidc-client",
                "hadoop-conf")) {
            assertTrue(secretExists(kept), kept + " is not the release's to remove");
        }
        assertTrue(cleanup.note().startsWith("Removed what KDPS created for the release outside its chart: "),
                cleanup.note());
    }

    @Test
    void whatTheAccountMayNotDeleteIsReportedWithItsCommand() {
        releaseObjects();
        server.expect().delete().withPath("/api/v1/namespaces/" + NS + "/secrets/" + RELEASE + "-keytab")
                .andReturn(403, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
                        + "\"reason\":\"Forbidden\",\"message\":\"secrets \\\"trino-keytab\\\" is forbidden\"}")
                .always();
        server.expect().get().withPath("/apis/rbac.authorization.k8s.io/v1/namespaces/" + NS
                        + "/roles?labelSelector=" + selectorQuery())
                .andReturn(403, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
                        + "\"reason\":\"Forbidden\",\"message\":\"roles is forbidden\"}")
                .always();

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, null);

        assertTrue(cleanup.removed().contains("Secret/trino-truststore"), "the rest is still removed");
        assertFalse(cleanup.removed().contains("Secret/trino-keytab"));
        KdpsReleaseObjects.Leftover keytab = cleanup.left().stream()
                .filter(l -> l.object().equals("Secret/trino-keytab")).findFirst().orElseThrow();
        assertEquals("this account may not delete it", keytab.reason());
        assertEquals("oc delete secret trino-keytab -n analytics", keytab.command());
        KdpsReleaseObjects.Leftover roles = cleanup.left().stream()
                .filter(l -> l.object().startsWith("Role objects")).findFirst().orElseThrow();
        assertEquals("this account may not list them", roles.reason());
        assertEquals("oc delete role -l app.kubernetes.io/managed-by=ambari-k8s-view,ambari.clemlab.com/release=trino"
                + " -n analytics", roles.command());
        assertTrue(cleanup.note().contains("oc delete secret trino-keytab -n analytics"), cleanup.note());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> left = (List<Map<String, Object>>) cleanup.toMap().get("left");
        assertEquals(2, left.size(), left.toString());
    }

    private static String selectorQuery() {
        return java.net.URLEncoder.encode(String.join(",", KdpsReleaseObjects.selector(RELEASE).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void clusterBinding(String name, Map<String, String> labels, Map<String, String> annotations) {
        client.rbac().clusterRoleBindings().resource(new ClusterRoleBindingBuilder()
                .withNewMetadata().withName(name).withLabels(labels).withAnnotations(annotations).endMetadata()
                .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole")
                .withName("cluster-monitoring-view").endRoleRef().build()).create();
    }

    @Test
    void aClusterBindingIsRemovedOnlyWhenTheReleaseRecordSaysKdpsCreatedIt() {
        clusterBinding("kdps-monview-analytics-trino-thanos-token", labelsOf(RELEASE),
                KdpsReleaseObjects.annotations(NS));
        // Labelled the same, but not in the release's record.
        clusterBinding("kdps-monview-analytics-unrecorded", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        // In the record, but made again by an administrator since: no labels.
        clusterBinding("kdps-monview-analytics-admin", Map.of(), Map.of());
        // The same release name in another project.
        clusterBinding("kdps-monview-other-trino-thanos-token", labelsOf(RELEASE),
                KdpsReleaseObjects.annotations("other"));

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of(
                "ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token",
                "ClusterRoleBinding/kdps-monview-analytics-admin",
                "ClusterRoleBinding/kdps-monview-other-trino-thanos-token",
                "Secret/trino-keytab"));

        assertEquals(List.of("ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"), cleanup.removed());
        assertEquals(List.of(), cleanup.left());
        assertNull(client.rbac().clusterRoleBindings().withName("kdps-monview-analytics-trino-thanos-token").get());
        for (String kept : List.of("kdps-monview-analytics-unrecorded", "kdps-monview-analytics-admin",
                "kdps-monview-other-trino-thanos-token")) {
            assertNotNull(client.rbac().clusterRoleBindings().withName(kept).get(), kept);
        }
    }

    @Test
    void aClusterBindingTheAccountMayNotDeleteIsLeftWithItsCommand() {
        clusterBinding("kdps-monview-analytics-trino-thanos-token", labelsOf(RELEASE),
                KdpsReleaseObjects.annotations(NS));
        server.expect().delete()
                .withPath("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings/kdps-monview-analytics-trino-thanos-token")
                .andReturn(403, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
                        + "\"reason\":\"Forbidden\",\"message\":\"forbidden\"}").always();

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE,
                List.of("ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"));

        assertEquals(List.of(), cleanup.removed());
        assertEquals(List.of(new KdpsReleaseObjects.Leftover(
                "ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token", "this account may not delete it",
                "oc delete clusterrolebinding kdps-monview-analytics-trino-thanos-token")), cleanup.left());
    }

    @Test
    void theObjectsOfAReleaseAreListedForItsRecordAndItsUninstallConfirmation() {
        releaseObjects();
        secret("trino-b-keytab", labelsOf("trino-b"), Map.of());
        clusterBinding("kdps-monview-analytics-trino-thanos-token", labelsOf(RELEASE),
                KdpsReleaseObjects.annotations(NS));

        List<String> objects = svc.listKdpsObjectsForRelease(NS, RELEASE);

        assertEquals(9, objects.size(), objects.toString());
        assertTrue(objects.contains("ClusterRoleBinding/kdps-monview-analytics-trino-thanos-token"), objects.toString());
        assertTrue(objects.contains("Secret/trino-keytab"), objects.toString());
        assertFalse(objects.contains("Secret/trino-b-keytab"), objects.toString());
    }

    @Test
    void nothingIsRemovedForAReleaseWithoutObjects() {
        secret("hadoop-conf", Map.of("managed-by", "ambari-k8s-view"), Map.of());

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertTrue(cleanup.isEmpty());
        assertTrue(secretExists("hadoop-conf"));
    }

    @Test
    void aCertificateKdpsCreatesAndTheSecretCertManagerWritesFromItCarryTheReleaseLabels() {
        svc.applyCertManagerCertificate(NS, "trino-ingress-tls", "trino-ingress-tls", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);

        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("trino-ingress-tls").get();
        assertTrue(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, RELEASE), cert.getMetadata().toString());
        assertEquals(NS, cert.getMetadata().getAnnotations().get(KdpsReleaseObjects.ANNOTATION_NAMESPACE));
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) ((Map<String, Object>) cert.getAdditionalProperties()
                .get("spec")).get("secretTemplate");
        assertEquals(KdpsReleaseObjects.labels(RELEASE), template.get("labels"));
        assertEquals(List.of("Certificate/trino-ingress-tls"), svc.listKdpsObjectsForRelease(NS, RELEASE));
    }

    @Test
    void aCertificateAnAdministratorMadeUnderThatNameIsNotTakenAsTheReleases() {
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName("wildcard").withNamespace(NS).endMetadata().build()).create();

        svc.applyCertManagerCertificate(NS, "wildcard", "wildcard", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);

        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("wildcard").get();
        assertFalse(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, RELEASE), cert.getMetadata().toString());
        assertTrue(svc.revokeKdpsObjectsForRelease(NS, RELEASE, null).isEmpty());
        assertNotNull(client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("wildcard").get());
    }

    /**
     * Every write of a Certificate sets {@code managed-by}: an administrator's Certificate named in the wizard must not
     * become the release's through a later deploy, nor lose its own labels.
     */
    @Test
    void anAdministratorsCertificateStaysTheirsThroughEveryDeploy() {
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName("wildcard").withNamespace(NS).addToLabels("team", "platform")
                .endMetadata().build()).create();

        for (int deploy = 0; deploy < 3; deploy++) {
            svc.applyCertManagerCertificate(NS, "wildcard", "wildcard", "corp-issuer", "ClusterIssuer",
                    List.of("trino.apps.example.org"), null, null, RELEASE);
        }

        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("wildcard").get();
        assertFalse(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, RELEASE), cert.getMetadata().toString());
        assertEquals("platform", cert.getMetadata().getLabels().get("team"));
        assertFalse(cert.getMetadata().getLabels().containsKey("managed-by"), cert.getMetadata().toString());
        assertTrue(svc.revokeKdpsObjectsForRelease(NS, RELEASE, null).isEmpty());
        assertNotNull(client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("wildcard").get());
    }

    /** A deploy of one release does not strip another release's claim, nor what cert-manager copies for it. */
    @Test
    void aCertificateAnotherReleaseClaimsKeepsItsClaimThroughEveryDeploy() {
        Map<String, Object> spec = new HashMap<>();
        spec.put("secretName", "shared");
        spec.put("secretTemplate", new HashMap<>(Map.of("labels", new HashMap<>(KdpsReleaseObjects.labels("superset")))));
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName("shared").withNamespace(NS)
                .withLabels(new HashMap<>(KdpsReleaseObjects.withLabels(Map.of("managed-by", "ambari-k8s-view"),
                        "superset"))).endMetadata()
                .withAdditionalProperties(Map.of("spec", spec)).build()).create();

        for (int deploy = 0; deploy < 2; deploy++) {
            svc.applyCertManagerCertificate(NS, "shared", "shared", "corp-issuer", "ClusterIssuer",
                    List.of("trino.apps.example.org"), null, null, RELEASE);
        }

        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("shared").get();
        assertTrue(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, "superset"), cert.getMetadata().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) ((Map<String, Object>) cert.getAdditionalProperties()
                .get("spec")).get("secretTemplate");
        assertEquals(KdpsReleaseObjects.labels("superset"), template.get("labels"));
        assertTrue(svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of()).isEmpty());
    }

    /**
     * The release's ExternalSecret keeps (and owns) the Secret another release's Ingress was told to bring as its
     * own: deleting it would have the Secret garbage-collected under that release. It goes with that release instead.
     */
    @Test
    void anExternalSecretWhoseSecretAnotherReleasesIngressUsesGoesWithThatRelease() {
        for (int deploy = 0; deploy < 2; deploy++) {
            svc.applyExternalSecret(NS, "wildcard-tls", "wildcard-tls", "vault", "ClusterSecretStore",
                    "pki/issue/wildcard", "1h", RELEASE);
        }
        ingress("superset", "superset", "wildcard-tls");

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of(), cleanup.removed());
        assertEquals(List.of("ExternalSecret/wildcard-tls"),
                cleanup.kept().stream().map(KdpsReleaseObjects.Leftover::object).toList());
        assertTrue(cleanup.kept().get(0).reason().contains("its Secret wildcard-tls is still used by the Ingress"
                + " superset"), cleanup.kept().get(0).reason());
        var es = client.genericKubernetesResources(EXTERNAL_SECRETS).inNamespace(NS).withName("wildcard-tls").get();
        assertNotNull(es);
        assertTrue(KdpsReleaseObjects.ofRelease(es.getMetadata(), NS, "superset"), es.getMetadata().toString());

        client.network().v1().ingresses().inNamespace(NS).withName("superset").delete();
        assertEquals(List.of("ExternalSecret/wildcard-tls"),
                svc.revokeKdpsObjectsForRelease(NS, "superset", List.of()).removed());
    }

    /**
     * The release's Certificate, and the Secret cert-manager writes from it, stay while another release's Ingress uses
     * that Secret: cert-manager keeps renewing it, and copies the new release's labels onto it.
     */
    @Test
    void aCertificateWhoseSecretAnotherReleasesIngressUsesStaysWithItsSecret() {
        svc.applyCertManagerCertificate(NS, "trino-ingress-tls", "wildcard-tls", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);
        secret("wildcard-tls", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        ingress("superset", "superset", "wildcard-tls");

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of(), cleanup.removed());
        assertEquals(List.of("Certificate/trino-ingress-tls", "Secret/wildcard-tls"),
                cleanup.kept().stream().map(KdpsReleaseObjects.Leftover::object).toList());
        var cert = client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("trino-ingress-tls").get();
        assertTrue(KdpsReleaseObjects.ofRelease(cert.getMetadata(), NS, "superset"), cert.getMetadata().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) ((Map<String, Object>) cert.getAdditionalProperties()
                .get("spec")).get("secretTemplate");
        assertEquals(KdpsReleaseObjects.labels("superset"), template.get("labels"));
    }

    @Test
    void anExternalSecretKdpsCreatesCarriesTheReleaseLabelsAndGoesWithIt() {
        svc.applyExternalSecret(NS, "trino-ingress-tls", "trino-ingress-tls", "vault", "ClusterSecretStore",
                "pki/issue/trino", "1h", RELEASE);

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, null);

        assertEquals(List.of("ExternalSecret/trino-ingress-tls"), cleanup.removed());
    }

    private static String forbidden(String what) {
        return "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"code\":403,"
                + "\"reason\":\"Forbidden\",\"message\":\"" + what + " is forbidden\"}";
    }

    /**
     * A project-limited account holds no rights on the kinds of operators it does not use, and is refused their list
     * even where the operator is not installed: KDPS made none of them for the release, so nothing is left.
     */
    @Test
    void anOperatorKindTheAccountMayNotListIsNoLeftoverWhenTheReleaseRecordsNone() {
        releaseObjects();
        server.expect().get().withPath("/apis/cert-manager.io/v1/namespaces/" + NS + "/certificates?labelSelector="
                + selectorQuery()).andReturn(403, forbidden("certificates")).always();
        server.expect().get().withPath("/apis/external-secrets.io/v1beta1/namespaces/" + NS
                + "/externalsecrets?labelSelector=" + selectorQuery()).andReturn(403, forbidden("externalsecrets"))
                .always();

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of("Secret/trino-keytab"));

        assertEquals(List.of(), cleanup.left());
        assertTrue(cleanup.removed().contains("Secret/trino-keytab"), cleanup.removed().toString());
        assertFalse(cleanup.note().contains("Could not remove"), cleanup.note());

        // One the release's record names is still reported: KDPS made it, and this account cannot see it now.
        KdpsReleaseObjects.Cleanup recorded = svc.revokeKdpsObjectsForRelease(NS, RELEASE,
                List.of("Certificate/trino-ingress-tls"));
        assertEquals(List.of("Certificate objects of the release"),
                recorded.left().stream().map(KdpsReleaseObjects.Leftover::object).toList());
    }

    private void ingress(String name, String helmRelease, String tlsSecret) {
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName(name).withNamespace(NS)
                .addToAnnotations("meta.helm.sh/release-name", helmRelease)
                .addToAnnotations("meta.helm.sh/release-namespace", NS).endMetadata()
                .withNewSpec().addNewTl().withSecretName(tlsSecret).withHosts(name + ".apps.example.org").endTl()
                .endSpec().build()).create();
    }

    /**
     * Two releases given the same uploaded certificate share its Secret, which carries the labels of the first. Its
     * uninstall leaves the Secret to the other release, whose Ingress still uses it, and that release's uninstall
     * removes it.
     */
    @Test
    void aSecretAnotherReleasesIngressStillUsesGoesWithThatRelease() {
        secret("wildcard-tls", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        secret(RELEASE + "-keytab", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        // The release's own Ingress, which Flux may not have removed yet, does not count.
        ingress("trino", RELEASE, "wildcard-tls");
        ingress("superset", "superset", "wildcard-tls");

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of("Secret/trino-keytab"), cleanup.removed());
        assertEquals(List.of(), cleanup.left());
        assertEquals(1, cleanup.kept().size(), cleanup.kept().toString());
        assertEquals("Secret/wildcard-tls", cleanup.kept().get(0).object());
        assertTrue(cleanup.kept().get(0).reason().contains("superset"), cleanup.kept().get(0).reason());
        assertNull(cleanup.kept().get(0).command(), "deleting it would take the other release's certificate away");
        assertTrue(cleanup.note().contains("Left in place:"), cleanup.note());
        var shared = client.secrets().inNamespace(NS).withName("wildcard-tls").get();
        assertNotNull(shared);
        assertTrue(KdpsReleaseObjects.ofRelease(shared.getMetadata(), NS, "superset"), shared.getMetadata().toString());

        // Once both releases are gone, so is the Secret.
        client.network().v1().ingresses().inNamespace(NS).withName("trino").delete();
        client.network().v1().ingresses().inNamespace(NS).withName("superset").delete();
        assertEquals(List.of("Secret/wildcard-tls"),
                svc.revokeKdpsObjectsForRelease(NS, "superset", List.of()).removed());
    }

    /**
     * When the Ingresses of the namespace cannot be listed, no one can tell whether another release still uses the
     * release's certificate: its TLS Secret and the Certificate that keeps it stay, reported with the command that
     * deletes them, while what is no Ingress's certificate (a keytab) still goes.
     */
    @Test
    void theCertificatesStayWhenTheIngressesCannotBeListed() {
        svc.applyCertManagerCertificate(NS, "trino-ingress-tls", "wildcard-tls", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName("wildcard-tls").withNamespace(NS).withLabels(labelsOf(RELEASE))
                .withAnnotations(KdpsReleaseObjects.annotations(NS)).endMetadata().withType("kubernetes.io/tls")
                .addToData("tls.crt", "Y3J0").addToData("tls.key", "a2V5").build()).create();
        secret(RELEASE + "-keytab", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        ingress("superset", "superset", "wildcard-tls");
        server.expect().get().withPath("/apis/networking.k8s.io/v1/namespaces/" + NS + "/ingresses")
                .andReturn(403, forbidden("ingresses.networking.k8s.io")).always();

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of("Secret/trino-keytab"), cleanup.removed());
        assertEquals(List.of("Certificate/trino-ingress-tls", "Secret/wildcard-tls"),
                cleanup.left().stream().map(KdpsReleaseObjects.Leftover::object).toList());
        cleanup.left().forEach(l -> assertEquals("could not check whether another release's Ingress still uses it",
                l.reason()));
        assertEquals("oc delete certificate.cert-manager.io trino-ingress-tls -n analytics",
                cleanup.left().get(0).command());
        assertEquals("oc delete secret wildcard-tls -n analytics", cleanup.left().get(1).command());
        assertTrue(cleanup.note().contains("oc delete secret wildcard-tls -n analytics"), cleanup.note());
        assertNotNull(client.secrets().inNamespace(NS).withName("wildcard-tls").get());
        assertNotNull(client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).withName("trino-ingress-tls")
                .get());
    }

    @Test
    void aSecretTheIngressesOfSeveralOtherReleasesUseIsNoReleasesAnyMore() {
        secret("wildcard-tls", labelsOf(RELEASE), KdpsReleaseObjects.annotations(NS));
        ingress("superset", "superset", "wildcard-tls");
        ingress("airflow", "airflow", "wildcard-tls");

        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of(), cleanup.removed());
        assertEquals(List.of("Secret/wildcard-tls"),
                cleanup.kept().stream().map(KdpsReleaseObjects.Leftover::object).toList());
        var shared = client.secrets().inNamespace(NS).withName("wildcard-tls").get();
        assertFalse(shared.getMetadata().getLabels().containsKey(KdpsReleaseObjects.LABEL_RELEASE),
                shared.getMetadata().toString());
    }

    /** A Certificate an earlier version wrote, before it labelled what it creates, is the release's on a redeploy. */
    @Test
    void aCertificateAnEarlierVersionWroteIsTakenAsTheReleasesOnARedeploy() {
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName("trino-ingress-tls").withNamespace(NS)
                .addToLabels("managed-by", "ambari-k8s-view").endMetadata().build()).create();

        svc.applyCertManagerCertificate(NS, "trino-ingress-tls", "trino-ingress-tls", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);

        assertEquals(List.of("Certificate/trino-ingress-tls"),
                svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of()).removed());
    }

    @Test
    void aCertificateAnotherReleaseClaimsIsNotTakenAsTheReleases() {
        client.genericKubernetesResources(CERTIFICATES).inNamespace(NS).resource(new GenericKubernetesResourceBuilder()
                .withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withNewMetadata().withName("shared").withNamespace(NS)
                .withLabels(new HashMap<>(KdpsReleaseObjects.withLabels(Map.of("managed-by", "ambari-k8s-view"),
                        "superset"))).endMetadata().build()).create();

        svc.applyCertManagerCertificate(NS, "shared", "shared", "corp-issuer", "ClusterIssuer",
                List.of("trino.apps.example.org"), null, null, RELEASE);

        assertTrue(svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of()).isEmpty());
    }

    /**
     * The autoscaler's monitoring objects a release deployed before KDPS labelled what it creates stay unlabelled
     * through a redeploy, and look the same as those an administrator makes by the instructions a deploy gives. They
     * are marked as the release's, unconfirmed: its uninstall names them with the command that deletes them, and
     * deletes none.
     */
    @Test
    void aRedeployOverUnlabelledAutoscalerObjectsHasTheUninstallNameThem() {
        String token = RELEASE + "-thanos-token";
        String tenancy = token + "-thanos-tenancy";
        client.serviceAccounts().inNamespace(NS).resource(new ServiceAccountBuilder()
                .withNewMetadata().withName(token).withNamespace(NS).endMetadata().build()).create();
        client.rbac().roles().inNamespace(NS).resource(new RoleBuilder()
                .withNewMetadata().withName(tenancy).withNamespace(NS).endMetadata().build()).create();
        client.rbac().roleBindings().inNamespace(NS).resource(new RoleBindingBuilder()
                .withNewMetadata().withName(tenancy).withNamespace(NS).endMetadata()
                .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName(tenancy)
                .endRoleRef().build()).create();
        client.secrets().inNamespace(NS).resource(new SecretBuilder()
                .withNewMetadata().withName(token).withNamespace(NS)
                .addToAnnotations("kubernetes.io/service-account.name", token).endMetadata()
                .withType("kubernetes.io/service-account-token").addToData("token", "dG9rZW4=").build()).create();
        // Another release's token account, named alike: not this release's to mark.
        client.serviceAccounts().inNamespace(NS).resource(new ServiceAccountBuilder()
                .withNewMetadata().withName("superset-thanos-token").withNamespace(NS).endMetadata().build()).create();

        assertNull(svc.ensureKedaThanosTokenSecret(NS, token, token, true, RELEASE));

        assertEquals(List.of(), svc.listKdpsObjectsForRelease(NS, RELEASE),
                "unconfirmed objects are not among those the uninstall deletes");
        KdpsReleaseObjects.Cleanup cleanup = svc.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());

        assertEquals(List.of(), cleanup.removed());
        assertEquals(List.of(), cleanup.left());
        assertEquals(List.of("Secret/" + token, "RoleBinding/" + tenancy, "Role/" + tenancy, "ServiceAccount/" + token),
                cleanup.kept().stream().map(KdpsReleaseObjects.Leftover::object).toList());
        assertEquals("oc delete secret " + token + " -n " + NS, cleanup.kept().get(0).command());
        assertNotNull(client.secrets().inNamespace(NS).withName(token).get());
        assertNotNull(client.serviceAccounts().inNamespace(NS).withName(token).get());
        Map<String, String> otherLabels = client.serviceAccounts().inNamespace(NS).withName("superset-thanos-token")
                .get().getMetadata().getLabels();
        assertTrue(otherLabels == null || otherLabels.isEmpty(), String.valueOf(otherLabels));
    }

    /** What a deploy by this version creates for the autoscaler is the release's, and its uninstall removes it. */
    @Test
    void theAutoscalerObjectsADeployCreatesGoWithTheRelease() {
        String token = RELEASE + "-thanos-token";
        KubernetesService spy = spy(svc);
        doReturn(true).when(spy).canI(anyString(), anyString(), anyString(), any(), any(), anyString());

        assertNull(spy.ensureKedaThanosTokenSecret(NS, token, token, true, RELEASE));
        // A redeploy finds them in place and leaves them the release's.
        assertNull(spy.ensureKedaThanosTokenSecret(NS, token, token, true, RELEASE));

        KdpsReleaseObjects.Cleanup cleanup = spy.revokeKdpsObjectsForRelease(NS, RELEASE, List.of());
        assertEquals(List.of(), cleanup.kept());
        assertEquals(4, cleanup.removed().size(), cleanup.removed().toString());
    }
}
