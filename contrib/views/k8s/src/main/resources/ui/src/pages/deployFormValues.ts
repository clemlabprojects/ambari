// Pure helper for ServiceWizardPage: the snapshot of the wizard's form sent with a deploy as `formValues`. The backend
// reads from it the form fields that are kept out of the chart values (hosts, the platform context, the Atlas restart
// authorization...) to render its own templates.
//
// Left out: the wizard's own keys, the blocks that travel in request fields of their own (git, kerberos, tls, oidc),
// and the certificate and private key of an uploaded ingress certificate, which the request carries in
// `ingressTlsUpload` already. A private key has no place in a copy of the form.

const WIZARD_KEYS = ['installMode', 'chartDirect', 'chartOverride', 'repoId', 'version', 'svcKey',
  'deploymentMode', 'git', 'kerberos', 'tls', 'oidc'];

export function deployFormValues(installValues: Record<string, any> | undefined,
                                 atlasRestartAuthorized: boolean): Record<string, any> {
  const snap = JSON.parse(JSON.stringify(installValues || {}));
  WIZARD_KEYS.forEach((k) => { delete snap[k]; });
  if (snap.ingress && typeof snap.ingress === 'object') {
    delete snap.ingress.tlsUpload;
  }
  Object.keys(snap).filter((k) => k.startsWith('ingress.tlsUpload')).forEach((k) => { delete snap[k]; });
  // The operator's Atlas-restart authorization from the pre-deploy confirmation modal. The ATLAS_USER_PROVISION_OM
  // step reads this; "false" makes it write the federation user but skip the Atlas restart (the operator restarts
  // manually). Default authorized.
  snap._atlasRestartAuthorized = String(atlasRestartAuthorized);
  return snap;
}
