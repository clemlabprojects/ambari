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

import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService.ServerKeystoreMaterial;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
