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

package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.utils.CommandUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Upgrade/Config or any other redeploy never replaces the certificate an Ingress serves unless KDPS minted it or the
 * operator confirmed the replacement: the INGRESS_TLS_SELFSIGN step, and the plan before it, refuse a Secret the
 * operator uploaded or brought, and always one cert-manager or the External Secrets Operator keeps. A leaf KDPS
 * minted that is still good is kept as it is. Run against a Kubernetes API that stores what it is sent.
 */
@EnableKubernetesMockClient(crud = true)
class CommandServiceIngressTlsStepTest {

    static KubernetesClient client;

    private static final String NS = "analytics";
    private static final String REL = "trino";
    private static final String SECRET = REL + "-ingress-tls";
    private static final String HOST = "trino.apps.example.org";
    private static final String CORPORATE_CERT = "-----BEGIN CERTIFICATE-----\nMIIBcorporate\n-----END CERTIFICATE-----\n";
    private static final String CORPORATE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIEcorporate\n-----END PRIVATE KEY-----\n";

    private final Gson gson = new Gson();
    private InMemoryDataStore dataStore;
    private ViewContext ctx;
    private CommandService service;
    private CommandPlanFactory planFactory;

    /** Steps run with slf4j's MDC; see CommandServiceHelmStepTest for why it is loaded this way. */
    @BeforeAll
    static void loadMdc() {
        String javaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "1.8");
        try {
            org.slf4j.MDC.getMDCAdapter();
        } finally {
            System.setProperty("java.version", javaVersion);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        client.secrets().inNamespace(NS).delete();
        client.network().v1().ingresses().inNamespace(NS).delete();
        if (client.namespaces().withName(NS).get() == null) {
            client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NS).endMetadata().build())
                    .create();
        }
        Path workDir = Files.createTempDirectory("kdps-ingress-tls");
        ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getInstanceName()).thenReturn("ingress-tls-" + Long.toHexString(System.nanoTime()));
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        // The Ambari Internal CA the step signs with, generated once into this directory.
        when(ctx.getAmbariProperty("k8s.view.webhook.ca.dir")).thenReturn(workDir.resolve("ca").toString());
        dataStore = new InMemoryDataStore();
        when(ctx.getDataStore()).thenReturn(dataStore);

        KubernetesService kubernetes = new KubernetesService(ctx, client, mock(HelmClient.class), true);
        service = new CommandService(ctx, mock(HelmService.class, RETURNS_DEEP_STUBS), kubernetes);
        // Its background sweep of operation files reads ctx: let it finish before the tests go on.
        service.startupSweep.get(30, TimeUnit.SECONDS);
        planFactory = new CommandPlanFactory(ctx, gson, new WebHookConfigurationService(ctx, kubernetes),
                new CommandUtils(ctx, kubernetes));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Plans an operation made of one INGRESS_TLS_SELFSIGN step for the Ambari Internal CA, as a deploy plans it
     * ({@code keepValidLeaf}) or as "Renew TLS" does (not), runs it and returns the step's final status.
     */
    private CommandStatusEntity runSelfSign(String host, boolean replaceExisting, boolean keepValidLeaf) {
        return runSelfSign(host, replaceExisting, keepValidLeaf, "ambariInternal", null);
    }

    /** {@link #runSelfSign(String, boolean, boolean)} signing with the given CA. */
    private CommandStatusEntity runSelfSign(String host, boolean replaceExisting, boolean keepValidLeaf, String caMode,
                                            String caName) {
        String id = "op-" + UUID.randomUUID();
        CommandEntity root = new CommandEntity();
        root.setId(id);
        root.setType(CommandType.K8S_MANAGER_RELEASE_DEPLOY.name());
        root.setTitle("Deploy release " + REL);
        root.setCommandStatusId(id + "-status");
        root.setParamsJson(gson.toJson(Map.of("releaseName", REL, "namespace", NS)));
        CommandStatusEntity rootStatus = new CommandStatusEntity();
        rootStatus.setId(id + "-status");
        rootStatus.setState(CommandState.RUNNING.name());
        rootStatus.setAttempt(0);
        dataStore.store(rootStatus);
        dataStore.store(root);

        String stepId = planFactory.appendIngressTlsSelfSignStep(root, REL, NS, SECRET, host,
                List.of(REL + "." + NS + ".svc"), caMode, caName, 90, replaceExisting, keepValidLeaf);
        root.setChildListJson(gson.toJson(List.of(stepId)));
        dataStore.store(root);

        service.runNextStep(id);
        return dataStore.find(CommandStatusEntity.class, stepId + "-status");
    }

    private static Secret uploadedCorporateSecret() {
        // What "Upload cert + key" leaves (KubernetesService#createOrUpdateTlsSecret): no issuer label.
        return new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(NS)
                .addToAnnotations("managed-by", "ambari-k8s-view").endMetadata()
                .withType("kubernetes.io/tls")
                .addToData("tls.crt", b64(CORPORATE_CERT))
                .addToData("tls.key", b64(CORPORATE_KEY))
                .build();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Secret create(Secret s) {
        return client.secrets().inNamespace(NS).resource(s).create();
    }

    private static Secret current() {
        return client.secrets().inNamespace(NS).withName(SECRET).get();
    }

    private static X509Certificate leaf(Secret s) throws Exception {
        byte[] chain = Base64.getDecoder().decode(s.getData().get("tls.crt"));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(chain)).iterator().next();
    }

    private static void assertFailedWith(CommandStatusEntity step, String expected) {
        assertEquals(CommandState.FAILED.name(), step.getState(), step.getMessage());
        assertTrue(step.getMessage().contains(expected), step.getMessage());
    }

    private static HelmDeployRequest deploy() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName(REL);
        request.setNamespace(NS);
        return request;
    }

    // ------------------------------------------------------------------ the self-sign step

    @Test
    void aLeafIsSignedByACompanyCaTheViewKeepsWithoutReadingItFromTheCluster() throws Exception {
        String[] ca = CaRegistryServiceTest.issuingCa("Corp Issuing CA");
        // Uploaded while the default cluster cannot be read: the view keeps the CA itself.
        new CaRegistryService(ctx, mock(KubernetesService.class)).upload("corp", ca[0], ca[1], "");

        CommandStatusEntity step = runSelfSign(HOST, false, false, "companyUploaded", "corp");

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        assertEquals("CN=Corp Issuing CA", leaf(current()).getIssuerX500Principal().getName());
        assertEquals("companyUploaded", current().getMetadata().getLabels().get("ambari.clemlab.com/ca-mode"));
        assertTrue(client.secrets().inAnyNamespace().list().getItems().stream()
                .noneMatch(s -> s.getData() != null && ca[1].equals(new String(Base64.getDecoder()
                        .decode(String.valueOf(s.getData().get("tls.key"))), StandardCharsets.UTF_8))),
                "the CA key is written to no cluster");
    }

    @Test
    void theSelfSignStepRefusesASecretKdpsDidNotIssue() {
        Secret before = create(uploadedCorporateSecret());

        CommandStatusEntity step = runSelfSign(HOST, false, true);

        assertFailedWith(step, "was not issued by KDPS");
        Secret after = current();
        assertEquals(before.getMetadata().getResourceVersion(), after.getMetadata().getResourceVersion(),
                "the uploaded corporate certificate is left exactly as it was");
        assertEquals(b64(CORPORATE_CERT), after.getData().get("tls.crt"));
        assertEquals(b64(CORPORATE_KEY), after.getData().get("tls.key"));
    }

    @Test
    void theSelfSignStepRefusesACertManagerSecretEvenWhenTheReplacementWasConfirmed() {
        Secret before = create(new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .withAnnotations(Map.of("cert-manager.io/certificate-name", SECRET,
                        "cert-manager.io/issuer-name", "corporate-issuer"))
                .endMetadata().build());

        CommandStatusEntity step = runSelfSign(HOST, true, true);

        assertFailedWith(step, "managed by cert-manager");
        Secret after = current();
        assertEquals(before.getMetadata().getResourceVersion(), after.getMetadata().getResourceVersion());
        assertEquals("corporate-issuer", after.getMetadata().getAnnotations().get("cert-manager.io/issuer-name"),
                "the controller's annotations are kept");
    }

    @Test
    void theSelfSignStepRefusesAnExternalSecretsSecretEvenWhenTheReplacementWasConfirmed() {
        Secret before = create(new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .withAnnotations(Map.of("reconcile.external-secrets.io/data-hash", "0123")).endMetadata().build());

        CommandStatusEntity step = runSelfSign(HOST, true, true);

        assertFailedWith(step, "External Secrets Operator");
        assertEquals(before.getMetadata().getResourceVersion(), current().getMetadata().getResourceVersion());
    }

    @Test
    void aConfirmedReplacementMintsOverTheOperatorsSecret() throws Exception {
        create(uploadedCorporateSecret());

        CommandStatusEntity step = runSelfSign(HOST, true, true);

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        Secret after = current();
        assertEquals("self-signed", after.getMetadata().getLabels().get(IngressTlsSecretGuard.ISSUER_LABEL));
        assertEquals("ambariInternal", after.getMetadata().getLabels().get("ambari.clemlab.com/ca-mode"));
        assertTrue(leaf(after).getSubjectAlternativeNames().toString().contains(HOST));
    }

    @Test
    void theSecretKdpsMintsCarriesTheReleaseLabelsItsUninstallRemovesItBy() {
        CommandStatusEntity step = runSelfSign(HOST, false, true);

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        Map<String, String> labels = current().getMetadata().getLabels();
        assertEquals("ambari-k8s-view", labels.get(KdpsReleaseObjects.LABEL_MANAGED_BY));
        assertEquals(REL, labels.get(KdpsReleaseObjects.LABEL_RELEASE));
    }

    /**
     * The leaf an earlier version minted carries the release label only. A redeploy that keeps it gives it the rest of
     * the release selector, without minting again, so the uninstall of the release removes it.
     */
    @Test
    void aKeptLeafAnEarlierVersionMintedGetsTheReleaseLabelsItLacks() throws Exception {
        assertEquals(CommandState.SUCCEEDED.name(), runSelfSign(HOST, false, true).getState());
        client.secrets().inNamespace(NS).withName(SECRET).edit(s -> {
            s.getMetadata().getLabels().remove(KdpsReleaseObjects.LABEL_MANAGED_BY);
            s.getMetadata().getAnnotations().remove(KdpsReleaseObjects.ANNOTATION_NAMESPACE);
            return s;
        });
        Secret before = current();
        assertFalse(KdpsReleaseObjects.ofRelease(before.getMetadata(), NS, REL));

        CommandStatusEntity step = runSelfSign(HOST, false, true);

        assertEquals(CommandState.SUCCEEDED.name(), step.getState(), step.getMessage());
        Secret after = current();
        assertTrue(KdpsReleaseObjects.ofRelease(after.getMetadata(), NS, REL), after.getMetadata().toString());
        assertEquals(NS, after.getMetadata().getAnnotations().get(KdpsReleaseObjects.ANNOTATION_NAMESPACE));
        assertEquals(before.getData(), after.getData(), "the kept leaf and its key are not written again");
        assertEquals(leaf(before).getSerialNumber(), leaf(after).getSerialNumber());
    }

    @Test
    void aKeptLeafMintedForAnotherReleaseIsNotTakenAsTheReleases() {
        Secret minted = new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(NS)
                .addToLabels("managed-by", "ambari-k8s-view")
                .addToLabels(IngressTlsSecretGuard.ISSUER_LABEL, "self-signed")
                .addToLabels(KdpsReleaseObjects.LABEL_RELEASE, "superset").endMetadata().build();
        assertEquals(Map.of(), CommandService.keptSelfSignedTlsLabels(minted, NS, REL));
        Secret legacy = new SecretBuilder(minted).editMetadata()
                .addToLabels(KdpsReleaseObjects.LABEL_RELEASE, REL).endMetadata().build();
        assertEquals(KdpsReleaseObjects.labels(REL), CommandService.keptSelfSignedTlsLabels(legacy, NS, REL));
        // Not minted by KDPS: an uploaded or operator's Secret is never kept by this step, nor labelled by it.
        assertEquals(Map.of(), CommandService.keptSelfSignedTlsLabels(uploadedCorporateSecret(), NS, REL));
    }

    @Test
    void anUploadLabelsOnlyASecretKdpsCreatesOrUploadedForTheRelease() {
        assertEquals(KdpsReleaseObjects.labels(REL), CommandService.ingressTlsUploadLabels(null, NS, REL));
        Secret ours = new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .withLabels(KdpsReleaseObjects.labels(REL)).endMetadata().build();
        assertEquals(KdpsReleaseObjects.labels(REL), CommandService.ingressTlsUploadLabels(ours, NS, REL));
        // What an earlier version uploaded under the release's default name, before it labelled what it creates.
        assertEquals(KdpsReleaseObjects.labels(REL),
                CommandService.ingressTlsUploadLabels(uploadedCorporateSecret(), NS, REL));
        // The operator's own Secret, which they confirmed replacing: it stays theirs, and the uninstall leaves it.
        Secret operators = new SecretBuilder(uploadedCorporateSecret()).editMetadata().withAnnotations(Map.of())
                .endMetadata().build();
        assertEquals(Map.of(), CommandService.ingressTlsUploadLabels(operators, NS, REL));
        // A Secret uploaded under a name typed for it, which other releases may share.
        Secret typedName = new SecretBuilder(uploadedCorporateSecret()).editMetadata().withName("wildcard-tls")
                .endMetadata().build();
        assertEquals(Map.of(), CommandService.ingressTlsUploadLabels(typedName, NS, REL));
        Secret anotherRelease = new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .withLabels(KdpsReleaseObjects.labels("trino-b")).endMetadata().build();
        assertEquals(Map.of(), CommandService.ingressTlsUploadLabels(anotherRelease, NS, REL));
    }

    @Test
    void aRedeployKeepsTheLeafKdpsMintedWhenItIsStillGood() throws Exception {
        assertEquals(CommandState.SUCCEEDED.name(), runSelfSign(HOST, false, true).getState());
        Secret minted = current();

        CommandStatusEntity again = runSelfSign(HOST, false, true);

        assertEquals(CommandState.SUCCEEDED.name(), again.getState(), again.getMessage());
        Secret after = current();
        assertEquals(minted.getMetadata().getResourceVersion(), after.getMetadata().getResourceVersion(),
                "same CA, same names, still valid: the Secret is not touched");
        assertEquals(leaf(minted).getSerialNumber(), leaf(after).getSerialNumber());
    }

    @Test
    void aRedeployReMintsTheLeafKdpsMintedWhenTheHostChanged() throws Exception {
        assertEquals(CommandState.SUCCEEDED.name(), runSelfSign(HOST, false, true).getState());
        Secret minted = current();

        CommandStatusEntity moved = runSelfSign("trino-new.apps.example.org", false, true);

        assertEquals(CommandState.SUCCEEDED.name(), moved.getState(), moved.getMessage());
        X509Certificate reissued = leaf(current());
        assertNotEquals(leaf(minted).getSerialNumber(), reissued.getSerialNumber());
        assertTrue(reissued.getSubjectAlternativeNames().toString().contains("trino-new.apps.example.org"));
    }

    @Test
    void renewTlsAlwaysMintsANewLeaf() throws Exception {
        assertEquals(CommandState.SUCCEEDED.name(), runSelfSign(HOST, false, true).getState());
        Secret minted = current();

        // submitReleaseTlsRenewal plans the step without keepValidLeaf: renewing is what the operator asked for.
        CommandStatusEntity renewed = runSelfSign(HOST, false, false);

        assertEquals(CommandState.SUCCEEDED.name(), renewed.getState(), renewed.getMessage());
        assertNotEquals(leaf(minted).getSerialNumber(), leaf(current()).getSerialNumber());
    }

    // ------------------------------------------------------------------ the plan, before anything is written

    @Test
    void aDeployUploadingOverACertificateKdpsDidNotIssueIsRefusedUnlessConfirmed() {
        Secret before = create(new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .withAnnotations(Map.of()).endMetadata().build());
        HelmDeployRequest request = deploy();
        request.setIngressTlsUpload(new LinkedHashMap<>(Map.of("secretName", SECRET,
                "certPem", "-----BEGIN CERTIFICATE-----\nMIIBother\n-----END CERTIFICATE-----",
                "keyPem", "-----BEGIN PRIVATE KEY-----\nMIIEother\n-----END PRIVATE KEY-----")));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.checkIngressTlsSecretsReplaceable(request));
        assertTrue(refused.getMessage().contains(NS + "/" + SECRET), refused.getMessage());
        assertEquals(before.getMetadata().getResourceVersion(), current().getMetadata().getResourceVersion());

        request.setReplaceExistingTls(true);
        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(request));
    }

    @Test
    void aReinstallOverTheSecretAnEarlierInstallUploadedIsRefusedUntilConfirmed() {
        // An uninstall by an earlier KDPS version did not remove the Secret an upload wrote outside Helm: the release is
        // installed again with the default TLS mode, "Sign with Ambari Internal CA", and the wizard has not asked
        // anything yet.
        Secret before = create(uploadedCorporateSecret());
        HelmDeployRequest reinstall = deploy();
        reinstall.setIngressTlsSelfSign(new LinkedHashMap<>(Map.of("mode", "ambariInternal", "ingressHost", HOST,
                "secretName", SECRET)));

        IngressTlsSecretGuard.ReplacementNotConfirmedException refused = assertThrows(
                IngressTlsSecretGuard.ReplacementNotConfirmedException.class,
                () -> service.checkIngressTlsSecretsReplaceable(reinstall));
        // What the wizard needs to ask the operator about it.
        assertEquals(NS, refused.getNamespace());
        assertEquals(SECRET, refused.getSecretName());
        assertEquals(before.getMetadata().getResourceVersion(), current().getMetadata().getResourceVersion());

        // Confirmed: the wizard deploys again with replaceExistingTls.
        reinstall.setReplaceExistingTls(true);
        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(reinstall));

        // A renewed corporate certificate uploaded over it is asked about the same way.
        HelmDeployRequest renewed = deploy();
        renewed.setIngressTlsUpload(new LinkedHashMap<>(Map.of(
                "certPem", "-----BEGIN CERTIFICATE-----\nMIIBrenewed\n-----END CERTIFICATE-----",
                "keyPem", "-----BEGIN PRIVATE KEY-----\nMIIErenewed\n-----END PRIVATE KEY-----")));
        assertThrows(IngressTlsSecretGuard.ReplacementNotConfirmedException.class,
                () -> service.checkIngressTlsSecretsReplaceable(renewed));
        renewed.setReplaceExistingTls(true);
        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(renewed));
    }

    @Test
    void aDeployUploadingTheCertificateTheSecretAlreadyHoldsReplacesNothing() {
        create(uploadedCorporateSecret());
        HelmDeployRequest request = deploy();
        request.setIngressTlsUpload(new LinkedHashMap<>(Map.of("certPem", CORPORATE_CERT, "keyPem", CORPORATE_KEY)));

        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(request));
    }

    @Test
    void aDeploySelfSigningOverACertManagerSecretIsRefusedEvenWhenConfirmed() {
        create(new SecretBuilder(uploadedCorporateSecret()).editMetadata()
                .addNewOwnerReference().withApiVersion("cert-manager.io/v1").withKind("Certificate")
                .withName(SECRET).withUid("c1").endOwnerReference().endMetadata().build());
        HelmDeployRequest request = deploy();
        request.setIngressTlsSelfSign(new LinkedHashMap<>(Map.of("mode", "ambariInternal", "ingressHost", HOST)));
        request.setReplaceExistingTls(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.checkIngressTlsSecretsReplaceable(request));
        assertTrue(refused.getMessage().contains("cert-manager"), refused.getMessage());
    }

    @Test
    void aDeployWithoutIngressCertificateFieldsChecksNothing() {
        create(uploadedCorporateSecret());
        // "Keep current certificate": Upgrade/Config sends no ingressTls* field at all.
        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(deploy()));
        HelmDeployRequest emptyUpload = deploy();
        emptyUpload.setIngressTlsUpload(new LinkedHashMap<>(Map.of("certPem", "", "keyPem", "")));
        assertDoesNotThrow(() -> service.checkIngressTlsSecretsReplaceable(emptyUpload));
    }

    // ------------------------------------------------------------------ what Upgrade/Config is told

    @Test
    void theReleaseTlsStateSaysWhichCaMintedTheLeafOrThatItWasUploaded() {
        client.network().v1().ingresses().inNamespace(NS).resource(new IngressBuilder()
                .withNewMetadata().withName(REL).withNamespace(NS).addToLabels("app.kubernetes.io/instance", REL)
                .endMetadata()
                .withNewSpec().addNewTl().withSecretName(SECRET).withHosts(HOST).endTl().endSpec()
                .build()).create();
        create(uploadedCorporateSecret());

        ReleaseTlsService tls = new ReleaseTlsService(ctx, new KubernetesService(ctx, client, mock(HelmClient.class), true));
        Map<String, Object> uploaded = tls.describe(NS, REL).get(0);
        assertEquals("external", uploaded.get("source"));
        assertEquals(Boolean.TRUE, uploaded.get("uploaded"));

        client.secrets().inNamespace(NS).withName(SECRET).delete();
        assertEquals(CommandState.SUCCEEDED.name(), runSelfSign(HOST, false, true).getState());
        Map<String, Object> minted = tls.describe(NS, REL).get(0);
        assertEquals("k8s-view-self-signed", minted.get("source"));
        assertEquals("ambariInternal", minted.get("caMode"));
        assertNull(minted.get("uploaded"));
    }
}
