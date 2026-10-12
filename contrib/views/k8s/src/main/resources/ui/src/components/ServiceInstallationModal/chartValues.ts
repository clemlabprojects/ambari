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
import { applyBindingTargets, buildVarContext, deepMerge, deleteAtStr } from './bindings';
import type { VariableSpec } from './bindings';
import { applyBlankValues } from './blankValues';
import type { FormFieldDef } from './resourceChecks';
import type { BindingSpec } from './types';

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
}

/**
 * The values sent to the chart, as the wizard builds them for the preview (no `editorValues`) and for the deploy:
 * form values, then bindings, then the excluded fields and the wizard's own keys removed, then the YAML editor
 * merged over the result, and only then blank fields resolved for the chart version.
 *
 * Blank fields are resolved last so that the editor counts like the form: a heap cleared in the editor
 * (`maxHeapSize: ""` or `maxHeapSize:`) is treated as a blank field instead of reaching an older chart that would
 * render a bare -Xmx, and a heap computed from the memory limit uses the limit actually sent, editor included.
 */
export function buildChartValues(input: ChartValuesInput): any {
  const { form, bindings, variables, formValues, resolvedFields, platform, editorValues, chartVersion } = input;
  const values = JSON.parse(JSON.stringify(formValues || {}));
  const mounts = formValues?.mounts || {};

  const varCtx = buildVarContext(variables, formValues, mounts, resolvedFields || {}, platform);
  applyBindingTargets(values, bindings || [], mounts, formValues, formValues?.releaseName || '', varCtx);

  excludedPaths(form).forEach(p => deleteAtStr(values, p));
  WIZARD_ENVELOPE_KEYS.forEach(k => delete values[k]);

  // The editor holds the whole document it was seeded with, so a deep merge only changes what the operator edited;
  // a shallow spread would let an edit to one key of a block drop every sibling the form produced.
  if (editorValues) deepMerge(values, editorValues);

  applyBlankValues(form, values, chartVersion);
  return values;
}
