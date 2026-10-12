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

/**
 * The `tls` part of a deploy request, from which the server (TlsManager) provisions the keystore and its password
 * Secret: the wizard's `installValues.tls`, completed at SUBMIT time from the service definition's `tls[]`.
 *
 * - SANs (`dnsNames`) from `tls[].dnsTemplates`. It cannot be done when the definition loads: `{{ingress.host}}` is
 *   only known once the operator has filled in step 3. Without this the backend falls back to a single
 *   `<release>.<namespace>.svc` SAN, which matches neither the coordinator Service DNS nor the Route host, and every
 *   client that verifies the certificate fails the handshake.
 * - The password Secret (`passwordSecretName`): the bindings mount `<keystore Secret>-pass`, from the Keystore Secret
 *   field as it is at submit time, so the server must write the password there too, not under the name seeded from
 *   the default keystore Secret when the definition loaded. Only a name the definition sets itself
 *   (`tls[].defaults.passwordSecretName`) is kept.
 *
 * @param specs         the service definition's `tls[]`
 * @param installValues the wizard's form values
 */
export function buildTlsPayload(specs: any, installValues: any): any {
  if (!Array.isArray(specs) || !specs.length) return installValues?.tls || undefined;
  const render = (tpl: string | undefined) =>
    (tpl || '').replace(/{{\s*([^}]+)\s*}}/g, (_m: string, raw: string) => {
      const path = String(raw || '').trim().split('.').filter(Boolean);
      let cur: any = installValues;
      for (const part of path) { if (cur == null) return ''; cur = cur[part]; }
      return cur == null ? '' : String(cur);
    });
  const out: any = { ...(installValues?.tls || {}) };
  for (const spec of specs) {
    const key = spec?.key;
    if (!key) continue;
    const entry = { ...(out[key] || {}) };
    const names: string[] = [];
    for (const tpl of (Array.isArray(spec.dnsTemplates) ? spec.dnsTemplates : [])) {
      const rendered = render(tpl);
      if (rendered && !names.includes(rendered)) names.push(rendered);
    }
    for (const extra of (Array.isArray(entry.extraDns) ? entry.extraDns : [])) {
      if (extra && !names.includes(String(extra))) names.push(String(extra));
    }
    if (names.length) entry.dnsNames = names;
    const fixedPasswordSecret = String(spec.defaults?.passwordSecretName || '').trim();
    const secretName = String(entry.secretName || '').trim();
    if (fixedPasswordSecret) entry.passwordSecretName = fixedPasswordSecret;
    else if (secretName) entry.passwordSecretName = `${secretName}-pass`;
    out[key] = entry;
  }
  return out;
}
