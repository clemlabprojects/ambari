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

import type { ReleaseTlsEntry } from '../../api/client';

/**
 * The ingress certificate of a release: what the wizard's TLS mode (`ingress.tlsMode`) sends with a deploy, and how
 * Upgrade/Config keeps the certificate a release serves unless the operator chooses another one.
 *
 * The TLS mode and its inputs (`excludeFromValues`) are not in the release's Helm values, so an Upgrade/Config cannot
 * read back the mode a release was deployed with. It used to start from the field's default, "Sign with Ambari
 * Internal CA (testing)", and a routine upgrade replaced a production certificate (company CA, uploaded, the
 * operator's own Secret, cert-manager or Vault) with a testing one. It now starts from "Keep current certificate",
 * which sends no ingress certificate at all: the deployed `ingress.tls` stays as it is. A release serving plain HTTP
 * has no certificate to keep: it starts from "None", as deployed.
 */

/** The TLS mode that keeps the certificate the release serves (offered on Upgrade/Config only). */
export const KEEP_CURRENT_TLS = 'keepCurrent';

/** Modes whose deploy writes the ingress TLS Secret: KDPS mints or uploads it, or has cert-manager / ESO fill it. */
const SECRET_WRITING_MODES = ['uploadLeaf', 'signedByAmbariCA', 'signedByCompanyCA', 'signedByClusterIssuer',
  'sourcedFromExternalSecret'];

/** Modes whose Secret the view writes itself: refused by the server over a Secret cert-manager or ESO keeps. */
const VIEW_WRITTEN_MODES = ['uploadLeaf', 'signedByAmbariCA', 'signedByCompanyCA'];

const text = (v: unknown): string => (v == null ? '' : String(v).trim());

const tlsModeOf = (form: any): string => text(form?.ingress?.tlsMode);

/** Whether the operator pasted a certificate or a key in "Upload cert + key". */
const pastedPem = (form: any): boolean => {
  const up = form?.ingress?.tlsUpload || {};
  return text(up.cert) !== '' || text(up.key) !== '';
};

/** The form field named `name`, groups included. */
const findField = (fields: any[] | undefined, name: string): any => {
  for (const f of fields || []) {
    if (f?.name === name) return f;
    if (Array.isArray(f?.fields)) {
      const found = findField(f.fields, name);
      if (found) return found;
    }
  }
  return undefined;
};

/** Whether the TLS mode field of the service definition offers `mode`. */
const offersTlsMode = (fields: any[] | undefined, mode: string): boolean => {
  const field = findField(fields, 'ingress.tlsMode');
  return Array.isArray(field?.options) && field.options.some((o: any) => o?.value === mode);
};

/** Whether the service definition lets the operator pick the ingress TLS mode, "Keep current certificate" included. */
export function offersKeepCurrentTls(fields: any[] | undefined): boolean {
  return offersTlsMode(fields, KEEP_CURRENT_TLS);
}

/** The `ingress.tls` entries of chart values or of a form. */
const keptTlsEntries = (values: any): any[] => (Array.isArray(values?.ingress?.tls)
  ? values.ingress.tls.filter((e: any) => e && typeof e === 'object') : []);

/**
 * Whether chart values (or the wizard's form, the deployed values merged in) wire the Ingress for HTTPS: an
 * `ingress.tls` entry, which every TLS mode writes, or a "Bring your own Secret" name, which its binding turns into one.
 * Without either the Ingress serves plain HTTP, and "Keep current certificate" keeps no certificate.
 */
export function carriesIngressTls(values: any): boolean {
  return keptTlsEntries(values).length > 0 || text(values?.ingress?.tlsSecret) !== '';
}

/**
 * Upgrade/Config: starts the TLS mode from the certificate the release's Ingress serves. Called on the wizard's initial
 * state once the deployed values are merged in, so after the field's default was applied.
 *
 * An Ingress serving HTTPS starts on "Keep current certificate". One serving plain HTTP starts on "None", as deployed:
 * there is no certificate to keep, and picking a security profile then moves it to a TLS mode, as on an install. A
 * release whose Ingress is off keeps the default: turning the Ingress on during the upgrade is a first TLS setup.
 *
 * @param fields   the service form (service.json `form`)
 * @param form     the wizard's initial state, changed in place
 * @param deployed the release's deployed values
 * @returns whether the mode was set to "Keep current certificate"
 */
