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

import type { UserPermissions, UserRole } from '../types';

/** Pages that change the KDPS configuration: only KDPS admins see them in the menu and can open them. */
export const CONFIGURE_ONLY_ROUTES: readonly string[] = [
  '/configuration',
  '/contexts',
  '/repositories',
  '/global-security',
  '/truststores',
  '/managed-configs',
];

/** What the UI shows when the role cannot be read: nothing that writes or configures. */
export const READ_ONLY_PERMISSIONS: UserPermissions = { role: 'VIEWER', canConfigure: false, canWrite: false };

const ROLES: readonly UserRole[] = ['ADMIN', 'OPERATOR', 'VIEWER'];

/**
 * Keeps only what the backend explicitly granted: a missing or malformed field reads as "not allowed". The backend
 * enforces the role on every call anyway; this only decides which buttons and pages the UI offers.
 */
export const toPermissions = (raw: unknown): UserPermissions => {
  if (!raw || typeof raw !== 'object') {
    return READ_ONLY_PERMISSIONS;
  }
  const answer = raw as Partial<Record<keyof UserPermissions, unknown>>;
  if (!ROLES.includes(answer.role as UserRole)) {
    return READ_ONLY_PERMISSIONS;
  }
  return { role: answer.role as UserRole, canConfigure: answer.canConfigure === true, canWrite: answer.canWrite === true };
};
