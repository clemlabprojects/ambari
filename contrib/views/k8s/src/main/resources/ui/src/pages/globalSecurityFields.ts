// Pure helper for GlobalSecurityPage: compute the flat/nested field map to load into the form for
// a security profile, WITHOUT inheriting the previously selected profile.
//
// Security-profile fields are nested (schema name "ldap.adUrl" -> ['ldap','adUrl']) and antd v5
// setFieldsValue DEEP-MERGES (it cannot clear a leaf by passing an empty object); with preserve
// defaulting to true, the values of the AD/LDAP/OIDC sections hidden by the current mode survive.
// Without clearing, "+ New profile" or switching profiles inherits the previous profile's fields —
// most visibly the ldap.ad* fields for an AD profile, and dangerously a bind DN / bind password.
//
// This builds a full field object that (1) blanks every known schema field, then (2) overlays the
// target profile's actual values. `mode` and `extraProperties` are handled by the caller.

export interface SchemaProp {
  name?: string;
  type?: string;
}

function setAt(obj: any, path: string[], value: any): void {
  let node = obj;
  for (let i = 0; i < path.length - 1; i++) {
    node[path[i]] = node[path[i]] || {};
    node = node[path[i]];
  }
  node[path[path.length - 1]] = value;
}

export function buildProfileFields(schemaProperties: SchemaProp[] | undefined, cfg: any): any {
  const fields: any = {};
  // 1) Blank every known schema field. schemaProperties is populated for every user-driven switch
  //    / "+ New profile" (the only cases where leakage happens); it may be empty on the very first
  //    load, which is fine because there is no prior profile to clear then.
  (schemaProperties || []).forEach((p) => {
    const name = String(p?.name || '');
    if (!name || name === 'mode') return;
    const path = name.split('.').filter(Boolean);
    if (path.length) setAt(fields, path, p?.type === 'boolean' ? false : '');
  });
  // 2) Overlay the target profile's actual values, schema-independently, so the initial load (where
  //    the schema state is not applied yet) still populates every field. `mode` and the
  //    `extraProperties` object are set by the caller, so skip them at the top level.
  const overlay = (src: any, base: string[] = []) => {
    Object.entries(src || {}).forEach(([k, v]) => {
      if (base.length === 0 && (k === 'mode' || k === 'extraProperties')) return;
      if (v && typeof v === 'object' && !Array.isArray(v)) overlay(v, [...base, k]);
      else setAt(fields, [...base, k], v);
    });
  };
  overlay(cfg);
  return fields;
}