export function seedKeepCurrentTls(fields: any[] | undefined, form: any, deployed: any): boolean {
  if (!offersKeepCurrentTls(fields)) return false;
  const ingress = deployed?.ingress;
  if (!ingress || typeof ingress !== 'object' || ingress.enabled === false) return false;
  if (carriesIngressTls(deployed)) {
    form.ingress = { ...(form.ingress || {}), tlsMode: KEEP_CURRENT_TLS };
    return true;
  }
  if (ingress.enabled === true && offersTlsMode(fields, 'none')) {
    form.ingress = { ...(form.ingress || {}), tlsMode: 'none' };
  }
  return false;
}

/** The Ingress settings a selected security profile requires (see {@link securityProfileIngress}). */
export interface SecurityProfileIngress {
  enabled?: boolean;
  tlsMode?: string;
}

/**
 * What a selected security profile requires of the Ingress (service.json `securityCoupling`). Logins go through it, so
 * it is turned on (`requireIngress`, on by default). When the view provisions its TLS (`tlsProvisioning` "k8s-view",
 * the default) it must also serve HTTPS: no TLS mode, "None", and "Keep current certificate" of a release serving plain
 * HTTP all move to `minTlsMode`. A chart that manages its own TLS ("chart-managed", "external", or the older
 * `requireTls: false`) keeps whatever mode it has.
 *
 * @param coupling the service's `securityCoupling` block
 * @param form     the wizard's form values
 * @returns the `ingress.*` values to set, or null when the form already complies or no profile is selected
 */
export function securityProfileIngress(coupling: any, form: any): SecurityProfileIngress | null {
  if (!text(form?.securityProfile)) return null;
  const rules = coupling || {};
  const tlsProvisioning = String(rules.tlsProvisioning ?? (rules.requireTls === false ? 'chart-managed' : 'k8s-view'));
  const ingress = form?.ingress || {};
  const mode = tlsModeOf(form);
  const servesHttpOnly = !mode || mode === 'none' || (mode === KEEP_CURRENT_TLS && !carriesIngressTls(form));
  const required: SecurityProfileIngress = {};
  if (rules.requireIngress !== false && ingress.enabled !== true) required.enabled = true;
  if (tlsProvisioning === 'k8s-view' && servesHttpOnly) required.tlsMode = rules.minTlsMode || 'signedByAmbariCA';
  return Object.keys(required).length ? required : null;
}

/** The ingress certificate fields of a deploy request (see HelmDeployRequest). */
export interface IngressTlsPayload {
  ingressTlsUpload?: Record<string, any>;
  ingressTlsSelfSign?: Record<string, any>;
  ingressTlsCertManager?: Record<string, any>;
  ingressTlsExternalSecret?: Record<string, any>;
}

/**
 * Translates ingress.tlsMode (form select) into the wire payload the backend expects:
 *   uploadLeaf                  → ingressTlsUpload   { secretName, certPem, keyPem }
 *   signedByAmbariCA            → ingressTlsSelfSign  { mode: ambariInternal, ... }
 *   signedByCompanyCA           → ingressTlsSelfSign  { mode: companyUploaded, caName, ... }
 *   signedByClusterIssuer       → ingressTlsCertManager { issuerName, durationHours, ... }
 *   sourcedFromExternalSecret   → ingressTlsExternalSecret { secretStoreName, remoteKey, ... }
 *   bringYourOwn                → nothing (values already carry ingress.tlsSecret)
 *   keepCurrent, none           → nothing: the deployed ingress.tls stays as it is
 * "Upload cert + key" with nothing pasted sends nothing either, so on an upgrade it keeps the uploaded certificate.
 * Nor does an Ingress that is off: the TLS mode is hidden then, and still on its default ("Sign with Ambari Internal
 * CA") on an upgrade, which would otherwise re-mint the Secret the release kept from when its Ingress was on.
 */
