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
import { applyBindingTargets, buildVarContext, deepMerge, deleteAtStr, getAtStr, setAtStr } from './bindings';
import type { VariableSpec } from './bindings';
import { applyBlankValues } from './blankValues';
import { fieldCapabilityAvailable } from './capabilities';
import type { FormFieldDef } from './resourceChecks';
import type { BindingSpec } from './types';
import type { ClusterCapabilities } from '../../api/client';

/**
 * Wizard state and request-level blocks that live next to the chart values in the form but are sent as their own
 * fields of the deploy request (kerberos, tls and oidc are seeded from the service definition and go out as
 * request.kerberos, request.tls and request.oidc). Strict chart schemas (JupyterHub's, for one) refuse any unknown
 * key at the root of the values and fail the dry run, so none of them may be left in.
 */
export const WIZARD_ENVELOPE_KEYS = [
  'installMode', 'chartDirect', 'chartOverride', 'repoId', 'version', 'svcKey',
  'releaseName', 'namespace', 'securityProfile', 'deploymentMode', 'git',
  'kerberos', 'tls', 'oidc',
];

/** The paths of the form fields marked `excludeFromValues`, groups included. */
export function excludedPaths(fields: FormFieldDef[] | undefined): string[] {
  const paths: string[] = [];
  for (const f of fields || []) {
    if ((f as any)?.excludeFromValues && f.name) paths.push(f.name);
    if (f?.type === 'group' && Array.isArray(f.fields)) paths.push(...excludedPaths(f.fields));
  }
  return paths;
}

/**
 * Seeds `target` (in place) with the `defaultValue` of every form field, groups included: the values the wizard starts
 * from. Fields hidden on the platform are seeded too, since the platform is not known yet when the wizard starts;
 * buildChartValues leaves them out of the chart values (see hiddenPaths).
 */
export function applyFormDefaults(fields: FormFieldDef[] | undefined, target: any): void {
  for (const f of fields || []) {
    if (f?.type === 'group' && Array.isArray(f.fields)) {
      applyFormDefaults(f.fields, target);
    } else if (f?.defaultValue !== undefined && f.name) {
      const parts = f.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
      let cur = target;
      for (let i = 0; i < parts.length - 1; i++) {
        cur[parts[i]] = cur[parts[i]] || {};
        cur = cur[parts[i]];
      }
      cur[parts[parts.length - 1]] = f.defaultValue;
    }
  }
}

/**
 * A value an earlier version of the service definition wrote and this one no longer does (service.json
 * `retiredValues`, keyed by dotted path): either the reason it was retired, and the value is removed, or, for a list
 * of lines, `{ reason, linesStartingWith }`, and only the lines starting with one of those prefixes are removed (a
 * property line a binding used to append, e.g. "internal-communication.shared-secret=").
 */
export type RetiredValue = string | { reason?: string; linesStartingWith?: string[] };

/**
 * Removes (in place) the retired values from `values`; keys starting with "_" are comments. An upgrade starts from the
 * release's values, so without this they would be sent again: a secret a binding used to copy into a plain environment
 * variable or into a configuration line, for one. The server does the same for every deploy
 * (CommandService.removeRetiredValues), redeploys without the wizard included; here it keeps the preview truthful.
 */
export function removeRetiredValues(values: any, retiredValues: Record<string, RetiredValue> | undefined | null): void {
  for (const [path, retired] of Object.entries(retiredValues || {})) {
    if (path.startsWith('_')) continue;
    const prefixes = typeof retired === 'object' && retired ? retired.linesStartingWith : undefined;
    if (!Array.isArray(prefixes)) {
      deleteAtStr(values, path);
      continue;
    }
    const lines = getAtStr(values, path);
    if (Array.isArray(lines)) {
      setAtStr(values, path, lines.filter(l => !(typeof l === 'string' && prefixes.some(p => l.trim().startsWith(p)))));
    }
  }
}

const isBlank = (v: unknown) => v === undefined || v === null || (typeof v === 'string' && v.trim() === '');

/**
 * Fills (in place) each blank field that declares `seedFromValues` with the first of those value paths that holds
 * something in `target`, groups included. Called on an upgrade, once `target` holds the release's values: a field kept
 * out of the values (excludeFromValues) is not in them, but the release keeps what it set elsewhere. The Superset
 * SECRET_KEY, sent in extraSecretEnv.SUPERSET_SECRET_KEY, must come back unchanged, or the database passwords Superset
 * stored with it can no longer be decrypted.
 */
