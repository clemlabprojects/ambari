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

package org.apache.ambari.view.k8s.service.om;

import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.FederationAuth;
import org.apache.ambari.view.k8s.service.om.OmAtlasProvisioning.FederationAuthUnavailableException;
import org.apache.ambari.view.k8s.utils.AmbariActionClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How OpenMetadata signs in to the Atlas of a cluster. OpenMetadata only speaks HTTP Basic, and turning on
 * Atlas file authentication makes the ODP Atlas scripts add a local administrator to the user store, so
 * KDPS must never turn it on unless the operator allowed it.
 */
class OmAtlasFederationAuthTest {

    private static final String CLUSTER = "c1";

    /** An Atlas with the given sign-in methods on (a method not listed is off). */
    private static AmbariActionClient atlas(boolean file, boolean ldap, boolean kerberos) throws Exception {
        AmbariActionClient ambari = mock(AmbariActionClient.class);
        when(ambari.getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.file"))
                .thenReturn(String.valueOf(file));
        when(ambari.getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.ldap"))
                .thenReturn(String.valueOf(ldap));
        when(ambari.getDesiredConfigProperty(CLUSTER, "application-properties", "atlas.authentication.method.kerberos"))
                .thenReturn(String.valueOf(kerberos));
        return ambari;
    }

    @Test
    void anAtlasWithFileAuthenticationAlreadyOnKeepsUsingIt() throws Exception {
        assertEquals(FederationAuth.FILE, OmAtlasProvisioning.planFederationAuth(atlas(true, false, true), CLUSTER, false, null, false));
        assertEquals(FederationAuth.FILE, OmAtlasProvisioning.planFederationAuth(atlas(true, true, false), CLUSTER, false, null, false));
        assertEquals(FederationAuth.FILE, OmAtlasProvisioning.planFederationAuth(atlas(true, false, false), CLUSTER, true, "svc-om", false));
    }

    @Test
    void anLdapAtlasWithFileAuthenticationAlsoOnTreatsTheSecretAsAnLdapAccount() throws Exception {
        // The directory account must never be copied into the file user store, where it would shadow the LDAP one.
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(atlas(true, true, false), CLUSTER, true, "svc-om", false));
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(atlas(true, true, true), CLUSTER, true, "svc-om", true));
        // Without a Secret the file user store is still the way in.
        assertEquals(FederationAuth.FILE, OmAtlasProvisioning.planFederationAuth(atlas(true, true, false), CLUSTER, false, null, false));
    }