export function buildIngressTlsPayload(form: any): IngressTlsPayload {
  if (form?.ingress?.enabled !== true) return {};
  const tlsMode = tlsModeOf(form);
  const releaseName = form?.releaseName;
  const ingressHost = form?.ingress?.host;
  if (tlsMode === 'uploadLeaf') {
    const up = form?.ingress?.tlsUpload || {};
    if (!up.cert || !up.key) return {};
    return {
      ingressTlsUpload: {
        secretName: up.secretName || `${releaseName}-ingress-tls`,
        certPem: up.cert,
        keyPem: up.key,
      },
    };
  }
  if (tlsMode === 'signedByAmbariCA' || tlsMode === 'signedByCompanyCA') {
    const selfSign = form?.ingress?.tlsSelfSign || {};
    const validityDays = parseInt(selfSign.validityDays, 10);
    return {
      ingressTlsSelfSign: {
        mode: tlsMode === 'signedByAmbariCA' ? 'ambariInternal' : 'companyUploaded',
        secretName: `${releaseName}-ingress-tls`,
        ingressHost,
        validityDays: Number.isFinite(validityDays) ? validityDays : 90,
        ...(tlsMode === 'signedByCompanyCA' ? { caName: selfSign.caName } : {}),
      },
    };
  }
  if (tlsMode === 'signedByClusterIssuer') {
    const cm = form?.ingress?.certManager || {};
    const validityDays = parseInt(cm.validityDays, 10);
    const trim = (v: any) => (v && String(v).trim() ? String(v).trim() : undefined);
    // O and OU accept multiple comma-separated values → emitted as repeated DN elements
    // (e.g. OU=dept,OU=team). This covers CAs that require an extra OU like "Team" using
    // only standard cert-manager subject fields — no LiteralCertificateSubject gate needed.
    const csv = (v: any) => {
      const parts = String(v || '').split(',').map((s) => s.trim()).filter(Boolean);
      return parts.length ? parts : undefined;
    };
    return {
      ingressTlsCertManager: {
        issuerName: cm.issuerName,
        issuerKind: cm.issuerKind || 'ClusterIssuer',
        secretName: `${releaseName}-ingress-tls`,
        ingressHost,
        durationHours: (Number.isFinite(validityDays) ? validityDays : 90) * 24,
        commonName: trim(cm.commonName),
        organizations: csv(cm.organization),
        organizationalUnits: csv(cm.organizationalUnit),
        literalSubject: trim(cm.literalSubject),
      },
    };
  }
  if (tlsMode === 'sourcedFromExternalSecret') {
    const es = form?.ingress?.externalSecret || {};
    return {
      ingressTlsExternalSecret: {
        secretStoreName: es.secretStoreName,
        secretStoreKind: es.secretStoreKind || 'ClusterSecretStore',
        remoteKey: es.remoteKey,
        refreshInterval: es.refreshInterval || '1h',
        secretName: `${releaseName}-ingress-tls`,
        ingressHost,
      },
    };
  }
  return {};
}

/** The certificate a release's Ingress serves now, as Upgrade/Config describes it. */
export interface CurrentIngressTls {
  /** The TLS mode it most likely came from (an `ingress.tlsMode` value), or 'external' when that cannot be told. */
  mode: string;
  /** Where the certificate comes from, in the operator's words. */
  origin: string;
  /** Who keeps the Secret, as the server reports it (cert-manager, external-secrets, k8s-view-self-signed, external). */
  source?: string;
  secretName?: string;
  notAfter?: string;
  /** The hosts the Ingress serves this Secret for. */
  hosts?: string[];
  /** The DNS names the certificate is valid for (its subject alternative names). */
  sans?: string[];
}

/**
 * Describes the certificate a release serves from its TLS state (GET /helm/releases/{ns}/{release}/tls), for a release
 * deployed before the TLS mode was kept anywhere. Null when the release has no Ingress (or the state is unknown).
 *
 * @param entries          the release's TLS state, one entry per Ingress and TLS Secret
 * @param deployedTlsSecret the "Bring your own Secret" name in the release's values, if any
 */
