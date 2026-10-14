/*
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
import * as fs from 'fs';
import * as path from 'path';
import { deepMerge } from '../bindings';
import { applyFormDefaults, buildChartValues, seedFieldsFromValues } from '../chartValues';
import { seedFieldsFromRelease } from '../seedFromRelease';
import {
  buildIngressTlsPayload, carriesIngressTls, describeCurrentIngressTls, describeExistingSecretReplacement,
  describeIngressTlsChange, dnsNameCovers, ingressTlsChange, ingressTlsNotice, keptCertificateHostProblem,
  KEEP_CURRENT_TLS, offersKeepCurrentTls, securityProfileIngress, seedKeepCurrentTls, TLS_REPLACEMENT_NOT_CONFIRMED,
  unconfirmedTlsReplacement, upgradeChartFormValues, type CurrentIngressTls,
} from '../ingressTls';
import type { ReleaseTlsEntry } from '../../../api/client';

/**
 * Upgrade/Config keeps the certificate a release's Ingress serves. The TLS mode is not in the Helm values, so the
 * wizard used to start an upgrade from the field's default, "Sign with Ambari Internal CA (testing)", and a routine
 * change of worker memory replaced the production certificate (company CA, uploaded, or the operator's own Secret)
 * with a testing one. Driven against the real service definitions: install, then upgrade the way the wizard does.
 */
const SERVICES = ['TRINO', 'SUPERSET', 'OPENMETADATA'];
const load = (svc: string) => JSON.parse(fs.readFileSync(
  path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));

const NS = 'analytics';
const HOST = 'bi.apps.example.org';
const CORP_CERT = '-----BEGIN CERTIFICATE-----\nMIIBcorporate\n-----END CERTIFICATE-----';
const CORP_KEY = '-----BEGIN PRIVATE KEY-----\nMIIEcorporate\n-----END PRIVATE KEY-----';

const find = (fields: any[], name: string): any => {
  for (const f of fields || []) {
    if (f?.name === name) return f;
    const inner = Array.isArray(f?.fields) ? find(f.fields, name) : undefined;
    if (inner) return inner;
  }
  return undefined;
};

const chartValues = (def: any, form: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues: form,
  platform: 'kubernetes', chartVersion: def.version, retiredValues: def.retiredValues,
});

/** The TLS inputs of an install in each mode, as the operator fills them in. */
const MODES: Record<string, (rel: string) => any> = {
  companyCA: () => ({ tlsMode: 'signedByCompanyCA', tlsSelfSign: { caName: 'Corp-Issuing-CA', validityDays: '365' } }),
  uploadLeaf: () => ({ tlsMode: 'uploadLeaf', tlsUpload: { cert: CORP_CERT, key: CORP_KEY, secretName: 'corp-bi-tls' } }),
  bringYourOwn: () => ({ tlsMode: 'bringYourOwn', tlsSecret: 'corp-wildcard-tls' }),
};

/** The Secret each install mode leaves the Ingress serving. */
const SERVED: Record<string, (rel: string) => string> = {
  companyCA: (rel) => `${rel}-ingress-tls`,
  uploadLeaf: () => 'corp-bi-tls',
  bringYourOwn: () => 'corp-wildcard-tls',
};

/**
 * What the release holds after a first install in `mode`: the wizard's chart values, plus the ingress.tls[0] override
 * the server writes for the certificate it minted or stored (CommandService, INGRESS_TLS_SELFSIGN / upload path).
 */
function installedValues(def: any, rel: string, mode: string): any {
  const form: any = {};
  applyFormDefaults(def.form, form);
  Object.assign(form, { releaseName: rel, namespace: NS });
  form.ingress = { ...(form.ingress || {}), enabled: true, host: HOST, ...MODES[mode](rel) };
  const values = chartValues(def, form);
  const payload = buildIngressTlsPayload(form);
  const written = payload.ingressTlsSelfSign?.secretName || payload.ingressTlsUpload?.secretName;
  if (written) {
    values.ingress.tls = values.ingress.tls || [];
    values.ingress.tls[0] = { secretName: written, hosts: [HOST] };
  }
  return JSON.parse(JSON.stringify(values));
}

