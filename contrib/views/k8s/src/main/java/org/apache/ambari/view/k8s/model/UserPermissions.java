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

package org.apache.ambari.view.k8s.model;

public class UserPermissions {
    private final String role;
    private final boolean canConfigure;
    private final boolean canWrite;

    /**
     * Creates a UserPermissions value object with an explicit role and capability flags.
     *
     * @param role         logical role name (e.g., {@code "ADMIN"}, {@code "OPERATOR"}, {@code "VIEWER"})
     * @param canConfigure whether the user may modify view configuration
     * @param canWrite     whether the user may perform write operations such as deploy or delete
     */
    public UserPermissions(String role, boolean canConfigure, boolean canWrite) {
        this.role = role;
        this.canConfigure = canConfigure;
        this.canWrite = canWrite;
    }

    public String getRole() { return role; }
    public boolean isCanConfigure() { return canConfigure; }
    public boolean isCanWrite() { return canWrite; }
}
