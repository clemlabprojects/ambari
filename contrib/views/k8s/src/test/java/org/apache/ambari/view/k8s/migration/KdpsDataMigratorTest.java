/*
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
package org.apache.ambari.view.k8s.migration;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ambari.view.migration.EntityConverter;
import org.apache.ambari.view.migration.ViewDataMigrationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KdpsDataMigrator delegates to the migration context with the accessor-based copy. */
public class KdpsDataMigratorTest {

    private final List<String> calls = new ArrayList<>();

    private ViewDataMigrationContext context() {
        return (ViewDataMigrationContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ViewDataMigrationContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "copyAllObjects":
                            calls.add("copy:" + ((Class<?>) args[1]).getSimpleName());
                            assertEquals(3, args.length, "the converter overload must be used");
                            assertInstanceOf(AccessorCopyConverter.class, args[2]);
                            assertTrue(args[2] instanceof EntityConverter);
                            return null;
                        case "copyAllInstanceData":
                            calls.add("instanceData");
                            return null;
                        case "getOriginInstanceDataByUser":
                            return Map.of("admin", Map.of("kubeconfig.path", "/x", "proxy.url", "http://p"));
                        case "getOriginDataVersion":
                            return 0;
                        case "getCurrentDataVersion":
                            return 8;
                        default:
                            return null;
                    }
                });
    }

    @Test
    void copiesDeclaredEntitiesWithTheAccessorConverterAndSkipsDroppedOnes() throws Exception {
        KdpsDataMigrator migrator = new KdpsDataMigrator(context());
        assertTrue(migrator.beforeMigration());
        migrator.migrateEntity(AccessorCopyConverterTest.LegacyRelease.class, String.class);
        migrator.migrateEntity(AccessorCopyConverterTest.LegacyRelease.class, null);
        migrator.migrateInstanceData();
        migrator.afterMigration();
        assertEquals(List.of("copy:String", "instanceData"), calls);
    }
}
