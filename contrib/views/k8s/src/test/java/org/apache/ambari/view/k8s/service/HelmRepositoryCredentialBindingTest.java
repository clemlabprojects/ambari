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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A chart repository's credentials only ever go to the registry they were entered for: whatever secret reference a
 * request carries, and whatever the repository is changed to. The view's DataStore merges a saved repository into the
 * stored one, as Ambari's does.
 */
class HelmRepositoryCredentialBindingTest {

    @TempDir
    Path workDir;

    PerUserViewContext view;
    HelmClient helm;
    HelmRepositoryService repositories;
    InstanceSettings settings;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("kdps", workDir).as("admin");
        helm = mock(HelmClient.class);
        repositories = new HelmRepositoryService(view.context, helm);
        settings = new InstanceSettings(view.context);
        // Other secrets of the view, kept in the same settings with the same key.
        settings.putSecret("openshift.password.enc", "OpenShift-Pass-1");
        settings.putSecret("context.ext.rangerAdminPassword", "Ranger-Pass-2");
    }

    private static HelmRepoEntity oci(String id, String url) {
        HelmRepoEntity repo = new HelmRepoEntity();
        repo.setId(id);
        repo.setType("OCI");
        repo.setName(id);
        repo.setUrl(url);
        repo.setAuthMode("basic");
        repo.setUsername("robot$kdps");
        return repo;
    }

    /** The repository as saved by the Helm repositories page: the stored one with some properties changed. */
    private static HelmRepoEntity edited(String id, String url, Consumer<HelmRepoEntity> change) {
        HelmRepoEntity repo = oci(id, url);
        change.accept(repo);
        return repo;
    }

    private void harborWithItsToken() {
        repositories.save(oci("harbor", "harbor.example.com/kdps"), "Registry-Token-3");
    }

    @Test
    void aSecretReferenceSentWithTheRepositoryIsNotKept() {
        HelmRepoEntity forged = oci("x", "attacker.example");
        forged.setSecretRef("openshift.password.enc");

        repositories.save(forged, null);

        assertEquals("", repositories.get("x").getSecretRef(), "the server's value: no secret");
        assertNull(repositories.readPlainSecret(repositories.get("x")));
        repositories.ociLogin("x");
        verify(helm).ociLogin(eq("attacker.example"), eq("robot$kdps"), isNull(), any(Path.class));

        // Nor on a repository that has a secret of its own.
        harborWithItsToken();
        HelmRepoEntity again = oci("harbor", "harbor.example.com/kdps");
        again.setSecretRef("context.ext.rangerAdminPassword");
        repositories.save(again, null);
        assertEquals("helm.repo.secret.harbor", repositories.get("harbor").getSecretRef());
        assertEquals("Registry-Token-3", repositories.readPlainSecret(repositories.get("harbor")));
    }

    @Test
    void theSecretReferenceIsNeitherReadFromNorWrittenToJson() throws Exception {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        HelmRepoEntity posted = json.readValue("{\"id\":\"x\",\"type\":\"OCI\",\"name\":\"x\",\"url\":\"attacker.example\","
                + "\"authMode\":\"basic\",\"username\":\"u\",\"secretRef\":\"openshift.password.enc\","
                + "\"plainSecret\":\"Typed-In\"}", HelmRepoEntity.class);

        assertNull(posted.getSecretRef());
        assertEquals("Typed-In", posted.getPlainSecret(), "the secret typed in still reaches the server");
        HelmRepoEntity stored = oci("harbor", "harbor.example.com/kdps");
        stored.setSecretRef("helm.repo.secret.harbor");
        assertFalse(json.writeValueAsString(stored).contains("secretRef"));
    }

    @Test
    void oneRepositoryCannotReadAnothersSecret() {
        harborWithItsToken();
        HelmRepoEntity other = oci("b", "attacker.example");
        other.setSecretRef("helm.repo.secret.harbor");
        repositories.save(other, null);

        assertNull(repositories.readPlainSecret(repositories.get("b")));
        repositories.ociLogin("b");
        verify(helm).ociLogin(eq("attacker.example"), eq("robot$kdps"), isNull(), any(Path.class));
        assertEquals("Registry-Token-3", repositories.readPlainSecret(repositories.get("harbor")), "untouched");
    }

    @Test
    void movingARepositoryToAnotherRegistryDropsItsSecret() {
        harborWithItsToken();

        repositories.save(edited("harbor", "attacker.example/kdps", r -> { }), null);

        HelmRepoEntity moved = repositories.get("harbor");
        assertNull(repositories.readPlainSecret(moved));
        assertNull(settings.get("helm.repo.secret.harbor"), "the secret itself is gone");
        assertTrue(moved.isAuthInvalid(), "the registry login asks for the secret again");
        repositories.ociLogin("harbor");
        verify(helm).ociLogin(eq("attacker.example/kdps"), eq("robot$kdps"), isNull(), any(Path.class));
    }

    @Test
    void anotherRegistryHostTypeOrUserDropsTheSecretToo() {
        for (Consumer<HelmRepoEntity> change : java.util.List.<Consumer<HelmRepoEntity>>of(
                r -> r.setImageRegistryHostOverride("attacker.example"),
                r -> r.setType("HTTP"),
                r -> r.setUsername("someone-else"))) {
            harborWithItsToken();

            repositories.save(edited("harbor", "harbor.example.com/kdps", change), null);

            assertNull(repositories.readPlainSecret(repositories.get("harbor")));
            assertTrue(repositories.get("harbor").isAuthInvalid());
            repositories.delete("harbor");
        }
    }

    @Test
    void otherChangesKeepTheSecretAndANewSecretMovesWithTheRepository() {
        harborWithItsToken();

        repositories.save(edited("harbor", "harbor.example.com/kdps", r -> {
            r.setName("Harbor (KDPS)");
            r.setImageProject("kdps-images");
            r.setUsername(null); // left out of the request: the stored user name stays
        }), null);
        assertEquals("Registry-Token-3", repositories.readPlainSecret(repositories.get("harbor")));
        assertFalse(repositories.get("harbor").isAuthInvalid());

        repositories.save(edited("harbor", "registry.internal/kdps", r -> { }), "Internal-Token-4");
        assertEquals("Internal-Token-4", repositories.readPlainSecret(repositories.get("harbor")));
        assertFalse(repositories.get("harbor").isAuthInvalid());
    }

    @Test
    void theSecretTypedInIsNeverKeptInTheRepositoryRow() {
        HelmRepoEntity posted = oci("harbor", "harbor.example.com/kdps");
        posted.setPlainSecret("Registry-Token-3");
        repositories.save(posted, posted.getPlainSecret());

        HelmRepoEntity row = view.dataStore.find(HelmRepoEntity.class, "harbor");
        assertNull(row.getPlainSecret());
        assertEquals("Registry-Token-3", repositories.readPlainSecret(row));
    }

    @Test
    void deletingARepositoryRemovesOnlyItsOwnSecret() throws Exception {
        harborWithItsToken();
        // A row an earlier version stored with another setting as its secret reference.
        HelmRepoEntity legacy = oci("x", "attacker.example");
        legacy.setSecretRef("openshift.password.enc");
        view.dataStore.store(legacy);

        repositories.delete("x");
        repositories.delete("harbor");

        assertEquals("OpenShift-Pass-1", settings.getSecret("openshift.password.enc"), "the view stays connected");
        assertNull(settings.get("helm.repo.secret.harbor"));
    }

    @Test
    void theStartupCheckClearsSecretReferencesAndSecretsEarlierVersionsKeptInRows() throws Exception {
        harborWithItsToken();
        HelmRepoEntity legacy = oci("x", "attacker.example");
        legacy.setSecretRef("openshift.password.enc");
        legacy.setPlainSecret("Kept-In-Clear");
        view.dataStore.store(legacy);

        assertEquals(1, repositories.clearForeignSecretRefs());

        HelmRepoEntity cleaned = view.dataStore.find(HelmRepoEntity.class, "x");
        assertEquals("", cleaned.getSecretRef());
        assertEquals("", cleaned.getPlainSecret());
        assertEquals("helm.repo.secret.harbor", view.dataStore.find(HelmRepoEntity.class, "harbor").getSecretRef());
        assertEquals("OpenShift-Pass-1", settings.getSecret("openshift.password.enc"));
        assertEquals(0, repositories.clearForeignSecretRefs(), "nothing left to clear");
    }
}
