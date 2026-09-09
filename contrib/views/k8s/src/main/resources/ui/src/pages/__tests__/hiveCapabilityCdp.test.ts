import * as fs from 'fs';
import * as path from 'path';

// Mirrors ContextsPage.appliesToKind: a capability field with NO appliesTo is collected only for
// kind "EXTERNAL"; otherwise the field's appliesTo comma-list must include the kind. A CDP context
// therefore never collects a field that lacks appliesTo — which is why the Hive Metastore URI went
// missing from the Trino catalog for an external CDP context.
function appliesToKind(appliesTo: string | undefined, kind: string): boolean {
  if (!appliesTo) return kind === 'EXTERNAL';
  return appliesTo.split(',').map((s) => s.trim().toUpperCase()).includes(kind.toUpperCase());
}

const hive = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../KDPS/contexts/capabilities/hive.json'), 'utf8'),
);

describe('hive capability is collectable for external CDP/REMOTE contexts', () => {
  it('metastoreUri (the reported bug) is collected for a CDP context', () => {
    const f = hive.fields.find((x: any) => x.name === 'metastoreUri');
    expect(f).toBeTruthy();
    expect(appliesToKind(f.appliesTo, 'CDP')).toBe(true);
    expect(appliesToKind(f.appliesTo, 'REMOTE')).toBe(true);
    expect(appliesToKind(f.appliesTo, 'EXTERNAL')).toBe(true);
  });

  it('every operator-providable Hive field is collected for CDP (no field left EXTERNAL-only)', () => {
    for (const f of hive.fields) {
      expect(appliesToKind(f.appliesTo, 'CDP')).toBe(true);
    }
  });

  it('managed contexts still resolve via managedResolver, not operator entry', () => {
    const f = hive.fields.find((x: any) => x.name === 'metastoreUri');
    expect(appliesToKind(f.appliesTo, 'MANAGED')).toBe(false); // not hand-entered
    expect(f.managedResolver).toBe('hive.metastoreUri');       // resolved from hive-site instead
  });
});
