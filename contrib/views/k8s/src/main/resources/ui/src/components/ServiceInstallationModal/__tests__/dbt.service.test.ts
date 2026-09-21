import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * The DBT catalog service, run against the real KDPS/services/DBT/service.json so the test cannot
 * drift from what ships. dbt is a Trino client: the definition's job is to fill the chart's Trino
 * profile and to switch on the security material that each authentication mode needs, without
 * asking the operator to tick the same thing twice.
 */
const SERVICE_JSON = path.join(__dirname, '../../../../../KDPS/services/DBT/service.json');
const CATALOG = path.join(__dirname, '../../../../../KDPS/services/catalog.json');
const def = JSON.parse(fs.readFileSync(SERVICE_JSON, 'utf8'));

function nest(flat: Record<string, any>): any {
  const out: any = {};
  for (const [k, v] of Object.entries(flat)) {
    const parts = k.split('.');
    let cur = out;
    for (const p of parts.slice(0, -1)) cur = cur[p] ??= {};
    cur[parts[parts.length - 1]] = v;
  }
  return out;
}

function resolve(flatForm: any) {
  const form = nest(flatForm);
  const varCtx = buildVarContext(def.variables as any, form, {}, {});
  const merged: any = {};
  applyBindingTargets(merged, def.bindings as any, {}, form, 'dbt', varCtx);
  return { varCtx, merged };
}

const fields = (groupName: string) =>
  def.form.find((g: any) => g.name === groupName).fields;
const field = (groupName: string, name: string) =>
  fields(groupName).find((f: any) => f.name === name);

describe('DBT service definition', () => {
  it('is registered in the catalog and pinned to the published chart', () => {
    expect(JSON.parse(fs.readFileSync(CATALOG, 'utf8'))).toContain('DBT');
    expect(def.chart).toBe('dbt');
    expect(def.version).toBe('0.1.6');
  });

  it('the Trino picker fills the two fields the chart profile needs', () => {
    const picker = field('trinoIntegration', 'trinoConnection');
    expect(picker.type).toBe('k8s-discovery');
    expect(picker.targetHost).toBe('trino.host');
    expect(picker.targetPort).toBe('trino.port');
    // Those targets must be real fields, or the auto-fill writes into nothing.
    expect(field('trinoIntegration', 'trino.host')).toBeTruthy();
    expect(field('trinoIntegration', 'trino.port')).toBeTruthy();
  });

  it('offers every authentication mode the adapter implements', () => {
    const modes = field('trinoIntegration', 'trino.auth.method').options.map((o: any) => o.value);
    expect(modes).toEqual(['none', 'ldap', 'kerberos', 'jwt', 'certificate']);
  });

  it('credential fields only appear for the mode that uses them', () => {
    expect(field('trinoIntegration', 'trino.auth.password').condition)
      .toEqual({ field: 'trino.auth.method', value: 'ldap' });
    expect(field('trinoIntegration', 'trino.auth.jwtToken').condition)
      .toEqual({ field: 'trino.auth.method', value: 'jwt' });
    // Scheme is only a choice when nothing authenticates; the rest are HTTPS by construction.
    expect(field('trinoIntegration', 'trino.httpScheme').condition)
      .toEqual({ field: 'trino.auth.method', value: 'none' });
  });

  it('kerberos switches the chart security block on by itself', () => {
    const { merged } = resolve({ 'trino.auth.method': 'kerberos' });
    // The engine keeps the boolean type, so the chart's `if` sees a real boolean.
    expect(merged.global.security.kerberos.enabled).toBe(true);
  });

  it('an unauthenticated connection asks for no security material', () => {
    const { merged } = resolve({ 'trino.auth.method': 'none', 'global.security.tls.truststoreSecret': 'ca' });
    expect(merged.global?.security?.kerberos).toBeUndefined();
    expect(merged.global?.security?.tls).toBeUndefined();
  });

  it('an authenticated connection mounts the company trust bundle when there is one', () => {
    const { merged } = resolve({ 'trino.auth.method': 'ldap', 'global.security.tls.truststoreSecret': 'ca' });
    expect(merged.global.security.tls.truststore.enabled).toBe(true);
    // Without a bundle there is nothing to mount, so nothing is written.
    expect(resolve({ 'trino.auth.method': 'ldap' }).merged.global?.security?.tls).toBeUndefined();
  });

  it('form field names match the chart value paths, so the wizard writes the profile directly', () => {
    const names = def.form.flatMap((g: any) => g.fields.map((f: any) => f.name));
    for (const n of ['trino.host', 'trino.port', 'trino.catalog', 'trino.schema', 'trino.threads',
                     'trino.auth.method', 'trino.auth.user', 'project.git.repo', 'project.git.branch',
                     'docs.enabled', 'runner.enabled', 'runner.schedule']) {
      expect(names).toContain(n);
    }
    // The picker itself is a control, not a chart value.
    expect(field('trinoIntegration', 'trinoConnection').excludeFromValues).toBe(true);
  });

  it('the git project is the default, and its fields are gated on it', () => {
    expect(field('projectIntegration', 'project.git.enabled').defaultValue).toBe(true);
    expect(field('projectIntegration', 'project.git.repo').condition)
      .toEqual({ field: 'project.git.enabled', value: true });
    expect(field('projectIntegration', 'project.existingClaim').condition)
      .toEqual({ field: 'project.git.enabled', value: false });
  });

  it('scheduling is off by default, because an orchestrator usually drives dbt', () => {
    expect(field('scheduleIntegration', 'runner.enabled').defaultValue).toBe(false);
  });
});
