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
import type { RequiredUnlessAny } from '../../types/ServiceTypes';
import { getAtStr, listEntries } from './bindings';
import { hiddenPaths } from './chartValues';
import type { FormFieldDef } from './resourceChecks';

/**
 * A field's `condition`: shown only while another field (`field`) holds `value` (or one of the values of an array),
 * or, with `operator`, while it is `non-empty` or `empty`.
 */
export type FieldCondition = { field: string; value?: unknown; operator?: 'non-empty' | 'empty' };

/** Whether a field with `condition` is shown while the field the condition watches holds `watched`. */
export function conditionMet(condition: FieldCondition | undefined | null, watched: unknown): boolean {
  if (!condition) return true;
  if (condition.operator === 'non-empty') return watched != null && String(watched).trim().length > 0;
  // e.g. a field only needed when discovery found nothing
  if (condition.operator === 'empty') return watched == null || String(watched).trim().length === 0;
  return Array.isArray(condition.value) ? condition.value.includes(watched) : watched === condition.value;
}

/**
 * Whether a field value holds something: a list with an entry, a switch that is on, or text with at least one
 * non-blank comma-separated entry (", ," holds nothing, so a list field filled with separators only counts as empty).
 */
export const holdsValue = (value: unknown): boolean => {
  if (Array.isArray(value)) return value.some(holdsValue);
  if (typeof value === 'boolean') return value;
  return listEntries(value).length > 0;
};

/**
 * The error of a field that must be filled in unless one of the other fields of `check` is: JupyterHub's allowed
 * groups, for one, which may stay empty only when administrators are named, or nobody could sign in. Null when the
 * field or one of the others holds a value.
 *
 * @param check       the field's `requiredUnlessAny` declaration
 * @param value       the field's value
 * @param otherValues the values of `check.fields`, in the same order
 * @param label       the field's label, for the default message
 */
export function requiredUnlessAnyError(check: RequiredUnlessAny | undefined, value: unknown, otherValues: unknown[],
                                       label?: string): string | null {
  if (!check || !Array.isArray(check.fields)) return null;
  if (holdsValue(value) || otherValues.some(holdsValue)) return null;
  return check.message || `Field '${label || 'this field'}' is required.`;
}

/**
 * Every `requiredUnlessAny` failure of a service form, read from the wizard state about to be deployed, each prefixed
 * with the field (and its group). The form shows the same error on the field, but only once the operator has touched
 * it or one of the others, and the wizard's steps do not validate the form: this is the check that stops the deploy.
 * Fields (or groups) hidden by their `condition` or by the platform are skipped, as the form skips them.
 *
 * @param fields   the service form (service.json `form`)
 * @param values   the wizard state: form values, release name, etc.
 * @param platform the detected platform (openshift, kubernetes), for fields shown on one platform only
 */
export function requiredUnlessAnyErrors(fields: FormFieldDef[] | undefined, values: unknown,
                                        platform?: string): string[] {
  const errors: string[] = [];
  const hidden = new Set(hiddenPaths(fields, platform));
  const shown = (f: FormFieldDef) => {
    const condition = (f as any)?.condition as FieldCondition | undefined;
    return conditionMet(condition, condition ? getAtStr(values, condition.field) : undefined);
  };
  const walk = (list: FormFieldDef[] | undefined, group?: string) => {
    for (const f of list || []) {
      if (!f || !shown(f)) continue;
      if (f.type === 'group' && Array.isArray(f.fields)) {
        walk(f.fields, f.label);
        continue;
      }
      const check = f.requiredUnlessAny;
      if (!check || !f.name || hidden.has(f.name)) continue;
      const others = (Array.isArray(check.fields) ? check.fields : []).map(other => getAtStr(values, other));
      const error = requiredUnlessAnyError(check, getAtStr(values, f.name), others, f.label);
      if (error) errors.push(`${group ? `${group} - ` : ''}${f.label}: ${error}`);
    }
  };
  walk(fields);
  return errors;
}
