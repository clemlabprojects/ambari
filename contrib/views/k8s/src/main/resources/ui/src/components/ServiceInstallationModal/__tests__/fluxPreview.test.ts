import { chartNameInSource, fluxPreview } from '../fluxPreview';
import type { HelmRepo } from '../../../types';

/**
 * The GitOps preview of the install wizard shows what the backend commits: the chart source of the release's own,
 * in its namespace, with the chart repository's URL (never the Git repository's), and a HelmRelease reading it
 * without naming another namespace.
 */
const charts: HelmRepo = { id: 'charts', name: 'Charts', type: 'HTTP', url: 'https://charts.example.com', authMode: 'anonymous' };

describe('fluxPreview', () => {
  it('names the source after the release, in its namespace, with the chart repository URL', () => {
    const preview = fluxPreview({ releaseName: 'trino', namespace: 'analytics', repoId: 'charts', repo: charts,
      chart: 'trinodb/trino', values: { workers: 2 } });

    expect(preview.directory).toBe('clusters/default/analytics/trino');
    expect(preview.source.metadata).toEqual({ name: 'trino-charts', namespace: 'analytics' });
    expect(preview.source.spec).toEqual({ interval: '5m', url: 'https://charts.example.com' });
    expect(preview.release.metadata).toEqual({ name: 'trino', namespace: 'analytics' });
    expect(preview.release.spec.chart.spec).toEqual({ chart: 'trino', sourceRef: { kind: 'HelmRepository', name: 'trino-charts' } });
    expect(preview.release.spec.values).toEqual({ workers: 2 });
  });

  it('shows the Secrets an authenticated OCI registry with a private CA is read with', () => {
    const harbor: HelmRepo = { id: 'harbor', name: 'Harbor', type: 'OCI', url: 'harbor.example.com/kdps', authMode: 'basic',
      caTruststores: 'corp-ca' };

    const preview = fluxPreview({ releaseName: 'superset', namespace: 'bi', repoId: 'harbor', repo: harbor,
      chart: 'charts/superset:0.12.42', values: {}, pathPrefix: 'apps' });

    expect(preview.directory).toBe('apps/bi/superset');
    expect(preview.source.spec).toEqual({ interval: '5m', type: 'oci', url: 'oci://harbor.example.com/kdps',
      secretRef: { name: 'superset-harbor-repo-auth' }, certSecretRef: { name: 'superset-harbor-repo-ca' } });
    expect(preview.release.spec.chart.spec.chart).toBe('charts/superset');
  });

  it('names the Secrets the repository names itself', () => {
    const preview = fluxPreview({ releaseName: 'trino', namespace: 'analytics', repoId: 'charts',
      repo: { ...charts, authMode: 'token', fluxSecretName: 'charts-pull', fluxCertSecretName: 'corporate-ca', caTruststores: 'x' },
      chart: 'trino', values: {} });

    expect(preview.source.spec.secretRef).toEqual({ name: 'charts-pull' });
    expect(preview.source.spec.certSecretRef).toEqual({ name: 'corporate-ca' });
  });

  it('leaves the URL to the backend for a repository it does not know', () => {
    const preview = fluxPreview({ releaseName: 'trino', namespace: 'analytics', chart: 'trino', values: {} });

    expect(preview.source.spec.url).toBe('resolved-by-backend');
    expect(preview.source.metadata.name).toBe('trino-repo');
  });
});

describe('chartNameInSource', () => {
  it('matches the backend', () => {
    expect(chartNameInSource('trinodb/trino', false)).toBe('trino');
    expect(chartNameInSource('trinodb/trino', true)).toBe('trinodb/trino');
    expect(chartNameInSource('charts/trino:1.43.14', true)).toBe('charts/trino');
  });
});