/** The form Upgrade/Config opens with (ServiceWizardPage): defaults, the deployed values over them, then the seeds. */
function upgradeForm(def: any, rel: string, deployed: any, keepCurrent = true): any {
  const form: any = {};
  applyFormDefaults(def.form, form);
  // Re-asserted after the defaults, as the wizard does: a definition may default the namespace.
  Object.assign(form, { releaseName: rel, namespace: NS });
  deepMerge(form, JSON.parse(JSON.stringify(deployed)));
  seedFieldsFromValues(def.form, form);
  const deployedHost = form?.ingress?.hosts?.[0]?.host;
  if (deployedHost && !form?.ingress?.host) form.ingress.host = deployedHost;
  seedFieldsFromRelease(def.form, form);
  if (keepCurrent) seedKeepCurrentTls(def.form, form, deployed);
  return form;
}

describe.each(SERVICES)('%s service.json — the TLS mode field', (svc) => {
  const def = load(svc);
  const field = find(def.form, 'ingress.tlsMode');

  it('offers "Keep current certificate" on Upgrade/Config only, and installs keep their default', () => {
    const keep = field.options.find((o: any) => o.value === KEEP_CURRENT_TLS);
    expect(keep).toEqual({ label: 'Keep current certificate', value: KEEP_CURRENT_TLS, upgradeOnly: true });
    expect(field.excludeFromValues).toBe(true);
    expect(field.defaultValue).toBe('signedByAmbariCA');
    expect(field.help).toContain('Keep current certificate');
  });
});

