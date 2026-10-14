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

/**
 * What the server sends in place of a stored password or client secret: it never sends the secret back, only that it
 * is set (a `<field>Set` flag next to it). Sent back unchanged, or left blank, the stored secret is kept.
 */
export const MASKED_SECRET = '********';

/** The text a password field shows when a secret is stored for it. */
export const SECRET_SET_PLACEHOLDER = 'Set - leave blank to keep';

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
  //    A stored secret comes as the mask: the field starts blank (its placeholder says it is set), so the mask is
  //    never shown as if it were the value.
  const overlay = (src: any, base: string[] = []) => {
    Object.entries(src || {}).forEach(([k, v]) => {
      if (base.length === 0 && (k === 'mode' || k === 'extraProperties')) return;
      if (v && typeof v === 'object' && !Array.isArray(v)) overlay(v, [...base, k]);
      else setAt(fields, [...base, k], v === MASKED_SECRET ? '' : v);
    });
  };
  overlay(cfg);
  return fields;
}

function getAt(obj: any, path: string[]): any {
  return path.reduce((node, key) => (node == null ? undefined : node[key]), obj);
}

/** Whether the server stores a secret for a password field of a profile (its `<field>Set` flag). */
export function isSecretSet(cfg: any, fieldPath: string): boolean {
  const path = fieldPath.split('.').filter(Boolean);
  if (!path.length) return false;
  return getAt(cfg, [...path.slice(0, -1), `${path[path.length - 1]}Set`]) === true;
}

/**
 * The profile as the page keeps it once saved: a secret typed in is now stored (masked, flagged as set, so it is not
 * held in clear and sent again), a password field left blank keeps what the profile had.
 *
 * @param saved the profile as sent
 * @param previous the profile as the page had it before, undefined for a new one
 * @param secretFields the password fields of the schema, e.g. `ldap.bindPassword`
 */
export function withSecretFlags(saved: any, previous: any, secretFields: string[]): any {
  const next = JSON.parse(JSON.stringify(saved || {}));
  secretFields.forEach((fieldPath) => {
    const path = fieldPath.split('.').filter(Boolean);
    if (!path.length) return;
    const section = getAt(next, path.slice(0, -1));
    if (!section || typeof section !== 'object') return;
    const leaf = path[path.length - 1];
    const typed = section[leaf];
    if (typeof typed === 'string' && typed.trim() !== '' && typed !== MASKED_SECRET) {
      section[leaf] = MASKED_SECRET;
      section[`${leaf}Set`] = true;
    } else {
      const wasSet = isSecretSet(previous, fieldPath);
      section[leaf] = wasSet ? MASKED_SECRET : typed;
      section[`${leaf}Set`] = wasSet;
    }
  });
  return next;
}