export function describeCurrentIngressTls(entries: ReleaseTlsEntry[] | null | undefined,
                                          deployedTlsSecret?: string): CurrentIngressTls | null {
  if (!Array.isArray(entries) || entries.length === 0) return null;
  const entry = entries.find((e) => e && e.secretName);
  if (!entry) return { mode: 'none', origin: 'no certificate: the Ingress serves plain HTTP' };
  const base = { source: entry.source, secretName: entry.secretName || undefined, notAfter: entry.notAfter,
    hosts: entry.hosts, sans: entry.sans };
  if (entry.status === 'secret-missing') {
    return { ...base, mode: 'external', origin: 'the Secret the Ingress names does not exist' };
  }
  switch (entry.source) {
    case 'cert-manager':
      return { ...base, mode: 'signedByClusterIssuer', origin: 'issued and renewed by cert-manager' };
    case 'external-secrets':
      return { ...base, mode: 'sourcedFromExternalSecret',
        origin: 'synced from a Secret store by the External Secrets Operator' };
    case 'k8s-view-self-signed':
      return entry.caMode === 'companyUploaded'
        ? { ...base, mode: 'signedByCompanyCA',
          origin: `signed by the Company CA${entry.caName ? ` '${entry.caName}'` : ''}` }
        : { ...base, mode: 'signedByAmbariCA', origin: 'signed by the Ambari Internal CA (testing)' };
    default:
      if (deployedTlsSecret && deployedTlsSecret === entry.secretName) {
        return { ...base, mode: 'bringYourOwn', origin: 'your own Secret' };
      }
      if (entry.uploaded) {
        return { ...base, mode: 'uploadLeaf', origin: 'the certificate and key uploaded when it was deployed' };
      }
      return { ...base, mode: 'external', origin: 'a Secret managed outside KDPS' };
  }
}

/** An Upgrade/Config that replaces the release's ingress certificate. */
export interface IngressTlsChange {
  /** The TLS mode chosen. */
  mode: string;
  /** The Secret the deploy writes, or has cert-manager / ESO write, when the mode writes one. */
  targetSecret?: string;
  /** Set when the server will refuse the change: why, and what to do instead. */
  refusal?: string;
}

/**
 * The ingress certificate change an Upgrade/Config submits, or null when the release keeps its certificate:
 * "Keep current certificate", "Upload cert + key" with nothing pasted, "None" on an Ingress already serving plain HTTP,
 * or an Ingress that is off.
 *
 * @param form    the wizard's form values
 * @param current the certificate the release serves now, when known
 */
export function ingressTlsChange(form: any, current?: CurrentIngressTls | null): IngressTlsChange | null {
  if (form?.ingress?.enabled !== true) return null;
  const mode = tlsModeOf(form);
  if (!mode || mode === KEEP_CURRENT_TLS) return null;
  if (mode === 'uploadLeaf' && !pastedPem(form)) return null;
  if (mode === 'none' && !carriesIngressTls(form)) return null;
  const change: IngressTlsChange = { mode };
  if (SECRET_WRITING_MODES.includes(mode)) {
    const custom = mode === 'uploadLeaf' ? text(form?.ingress?.tlsUpload?.secretName) : '';
    change.targetSecret = custom || `${form?.releaseName}-ingress-tls`;
  }
  const controlled = current?.source === 'cert-manager' || current?.source === 'external-secrets';
  if (controlled && VIEW_WRITTEN_MODES.includes(mode) && current?.secretName === change.targetSecret) {
    const controller = current?.source === 'cert-manager' ? 'cert-manager' : 'the External Secrets Operator';
    change.refusal = `The Secret ${change.targetSecret} is kept by ${controller}, so KDPS cannot replace it. Choose `
      + `'Keep current certificate', or remove its ${current?.source === 'cert-manager' ? 'Certificate' : 'ExternalSecret'}`
      + ' first.';
  }
  return change;
}

