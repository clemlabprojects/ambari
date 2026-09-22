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

import org.apache.ambari.view.k8s.model.ContextCapabilitySchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A secret is never resolved onto a platform context, because a resolved context is serialised
 * straight to the browser. So a capability field that is both {@code secret} and declares a
 * {@code managedResolver} is asking for something that will not happen: on an Ambari-managed
 * cluster the value stays empty, and whatever step needed it fails far away from the cause. That
 * is exactly how the Polaris administrator password went missing — the credential sat in the
 * cluster's own configuration while a deploy reported it had none.
 *
 * <p>This test is the tripwire. When it fails, a new field has walked into the same trap: either
 * drop the {@code managedResolver} because the operator must supply the value, or give the step
 * that needs it a server-side read of its own, as Polaris provisioning has, and add it below.
 */
class ContextSecretFieldsTest {

    /** Fields known to be secret with a managed resolver, each with a server-side path of its own. */
    private static final List<String> HANDLED_ELSEWHERE = List.of("polaris.adminPassword");

    private static List<String> secretFieldsWithManagedResolver() {
        List<String> found = new ArrayList<>();
        for (ContextCapabilitySchema cap : new ContextSchemaService().loadSchema()) {
            if (cap.fields == null) continue;
            for (ContextCapabilitySchema.ContextFieldDef f : cap.fields) {
                if (f.secret && f.managedResolver != null && !f.managedResolver.isBlank()) {
                    found.add(cap.capability + "." + f.name);
                }
            }
        }
        return found;
    }

    @Test
    void noSecretFieldExpectsToBeResolvedOnAManagedClusterUnlessAStepFetchesItItself() {
        List<String> offenders = new ArrayList<>(secretFieldsWithManagedResolver());
        offenders.removeAll(HANDLED_ELSEWHERE);
        assertEquals(List.of(), offenders,
                "these secret fields declare a managedResolver that will never run, so their value will be "
                + "empty on an Ambari-managed context: " + offenders);
    }

    @Test
    void theSchemaStillLoadsAndDescribesSomething() {
        List<ContextCapabilitySchema> schema = new ContextSchemaService().loadSchema();
        assertFalse(schema.isEmpty(), "the capability schema should not be empty");
    }
}
