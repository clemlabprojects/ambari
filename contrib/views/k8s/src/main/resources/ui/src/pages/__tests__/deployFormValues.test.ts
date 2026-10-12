import { deployFormValues } from '../deployFormValues';

// The wizard sends a copy of its form with each deploy, for the fields the backend reads outside the chart values.
// The certificate and private key of an uploaded ingress certificate travel in their own request field: they are
// not copied into it.
describe('deployFormValues', () => {
  const installValues = {
    releaseName: 'superset',
    installMode: 'chart',
    repoId: 'charts',
    version: '0.12.40',
    git: { repoUrl: 'https://git.example.org/platform.git' },
    kerberos: { enabled: true },
    platformContextId: 'cdp-prod',
    superset: { secretKey: 'kept-for-the-server' },
    ingress: {
      host: 'superset.example.org',
      tlsMode: 'uploadLeaf',
      tlsUpload: {
        cert: '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----',
        key: '-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----',
      },
    },
  };

  it('leaves out the uploaded certificate and key, and the wizard keys', () => {
    const sent = deployFormValues(installValues, true);

    expect(JSON.stringify(sent)).not.toContain('PRIVATE KEY');
    expect(sent.ingress).toEqual({ host: 'superset.example.org', tlsMode: 'uploadLeaf' });
    for (const key of ['installMode', 'repoId', 'version', 'git', 'kerberos']) {
      expect(sent).not.toHaveProperty(key);
    }
  });

  it('keeps the other fields and records the Atlas restart authorization', () => {
    const sent = deployFormValues(installValues, false);

    expect(sent.platformContextId).toBe('cdp-prod');
    expect(sent.superset).toEqual({ secretKey: 'kept-for-the-server' });
    expect(sent._atlasRestartAuthorized).toBe('false');
    expect(installValues.ingress.tlsUpload.key).toContain('PRIVATE KEY');
  });

  it('also drops an uploaded key kept under a dotted name', () => {
    const sent = deployFormValues({ 'ingress.tlsUpload.key': '-----BEGIN EC PRIVATE KEY-----' }, true);

    expect(sent).toEqual({ _atlasRestartAuthorized: 'true' });
  });
});