describe.each(SERVICES)('%s — Upgrade/Config of a release keeps its ingress certificate', (svc) => {
  const def = load(svc);
  const rel = svc.toLowerCase();

  it.each(Object.keys(MODES))('deployed with %s: no certificate is sent, and ingress.tls stays as deployed', (mode) => {
    const deployed = installedValues(def, rel, mode);
    expect(deployed.ingress.tls[0].secretName).toBe(SERVED[mode](rel));

    // The bug: started from the field's default, the upgrade minted a testing certificate over the release's own.
    const before = upgradeForm(def, rel, deployed, false);
    expect(before.ingress.tlsMode).toBe('signedByAmbariCA');
    expect(buildIngressTlsPayload(before).ingressTlsSelfSign).toMatchObject({ mode: 'ambariInternal' });

    const form = upgradeForm(def, rel, deployed);
    expect(form.ingress.tlsMode).toBe(KEEP_CURRENT_TLS);
    // The operator changes something unrelated and submits.
    form.replicaCount = 3;

    const payload = buildIngressTlsPayload(form);
    expect(payload).toEqual({});
    expect(payload.ingressTlsSelfSign).toBeUndefined();
    expect(ingressTlsChange(form)).toBeNull();

    const values = chartValues(def, upgradeChartFormValues(form, true));
    expect(values.ingress.tls[0]).toEqual(deployed.ingress.tls[0]);
    expect(JSON.stringify(values)).not.toContain(KEEP_CURRENT_TLS);
  });

  it('a release whose Ingress is off keeps the default: turning it on is a first TLS setup', () => {
    const deployed = { ingress: { enabled: false } };
    const form = upgradeForm(def, rel, deployed);
    expect(form.ingress.tlsMode).toBe('signedByAmbariCA');
    // The TLS mode is hidden while the Ingress is off: its default sends no certificate.
    expect(buildIngressTlsPayload(form)).toEqual({});
  });

  it('an Ingress turned off after a Company CA install: a later upgrade does not re-mint its Secret', () => {
    const deployed = installedValues(def, rel, 'companyCA');
    deployed.ingress.enabled = false;
    const form = upgradeForm(def, rel, deployed);
    expect(form.ingress.tlsMode).toBe('signedByAmbariCA');
    form.replicaCount = 3;

    expect(buildIngressTlsPayload(form)).toEqual({});
    expect(ingressTlsChange(form)).toBeNull();

    // Turning the Ingress back on with that default is an explicit change, confirmed before it is sent.
    form.ingress.enabled = true;
    expect(ingressTlsChange(form)).toMatchObject({ mode: 'signedByAmbariCA', targetSecret: `${rel}-ingress-tls` });
    expect(buildIngressTlsPayload(form).ingressTlsSelfSign).toMatchObject({ mode: 'ambariInternal' });
  });

  it('an explicit switch to the Ambari Internal CA names the Secret it replaces and wires the Ingress anew', () => {
    const deployed = installedValues(def, rel, 'bringYourOwn');
    const form = upgradeForm(def, rel, deployed);
    form.ingress.tlsMode = 'signedByAmbariCA';
    const current = { mode: 'bringYourOwn', origin: 'your own Secret', source: 'external', secretName: 'corp-wildcard-tls' };

    const change = ingressTlsChange(form, current)!;
    expect(change).toMatchObject({ mode: 'signedByAmbariCA', targetSecret: `${rel}-ingress-tls` });
    expect(change.refusal).toBeUndefined();
    const lines = describeIngressTlsChange(change, form, current).join(' ');
    expect(lines).toContain(`${NS}/${rel}-ingress-tls`);
    expect(lines).toContain('Ambari Internal CA');
    expect(lines).toContain(`${NS}/corp-wildcard-tls (your own Secret)`);
    expect(buildIngressTlsPayload(form).ingressTlsSelfSign).toMatchObject({ mode: 'ambariInternal', secretName: `${rel}-ingress-tls` });

    // The server writes ingress.tls[0] for the new Secret: neither the deployed entry nor the binding of the old
    // "Bring your own Secret" name may keep the old one next to it.
    const values = chartValues(def, upgradeChartFormValues(form, true));
    expect(values.ingress?.tls ?? []).toEqual([]);
    expect(values.ingress?.tlsSecret).toBeUndefined();
  });

  it('an explicit switch to the operator\'s own Secret serves that Secret only', () => {
    const deployed = installedValues(def, rel, 'companyCA');
    const form = upgradeForm(def, rel, deployed);
    form.ingress.tlsMode = 'bringYourOwn';
    form.ingress.tlsSecret = 'corp-wildcard-tls';

    expect(ingressTlsChange(form)).toEqual({ mode: 'bringYourOwn' });
    expect(buildIngressTlsPayload(form)).toEqual({});
    const values = chartValues(def, upgradeChartFormValues(form, true));
    // Every entry is the operator's Secret (Superset's definition declares its ingress binding twice).
    expect(values.ingress.tls.length).toBeGreaterThan(0);
    for (const tls of values.ingress.tls) expect(tls).toEqual({ secretName: 'corp-wildcard-tls', hosts: [HOST] });
  });

  it('"Upload cert + key" with nothing pasted keeps the uploaded certificate; pasting one replaces it', () => {
    const deployed = installedValues(def, rel, 'uploadLeaf');
    const form = upgradeForm(def, rel, deployed);
    form.ingress.tlsMode = 'uploadLeaf';
    form.ingress.tlsUpload = { secretName: 'corp-bi-tls' };

    expect(ingressTlsChange(form)).toBeNull();
    expect(buildIngressTlsPayload(form)).toEqual({});
    expect(chartValues(def, upgradeChartFormValues(form, true)).ingress.tls[0].secretName).toBe('corp-bi-tls');

    form.ingress.tlsUpload = { secretName: 'corp-bi-tls', cert: CORP_CERT, key: CORP_KEY };
    expect(ingressTlsChange(form)).toEqual({ mode: 'uploadLeaf', targetSecret: 'corp-bi-tls' });
    expect(buildIngressTlsPayload(form).ingressTlsUpload).toMatchObject({ secretName: 'corp-bi-tls' });
  });
});

