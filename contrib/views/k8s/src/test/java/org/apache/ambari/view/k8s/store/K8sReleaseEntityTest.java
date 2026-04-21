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

package org.apache.ambari.view.k8s.store;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.List;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.junit.Test;

/**
 * Simple structural guard to ensure the release entity stays within
 * Ambari's DataStore string limits (field-based access, transient endpoints).
 * This does not hit a real database; it validates annotations and counts
 * persisted string columns as a quick regression check.
 */
public class K8sReleaseEntityTest {

    @Test
    public void testTableAndAccess() {
        Table table = K8sReleaseEntity.class.getAnnotation(Table.class);
        assertNotNull("Table annotation missing", table);
        assertEquals("k8s_release2", table.name());

        Access access = K8sReleaseEntity.class.getAnnotation(Access.class);
        assertNotNull("Access annotation missing", access);
        assertEquals(AccessType.FIELD, access.value());
    }

    @Test
    public void testTransientEndpointsAndTimestamps() throws Exception {
        // endpointsJson must be transient (computed/cached, not persisted)
        Field endpoints = K8sReleaseEntity.class.getDeclaredField("endpointsJson");
        assertTrue("endpointsJson must be @Transient", endpoints.isAnnotationPresent(Transient.class));

        // createdAt/updatedAt are persisted columns (survive Ambari restarts) — must NOT be @Transient
        Field createdAt = K8sReleaseEntity.class.getDeclaredField("createdAt");
        Field updatedAt = K8sReleaseEntity.class.getDeclaredField("updatedAt");
        assertFalse("createdAt must be persisted (@Column), not @Transient", createdAt.isAnnotationPresent(Transient.class));
        assertFalse("updatedAt must be persisted (@Column), not @Transient", updatedAt.isAnnotationPresent(Transient.class));
        assertTrue("createdAt must have @Column", createdAt.isAnnotationPresent(javax.persistence.Column.class));
        assertTrue("updatedAt must have @Column", updatedAt.isAnnotationPresent(javax.persistence.Column.class));
    }

    @Test
    public void testStringColumnLengthsUnderLimit() {
        // Rough check that persisted string columns remain under Ambari's limits.
        List<String> fields = Arrays.asList(
                // id is on the BaseModel; we care about columns declared directly on this class
                "namespace", "releaseName", "serviceKey", "chartRef", "repoId", "version",
                "deploymentId", "deploymentMode", "globalConfigVersion", "securityProfile", "securityProfileHash",
                "gitCommitSha", "gitBranch", "gitRepoUrl", "gitPath", "gitCredentialAlias",
                "gitCommitMode", "gitPrUrl", "gitPrNumber", "gitPrState"
        );

        int totalLength = 0;
        for (String name : fields) {
            try {
                Field f = K8sReleaseEntity.class.getDeclaredField(name);
                Column col = f.getAnnotation(Column.class);
                if (col != null && col.length() > 0) {
                    totalLength += col.length();
                }
            } catch (NoSuchFieldException e) {
                fail("Expected field missing: " + name);
            }
        }
        assertTrue("Total string length should be well under 65000", totalLength < 65000);
    }

    @Test
    public void testBeanInfoMatchesAmbariDataStoreLimits() throws Exception {
        // Ambari DataStore counts each String property as MAX_ENTITY_STRING_FIELD_LENGTH (3000) and enforces 65k total.
        // With the custom BeanInfo, only the persisted fields should be exposed.
        PropertyDescriptor[] pds = Introspector.getBeanInfo(K8sReleaseEntity.class).getPropertyDescriptors();
        int stringProps = 0;
        for (PropertyDescriptor pd : pds) {
            if (pd.getReadMethod() == null || "class".equals(pd.getName())) {
                continue;
            }
            if (pd.getPropertyType() == String.class) {
                stringProps++;
            }
        }
        int total = stringProps * 3000; // mirrors DataStoreImpl.MAX_ENTITY_STRING_FIELD_LENGTH
        assertTrue("String properties * 3000 must stay under 65000 (found " + total + ")",
                total < 65000);
    }
}
