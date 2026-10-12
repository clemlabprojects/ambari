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
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService.ServerKeystoreMaterial;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TlsManagerTest {

    @Test
    void generatesKeystoreAndPasswordSecrets() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);

        when(webHookService.issueServerKeystore(anyList(), anyString(), any(), anyInt()))
                .thenReturn(new ServerKeystoreMaterial("CERT", "KEY", "CA", new byte[]{1, 2, 3}));

        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);

        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName("rel");
        request.setNamespace("ns");

        Map<String, Object> httpsSpec = new HashMap<>();
        httpsSpec.put("enabled", true);
        httpsSpec.put("autoGenerate", true);
        httpsSpec.put("secretName", "rel-https");
        httpsSpec.put("passwordSecretName", "rel-https-pass");
        httpsSpec.put("keystoreKey", "keystore.p12");
        httpsSpec.put("passwordKey", "truststore.password");
        httpsSpec.put("keystoreType", "PKCS12");
        httpsSpec.put("password", "testpass");
        httpsSpec.put("dnsNames", new ArrayList<>(List.of("rel.ns.svc")));

        Map<String, Object> tls = new HashMap<>();
        tls.put("https", httpsSpec);

        tlsManager.applyTls(tls, request, new HashMap<>());

        verify(kubernetesService).createNamespace("ns");

        ArgumentCaptor<Map<String, byte[]>> keystoreCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetesService).createOrUpdateOpaqueSecret(eq("ns"), eq("rel-https"), keystoreCaptor.capture());
        Map<String, byte[]> keystoreData = keystoreCaptor.getValue();
        assertTrue(keystoreData.containsKey("keystore.p12"), "Keystore secret should contain the keystore binary");
        assertArrayEquals("testpass".getBytes(), keystoreData.get("truststore.password"));

        ArgumentCaptor<Map<String, byte[]>> passwordCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetesService).createOrUpdateOpaqueSecret(eq("ns"), eq("rel-https-pass"), passwordCaptor.capture());
        Map<String, byte[]> passwordData = passwordCaptor.getValue();
        assertArrayEquals("testpass".getBytes(), passwordData.get("truststore.password"));

        verify(webHookService).issueServerKeystore(anyList(), eq("PKCS12"), any(), anyInt());
    }

    /**
     * With the operator's own keystore, the typed password goes to the password Secret the chart mounts, instead of a
     * line in the server configuration, which is a ConfigMap.
     */
    @Test
    void ownKeystoreTypedPasswordCreatesTheMissingPasswordSecret() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);
        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);

        tlsManager.applyTls(ownKeystore("typed-pass"), request(), new HashMap<>());

        ArgumentCaptor<Map<String, byte[]>> passwordCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetesService).createOrUpdateOpaqueSecret(eq("ns"), eq("rel-https-pass"), passwordCaptor.capture());
        assertArrayEquals("typed-pass".getBytes(), passwordCaptor.getValue().get("truststore.password"));
        assertEquals(1, passwordCaptor.getValue().size(), "the password Secret holds the password only");
        verify(kubernetesService, never()).createOrUpdateOpaqueSecret(eq("ns"), eq("rel-https"), any());
        verifyNoInteractions(webHookService);
    }

    @Test
    void ownKeystoreLeavesAPasswordSecretHoldingTheTypedPasswordAsItIs() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);
        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);
        for (boolean writtenByTheView : List.of(false, true)) {
            when(kubernetesService.getSecret("ns", "rel-https-pass"))
                    .thenReturn(passwordSecret("truststore.password", "typed-pass", writtenByTheView));

            tlsManager.applyTls(ownKeystore("typed-pass"), request(), new HashMap<>());
        }

        verify(kubernetesService, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), any());
    }

    /**
     * A password Secret an earlier deploy wrote (a generated keystore, or another typed password) takes the typed
     * password: kept, the service would not open the operator's keystore with the old one.
     */
    @Test
    void ownKeystoreTypedPasswordReplacesThePasswordAnEarlierDeployWrote() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);
        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);
        when(kubernetesService.getSecret("ns", "rel-https-pass"))
                .thenReturn(passwordSecret("truststore.password", "generated-pass", true));

        tlsManager.applyTls(ownKeystore("typed-pass"), request(), new HashMap<>());

        ArgumentCaptor<Map<String, byte[]>> passwordCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetesService).createOrUpdateOpaqueSecret(eq("ns"), eq("rel-https-pass"), passwordCaptor.capture());
        assertArrayEquals("typed-pass".getBytes(), passwordCaptor.getValue().get("truststore.password"));
    }

    /**
     * The operator's own password Secret is never overwritten. When it does not hold the typed password, the deploy is
     * refused with what to do, instead of a service that cannot open its keystore.
     */
    @Test
    void ownKeystoreRefusesAnOperatorPasswordSecretThatDoesNotHoldTheTypedPassword() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);
        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);

        when(kubernetesService.getSecret("ns", "rel-https-pass"))
                .thenReturn(passwordSecret("truststore.password", "operator-pass", false));
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> tlsManager.applyTls(ownKeystore("typed-pass"), request(), new HashMap<>()));
        assertTrue(other.getMessage().contains("Secret 'rel-https-pass' in namespace 'ns' holds a different keystore"
                + " password (key 'truststore.password')"), other.getMessage());
        assertTrue(other.getMessage().contains("Clear 'Keystore password'"), other.getMessage());

        when(kubernetesService.getSecret("ns", "rel-https-pass"))
                .thenReturn(passwordSecret("other.key", "typed-pass", false));
        IllegalArgumentException noKey = assertThrows(IllegalArgumentException.class,
                () -> tlsManager.applyTls(ownKeystore("typed-pass"), request(), new HashMap<>()));
        assertTrue(noKey.getMessage().contains("Secret 'rel-https-pass' in namespace 'ns' has no key"
                + " 'truststore.password'"), noKey.getMessage());

        verify(kubernetesService, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), any());
    }

    @Test
    void ownKeystoreWithoutATypedPasswordCreatesNothing() {
        KubernetesService kubernetesService = mock(KubernetesService.class);
        WebHookConfigurationService webHookService = mock(WebHookConfigurationService.class);
        TlsManager tlsManager = new TlsManager(kubernetesService, webHookService);

        tlsManager.applyTls(ownKeystore(""), request(), new HashMap<>());

        verify(kubernetesService, never()).getSecret(anyString(), anyString());
        verify(kubernetesService, never()).createOrUpdateOpaqueSecret(anyString(), anyString(), any());
    }

    private static HelmDeployRequest request() {
        HelmDeployRequest request = new HelmDeployRequest();
        request.setReleaseName("rel");
        request.setNamespace("ns");
        return request;
    }

    /** A password Secret holding {@code password} under {@code key}, written by this view or by the operator. */
    private static Secret passwordSecret(String key, String password, boolean writtenByTheView) {
        SecretBuilder secret = new SecretBuilder()
                .withNewMetadata().withName("rel-https-pass").withNamespace("ns").endMetadata()
                .withData(Map.of(key, Base64.getEncoder().encodeToString(password.getBytes())));
        if (writtenByTheView) {
            secret.editMetadata().addToAnnotations("managed-by", "ambari-k8s-view").endMetadata();
        }
        return secret.build();
    }

    private static Map<String, Object> ownKeystore(String password) {
        Map<String, Object> httpsSpec = new HashMap<>();
        httpsSpec.put("enabled", true);
        httpsSpec.put("autoGenerate", false);
        httpsSpec.put("secretName", "rel-https");
        httpsSpec.put("passwordSecretName", "rel-https-pass");
        httpsSpec.put("passwordKey", "truststore.password");
        httpsSpec.put("password", password);
        Map<String, Object> tls = new HashMap<>();
        tls.put("https", httpsSpec);
        return tls;
    }
}