describe.each(SERVICES)('%s — Upgrade/Config of a release serving plain HTTP', (svc) => {
  const def = load(svc);
  const rel = svc.toLowerCase();
  /** What the release holds after an install with "None (HTTP only)": an Ingress without TLS. */
  const httpValues = () => {
    const form: any = {};
    applyFormDefaults(def.form, form);
    Object.assign(form, { releaseName: rel, namespace: NS });
    form.ingress = { ...(form.ingress || {}), enabled: true, host: HOST, tlsMode: 'none' };
    expect(buildIngressTlsPayload(form)).toEqual({});
    return JSON.parse(JSON.stringify(chartValues(def, form)));
  };
  /** Step 1: the operator picks a security profile; the wizard applies what it requires of the Ingress. */
  const pickProfile = (form: any) => {
    form.securityProfile = 'corp-ldap';
    const required = securityProfileIngress(def.securityCoupling, form);
    form.ingress = { ...form.ingress, ...(required || {}) };
    return required;
  };

  it.each([
    ['installed with "None"', () => httpValues()],
    ['an Ingress with an empty TLS list', () => ({ ingress: { enabled: true, hosts: [{ host: HOST }], tls: [] } })],
  ])('%s: starts on "None", and a routine upgrade stays on HTTP without asking', (_label, deployedOf) => {
    const deployed = deployedOf();
    expect(carriesIngressTls(deployed)).toBe(false);
    const form = upgradeForm(def, rel, deployed);
    expect(form.ingress.tlsMode).toBe('none');
    form.replicaCount = 3;

    expect(buildIngressTlsPayload(form)).toEqual({});
    expect(ingressTlsChange(form)).toBeNull();
    expect(keptCertificateHostProblem(form, null)).toBeNull();
    expect(ingressTlsNotice(form, null, true)).toMatchObject({ type: 'info',
      lines: ['The release serves plain HTTP: its Ingress has no certificate.', 'Choose a TLS mode to serve HTTPS.'] });
    expect(chartValues(def, upgradeChartFormValues(form, true)).ingress?.tls ?? []).toEqual([]);
  });

  it('picking a security profile moves it to a TLS mode that issues a certificate, as on an install', () => {
    const form = upgradeForm(def, rel, { ingress: { enabled: true, hosts: [{ host: HOST }], tls: [] } });
    expect(pickProfile(form)).toEqual({ tlsMode: def.securityCoupling.minTlsMode });

    expect(form.ingress.tlsMode).toBe('signedByAmbariCA');
    expect(buildIngressTlsPayload(form).ingressTlsSelfSign)
      .toMatchObject({ mode: 'ambariInternal', secretName: `${rel}-ingress-tls`, ingressHost: HOST });
    // A change of certificate: confirmed before it is sent.
    expect(ingressTlsChange(form)).toMatchObject({ mode: 'signedByAmbariCA', targetSecret: `${rel}-ingress-tls` });
  });

  it('"Keep current certificate" chosen by hand keeps nothing: a security profile still moves it to TLS', () => {
    const form = upgradeForm(def, rel, httpValues());
    form.ingress.tlsMode = KEEP_CURRENT_TLS;
    expect(ingressTlsNotice(form, null, true)?.lines[0]).toContain('plain HTTP');

    pickProfile(form);
    expect(form.ingress.tlsMode).toBe('signedByAmbariCA');
    expect(buildIngressTlsPayload(form).ingressTlsSelfSign).toMatchObject({ mode: 'ambariInternal' });
  });

  it('a release serving HTTPS keeps its certificate when a security profile is picked', () => {
    const form = upgradeForm(def, rel, installedValues(def, rel, 'companyCA'));
    expect(form.ingress.tlsMode).toBe(KEEP_CURRENT_TLS);

    expect(pickProfile(form)).toBeNull();
    expect(form.ingress.tlsMode).toBe(KEEP_CURRENT_TLS);
    expect(buildIngressTlsPayload(form)).toEqual({});
  });

  it('switching an HTTPS release to "None" is a change, confirmed before it is sent', () => {
    const form = upgradeForm(def, rel, installedValues(def, rel, 'companyCA'));
    form.ingress.tlsMode = 'none';
    const change = ingressTlsChange(form)!;
    expect(change).toEqual({ mode: 'none' });
    expect(describeIngressTlsChange(change, form)[0]).toContain('stops serving HTTPS');
    expect(chartValues(def, upgradeChartFormValues(form, true)).ingress?.tls ?? []).toEqual([]);
  });
});

