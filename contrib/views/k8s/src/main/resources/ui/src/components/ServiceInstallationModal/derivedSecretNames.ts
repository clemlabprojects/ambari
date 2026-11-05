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

/** Fills a `{{path}}` template (e.g. `{{releaseName}}-keytab`) from the wizard state. */
export const resolveTemplate = (tpl: string | undefined, ctx: any): string =>
  (tpl || '').replace(/{{\s*([^}]+)\s*}}/g, (_m, raw) => {
    let cur: any = ctx;
    for (const p of String(raw || '').trim().split('.').filter(Boolean)) {
      if (cur == null) return '';
      cur = cur[p];
    }
    return cur == null ? '' : String(cur);
  });

/** The keytab Secret a `kerberos[]` entry of the service definition names for this wizard state. */
export const keytabSecretName = (entry: any, values: any): string =>
  entry?.secretNameTemplate ? resolveTemplate(entry.secretNameTemplate, values) : `${values?.releaseName}-keytab`;

/** The keystore Secret a `tls[]` entry of the service definition names for this wizard state. */
export const tlsSecretName = (entry: any, values: any): string =>
  entry?.secretNameTemplate ? resolveTemplate(entry.secretNameTemplate, values) : `${values?.releaseName}-${entry?.key}-tls`;

/**
 * The wizard state once the release name changed from `previous`: the keytab and keystore Secret names derived from
 * the release name follow it, as the service definition's templates name them. The wizard fills them from the release
 * name it proposes, before the operator types one, so without this a release 'kdc-superset' kept its keytab in
 * 'superset-keytab'. A name the operator changed is kept.
 */
export function followReleaseName(def: any, values: any, previous: string | undefined): any {
  if (!def || !values || previous === undefined || previous === values.releaseName) return values;
  const before = { ...values, releaseName: previous };
  let next = values;
  const update = (section: 'kerberos' | 'tls', key: string, patch: Record<string, string>) => {
    next = { ...next, [section]: { ...(next[section] || {}), [key]: { ...(next[section]?.[key] || {}), ...patch } } };
  };
  for (const entry of Array.isArray(def.kerberos) ? def.kerberos : []) {
    const current = values.kerberos?.[entry?.key];
    if (current && current.secretName === keytabSecretName(entry, before)) {
      update('kerberos', entry.key, { secretName: keytabSecretName(entry, values) });
    }
  }
  for (const entry of Array.isArray(def.tls) ? def.tls : []) {
    const current = values.tls?.[entry?.key];
    if (!current) continue;
    const oldName = tlsSecretName(entry, before);
    const patch: Record<string, string> = {};
    if (current.secretName === oldName) patch.secretName = tlsSecretName(entry, values);
    // The password Secret is named after the keystore Secret unless the definition names it.
    if (!entry.defaults?.passwordSecretName && current.passwordSecretName === `${oldName}-pass` && patch.secretName) {
      patch.passwordSecretName = `${patch.secretName}-pass`;
    }
    if (Object.keys(patch).length) update('tls', entry.key, patch);
  }
  return next;
}
