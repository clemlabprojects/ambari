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

import type { ContextFieldDef } from '../api/client';

/*
 * Where the Platform Contexts form reads and writes each capability field in a context's config. A context keeps one
 * flat config map, so fields of the same name in different capabilities (the Kerberos, OIDC and Polaris realms) are
 * stored under their own key, the field's configKey. The server applies the same rules.
 */

/** Config key of the Polaris realm, which older versions kept under the bare "realm" key on a REMOTE context. */
const POLARIS_REALM_KEY = 'polarisRealm';
const LEGACY_REALM_KEY = 'realm';

/** The key a field's value is stored under in the context config: its configKey, else its name. */
export const storageKey = (f: Pick<ContextFieldDef, 'name' | 'configKey'>): string => f.configKey || f.name;

/**
 * The value a context stores for a field. A REMOTE context saved by an older version kept its Polaris realm, the only
 * realm it offered, under the bare "realm" key.
 */
export const storedValue = (
  config: Record<string, any> | undefined,
  kind: string,
  f: Pick<ContextFieldDef, 'name' | 'configKey'>,
): any => {
  const key = storageKey(f);
  const value = config?.[key];
  if ((value === undefined || value === null || value === '') && kind === 'REMOTE' && key === POLARIS_REALM_KEY) {
    return config?.[LEGACY_REALM_KEY];
  }
  return value;
};
