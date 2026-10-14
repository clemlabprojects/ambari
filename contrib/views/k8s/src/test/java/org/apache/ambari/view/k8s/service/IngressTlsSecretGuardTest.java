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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which existing ingress TLS Secrets a deploy may write over: none it did not mint unless the operator confirmed, and
 * never one kept by cert-manager or the External Secrets Operator, however that controller marks it.
 */
class IngressTlsSecretGuardTest {

    private static SecretBuilder secret() {
        return new SecretBuilder().withNewMetadata().withName("trino-ingress-tls").withNamespace("analytics")
                .endMetadata().withType("kubernetes.io/tls");
    }

    private static IllegalStateException refused(Secret existing, boolean replaceExisting) {
        return assertThrows(IllegalStateException.class, () ->
                IngressTlsSecretGuard.checkReplaceable(existing, "analytics", "trino-ingress-tls", replaceExisting));
    }

    @Test
    void aMissingSecretOrOneKdpsMintedMayBeWritten() {
        assertDoesNotThrow(() -> IngressTlsSecretGuard.checkReplaceable(null, "analytics", "trino-ingress-tls", false));
        Secret minted = secret().editMetadata().addToLabels(IngressTlsSecretGuard.ISSUER_LABEL, "self-signed")
                .endMetadata().build();
        assertDoesNotThrow(() -> IngressTlsSecretGuard.checkReplaceable(minted, "analytics", "trino-ingress-tls", false));
    }

    @Test
    void anUploadedOrOperatorSecretIsReplacedOnlyWhenTheOperatorConfirms() {
        // What an upload leaves: KubernetesService#createOrUpdateTlsSecret annotates it, but it carries no issuer label.
        Secret uploaded = secret().editMetadata().addToAnnotations("managed-by", "ambari-k8s-view").endMetadata().build();
        IllegalStateException e = refused(uploaded, false);
        assertTrue(e.getMessage().contains("analytics/trino-ingress-tls"), e.getMessage());
        assertDoesNotThrow(() -> IngressTlsSecretGuard.checkReplaceable(uploaded, "analytics", "trino-ingress-tls", true));
    }

    @Test
    void theRefusalAConfirmationLiftsNamesTheSecretAndFitsAFirstInstallToo() {
        // Uninstalling keeps the Secret, so a reinstall of the release meets the one its earlier install uploaded.
        Secret uploaded = secret().editMetadata().addToAnnotations("managed-by", "ambari-k8s-view").endMetadata().build();
        IngressTlsSecretGuard.ReplacementNotConfirmedException e = assertThrows(
                IngressTlsSecretGuard.ReplacementNotConfirmedException.class,
                () -> IngressTlsSecretGuard.checkReplaceable(uploaded, "analytics", "trino-ingress-tls", false));
        assertEquals("analytics", e.getNamespace());
        assertEquals("trino-ingress-tls", e.getSecretName());
        // An install offers no "Keep current certificate": "Bring your own Secret" serves the Secret as it is there too.
        assertTrue(e.getMessage().contains("'Bring your own Secret' with this Secret name"), e.getMessage());
        assertTrue(e.getMessage().contains("'Keep current certificate' when you upgrade"), e.getMessage());
        assertTrue(e.getMessage().contains("replaceExistingTls=true"), e.getMessage());
    }

    @Test
    void aControllerKeptSecretIsNotRefusedAsSomethingAConfirmationLifts() {
        Secret certManager = secret().editMetadata().addToAnnotations("cert-manager.io/certificate-name", "trino")
                .endMetadata().build();
        Secret externalSecret = secret().editMetadata().addToLabels("reconcile.external-secrets.io/managed", "true")
                .endMetadata().build();
        for (Secret s : new Secret[]{certManager, externalSecret}) {
            IllegalStateException e = refused(s, false);
            assertFalse(e instanceof IngressTlsSecretGuard.ReplacementNotConfirmedException, e.getMessage());
            assertTrue(e.getMessage().contains("'Bring your own Secret' with this Secret name"), e.getMessage());
        }
    }

    @Test
    void aCertManagerSecretIsNeverReplaced() {
        Secret annotated = secret().editMetadata().addToAnnotations("cert-manager.io/certificate-name", "trino")
                .endMetadata().build();
        Secret labelled = secret().editMetadata().addToLabels("controller.cert-manager.io/fao", "true").endMetadata()
                .build();
        Secret owned = secret().editMetadata().addNewOwnerReference().withApiVersion("cert-manager.io/v1")
                .withKind("Certificate").withName("trino").withUid("u1").endOwnerReference().endMetadata().build();
        for (Secret s : new Secret[]{annotated, labelled, owned}) {
            assertEquals(IngressTlsSecretGuard.CERT_MANAGER, IngressTlsSecretGuard.controllerOf(s));
            assertTrue(refused(s, true).getMessage().contains("cert-manager"));
        }
        // Even one that also carries the label of a leaf KDPS minted earlier, before cert-manager took it over.
        Secret adopted = secret().editMetadata().addToLabels(IngressTlsSecretGuard.ISSUER_LABEL, "self-signed")
                .addToAnnotations("cert-manager.io/certificate-name", "trino").endMetadata().build();
        assertTrue(refused(adopted, true).getMessage().contains("cert-manager"));
    }

    @Test
    void anExternalSecretsSecretIsNeverReplaced() {
        Secret hashed = secret().editMetadata().addToAnnotations("reconcile.external-secrets.io/data-hash", "abc")
                .endMetadata().build();
        Secret labelled = secret().editMetadata().addToLabels("reconcile.external-secrets.io/managed", "true")
                .endMetadata().build();
        Secret owned = secret().editMetadata().addNewOwnerReference().withApiVersion("external-secrets.io/v1beta1")
                .withKind("ExternalSecret").withName("trino").withUid("u2").endOwnerReference().endMetadata().build();
        for (Secret s : new Secret[]{hashed, labelled, owned}) {
            assertEquals(IngressTlsSecretGuard.EXTERNAL_SECRETS, IngressTlsSecretGuard.controllerOf(s));
            assertTrue(refused(s, true).getMessage().contains("External Secrets Operator"));
        }
    }

    @Test
    void aSecretWithoutMetadataIsTreatedAsNotMintedByKdps() {
        Secret bare = new Secret();
        assertNull(IngressTlsSecretGuard.controllerOf(bare));
        assertFalse(IngressTlsSecretGuard.mintedByView(bare));
        refused(bare, false);
    }
}