describe('securityProfileIngress — what a security profile requires of the Ingress', () => {
  const viewTls = { requireIngress: true, minTlsMode: 'signedByCompanyCA', tlsProvisioning: 'k8s-view' };

  it('requires nothing without a profile', () => {
    expect(securityProfileIngress(viewTls, { ingress: { enabled: false, tlsMode: 'none' } })).toBeNull();
  });

  it('turns the Ingress on and moves a mode without TLS to the minimum TLS mode', () => {
    expect(securityProfileIngress(viewTls, { securityProfile: 'p', ingress: { enabled: false, tlsMode: 'none' } }))
      .toEqual({ enabled: true, tlsMode: 'signedByCompanyCA' });
    expect(securityProfileIngress({}, { securityProfile: 'p', ingress: { enabled: true } }))
      .toEqual({ tlsMode: 'signedByAmbariCA' });
    expect(securityProfileIngress(viewTls, { securityProfile: 'p', ingress: { enabled: true, tlsMode: 'uploadLeaf' } }))
      .toBeNull();
  });

  it('"Keep current certificate" stays only when the release serves a certificate', () => {
    const keep = (ingress: any) => securityProfileIngress(viewTls,
      { securityProfile: 'p', ingress: { enabled: true, tlsMode: KEEP_CURRENT_TLS, ...ingress } });
    expect(keep({ tls: [{ secretName: 'bi-tls', hosts: [HOST] }] })).toBeNull();
    expect(keep({ tlsSecret: 'corp-wildcard-tls' })).toBeNull();
    expect(keep({ tls: [] })).toEqual({ tlsMode: 'signedByCompanyCA' });
    expect(keep({})).toEqual({ tlsMode: 'signedByCompanyCA' });
  });

  it('leaves the TLS mode of a chart that manages its own TLS, and the Ingress when it is not required', () => {
    const form = { securityProfile: 'p', ingress: { enabled: false, tlsMode: 'none' } };
    expect(securityProfileIngress({ tlsProvisioning: 'chart-managed' }, form)).toEqual({ enabled: true });
    expect(securityProfileIngress({ requireTls: false }, form)).toEqual({ enabled: true });
    expect(securityProfileIngress({ requireIngress: false, tlsProvisioning: 'external' }, form)).toBeNull();
  });
});

describe.each(SERVICES)('%s — Upgrade/Config that moves the release to a new hostname', (svc) => {
  const def = load(svc);
  const rel = svc.toLowerCase();
  const NEW_HOST = 'bi-new.apps.example.org';
  /** The release's certificate as its TLS state describes it: minted by KDPS with the company CA. */
  const minted = (sans: string[]): CurrentIngressTls => ({
    mode: 'signedByCompanyCA', origin: "signed by the Company CA 'corp-issuing-ca'", source: 'k8s-view-self-signed',
    secretName: `${rel}-ingress-tls`, hosts: [HOST], sans,
  });
  const movedForm = () => {
    const form = upgradeForm(def, rel, installedValues(def, rel, 'companyCA'));
    form.ingress.host = NEW_HOST;
    return form;
  };

  it('keeping a certificate that is not valid for the new hostname is refused, and says what to choose', () => {
    const form = movedForm();
    expect(form.ingress.tlsMode).toBe(KEEP_CURRENT_TLS);
    const current = minted([HOST]);

    const problem = keptCertificateHostProblem(form, current)!;
    expect(problem).toContain(`not valid for ${NEW_HOST}`);
    expect(problem).toContain(`Secret ${NS}/${rel}-ingress-tls`);
    expect(problem).toContain("'Sign with Company CA (production)' with the CA it was signed with");
    expect(problem).toContain(`keep the hostname ${HOST}`);
    // Shown in place of "The release keeps its current certificate".
    const notice = ingressTlsNotice(form, current, true)!;
    expect(notice.type).toBe('warning');
    expect(notice.lines).toEqual([problem]);
    // The certificate the release serves could not be read: refused too, rather than risk the new hostname.
    expect(keptCertificateHostProblem(form, null)).toContain(`cannot tell whether it is valid for ${NEW_HOST}`);
    // "Upload cert + key" with nothing pasted keeps the certificate as well.
    const nothingPasted = { ...form, ingress: { ...form.ingress, tlsMode: 'uploadLeaf', tlsUpload: {} } };
    expect(keptCertificateHostProblem(nothingPasted, current)).toContain(`not valid for ${NEW_HOST}`);
  });

  it('choosing the CA again issues a certificate for the new hostname', () => {
    const form = movedForm();
    form.ingress.tlsMode = 'signedByCompanyCA';
    form.ingress.tlsSelfSign = { caName: 'Corp-Issuing-CA', validityDays: '365' };

    expect(keptCertificateHostProblem(form, minted([HOST]))).toBeNull();
    expect(buildIngressTlsPayload(form).ingressTlsSelfSign)
      .toMatchObject({ mode: 'companyUploaded', caName: 'Corp-Issuing-CA', ingressHost: NEW_HOST });
  });

  it('a certificate also valid for the new hostname is kept, and the Ingress serves it for that hostname', () => {
    const form = movedForm();
    const current = minted(['*.apps.example.org']);

    expect(keptCertificateHostProblem(form, current)).toBeNull();
    expect(ingressTlsNotice(form, current, true)?.type).toBe('info');
    expect(buildIngressTlsPayload(form)).toEqual({});
    const values = chartValues(def, upgradeChartFormValues(form, true));
    expect(values.ingress.tls[0]).toEqual({ secretName: `${rel}-ingress-tls`, hosts: [HOST, NEW_HOST] });
    // The wizard's form is left as it is.
    expect(form.ingress.tls[0].hosts).toEqual([HOST]);
  });

  it('an unchanged hostname keeps everything as deployed', () => {
    const deployed = installedValues(def, rel, 'companyCA');
    const form = upgradeForm(def, rel, deployed);

    expect(keptCertificateHostProblem(form, minted([HOST]))).toBeNull();
    expect(upgradeChartFormValues(form, true)).toBe(form);
  });
});