const listsHost = (entry: any, host: string): boolean => Array.isArray(entry?.hosts)
  && entry.hosts.some((h: unknown) => text(h).toLowerCase() === host.toLowerCase());

/** Whether a certificate DNS name is valid for `host`: the same name, or a `*.` wildcard standing for its first label. */
export function dnsNameCovers(dnsName: string, host: string): boolean {
  const name = text(dnsName).toLowerCase();
  const target = text(host).toLowerCase();
  if (!name || !target) return false;
  if (name === target) return true;
  if (!name.startsWith('*.')) return false;
  const dot = target.indexOf('.');
  return dot > 0 && target.slice(dot + 1) === name.slice(2);
}

/** The TLS mode that issues a certificate for a new hostname, after the one the release's certificate came from. */
const reissueHint = (current: CurrentIngressTls | null | undefined, host: string): string => {
  switch (current?.mode) {
    case 'signedByCompanyCA': return "'Sign with Company CA (production)' with the CA it was signed with";
    case 'signedByAmbariCA': return "'Sign with Ambari Internal CA (testing)'";
    case 'signedByClusterIssuer': return "'Sign via cert-manager'";
    case 'sourcedFromExternalSecret': return `'Source from external Secret store' with a certificate for ${host}`;
    default: return `'Upload cert + key' or 'Bring your own Secret' with a certificate for ${host}`;
  }
};

/**
 * Upgrade/Config keeping the certificate (see {@link ingressTlsChange}) while the hostname changes. The kept
 * `ingress.tls` only names the old hostname, and the certificate was issued for it: unless that certificate is also
 * valid for the new hostname (a wildcard, another SAN), clients of the new hostname would get the ingress controller's
 * default certificate and fail to connect. Says why the certificate cannot be kept, or null when it can.
 *
 * @param form    the wizard's form values
 * @param current the certificate the release serves now, when known
 */
export function keptCertificateHostProblem(form: any, current?: CurrentIngressTls | null): string | null {
  if (form?.ingress?.enabled !== true || ingressTlsChange(form, current)) return null;
  const host = text(form?.ingress?.host);
  const kept = keptTlsEntries(form);
  if (!host || kept.length === 0 || kept.some((e) => listsHost(e, host))) return null;
  if (current?.sans?.some((name) => dnsNameCovers(name, host))) return null;
  const served = Array.from(new Set(kept.flatMap((e) => (Array.isArray(e.hosts) ? e.hosts.map(text) : []))))
    .filter(Boolean);
  const keepHost = served.length ? `, or keep the hostname ${served.join(', ')}` : '';
  const choose = `Choose a TLS mode that issues one for ${host}, such as ${reissueHint(current, host)}${keepHost}.`;
  if (!current) {
    return `KDPS could not read the certificate the release serves, so it cannot tell whether it is valid for ${host}. `
      + choose;
  }
  const ns = text(form?.namespace);
  const secret = current.secretName ? ` (Secret ${ns ? `${ns}/` : ''}${current.secretName})` : '';
  const names = current.sans?.length ? `: it is for ${current.sans.join(', ')}` : '';
  return `The current certificate${secret} is not valid for ${host}${names}. Kept as it is, clients of ${host} would `
    + `get the ingress controller's default certificate and fail to connect. ${choose}`;
}

/** What the new certificate is, for the confirmation. */
const newCertificate = (form: any, mode: string): string => {
  switch (mode) {
    case 'uploadLeaf': return 'the certificate and key you pasted';
    case 'signedByAmbariCA':
      return 'a certificate signed by the Ambari Internal CA (testing: browsers and clients do not trust it)';
    case 'signedByCompanyCA': {
      const ca = text(form?.ingress?.tlsSelfSign?.caName);
      return `a certificate signed by the Company CA${ca ? ` '${ca}'` : ''}`;
    }
    case 'signedByClusterIssuer': {
      const issuer = text(form?.ingress?.certManager?.issuerName);
      return `a certificate cert-manager issues${issuer ? ` from '${issuer}'` : ''}`;
    }
    case 'sourcedFromExternalSecret': {
      const store = text(form?.ingress?.externalSecret?.secretStoreName);
      return `the certificate the External Secrets Operator syncs${store ? ` from '${store}'` : ''}`;
    }
    default: return 'a new certificate';
  }
};

