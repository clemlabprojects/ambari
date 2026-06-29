import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * Verifies the OpenMetadata "external-opensearch" binding wires the OpenShift-gated
 * external OpenSearch fields into Helm values: entering a host disables the bundled
 * OpenSearch subchart (opensearch.enabled=false) and populates externalSearch.*,
 * while leaving the host blank keeps the chart defaults untouched. Loads the REAL
 * service.json so the test fails if the binding/variable wiring drifts.
 */
const svc = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, '../../../../../KDPS/services/OPENMETADATA/service.json'),
    'utf8'
  )
);
const variables = svc.variables;
const binding = svc.bindings.find((b: any) => b.name === 'external-opensearch');

const apply = (formValues: any) => {
  const varCtx = buildVarContext(variables, formValues, {});
  // Mirror the chart defaults the values would start from.
  const values: any = { opensearch: { enabled: true }, externalSearch: { host: '', port: 9200, scheme: 'http' } };
  applyBindingTargets(values, [binding], {}, formValues, 'om', varCtx);
  return values;
};

describe('OpenMetadata external-opensearch binding', () => {
  it('exists with the expected targets', () => {
    expect(binding).toBeTruthy();
    const paths = binding.targets.map((t: any) => t.path);
    expect(paths).toEqual(['opensearch.enabled', 'externalSearch.host', 'externalSearch.port', 'externalSearch.scheme']);
  });

  it('disables the bundled OpenSearch and sets externalSearch.* when a host is given', () => {
    const v = apply({ externalOpensearch: { host: 'os.search.svc', port: 9201, scheme: 'https' } });
    expect(v.opensearch.enabled).toBe(false);
    expect(v.externalSearch.host).toBe('os.search.svc');
    expect(v.externalSearch.port).toBe(9201);
    expect(v.externalSearch.scheme).toBe('https');
  });

  it('keeps the bundled OpenSearch (defaults untouched) when no host is given', () => {
    const v = apply({});
    expect(v.opensearch.enabled).toBe(true); // target skipped → chart default preserved
    expect(v.externalSearch.host).toBe('');
    expect(v.externalSearch.port).toBe(9200);
    expect(v.externalSearch.scheme).toBe('http');
  });

  it('host only: disables bundled + sets host, leaves port/scheme at chart defaults', () => {
    const v = apply({ externalOpensearch: { host: 'os.search.svc' } });
    expect(v.opensearch.enabled).toBe(false);
    expect(v.externalSearch.host).toBe('os.search.svc');
    expect(v.externalSearch.port).toBe(9200); // externalOpensearchPort empty → skipped → default
    expect(v.externalSearch.scheme).toBe('http');
  });
});