export function seedFieldsFromValues(fields: FormFieldDef[] | undefined, target: any): void {
  for (const f of fields || []) {
    if (f?.type === 'group' && Array.isArray(f.fields)) {
      seedFieldsFromValues(f.fields, target);
      continue;
    }
    if (!f?.name || !Array.isArray(f.seedFromValues) || !isBlank(getAtStr(target, f.name))) continue;
    const found = f.seedFromValues.map(p => getAtStr(target, p)).find(v => !isBlank(v));
    if (found !== undefined) setAtStr(target, f.name, found);
  }
}

/** The field capabilities that depend on the platform alone, the only part of the cluster probe known here. */
const PLATFORM_CAPABILITIES = ['kubernetes', 'openshift'];

/**
 * The paths of the form fields the wizard hides on `platform` because they, or a group around them, belong to the
 * other platform (a `capability` of `kubernetes` or `openshift`, see fieldCapabilityAvailable). Such a field is never
 * shown, yet it keeps its default in the form: the kube-prometheus monitoring namespace, for one, has nothing to do on
 * OpenShift. Fields marked `keepWhenHidden` are left out of the list, and so is a path that a shown field declares as
 * well (Trino has its "Collect metrics" switch in both its OpenShift and its Kubernetes monitoring group).
 */
export function hiddenPaths(fields: FormFieldDef[] | undefined, platform?: string): string[] {
  const caps = platform ? ({ platform } as ClusterCapabilities) : undefined;
  const hidden = new Set<string>();
  const shown = new Set<string>();
  const walk = (list: FormFieldDef[] | undefined, inHiddenGroup: boolean) => {
    for (const f of list || []) {
      const cap = f?.capability;
      const isHidden = inHiddenGroup
        || (!!cap && PLATFORM_CAPABILITIES.includes(cap) && !fieldCapabilityAvailable(cap, caps));
      if (f?.type === 'group' && Array.isArray(f.fields)) {
        walk(f.fields, isHidden);
      } else if (f?.name) {
        (isHidden && !f.keepWhenHidden ? hidden : shown).add(f.name);
      }
    }
  };
  walk(fields, false);
  return [...hidden].filter(p => !shown.has(p));
}

export interface ChartValuesInput {
  /** The service form (service.json `form`). */
  form?: FormFieldDef[];
  bindings?: BindingSpec[];
  variables?: VariableSpec[];
  /** What the wizard holds: form values plus its own state (release name, version, mounts...). */
  formValues: any;
  /** Fields resolved from the selected platform context, for `context` variables. */
  resolvedFields?: Record<string, string>;
  /** The detected platform (openshift, kubernetes), for platform-conditional bindings. */
  platform?: string;
  /** The values typed in the YAML editor, or null while the operator has not edited it. */
  editorValues?: Record<string, any> | null;
  /** The chart version the deploy asks for. */
  chartVersion?: unknown;
  /** The values the service definition no longer writes (service.json `retiredValues`, see removeRetiredValues). */
  retiredValues?: Record<string, RetiredValue> | null;
}

/**
 * The values sent to the chart, as the wizard builds them for the preview (no `editorValues`) and for the deploy:
 * form values without the fields hidden on this platform, then bindings, then the excluded fields, the retired values
 * and the wizard's own keys removed, then the YAML editor merged over the result, and only then blank fields resolved
 * for the chart version.
 *
 * Hidden fields are removed before the bindings, not after, because a binding may set the same path on purpose:
 * OpenMetadata turns its ServiceMonitor on for OpenShift although its "Expose ServiceMonitor" switch is hidden there.
 *
 * Blank fields are resolved last so that the editor counts like the form: a heap cleared in the editor
 * (`maxHeapSize: ""` or `maxHeapSize:`) is treated as a blank field instead of reaching an older chart that would
 * render a bare -Xmx, and a heap computed from the memory limit uses the limit actually sent, editor included.
 */
export function buildChartValues(input: ChartValuesInput): any {
  const { form, bindings, variables, formValues, resolvedFields, platform, editorValues, chartVersion, retiredValues } = input;
  const values = JSON.parse(JSON.stringify(formValues || {}));
  const mounts = formValues?.mounts || {};
  hiddenPaths(form, platform).forEach(p => deleteAtStr(values, p));

  const varCtx = buildVarContext(variables, formValues, mounts, resolvedFields || {}, platform);
  applyBindingTargets(values, bindings || [], mounts, formValues, formValues?.releaseName || '', varCtx);

  excludedPaths(form).forEach(p => deleteAtStr(values, p));
  removeRetiredValues(values, retiredValues);
  WIZARD_ENVELOPE_KEYS.forEach(k => delete values[k]);

  // The editor holds the whole document it was seeded with, so a deep merge only changes what the operator edited;
  // a shallow spread would let an edit to one key of a block drop every sibling the form produced.
  if (editorValues) deepMerge(values, editorValues);

  applyBlankValues(form, values, chartVersion);
  return values;
}
