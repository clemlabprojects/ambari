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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.utils.TestCertificates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The release truststore of a deploy. A truststore selected for the release that cannot be read, or a Secret that
 * cannot be written for a release that needs it, stops the deploy with the reason, before anything is installed or
 * committed; the sources every release trusts are left out with a warning for the operation log.
 */
class TrustBundleStrictTest {

    private static final String NS = "analytics";
    private static final String RELEASE = "superset";

    private ViewContext ctx;
    private KubernetesService k8s;
    private WebHookConfigurationService webhook;
    private TrustBundleService trustBundle;

    private static String internalCaPem;
    private static String corporateCaPem;
    private static String partnerCaPem;

    @BeforeEach
    void setUp() {
        if (internalCaPem == null) {
            internalCaPem = TestCertificates.caPem("CN=Ambari Internal CA");
            corporateCaPem = TestCertificates.caPem("CN=Corporate Root CA");
            partnerCaPem = TestCertificates.caPem("CN=Partner Root CA");
        }
        ctx = mock(ViewContext.class);
        when(ctx.getAmbariProperty(anyString())).thenReturn(null);
        when(ctx.getProperties()).thenReturn(Map.of());
        k8s = mock(KubernetesService.class);
        webhook = mock(WebHookConfigurationService.class);
        when(webhook.ensureAmbariCertificateAuthority())
                .thenReturn(new WebHookConfigurationService.CertificateAuthorityMaterial(internalCaPem, null, null));
        TruststoreRegistryService registry = new TruststoreRegistryService(ctx, k8s, new PkiNamespaces(ctx, Map::of));
        trustBundle = new TrustBundleService(ctx, k8s, webhook, registry);
    }