/**
 * The confirmation an Upgrade/Config asks for before it replaces the ingress certificate: which Secret is replaced,
 * by what, and which one the Ingress stops serving.
 */
export function describeIngressTlsChange(change: IngressTlsChange, form: any,
                                         current?: CurrentIngressTls | null): string[] {
  const ns = text(form?.namespace);
  const qualified = (name: string) => (ns ? `${ns}/${name}` : name);
  const lines: string[] = [];
  const now = current?.secretName ? `${qualified(current.secretName)} (${current.origin})` : undefined;
  if (change.targetSecret) {
    const target = qualified(change.targetSecret);
    const next = newCertificate(form, change.mode);
    if (current?.secretName === change.targetSecret) {
      lines.push(`The Secret ${target} will be replaced by ${next}.`);
    } else {
      lines.push(`The Secret ${target} will hold ${next}; if that Secret already exists, it is replaced.`);
      if (now) lines.push(`The Ingress stops serving ${now}.`);
    }
  } else if (change.mode === 'bringYourOwn') {
    const own = text(form?.ingress?.tlsSecret);
    lines.push(`The Ingress will serve your Secret ${qualified(own)}${now ? ` instead of ${now}` : ''}.`);
  } else if (change.mode === 'none') {
    lines.push(`The Ingress stops serving HTTPS${now ? `: ${now} is no longer used` : ''}.`);
  }
  lines.push('Browsers, JDBC/ODBC clients and BI tools that trust the current certificate may fail to connect until '
    + 'they trust the new one.');
  return lines;
}

/** What Upgrade/Config tells the operator about the ingress certificate, next to the TLS mode. */
export interface IngressTlsNotice {
  type: 'info' | 'warning';
  title: string;
  lines: string[];
}

/**
 * The notice Upgrade/Config shows about the ingress certificate: kept (and which one it is), or about to change.
 * Null outside an upgrade of a release that exposes an Ingress.
 *
 * @param upgrade whether this is an Upgrade/Config of a service whose TLS mode the wizard sets (see
 *                offersKeepCurrentTls); a chart that manages its own TLS gets no notice
 */
export function ingressTlsNotice(form: any, current: CurrentIngressTls | null | undefined,
                                 upgrade: boolean): IngressTlsNotice | null {
  if (!upgrade || form?.ingress?.enabled !== true) return null;
  const change = ingressTlsChange(form, current);
  if (change) {
    return { type: 'warning', title: 'The ingress certificate will change',
      lines: change.refusal ? [change.refusal] : describeIngressTlsChange(change, form, current) };
  }
  const hostProblem = keptCertificateHostProblem(form, current);
  if (hostProblem) {
    return { type: 'warning', title: 'The current certificate is not valid for the new hostname', lines: [hostProblem] };
  }
  if (!carriesIngressTls(form)) {
    return { type: 'info', title: 'Ingress certificate',
      lines: ['The release serves plain HTTP: its Ingress has no certificate.', 'Choose a TLS mode to serve HTTPS.'] };
  }
  const ns = text(form?.namespace);
  const secret = current?.secretName ? ` (Secret ${ns ? `${ns}/` : ''}${current.secretName}`
    + `${current.notAfter ? `, valid until ${current.notAfter.slice(0, 10)}` : ''})` : '';
  const what = current ? `${current.origin}${secret}` : 'the certificate its Ingress serves now';
  const lines = [`The release keeps its current certificate: ${what}.`];
  lines.push(tlsModeOf(form) === 'uploadLeaf'
    ? 'Paste a certificate and key only to replace it.'
    : 'Choose another TLS mode only to replace it.');
  return { type: 'info', title: 'Ingress certificate', lines };
}

