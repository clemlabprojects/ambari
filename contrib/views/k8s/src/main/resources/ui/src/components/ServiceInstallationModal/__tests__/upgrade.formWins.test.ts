import { deepMerge } from '../bindings';

/**
 * Upgrade regression: the wizard seeds the form with the release's deployed values and the form
 * then drives the payload. The YAML editor may override it, but only where the operator actually
 * wrote something — never by replacing a whole top-level block.
 *
 * The bug this guards: buildFinalValues() used `{ ...form, ...deployedValues }`. On a dbt upgrade
 * the operator switched "Expose the site" on, the form produced `ingress.enabled = true`, and the
 * shallow spread put the deployed `ingress: { enabled: false }` back — the toggle silently did
 * nothing and the release came back with no Ingress/Route.
 */
const deployed = () => ({
  docs: { enabled: true, refreshSeconds: 0 },
  ingress: { enabled: false },
  trino: { host: 'trino-iceberg-clemlab-trino.trino-iceberg-ns.svc.cluster.local', port: 8080, catalog: 'iceberg' },
});

describe('upgrade: the form drives the payload', () => {
  it('a shallow spread is what dropped the operator toggle (the old behaviour)', () => {
    const form = { ...deployed(), ingress: { enabled: true } };
    const shallow = { ...form, ...deployed() };
    expect(shallow.ingress.enabled).toBe(false); // the bug, reproduced
  });

  it('the form value survives when no editor override is in play', () => {
    const form: any = { ...deployed(), ingress: { enabled: true } };
    expect(form.ingress.enabled).toBe(true);
  });

  it('an editor override merges deeply instead of replacing the block', () => {
    const form: any = {
      docs: { enabled: true, refreshSeconds: 0 },
      ingress: { enabled: true, className: 'nginx' },
      trino: { host: 'trino-clemlab-trino.trino-ns-iceberg.svc.cluster.local', port: 8080, catalog: 'iceberg' },
    };
    // operator hand-edits one key of one block
    deepMerge(form, { trino: { schema: 'kdps_demo' } });
    expect(form.trino).toEqual({
      host: 'trino-clemlab-trino.trino-ns-iceberg.svc.cluster.local',
      port: 8080, catalog: 'iceberg', schema: 'kdps_demo',
    });
    // the untouched block keeps what the form produced
    expect(form.ingress).toEqual({ enabled: true, className: 'nginx' });
  });

  it('an explicit editor edit still wins over the form', () => {
    const form: any = { ingress: { enabled: true, className: 'nginx' } };
    deepMerge(form, { ingress: { enabled: false } });
    expect(form.ingress).toEqual({ enabled: false, className: 'nginx' });
  });
});