    /** A truststore as the Truststores tab creates it, optionally flagged default. */
    private static Secret truststore(String namespace, String name, String caPem, boolean isDefault) {
        return new SecretBuilder().withNewMetadata().withNamespace(namespace).withName(name)
                .addToLabels(TruststoreRegistryService.LABEL_RESOURCE_TYPE, TruststoreRegistryService.RESOURCE_TYPE_TRUSTSTORE)
                .addToAnnotations(TruststoreRegistryService.ANNOTATION_TRUST_DEFAULT, Boolean.toString(isDefault))
                .endMetadata()
                .addToData("ca.crt", Base64.getEncoder().encodeToString(caPem.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    /** Whether a PEM bundle holds a certificate, whatever its line breaks. */
    private static boolean holds(String bundle, String certificatePem) {
        String body = certificatePem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return bundle.replaceAll("\\s", "").contains(body);
    }

    @SuppressWarnings("unchecked")
    private String writtenCaBundle() {
        ArgumentCaptor<Map<String, byte[]>> data = ArgumentCaptor.forClass(Map.class);
        verify(k8s).createOrUpdateOpaqueSecret(eq(NS), eq(RELEASE + "-truststore"), data.capture());
        return new String(data.getValue().get("ca.crt"), StandardCharsets.UTF_8);
    }

    @Test
    void aSelectedTruststoreThatCannotBeReadStopsTheDeployNamingEveryOne() {
        when(k8s.getSecret("ambari-pki", "corp-ca-truststore"))
                .thenReturn(truststore("ambari-pki", "corp-ca-truststore", corporateCaPem, false));

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> trustBundle.provisionForDeploy(
                NS, RELEASE, "SUPERSET", List.of("ambari-pki/corp-ca-truststore", "gone-ca", "team-a/partner-ca-truststore")));

        String message = stopped.getMessage();
        assertTrue(message.startsWith("Could not write truststore Secret analytics/superset-truststore: "), message);
        assertTrue(message.contains("'gone-ca' (Truststore not found: ambari-pki/gone-ca-truststore)"), message);
        assertTrue(message.contains("'team-a/partner-ca-truststore' (Truststore team-a/partner-ca-truststore is not in the "
                + "truststore project ambari-pki"), message);
        assertFalse(message.contains("'ambari-pki/corp-ca-truststore'"), "the readable one is not named: " + message);
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap());
    }

    @Test
    void onlyATruststoreCreatedInTheTruststoresTabIsTrusted() {
        when(k8s.getSecret("ambari-pki", "trino-truststore")).thenReturn(new SecretBuilder()
                .withNewMetadata().withNamespace("ambari-pki").withName("trino-truststore").endMetadata()
                .addToData("ca.crt", Base64.getEncoder().encodeToString(corporateCaPem.getBytes(StandardCharsets.UTF_8)))
                .build());

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> trustBundle.provisionForDeploy(NS, RELEASE, null, List.of("ambari-pki/trino-truststore")));

        assertTrue(stopped.getMessage().contains("is not a truststore created in the Truststores tab"), stopped.getMessage());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap());
    }

    @Test
    void aSelectedTruststoreIsReadFromTheTruststoreProjectTheWizardNamesIt() {
        when(k8s.getSecret("ambari-pki", "corp-ca-truststore"))
                .thenReturn(truststore("ambari-pki", "corp-ca-truststore", corporateCaPem, false));

        TrustBundleService.ProvisionResult result = trustBundle.provisionForDeploy(NS, RELEASE, "SUPERSET",
                List.of("ambari-pki/corp-ca-truststore"));

        assertTrue(result.provisioned());
        assertEquals(2, result.caCount(), "the Ambari Internal CA and the selected one");
        assertEquals("superset-truststore", result.helmOverrides().get("global.security.tls.truststoreSecret"));
        assertTrue(holds(writtenCaBundle(), corporateCaPem));
    }

    @Test
    void aTruststoreLabelledInAWorkloadProjectIsNeverTrusted() {
        // Whoever may write Secrets in a workload project can label one as a truststore: selecting it by reference
        // must not make it a trust anchor of the release.
        when(k8s.getSecret("team-a", "corp-ca-truststore"))
                .thenReturn(truststore("team-a", "corp-ca-truststore", partnerCaPem, false));

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> trustBundle.provisionForDeploy(NS, RELEASE, "SUPERSET", List.of("team-a/corp-ca-truststore")));

        assertTrue(stopped.getMessage().contains("is not in the truststore project ambari-pki"), stopped.getMessage());
        verify(k8s, never()).getSecret(eq("team-a"), anyString());
        verify(k8s, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), anyMap());
    }

    @Test
    void aRefWithoutANamespaceIsReadFromTheTruststoreProject() {
        trustBundle = new TrustBundleService(ctx, k8s, webhook, new TruststoreRegistryService(ctx, k8s,
                new PkiNamespaces(ctx, () -> Map.of("truststore", Map.of("namespace", "kdps-pki")))));
        when(k8s.getSecret("kdps-pki", "corp-ca-truststore"))
                .thenReturn(truststore("kdps-pki", "corp-ca-truststore", corporateCaPem, false));

        assertTrue(trustBundle.provisionForDeploy(NS, RELEASE, null, List.of("corp-ca")).provisioned());
        verify(k8s).getSecret("kdps-pki", "corp-ca-truststore");
    }

    @Test
    void aTruststoreSecretThatCannotBeWrittenStopsADeployThatSelectedTruststores() {
        when(k8s.getSecret("ambari-pki", "corp-ca-truststore"))
                .thenReturn(truststore("ambari-pki", "corp-ca-truststore", corporateCaPem, false));
        doThrow(new KubernetesClientException("secrets \"superset-truststore\" is forbidden", 403, null))
                .when(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("superset-truststore"), anyMap());

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> trustBundle.provisionForDeploy(NS, RELEASE, "SUPERSET", List.of("corp-ca")));

        assertTrue(stopped.getMessage().startsWith("Could not write truststore Secret analytics/superset-truststore: "),
                stopped.getMessage());
        assertTrue(stopped.getMessage().contains("is forbidden"), stopped.getMessage());
    }

    @Test
    void aServiceThatMountsTheTruststoreSecretCannotDoWithoutIt() {
        doThrow(new KubernetesClientException("connection reset", 0, null))
                .when(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("gitlab-truststore"), anyMap());

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> trustBundle.provisionForDeploy(NS, "gitlab", "GITLAB", null));

        assertTrue(stopped.getMessage().startsWith("Could not write truststore Secret analytics/gitlab-truststore: "),
                stopped.getMessage());
    }

    @Test
    void aServiceThatMountsTheTruststoreSecretStopsWhenNoCaCanBeAssembled() {
        when(webhook.ensureAmbariCertificateAuthority()).thenThrow(new IllegalStateException("CA store unreadable"));
        when(k8s.listViewSecretsWithLabel(anyString(), anyString(), anyString())).thenReturn(List.of());

        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> trustBundle.provisionForDeploy(NS, "hub", "JUPYTERHUB", List.of()));

        assertTrue(stopped.getMessage().startsWith("Could not write truststore Secret analytics/hub-truststore: no CA "
                + "certificate could be assembled"), stopped.getMessage());
        assertTrue(stopped.getMessage().contains("CA store unreadable"), stopped.getMessage());
    }

    @Test
    void withoutASelectionAndForAServiceThatDoesNotMountItAWriteFailureIsAWarning() {
        doThrow(new KubernetesClientException("connection reset", 0, null))
                .when(k8s).createOrUpdateOpaqueSecret(eq(NS), eq("trino-truststore"), anyMap());

        TrustBundleService.ProvisionResult result = trustBundle.provisionForDeploy(NS, "trino", "TRINO", List.of());

        assertFalse(result.provisioned());
        assertTrue(result.helmOverrides().isEmpty());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).startsWith("The truststore Secret analytics/trino-truststore could not be "
                + "written"), result.warnings().get(0));
    }

    @Test
    void theSourcesEveryReleaseTrustsAreLeftOutWithAWarning() {
        when(webhook.ensureAmbariCertificateAuthority()).thenThrow(new IllegalStateException("CA store unreadable"));
        when(k8s.listViewSecretsWithLabel(anyString(), anyString(), anyString()))
                .thenThrow(new KubernetesClientException("connection reset", 0, null));
        when(k8s.getSecret("ambari-pki", "corp-ca-truststore"))
                .thenReturn(truststore("ambari-pki", "corp-ca-truststore", corporateCaPem, false));

        TrustBundleService.ProvisionResult result = trustBundle.provisionForDeploy(NS, RELEASE, null, List.of("corp-ca"));

        assertTrue(result.provisioned());
        assertEquals(1, result.caCount());
        assertEquals(2, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).startsWith("The Ambari Internal CA is not trusted by this release"));
        assertTrue(result.warnings().get(1).startsWith("The default truststores of the Truststores tab are not trusted"));
    }

    @Test
    void defaultTruststoresComeFromTheTruststoreProjectOnly() {
        String otherCaPem = TestCertificates.caPem("CN=Not Default CA");
        // A cluster-wide account could list a default-flagged truststore Secret a tenant wrote in a workload project.
        when(k8s.listViewSecretsWithLabel(anyString(), eq(TruststoreRegistryService.LABEL_RESOURCE_TYPE),
                eq(TruststoreRegistryService.RESOURCE_TYPE_TRUSTSTORE))).thenAnswer(call -> switch ((String) call.getArgument(0)) {
                    case "ambari-pki" -> List.of(truststore("ambari-pki", "corp-ca-truststore", corporateCaPem, true),
                            truststore("ambari-pki", "other-ca-truststore", otherCaPem, false));
                    case "team-b" -> List.of(truststore("team-b", "partner-ca-truststore", partnerCaPem, true));
                    default -> List.of();
                });

        TrustBundleService.ProvisionResult result = trustBundle.provisionForDeploy(NS, RELEASE, null, null);

        assertEquals(2, result.caCount(), "the Ambari Internal CA and the default one");
        String bundle = writtenCaBundle();
        assertTrue(holds(bundle, corporateCaPem));
        assertFalse(holds(bundle, otherCaPem), "not flagged default");
        assertFalse(holds(bundle, partnerCaPem), "flagged default outside the truststore project");
        verify(k8s, never()).listViewSecretsWithLabel(eq("team-b"), anyString(), anyString());
    }

    @Test
    void gitLabAndJupyterHubMountTheReleaseTruststoreSecret() {
        StackDefinitionService definitions = new StackDefinitionService();
        assertTrue(TrustBundleService.bindsReleaseTruststore(definitions.getServiceDefinition("GITLAB")));
        assertTrue(TrustBundleService.bindsReleaseTruststore(definitions.getServiceDefinition("JUPYTERHUB")));
        assertFalse(TrustBundleService.bindsReleaseTruststore(definitions.getServiceDefinition("TRINO")));
        assertFalse(TrustBundleService.bindsReleaseTruststore(null));
    }
}
