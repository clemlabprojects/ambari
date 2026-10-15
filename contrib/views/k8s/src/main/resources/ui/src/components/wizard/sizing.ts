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

import { getAtStr, setAtStr } from '../ServiceInstallationModal/bindings';

/*
 * The sizes a service definition offers at the wizard's chart step (service.json `sizing`). A size only fills form
 * fields the definition already has, so the values a deploy sends are the same as when they are typed in the form.
 */

export interface SizingPreset {
  key: string;
  label: string;
  summary: string;
  values: Record<string, any>;
}

export interface Sizing {
  default?: string;
  fields: string[];
  presets: SizingPreset[];
}

/** What the wizard shows when the values match no size. */
export const CUSTOM_SIZE = 'custom';

/** The definition's sizing, or null when it has none or it is not usable. */
export const sizingOf = (def: any): Sizing | null => {
  const s = def?.sizing;
  return s && Array.isArray(s.fields) && s.fields.length && Array.isArray(s.presets) && s.presets.length ? s : null;
};

/** The form without the given fields; a group left empty is dropped. */
export const formWithout = (form: any[] | undefined, names: string[]): any[] =>
  (form || []).flatMap((f: any) => {
    if (f?.type === 'group' && Array.isArray(f.fields)) {
      const fields = formWithout(f.fields, names);
      return fields.length ? [{ ...f, fields }] : [];
    }
    return names.includes(f?.name) ? [] : [f];
  });

/** Only the given fields of the form, in their groups. */
export const formOnly = (form: any[] | undefined, names: string[]): any[] =>
  (form || []).flatMap((f: any) => {
    if (f?.type === 'group' && Array.isArray(f.fields)) {
      const fields = formOnly(f.fields, names);
      return fields.length ? [{ ...f, fields, collapsed: false }] : [];
    }
    return names.includes(f?.name) ? [f] : [];
  });

/** The wizard values with a size's values filled in. */
export const applySize = (values: any, preset: SizingPreset): any => {
  const next = JSON.parse(JSON.stringify(values || {}));
  Object.entries(preset.values).forEach(([path, v]) => setAtStr(next, path, v));
  return next;
};

/** The size the values match (number and text forms of a value are equal), or {@link CUSTOM_SIZE}. */
export const currentSize = (sizing: Sizing, values: any): string => {
  const match = sizing.presets.find((p) =>
    Object.entries(p.values).every(([path, v]) => String(getAtStr(values, path) ?? '') === String(v)));
  return match ? match.key : CUSTOM_SIZE;
};