    @Test
    void anLdapAtlasWhoseUserStoreAlreadyHoldsTheSecretsAccountKeepsItInStep() throws Exception {
        // An earlier version wrote the Secret's account into the file user store: dropping it from the sync would
        // leave a stale local password that refuses the Secret's rotated one.
        AmbariActionClient ambari = atlas(true, true, false);
        when(ambari.getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.username")).thenReturn("svc-om");

        assertEquals(FederationAuth.FILE, OmAtlasProvisioning.planFederationAuth(ambari, CLUSTER, true, " svc-om ", false));
        // Another account in the user store, or a Secret not readable yet: the Secret holds an LDAP account.
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(ambari, CLUSTER, true, "svc-other", false));
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(ambari, CLUSTER, true, null, false));
    }

    @Test
    void anLdapAtlasWithFileAuthenticationTurnedOffSinceIgnoresTheLeftOverLocalAccount() throws Exception {
        AmbariActionClient ambari = atlas(false, true, false);
        when(ambari.getDesiredConfigProperty(CLUSTER, "atlas-env", "openmetadata.federation.username")).thenReturn("svc-om");

        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(ambari, CLUSTER, true, "svc-om", false));
    }

    @Test
    void aKerberosOnlyAtlasGetsNoFileAuthenticationUnlessTheOperatorAllowsIt() throws Exception {
        assertEquals(FederationAuth.NONE, OmAtlasProvisioning.planFederationAuth(atlas(false, false, true), CLUSTER, false, null, false));
        assertEquals(FederationAuth.FILE_OPT_IN, OmAtlasProvisioning.planFederationAuth(atlas(false, false, true), CLUSTER, false, null, true));
    }

    @Test
    void anLdapAtlasUsesTheServiceAccountInsteadOfFileAuthentication() throws Exception {
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(atlas(false, true, false), CLUSTER, true, "svc-om", false));
        // The operator's service account wins over the opt-in: Atlas is left alone.
        assertEquals(FederationAuth.LDAP_ACCOUNT, OmAtlasProvisioning.planFederationAuth(atlas(false, true, true), CLUSTER, true, "svc-om", true));
    }

    @Test
    void anLdapAtlasWithoutAServiceAccountNeedsTheOperatorsChoice() throws Exception {
        assertEquals(FederationAuth.NONE, OmAtlasProvisioning.planFederationAuth(atlas(false, true, false), CLUSTER, false, null, false));
        assertEquals(FederationAuth.FILE_OPT_IN, OmAtlasProvisioning.planFederationAuth(atlas(false, true, false), CLUSTER, false, null, true));
    }

    @Test
    void theMessageTellsTheOperatorWhatToChoose() throws Exception {
        String ldap = OmAtlasProvisioning.federationAuthUnavailableMessage(atlas(false, true, false), CLUSTER);
        assertTrue(ldap.contains("LDAP service account"), ldap);
        assertTrue(ldap.contains("Allow Atlas file authentication"), ldap);
        String kerberos = OmAtlasProvisioning.federationAuthUnavailableMessage(atlas(false, false, true), CLUSTER);
        assertFalse(kerberos.contains("LDAP service account"), kerberos);
        assertTrue(kerberos.contains("Allow Atlas file authentication"), kerberos);
    }

    @Test
    void notAllowedAndOffRefusesWithoutChangingAtlas() throws Exception {
        AmbariActionClient ambari = atlas(false, false, true);

        assertThrows(FederationAuthUnavailableException.class,
                () -> OmAtlasProvisioning.applyFileAuthDecision(ambari, CLUSTER, false));

        verify(ambari, never()).updateClusterConfig(anyString(), anyString(), any(Map.class), anyString());
    }

    @Test
    void alreadyOnChangesNothingEvenWhenNotAllowed() throws Exception {
        AmbariActionClient ambari = atlas(true, false, false);

        assertFalse(OmAtlasProvisioning.applyFileAuthDecision(ambari, CLUSTER, false));

        verify(ambari, never()).updateClusterConfig(anyString(), anyString(), any(Map.class), anyString());
    }

    @Test
    void allowedAndOffTurnsFileAuthenticationOn() throws Exception {
        AmbariActionClient ambari = atlas(false, false, true);
        when(ambari.updateClusterConfig(anyString(), anyString(), any(Map.class), anyString())).thenReturn(true);

        assertTrue(OmAtlasProvisioning.applyFileAuthDecision(ambari, CLUSTER, true));

        verify(ambari).updateClusterConfig(CLUSTER, "application-properties",
                Map.of("atlas.authentication.method.file", "true",
                        "atlas.authentication.method.file.filename", "{{conf_dir}}/users-credentials.properties"),
                "kdps-om-atlas-fileauth");
    }

    @Test
    void theOperatorSecretsUsernameIsUsedWithoutItsTrailingNewline() {
        java.util.function.Function<String, String> b64 = v -> java.util.Base64.getEncoder()
                .encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        io.fabric8.kubernetes.api.model.Secret sec = new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata().withName("om-file-account").endMetadata()
                .withData(Map.of("username", b64.apply("svc-om\n"), "password", b64.apply("pw"))).build();
        io.fabric8.kubernetes.client.KubernetesClient k8s = mock(io.fabric8.kubernetes.client.KubernetesClient.class);
        io.fabric8.kubernetes.client.dsl.MixedOperation secrets = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation inNs =
                mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource named = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8s.secrets()).thenReturn(secrets);
        when(secrets.inNamespace("om")).thenReturn(inNs);
        when(inNs.withName("om-file-account")).thenReturn(named);
        when(named.get()).thenReturn(sec);

        OmAtlasProvisioning.Credentials creds =
                OmAtlasProvisioning.resolveOrCreate(k8s, "om", "om1", null, "om-file-account");

        assertEquals("svc-om", creds.username);
    }
}
