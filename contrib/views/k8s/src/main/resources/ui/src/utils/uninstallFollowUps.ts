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

import type { UninstallNotes } from '../api/client';

/** One thing an uninstall took back or leaves to an administrator, as the Releases page shows it. */
export interface UninstallFollowUp {
  title: string;
  text: string;
}

/**
 * The notes of an uninstall, in the order the Releases page shows them: what became of the release's Polaris access
 * and single sign-on client, and what is left for an administrator to do. Empty when there is nothing to say.
 */
export function uninstallFollowUps(notes: UninstallNotes | undefined): UninstallFollowUp[] {
  const out: UninstallFollowUp[] = [];
  if (notes?.polarisCleanup) {
    out.push({ title: 'Polaris catalog access', text: notes.polarisCleanup });
  }
  if (notes?.oidcCleanup) {
    out.push({ title: 'Single sign-on client', text: notes.oidcCleanup });
  }
  if (notes?.sccCleanup) {
    out.push({ title: 'OpenShift security context constraints left in place', text: notes.sccCleanup });
  }
  if (notes?.tagSyncCleanup) {
    out.push({ title: 'Ranger account of Atlas tag sync', text: notes.tagSyncCleanup });
  }
  return out;
}

/** An `oidc[]` entry of a service definition, as far as the uninstall confirmation reads it. */
type OidcEntry = Record<string, any>;

const render = (template: unknown, fallback: string, release: { name: string; namespace: string }) =>
  String(template || fallback)
    .replace(/\{\{\s*releaseName\s*\}\}/g, release.name)
    .replace(/\{\{\s*namespace\s*\}\}/g, release.namespace);

/**
 * What the uninstall of a release removes of its single sign-on, as the confirmation names it: the client KDPS
 * registered for it in Keycloak and the Secrets holding its client secret. Null when its service has no OIDC sign-in.
 *
 * @param release the release
 * @param oidc    the `oidc[]` entries of its service definition
 */
export function uninstallSignInRemovals(
  release: { name: string; namespace: string },
  oidc: OidcEntry[] | undefined,
): string | null {
  const entries = (oidc || []).filter((e) => e && e.enabled !== false && String(e.enabled).toLowerCase() !== 'false');
  if (entries.length === 0) return null;
  const clients = Array.from(new Set(entries.map((e) => render(e.clientIdTemplate, '{{releaseName}}-{{namespace}}', release))));
  const secrets = Array.from(new Set(entries.flatMap((e) => [
    render(e.secretNameTemplate, '{{releaseName}}-oidc-client', release),
    ...(e.gitlabOmniauth === true || String(e.gitlabOmniauth).toLowerCase() === 'true' ? [`${release.name}-omniauth-provider`] : []),
  ])));
  return `If it signs in through single sign-on, KDPS also removes the Keycloak client ${clients.join(', ')} it `
    + `registered for it (an identity provider of a security profile is left as it is) and the `
    + `Secret${secrets.length > 1 ? 's' : ''} ${secrets.join(', ')} holding its client secret.`;
}