describe('dnsNameCovers', () => {
  it('matches the same name, and a wildcard for the first label only', () => {
    expect(dnsNameCovers('bi.example.org', 'BI.example.org')).toBe(true);
    expect(dnsNameCovers('*.example.org', 'bi.example.org')).toBe(true);
    expect(dnsNameCovers('*.example.org', 'a.bi.example.org')).toBe(false);
    expect(dnsNameCovers('*.example.org', 'example.org')).toBe(false);
    expect(dnsNameCovers('other.example.org', 'bi.example.org')).toBe(false);
  });
});

describe('a deploy refused for an existing Secret KDPS did not issue', () => {
  // Uninstalling keeps the ingress Secret: reinstalling a release that served an uploaded certificate meets it.
  const refusal = () => Object.assign(new Error('Submit failed: 409: The ingress certificate Secret already exists'),
    { status: 409, code: TLS_REPLACEMENT_NOT_CONFIRMED, namespace: NS, secretName: 'trino-ingress-tls' });

  it('is told apart from any other refusal', () => {
    expect(unconfirmedTlsReplacement(refusal())).toEqual({ namespace: NS, secretName: 'trino-ingress-tls' });
    expect(unconfirmedTlsReplacement(Object.assign(refusal(), { code: 'OTHER' }))).toBeNull();
    expect(unconfirmedTlsReplacement(new Error('Submit failed: 400: bad'))).toBeNull();
    expect(unconfirmedTlsReplacement(undefined)).toBeNull();
  });

  it('a first install asks to replace it, naming it and what replaces it, or to serve it as it is', () => {
    const form = { releaseName: 'trino', namespace: NS, ingress: { enabled: true, tlsMode: 'signedByAmbariCA' } };
    const lines = describeExistingSecretReplacement(unconfirmedTlsReplacement(refusal())!, form).join(' ');
    expect(lines).toContain(`The Secret ${NS}/trino-ingress-tls already exists and was not issued by KDPS`);
    expect(lines).toContain('an earlier install of trino');
    expect(lines).toContain('replaces it with a certificate signed by the Ambari Internal CA');
    expect(lines).toContain("'Bring your own Secret' with the Secret name trino-ingress-tls");
    // An install offers no "Keep current certificate".
    expect(lines).not.toContain('Keep current certificate');

    const renewed = { ...form, ingress: { enabled: true, tlsMode: 'uploadLeaf', tlsUpload: { cert: CORP_CERT, key: CORP_KEY } } };
    expect(describeExistingSecretReplacement(unconfirmedTlsReplacement(refusal())!, renewed).join(' '))
      .toContain('replaces it with the certificate and key you pasted');
  });
});

