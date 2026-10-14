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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.k8s.requests.HelmRepoRequest;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.store.HelmRepoEntity;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        // As the view's REST layer binds a request body: the page also sends back what it read (authInvalid...).
        ObjectMapper json = new ObjectMapper();

        HelmRepoRequest posted = json.readValue("{\"id\":\"x\",\"type\":\"OCI\",\"name\":\"x\","
                + "\"url\":\"attacker.example\",\"authMode\":\"basic\",\"username\":\"u\",\"secretRef\":\"openshift.password.enc\","
                + "\"authInvalid\":false,\"lastChecked\":null,\"plainSecret\":\"Typed-In\"}", HelmRepoRequest.class);

        assertNull(posted.toEntity().getSecretRef());
        assertEquals("Typed-In", posted.plainSecret, "the secret typed in still reaches the server");
        HelmRepoEntity stored = oci("harbor", "harbor.example.com/kdps");
        stored.setSecretRef("helm.repo.secret.harbor");
        String written = json.writeValueAsString(stored);
        assertFalse(written.contains("secretRef"), written);
        assertFalse(written.contains("plainSecret"), written);
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
    void theSecretTypedInIsNeverKeptInTheRepositoryRow() throws Exception {
        HelmRepoRequest posted = new HelmRepoRequest();
        posted.id = "harbor";
        posted.type = "OCI";
        posted.name = "harbor";
        posted.url = "harbor.example.com/kdps";
        posted.authMode = "basic";
        posted.username = "robot$kdps";
        posted.plainSecret = "Registry-Token-3";
        repositories.save(posted.toEntity(), posted.plainSecret);

        // Ambari's DataStore stores every bean property of the entity (and the migration copies every one): the
        // repository has none that could hold the secret, so its row has no column for it.
        for (PropertyDescriptor property : Introspector.getBeanInfo(HelmRepoEntity.class).getPropertyDescriptors()) {
            assertFalse(property.getName().toLowerCase(java.util.Locale.ROOT).contains("plain"), property.getName());
        }
        HelmRepoEntity row = view.dataStore.find(HelmRepoEntity.class, "harbor");
        assertEquals("Registry-Token-3", repositories.readPlainSecret(row));
        assertNotEquals("Registry-Token-3", settings.get("helm.repo.secret.harbor"), "stored encrypted");
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
    void theStartupCheckClearsSecretReferencesEarlierVersionsKeptInRows() throws Exception {
        harborWithItsToken();
        HelmRepoEntity legacy = oci("x", "attacker.example");
        legacy.setSecretRef("openshift.password.enc");
        view.dataStore.store(legacy);

        assertEquals(1, repositories.clearForeignSecretRefs());

        HelmRepoEntity cleaned = view.dataStore.find(HelmRepoEntity.class, "x");
        assertEquals("", cleaned.getSecretRef());
        assertEquals("helm.repo.secret.harbor", view.dataStore.find(HelmRepoEntity.class, "harbor").getSecretRef());
        assertEquals("OpenShift-Pass-1", settings.getSecret("openshift.password.enc"));
        assertEquals(0, repositories.clearForeignSecretRefs(), "nothing left to clear");
    }

    @Test
    void theStartupCheckNeverRemovesARepositoryRow() throws Exception {
        harborWithItsToken();
        repositories.save(edited("charts", "https://charts.example.com", r -> {
            r.setType("HTTP");
            r.setAuthMode("anonymous");
            r.setImageProject("kdps");
        }), null);
        HelmRepoEntity legacy = oci("x", "attacker.example");
        legacy.setSecretRef("openshift.password.enc");
        view.dataStore.store(legacy);
        // The same rows, with the calls recorded.
        DataStore rows = spy(view.dataStore);
        when(view.context.getDataStore()).thenReturn(rows);
        HelmRepositoryService atStartup = new HelmRepositoryService(view.context, helm);

        assertEquals(1, atStartup.clearForeignSecretRefs());

        // Updated in place only: a row removed to be stored again is lost if the second write fails.
        verify(rows, never()).remove(any());
        assertEquals("kdps", repositories.get("charts").getImageProject());
        assertEquals("", repositories.get("x").getSecretRef());
        assertEquals("helm.repo.secret.harbor", repositories.get("harbor").getSecretRef());
        assertEquals("Registry-Token-3", repositories.readPlainSecret(repositories.get("harbor")));
    }
}
