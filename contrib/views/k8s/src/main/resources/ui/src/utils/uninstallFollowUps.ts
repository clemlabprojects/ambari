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

import type { ExternalCleanupMode, ExternalCleanupReport, UninstallNotes } from '../api/client';

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
  const objects = notes?.kdpsObjectsCleanup;
  if (objects && ((objects.removed || []).length > 0 || (objects.left || []).length > 0
    || (objects.kept || []).length > 0)) {
    out.push({ title: 'Objects KDPS created for the release', text: objects.note || kdpsObjectsText(objects) });
  }
  const external = notes?.externalCleanup;
  if (external && ((external.removed || []).length > 0 || (external.left || []).length > 0
    || (external.kept || []).length > 0)) {
    out.push({ title: 'Outside Kubernetes', text: external.note || externalText(external) });
  }
  return out;
}

/** What the uninstall did outside Kubernetes, for a server that sent no note. */
function externalText(report: ExternalCleanupReport): string {
  const lines: string[] = [];
  if ((report.removed || []).length > 0) {
    lines.push('Removed what KDPS registered for the release outside Kubernetes:');
    report.removed.forEach((r) => lines.push(`  ${r}`));
  }
  for (const l of report.left || []) {
    lines.push(`Left ${l.object} (${l.reason})${l.command ? `: ${l.command}` : ''}`);
  }
  for (const l of report.kept || []) {
    lines.push(`Kept ${l.object} (${l.reason})${l.command ? `: ${l.command}` : ''}`);
  }
  return lines.join('\n');
}

/** The choices the uninstall confirmation offers for what KDPS registered outside Kubernetes. */
export const EXTERNAL_CLEANUP_CHOICES: { value: ExternalCleanupMode; label: string }[] = [
  { value: 'credentials', label: 'Remove access and credentials, keep the Ranger repositories (recommended)' },
  { value: 'all', label: 'Remove everything, the Ranger repositories of the release included' },
  { value: 'none', label: 'Keep everything outside Kubernetes, the single sign-on client included' },
];

/**
 * What the uninstall confirmation says of what KDPS registered for the release outside Kubernetes, from the preview of
 * the chosen mode: what is removed, then what is kept. Null when nothing is recorded for the release.
 *
 * @param preview what the uninstall would do (see getReleaseExternalRegistrations); null when it could not be read
 */
export function uninstallExternalRemovals(preview: ExternalCleanupReport | null | undefined): string[] | null {
  if (!preview) return null;
  const removed = preview.removed || [];
  const kept = [...(preview.kept || []), ...(preview.left || [])];
  if (removed.length === 0 && kept.length === 0) {
    return preview.recorded === false
      ? ['KDPS has no record of what it registered for this release outside Kubernetes (deployed by an earlier'
        + ' version): check Keycloak, Ranger and Atlas for what is named after it once it is uninstalled.']
      : null;
  }
  const lines: string[] = [];
  if (removed.length > 0) lines.push(`Outside Kubernetes, KDPS also removes: ${removed.join('; ')}.`);
  if (kept.length > 0) lines.push(`It keeps: ${kept.map((k) => `${k.object} (${k.reason})`).join('; ')}.`);
  return lines;
}

/** What the uninstall did with the objects KDPS created for the release, for a server that sent no note. */
function kdpsObjectsText(cleanup: NonNullable<UninstallNotes['kdpsObjectsCleanup']>): string {
  const lines: string[] = [];
  if ((cleanup.removed || []).length > 0) {
    lines.push(`Removed what KDPS created for the release outside its chart: ${cleanup.removed.join(', ')}.`);
  }
  for (const l of cleanup.left || []) {
    lines.push(`Left ${l.object} (${l.reason})${l.command ? `: ${l.command}` : ''}`);
  }
  for (const l of cleanup.kept || []) {
    lines.push(`Kept ${l.object} (${l.reason})${l.command ? `: ${l.command}` : ''}`);
  }
  return lines.join('\n');
}

/**
 * What the uninstall confirmation says of the objects KDPS created for the release outside its chart, which the
 * uninstall removes: those still there, by name. Null when there are none.
 *
 * @param objects the objects, as `<Kind>/<name>`
 */
export function uninstallKdpsObjectRemovals(objects: string[] | undefined): string | null {
  if (!objects || objects.length === 0) return null;
  return `KDPS also deletes what it created for this release outside its chart: ${objects.join(', ')}. Objects an `
    + 'administrator provided, or shared with other releases of the project, are kept.';
}

/**
 * What the uninstall confirmation says of the release's Polaris access, taken back once the release is gone (the
 * default Polaris cleanup of an uninstall).
 */
export const UNINSTALL_POLARIS_REVOCATION = 'If it was given access to a Polaris catalog, KDPS also revokes its '
  + 'Polaris principal and role once the release is removed, so its catalog credential stops working. The catalog and '
  + 'its data are kept. If the removal fails, the release keeps its access and its record, and you can uninstall it '
  + 'again once the cause is fixed.';

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
