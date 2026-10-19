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

package org.apache.ambari.view.k8s.service.gitops;

import org.apache.ambari.view.k8s.security.TestMasterKeys;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GitOps output profiles as an administrator saves them: built-in ones fixed, saved ones versioned. */
class GitOpsProfileStoreTest {

    private final GitOpsProfileStore store = new GitOpsProfileStore(
            new InstanceSettings(new InMemoryDataStore(), TestMasterKeys.encryptionWithoutKey()));

    private static GitOpsProfile profile(String name) {
        GitOpsProfile p = new GitOpsProfile();
        p.name = name;
        p.chartPath = "chart";
        GitOpsProfile.FileRule rule = new GitOpsProfile.FileRule();
        rule.path = "environments/{{ namespace }}/values.yaml";
        rule.content = "{{ values | toYaml }}\n" + "# padding\n".repeat(2000);
        p.files.add(rule);
        return p;
    }

    @Test
    void theFluxProfileIsBuiltInAndIsWhatABlankNameMeans() {
        assertEquals("flux", store.get(null).name);
        assertEquals("flux", store.get(" ").name);
        assertEquals(List.of("flux"), store.list().stream().map(p -> p.name).toList());
        assertNull(store.get("unknown"));
    }

    @Test
    void aSavedProfileIsReadBackWholeAndItsVersionGoesUp() {
        GitOpsProfile saved = store.save(profile("environments"));
        assertEquals(1, saved.version);
        assertEquals(2, store.save(profile("environments")).version);

        GitOpsProfile read = store.get("environments");
        assertEquals("chart", read.chartPath);
        assertEquals(profile("environments").files.get(0).content, read.files.get(0).content, "a long template survives");
        assertEquals(List.of("flux", "environments"), store.list().stream().map(p -> p.name).toList());
    }

    @Test
    void aBuiltInProfileCannotBeReplacedNorDeleted() {
        GitOpsProfile flux = profile("flux");
        assertThrows(IllegalArgumentException.class, () -> store.save(flux));
        assertThrows(IllegalArgumentException.class, () -> store.delete("flux"));
    }

    @Test
    void anInvalidProfileIsNotSaved() {
        GitOpsProfile p = profile("environments");
        p.files.get(0).path = "../outside.yaml";
        assertThrows(IllegalArgumentException.class, () -> store.save(p));
        assertNull(store.get("environments"));
    }

    @Test
    void aDeletedProfileIsGone() {
        store.save(profile("environments"));
        store.delete("environments");
        assertNull(store.get("environments"));
        assertEquals(List.of("flux"), store.list().stream().map(p -> p.name).toList());
    }
}
