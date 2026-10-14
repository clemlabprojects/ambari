import * as fs from 'fs';
import * as path from 'path';
import {
  NAMESPACE_HINT,
  RELEASE_NAME_HINT,
  RELEASE_NAME_PATTERN,
  namespaceRules,
  patternRules,
  releaseNameRules,
} from '../nameRules';

/**
 * The wizard warns about a release name or namespace that Helm or Kubernetes would refuse while it is typed, with the
 * rule the server applies when the deploy is submitted.
 */
const patternOf = (rules: any[]): RegExp => rules.find((r) => r.pattern instanceof RegExp)!.pattern;
const messageOf = (rules: any[]): string => rules.find((r) => r.pattern instanceof RegExp)!.message;

const VALID_RELEASES = ['trino', 'trino-prod', 'superset.analytics', '1trino', 'a'.repeat(53)];
const INVALID_RELEASES = ['Trino', 'trino_prod', 'trino prod', 'trino: prod', '../team-b/trino', '-trino', 'trino-',
  'trino.', 'a'.repeat(54), 'trino\n---'];

const service = (name: string) => JSON.parse(
  fs.readFileSync(path.join(__dirname, `../../../../KDPS/services/${name}/service.json`), 'utf8'),
);

describe('release name rules', () => {
  it("follow Helm's rule when the service declares none", () => {
    const rules = releaseNameRules({ form: [{ name: 'releaseName', label: 'Release name', type: 'string' }] });

    expect(rules[0]).toMatchObject({ required: true });
    expect(messageOf(rules)).toBe(RELEASE_NAME_HINT);
    for (const name of VALID_RELEASES) expect(patternOf(rules).test(name)).toBe(true);
    for (const name of INVALID_RELEASES) expect(patternOf(rules).test(name)).toBe(false);
    expect(patternOf(releaseNameRules(undefined)).source).toBe(new RegExp(RELEASE_NAME_PATTERN).source);
  });

  it('use the pattern and message the service declares', () => {
    const rules = releaseNameRules({ form: [{ name: 'releaseName', pattern: '^trino-[a-z]+$', validationHint: 'Start with trino-.' }] });

    expect(patternOf(rules).test('trino-prod')).toBe(true);
    expect(patternOf(rules).test('superset')).toBe(false);
    expect(messageOf(rules)).toBe('Start with trino-.');
  });

  it('every service definition that asks for a release name declares the same rule as the server', () => {
    for (const name of ['TRINO', 'SUPERSET', 'OPENMETADATA']) {
      const definition = service(name);
      const field = definition.form.find((f: any) => f.name === 'releaseName');
      expect(field.pattern).toBe(RELEASE_NAME_PATTERN);
      const rules = releaseNameRules(definition);
      for (const valid of VALID_RELEASES) expect(patternOf(rules).test(valid)).toBe(true);
      for (const invalid of INVALID_RELEASES) expect(patternOf(rules).test(invalid)).toBe(false);
      expect(messageOf(rules)).toBe(RELEASE_NAME_HINT);
    }
  });

  it('a pattern the browser cannot compile does not stop the wizard', () => {
    expect(patternRules('([unclosed', 'hint')).toEqual([]);
    expect(patternRules(undefined, 'hint')).toEqual([]);
    expect(patternOf(releaseNameRules({ form: [{ name: 'releaseName', pattern: '([unclosed' }] })).source)
      .toBe(new RegExp(RELEASE_NAME_PATTERN).source);
  });
});

describe('namespace rules', () => {
  it('are a DNS-1123 label of at most 63 characters', () => {
    const rules = namespaceRules();

    expect(rules[0]).toMatchObject({ required: true });
    expect(messageOf(rules)).toBe(NAMESPACE_HINT);
    for (const ns of ['analytics', 'team-a', 'a'.repeat(63)]) expect(patternOf(rules).test(ns)).toBe(true);
    for (const ns of ['Analytics', 'team_a', 'team.a', '../team-b', 'a'.repeat(64), '-team']) {
      expect(patternOf(rules).test(ns)).toBe(false);
    }
  });
});
