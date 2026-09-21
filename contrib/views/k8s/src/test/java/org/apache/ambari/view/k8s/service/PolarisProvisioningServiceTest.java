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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The names decide whether re-installing a release re-uses what it already has or leaves a second
 * catalog and bucket behind, and the install wizard has to arrive at the same string when it renders
 * the engine's catalog file. Both properties are asserted here.
 */
class PolarisProvisioningServiceTest {

    @Test
    void namesAreDerivedFromTheReleaseAndNamespace() {
        assertEquals("lake-analytics",
                PolarisProvisioningService.deterministicCatalogName("lake", "analytics"));
        assertEquals("lake-analytics",
                PolarisProvisioningService.deterministicPrincipalName("lake", "analytics"));
        assertEquals("lake-analytics",
                PolarisProvisioningService.deterministicBucketName("lake", "analytics"));
    }

    @Test
    void theSameReleaseAlwaysProducesTheSameNames() {
        // This is what makes a re-install converge instead of creating a second catalog.
        for (int i = 0; i < 5; i++) {
            assertEquals("trino-prod-data-eng",
                    PolarisProvisioningService.deterministicCatalogName("trino-prod", "data-eng"));
        }
    }

    @Test
    void theSameReleaseNameInAnotherNamespaceIsADifferentCatalog() {
        assertNotEquals(
                PolarisProvisioningService.deterministicCatalogName("trino", "team-a"),
                PolarisProvisioningService.deterministicCatalogName("trino", "team-b"));
    }

    @Test
    void namesAreSafeForAnObjectStoreBucket() {
        String name = PolarisProvisioningService.deterministicBucketName("Trino_Prod.01", "Data_Eng");
        assertTrue(name.matches("[a-z0-9-]+"), "unexpected characters in " + name);
        assertFalse(name.startsWith("-") || name.endsWith("-"), "dangling dash in " + name);
        assertTrue(name.length() <= 63, "too long: " + name);
    }

    @Test
    void longNamesAreTruncatedWithoutADanglingDash() {
        String release = "a-very-long-release-name-that-goes-on-and-on-for-quite-a-while";
        String name = PolarisProvisioningService.deterministicBucketName(release, "namespace");
        assertTrue(name.length() <= 63 && name.length() >= 60, "unexpected length " + name.length() + ": " + name);
        // Truncation can land on a dash, which is then dropped rather than left dangling.
        assertFalse(name.endsWith("-"), "dangling dash in " + name);
    }

    @Test
    void theWizardFormulaMatchesTheServerFormula() {
        // The Trino service definition renders the catalog name as "{{releaseName}}-{{namespace}}".
        String release = "lake";
        String namespace = "analytics";
        assertEquals(release + "-" + namespace,
                PolarisProvisioningService.deterministicCatalogName(release, namespace));
    }

    @Test
    void theBucketIsReadFromTheBaseLocation() {
        assertEquals("lake-analytics", PolarisProvisioningService.bucketOf("s3://lake-analytics/warehouse/"));
        assertEquals("cluster", PolarisProvisioningService.bucketOf("s3://cluster/polaris/"));
        assertNull(PolarisProvisioningService.bucketOf("not-a-url"));
        assertNull(PolarisProvisioningService.bucketOf(null));
    }

    @Test
    void theSecretCarriesTheObjectStoreKeysOnlyWhenThereAreSome() {
        PolarisProvisioningService.Result r = new PolarisProvisioningService.Result();
        r.clientId = "cid";
        r.clientSecret = "csec";

        var withoutS3 = PolarisProvisioningService.secretData(r, null, null);
        assertEquals(java.util.Set.of("client_id", "client_secret"), withoutS3.keySet());

        var withS3 = PolarisProvisioningService.secretData(r, "AK", "SK");
        assertEquals(java.util.Set.of("client_id", "client_secret", "access_key", "secret_key"), withS3.keySet());
        // The chart's default key names, so the Secret drops straight into both credential blocks.
        assertEquals("AK", withS3.get("access_key"));
        assertEquals("csec", withS3.get("client_secret"));
    }
}
