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
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uninstalling a release has to take its Polaris credential back — otherwise a removed release
 * leaves a key that still works — while leaving the data where it is. An uninstall is routinely one
 * half of a reinstall, so dropping tables would destroy work that has nothing to do with the
 * release. These tests pin both halves of that: what is removed, and what is deliberately not.
 */
class PolarisRevokeTest {

    /** Records every call the service makes, and answers each one with a given status. */
    private static class Recorder implements org.mockito.stubbing.Answer<HttpResponse<String>> {
        final List<String> calls = new ArrayList<>();
        int deleteStatus = 204;

        @Override
        public HttpResponse<String> answer(org.mockito.invocation.InvocationOnMock inv) {
            HttpRequest req = inv.getArgument(0);
            String path = req.uri().getPath();
            calls.add(req.method() + " " + path);

            @SuppressWarnings("unchecked")
            HttpResponse<String> resp = Mockito.mock(HttpResponse.class);
            if (path.endsWith("/v1/oauth/tokens")) {
                Mockito.when(resp.statusCode()).thenReturn(200);
                Mockito.when(resp.body()).thenReturn("{\"access_token\":\"t0ken\"}");
            } else {
                Mockito.when(resp.statusCode()).thenReturn(deleteStatus);
                Mockito.when(resp.body()).thenReturn("");
            }
            return resp;
        }
    }

    private static PolarisProvisioningService.Request request() {
        PolarisProvisioningService.Request req = new PolarisProvisioningService.Request();
        req.restUri = "https://polaris.example:8181/api/catalog";
        req.managementUri = "https://polaris.example:8181/api/management/v1";
        req.adminClientId = "root";
        req.adminClientSecret = "secret";
        req.principalName = "lake-analytics";
        req.principalRoleName = "lake-analytics-role";
        req.catalogName = "lake-analytics";
        req.catalogRoleName = "content-admin";
        return req;
    }

    private static Recorder run(boolean dropCatalog, int deleteStatus) throws Exception {
        Recorder rec = new Recorder();
        rec.deleteStatus = deleteStatus;
        HttpClient http = Mockito.mock(HttpClient.class);
        Mockito.when(http.send(Mockito.any(), Mockito.any())).thenAnswer(rec);
        new PolarisProvisioningService(http).revoke(request(), dropCatalog);
        return rec;
    }

    @Test
    void takesBackThePrincipalAndItsRole() throws Exception {
        Recorder rec = run(false, 204);
        assertTrue(rec.calls.contains("DELETE /api/management/v1/principals/lake-analytics"),
                "the credential must stop working: " + rec.calls);
        assertTrue(rec.calls.contains("DELETE /api/management/v1/principal-roles/lake-analytics-role"),
                "the role exists only to carry this release's grants: " + rec.calls);
    }

    @Test
    void leavesTheCatalogAndItsDataAloneByDefault() throws Exception {
        Recorder rec = run(false, 204);
        for (String call : rec.calls) {
            assertFalse(call.startsWith("DELETE /api/management/v1/catalogs/"),
                    "an uninstall must not drop the catalog unless asked: " + call);
        }
    }

    @Test
    void neverTouchesTheBucket() throws Exception {
        for (boolean dropCatalog : new boolean[]{false, true}) {
            Recorder rec = run(dropCatalog, 204);
            for (String call : rec.calls) {
                assertTrue(call.contains("/api/management/v1/") || call.endsWith("/v1/oauth/tokens"),
                        "revoke speaks only to Polaris management; the object store is never called: " + call);
            }
        }
    }

    @Test
    void dropsTheCatalogOnlyWhenAsked() throws Exception {
        Recorder rec = run(true, 204);
        assertTrue(rec.calls.contains("DELETE /api/management/v1/catalogs/lake-analytics"), rec.calls.toString());
        assertTrue(rec.calls.contains(
                "DELETE /api/management/v1/catalogs/lake-analytics/catalog-roles/content-admin"), rec.calls.toString());
    }

    @Test
    void anAlreadyRemovedPrincipalIsNotAFailure() throws Exception {
        // Uninstalling twice, or after an operator cleaned up by hand, has to finish cleanly.
        Recorder rec = run(false, 404);
        assertEquals(3, rec.calls.size(), "token + principal + role: " + rec.calls);
    }

    @Test
    void aCatalogPolarisRefusesToDropIsReportedNotThrown() throws Exception {
        Recorder rec = new Recorder();
        rec.deleteStatus = 409; // Polaris refuses while the catalog still holds namespaces
        HttpClient http = Mockito.mock(HttpClient.class);
        Mockito.when(http.send(Mockito.any(), Mockito.any())).thenAnswer(rec);

        PolarisProvisioningService.Result res = new PolarisProvisioningService(http).revoke(request(), true);
        assertFalse(res.catalogCreated, "nothing was dropped");
        assertTrue(res.warning != null && res.warning.contains("data is untouched"),
                "the operator needs to be told the data is still there: " + res.warning);
    }
}