/**
 * The form with the kept `ingress.tls` also naming the hostname, when the operator changed it: added to the first entry
 * with a Secret, which is the one the server writes and the release serves (`ingress.tls[0]`). The old hostname stays,
 * as it stays among the Ingress rules. The form itself is left as it is, and so is a service without a TLS mode (its
 * chart manages its own TLS).
 */
function withKeptTlsServingHost(form: any): any {
  const host = text(form?.ingress?.host);
  const tls = Array.isArray(form?.ingress?.tls) ? form.ingress.tls : [];
  if (!tlsModeOf(form) || form?.ingress?.enabled !== true || !host || tls.some((e: any) => listsHost(e, host))) {
    return form;
  }
  let index = tls.findIndex((e: any) => e && typeof e === 'object' && text(e.secretName));
  if (index < 0) index = tls.findIndex((e: any) => e && typeof e === 'object');
  if (index < 0) return form;
  const entry = tls[index];
  const hosts = Array.isArray(entry.hosts) ? entry.hosts : [];
  const keptTls = tls.map((e: any, i: number) => (i === index ? { ...entry, hosts: [...hosts, host] } : e));
  return { ...form, ingress: { ...form.ingress, tls: keptTls } };
}

/**
 * The form values Upgrade/Config builds the chart values from. A release keeping its certificate keeps the deployed
 * `ingress.tls`, which also names a new hostname: the Ingress only serves a certificate for the hosts its TLS entry
 * lists (whether the certificate is valid for it is {@link keptCertificateHostProblem}'s check). An explicit change
 * wires the Ingress TLS again, as an install does: the deployed `ingress.tls` is dropped (it would keep the old Secret
 * first, next to the new one), and so is the "Bring your own Secret" name unless that is the mode chosen (its binding
 * would add the old Secret back).
 */
export function upgradeChartFormValues(form: any, upgrade: boolean): any {
  if (!upgrade) return form;
  if (!ingressTlsChange(form)) return withKeptTlsServingHost(form);
  const ingress = { ...(form.ingress || {}) };
  delete ingress.tls;
  if (tlsModeOf(form) !== 'bringYourOwn') delete ingress.tlsSecret;
  return { ...form, ingress };
}

/**
 * The code of the server's answer (409) to a deploy whose ingress certificate would replace an existing Secret KDPS
 * did not issue, without the operator's confirmation (see IngressTlsSecretGuard).
 */
export const TLS_REPLACEMENT_NOT_CONFIRMED = 'INGRESS_TLS_REPLACEMENT_NOT_CONFIRMED';

/** The existing Secret a deploy was refused for, until the operator confirms its replacement. */
export interface UnconfirmedTlsReplacement {
  namespace?: string;
  secretName: string;
}

/** The Secret named by the server's refusal to replace it unconfirmed (see submitHelmDeploy), or null for any other error. */
export function unconfirmedTlsReplacement(error: any): UnconfirmedTlsReplacement | null {
  if (error?.code !== TLS_REPLACEMENT_NOT_CONFIRMED || !text(error?.secretName)) return null;
  return { namespace: text(error.namespace) || undefined, secretName: text(error.secretName) };
}

/**
 * The confirmation a deploy asks for once the server refused to replace an existing Secret KDPS did not issue. On a
 * first install that is mostly the Secret an earlier install of the release left behind (an uninstall by an earlier
 * KDPS version kept it), with the certificate uploaded then, or one created outside KDPS; Upgrade/Config asks before
 * it submits instead.
 */
export function describeExistingSecretReplacement(refused: UnconfirmedTlsReplacement, form: any): string[] {
  const ns = refused.namespace || text(form?.namespace);
  const target = ns ? `${ns}/${refused.secretName}` : refused.secretName;
  const release = text(form?.releaseName);
  return [
    `The Secret ${target} already exists and was not issued by KDPS: it may hold a certificate uploaded for an earlier `
      + `install${release ? ` of ${release}` : ''}, or one created outside KDPS.`,
    `Deploying replaces it with ${newCertificate(form, tlsModeOf(form))}.`,
    `To serve it as it is instead, cancel and choose 'Bring your own Secret' with the Secret name ${refused.secretName}.`,
  ];
}