describe('services whose chart manages its own TLS', () => {
  it.each(['GITLAB'])('%s gets no ingress certificate handling on Upgrade/Config', (svc) => {
    const def = load(svc);
    expect(offersKeepCurrentTls(def.form)).toBe(false);
    const form: any = { ingress: { enabled: true } };
    expect(seedKeepCurrentTls(def.form, form, { ingress: { enabled: true, tls: [{ secretName: 'x' }] } })).toBe(false);
    expect(form.ingress.tlsMode).toBeUndefined();
  });

  it('a new hostname leaves their ingress.tls to them', () => {
    const form = { ingress: { enabled: true, host: 'git-new.example.org',
      tls: [{ secretName: 'gitlab-tls', hosts: ['git.example.org'] }] } };
    expect(upgradeChartFormValues(form, true)).toBe(form);
  });
});

describe('the certificate a release serves, from its TLS state', () => {
  const entry = (over: Partial<ReleaseTlsEntry>): ReleaseTlsEntry => ({
    ingressName: 'trino', namespace: NS, secretName: 'trino-ingress-tls', hosts: [HOST], status: 'valid', ...over,
  });

  it('tells every way a release gets its certificate apart', () => {
    expect(describeCurrentIngressTls([entry({ source: 'cert-manager' })])?.mode).toBe('signedByClusterIssuer');
    expect(describeCurrentIngressTls([entry({ source: 'external-secrets' })])?.mode).toBe('sourcedFromExternalSecret');
    const company = describeCurrentIngressTls([entry({ source: 'k8s-view-self-signed', caMode: 'companyUploaded', caName: 'corp-issuing-ca' })]);
    expect(company).toMatchObject({ mode: 'signedByCompanyCA', secretName: 'trino-ingress-tls' });
    expect(company?.origin).toContain("'corp-issuing-ca'");
    expect(describeCurrentIngressTls([entry({ source: 'k8s-view-self-signed', caMode: 'ambariInternal' })])?.mode)
      .toBe('signedByAmbariCA');
    expect(describeCurrentIngressTls([entry({ source: 'external', secretName: 'corp-own' })], 'corp-own')?.mode)
      .toBe('bringYourOwn');
    expect(describeCurrentIngressTls([entry({ source: 'external', uploaded: true })])?.mode).toBe('uploadLeaf');
    expect(describeCurrentIngressTls([entry({ source: 'external' })])?.mode).toBe('external');
    expect(describeCurrentIngressTls([entry({ secretName: null, status: 'no-tls' })])?.mode).toBe('none');
    expect(describeCurrentIngressTls([])).toBeNull();
  });

  it('Upgrade/Config says which certificate is kept', () => {
    const current = describeCurrentIngressTls([entry({ source: 'external', uploaded: true, notAfter: '2027-03-01T00:00:00Z' })]);
    const notice = ingressTlsNotice({ releaseName: 'trino', namespace: NS, ingress: { enabled: true,
      tlsMode: KEEP_CURRENT_TLS, tls: [{ secretName: 'trino-ingress-tls', hosts: [HOST] }] } }, current, true)!;
    expect(notice.type).toBe('info');
    expect(notice.lines[0]).toContain('uploaded');
    expect(notice.lines[0]).toContain(`${NS}/trino-ingress-tls, valid until 2027-03-01`);
    expect(ingressTlsNotice({ ingress: { enabled: true, tlsMode: 'signedByAmbariCA' } }, current, false)).toBeNull();
  });

  it('a Secret cert-manager or ESO keeps is not offered for KDPS to overwrite', () => {
    const current = describeCurrentIngressTls([entry({ source: 'cert-manager' })]);
    const form = { releaseName: 'trino', namespace: NS, ingress: { enabled: true, tlsMode: 'signedByCompanyCA', tlsSelfSign: { caName: 'corp' } } };
    expect(ingressTlsChange(form, current)?.refusal).toContain('cert-manager');
    expect(ingressTlsNotice(form, current, true)?.lines[0]).toContain('cert-manager');
    // Another issuer for cert-manager itself is not refused.
    const reissue = { ...form, ingress: { enabled: true, tlsMode: 'signedByClusterIssuer', certManager: { issuerName: 'other' } } };
    expect(ingressTlsChange(reissue, current)?.refusal).toBeUndefined();
  });
});
