import type { BindingSpec, BindingTarget } from './types';

/* ==============================================================================================
   PATH & OBJECT HELPERS
   These functions handle dot-notation access (e.g. "server.port") and safe object mutation.
   Crucially, they support escaped dots (e.g. "node\.data-dir") which are common in Helm keys.
   ============================================================================================== */

/** Splits a path string into parts, respecting escaped dots AND Helm-style
 *  list indices (e.g. `foo[0].bar` -> ['foo', 0, 'bar']).
 *  Numbers in the returned array signal "index into a list" for the writers.
 *  Without honoring [N], a binding path like `customCAs[0].secret` writes a
 *  literal map key `customCAs[0]` instead of a list element, producing
 *  structurally wrong YAML that the chart silently ignores. */
export const pathToParts = (p: string): (string | number)[] => {
  const raw = p.replace(/\\\./g, '__DOT__').split('.').map(s => s.replace(/__DOT__/g, '.'));
  const out: (string | number)[] = [];
  for (const seg of raw) {
    const m = /^([^\[\]]+)((?:\[\d+\])*)$/.exec(seg);
    if (!m) { out.push(seg); continue; }
    out.push(m[1]);
    const idxBlock = m[2];
    if (idxBlock) {
      for (const num of idxBlock.matchAll(/\[(\d+)\]/g)) out.push(Number(num[1]));
    }
  }
  return out;
};

/** Safely retrieves a value from a nested object/list using a dot-notation
 *  string with optional [N] indices. Returns undefined if the path doesn't exist. */
export const getAtStr = (obj: any, path: string) =>
    pathToParts(path).reduce((o: any, k) => (o == null ? undefined : o[k as any]), obj);

/**
 * Basic string interpolation. Replaces ${key} with value from ctx.
 * Used primarily for simple string replacements.
 */
export const interpolateStr = (tpl: string, ctx: any) =>
    String(tpl).replace(/\$\{([^}]+)\}/g, (_m, p) => {
      const v = getAtStr(ctx, p.trim());
      return v == null ? '' : String(v);
    });

/** Ensure each segment of `parts` exists as a Map (for string keys) or List
 *  (for numeric keys) on `cur`, and return the parent of the final segment.
 *  Used by both ensureAtStr and setAtStr so the list-vs-map decision happens
 *  in exactly one place. */
const walkAndEnsure = (cur: any, parts: (string | number)[]): any => {
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    const nextIsIndex = typeof parts[i + 1] === 'number';
    if (typeof k === 'number') {
      while ((cur as any[]).length <= k) (cur as any[]).push(undefined);
      if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = nextIsIndex ? [] : {};
    } else {
      if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = nextIsIndex ? [] : {};
    }
    cur = cur[k];
  }
  return cur;
};

/**
 * Navigates to a path in an object, creating nested objects/lists if they don't exist.
 * If the leaf node doesn't exist, initializes it with `init`. Returns the leaf node.
 */
export const ensureAtStr = (obj: any, path: string, init: any) => {
  const parts = pathToParts(path);
  const parent = walkAndEnsure(obj, parts);
  const leaf = parts[parts.length - 1];
  if (parent[leaf as any] == null) parent[leaf as any] = typeof init === 'function' ? init() : init;
  return parent[leaf as any];
};

/**
 * Sets a value at a specific dot-notation path (with optional [N] indices),
 * creating intermediate objects/lists as needed.
 */
export const setAtStr = (obj: any, path: string, value: any) => {
  const parts = pathToParts(path);
  const parent = walkAndEnsure(obj, parts);
  parent[parts[parts.length - 1] as any] = value;
};

/**
 * Helper to delete a nested key based on a dot-notation path.
 * Used to clean up "Transient" form fields (excludeFromValues) before final render.
 */
export const deleteAtStr = (obj: any, path: string) => {
  const parts = pathToParts(path);
  const leaf = parts.pop();
  // Index 0 is a valid leaf, so check for undefined explicitly (truthy-check would skip).
  if (leaf === undefined) return;

  let cur = obj;
  for (const p of parts) {
    if (!cur || typeof cur !== 'object') return;
    cur = cur[p as any];
  }

  if (cur && typeof cur === 'object') {
    delete cur[leaf as any];
  }
};

/** Removes '[]' suffix from a path string (used for array targets) */
export const parentOfArrayPath = (p: string) => (p.endsWith('[]') ? p.slice(0, -2) : p);

/** Pushes an item to an array only if a key function result doesn't already exist */
export const pushUniqueBy = (arr: any[], key: (x: any) => string, entry: any) => {
  if (!arr.some(e => key(e) === key(entry))) arr.push(entry);
};

/* ==============================================================================================
   ARRAY PATH HELPERS (Legacy/Form Sync)
   Similar to above, but works with pre-split string arrays (['a', 'b']) instead of strings.
   ============================================================================================== */

export const toPath = (p: string) => pathToParts(p);
export const getAt = (obj: any, path: (string | number)[]): any => path.reduce((o, k) => (o ? o[k] : undefined), obj);
export const setAt = (obj: any, path: string[], value: any) => {
  let o = obj;
  for (let i = 0; i < path.length - 1; i++) {
    o[path[i]] ??= {};
    o = o[path[i]];
  }
  o[path[path.length - 1]] = value;
};

/* ==============================================================================================
   MERGE & TEMPLATING LOGIC
   ============================================================================================== */

/** * Deep merge for sparse patches.
 * Merges source properties into destination, preserving existing data where possible.
 */
export const deepMerge = (dst: any, src: any) => {
  for (const k of Object.keys(src)) {
    const sv = src[k];
    if (sv && typeof sv === 'object' && !Array.isArray(sv)) {
      dst[k] ??= {};
      deepMerge(dst[k], sv);
    } else {
      dst[k] = sv;
    }
  }
};

/** * Advanced interpolator: Replaces ${path} in a string with values from a context object.
 * Handles missing values gracefully by returning empty string.
 */
export const interpolate = (tpl: string, ctx: Record<string, any>): string =>
    String(tpl).replace(/\$\{([^}]+)\}/g, (_m, p) => {
      const key = p.trim();
      const v = getAtStr(ctx, key);
      return v == null ? '' : String(v);
    });

/* ==============================================================================================
   VARIABLE CONTEXT ENGINE
   This section builds the "Intermediate State" used for bindings.
   It resolves variables defined in charts.json based on Form Inputs or Mounts.
   ============================================================================================== */

export type VariableSpec =
    | { name: string; from: { type: 'form'; field: string } }
    | { name: string; from: { type: 'mountPath'; mountKey: string; suffix?: string } }
    // `context` reads a value the KDPS engine resolved for the selected Platform Context, keyed
    // `<capability>.<field>` (e.g. `hive.metastoreUri`, `ranger.rangerUrl`, `kerberos.realm`).
    // This is the generic primitive that lets ANY service consume the platform context: the
    // resolved value flows into a variable and then into chart values via the normal bindings.
    // `overrideFields` (in precedence order) let operator-supplied form fields take priority over
    // the resolved value — e.g. an explicit URI or a discovered-service picker overrides the
    // platform default. The resolved value is used only when every override field is blank.
    // `enabledField` gates the whole variable on a boolean toggle: when that form field is not
    // truthy the variable is left unset, so a `skipIfVarEmpty` binding (e.g. the Hive catalog) is
    // skipped entirely — i.e. "use the context's Hive only when the operator turned it on".
    | { name: string; from: { type: 'context'; key: string; overrideFields?: string[]; enabledField?: string } }
    // `equals` derives a boolean-ish gate var ("true" / "") by comparing another (already-resolved)
    // variable against a constant. Used to branch a binding on a resolved capability value that has
    // no boolean form field — e.g. Superset picks hive:// vs impala:// from `hive.transportMode`.
    // `negate` flips the result, so `{var:'hiveTransportMode', value:'binary', negate:true}` is
    // "true for anything that is NOT binary" (http / all / unresolved) — the safe default branch.
    // Declare an `equals` var AFTER the variable it references (phase-1 resolution is in array order).
    | { name: string; from: { type: 'equals'; var: string; value: string; negate?: boolean; caseInsensitive?: boolean } }
    | { name: string; template: string };

/**
 * Builds a dictionary of variables (key-value pairs) based on charts.json definitions.
 * Process runs in two phases to allow variables to depend on form values or other variables.
 *
 * @param resolvedFields the selected Platform Context's resolved `<capability>.<field>` values
 *        (from `GET /contexts/{id}/resolved`); consumed by `from.type === 'context'` variables.
 */
export const buildVarContext = (
    vars: VariableSpec[] | undefined,
    formVals: any,
    mounts: Record<string, any>,
    resolvedFields: Record<string, string> = {},
    platform?: string
): Record<string, any> => {
  const ctx: Record<string, any> = {};
  const nonBlank = (x: any) => x != null && String(x).trim() !== '';

  // Platform gate seeds (from the /cluster/capabilities probe). These let service.json
  // branch bindings on the detected target platform via `skipIfVarEmpty` and `${...}`
  // interpolation — the same primitive the engine already uses for context/toggle gating.
  //
  //   isOpenShift  — "true" ONLY when the probe confirmed OpenShift (fail-CLOSED, so an
  //                  OpenShift-only binding never fires on vanilla K8s or while caps load).
  //   isVanillaK8s — "true" UNLESS the probe confirmed OpenShift (fail-OPEN / defaults to
  //                  the vanilla path when the platform is still unknown), so gating the
  //                  default bindings on it never makes them vanish on a probe miss.
  //
  // e.g. Trino KEDA: the default Prometheus triggers are gated on isVanillaK8s and an
  // OpenShift variant (Thanos tenancy + bearer auth) on isOpenShift.
  ctx.platform = platform || '';
  ctx.isOpenShift = platform === 'openshift' ? 'true' : '';
  ctx.isVanillaK8s = platform === 'openshift' ? '' : 'true';

  // PHASE 1: Materialize direct values (Form fields, Mount paths, Platform Context values)
  (vars || []).forEach(v => {
    if ('from' in v) {
      if (v.from.type === 'form') {
        setAtStr(ctx, v.name, getAtStr(formVals, v.from.field));
      } else if (v.from.type === 'mountPath') {
        const m = mounts?.[v.from.mountKey];
        const base = m?.mountPath || '/data';
        setAtStr(ctx, v.name, base + (v.from.suffix || ''));
      } else if (v.from.type === 'context') {
        // Gated by an enable toggle: leave the variable unset when the toggle is off.
        if (v.from.enabledField) {
          const en = getAtStr(formVals, v.from.enabledField);
          if (en !== true && en !== 'true') return;
        }
        // Operator override fields win (in declared order); otherwise the engine-resolved value.
        let val: any;
        for (const of of (v.from.overrideFields || [])) {
          const o = getAtStr(formVals, of);
          if (nonBlank(o)) { val = o; break; }
        }
        if (!nonBlank(val)) val = resolvedFields?.[v.from.key];
        if (nonBlank(val)) setAtStr(ctx, v.name, val);
      } else if ((v.from as any).type === 'equals') {
        // Compares an already-resolved var (declared earlier) against a constant → "true" / "".
        const cur = getAtStr(ctx, (v.from as any).var);
        const target = (v.from as any).value;
        const ci = (v.from as any).caseInsensitive;
        let match = ci
            ? String(cur ?? '').toLowerCase() === String(target ?? '').toLowerCase()
            : String(cur ?? '') === String(target ?? '');
        if ((v.from as any).negate) match = !match;
        setAtStr(ctx, v.name, match ? 'true' : '');
      }
    }
  });

  // PHASE 2: Resolve templates.
  // We merge formVals + ctx so templates can reference both user input and previously calculated vars.
  (vars || []).forEach(v => {
    if ('template' in v) {
      const composed: Record<string, any> = {};
      deepMerge(composed, formVals || {});
      deepMerge(composed, ctx); // ctx overrides formVals if names collide
      const val = interpolate(v.template, composed);
      setAtStr(ctx, v.name, val);
    }
  });

  return ctx;
};

/**
 * Determines the final value for a specific Binding Target.
 * It can pull from:
 * 1. Mount Paths
 * 2. Form Fields
 * 3. Calculated Variables (varCtx)
 * 4. Complex Templates (Strings or JSON Objects)
 */
export function valueFromTargetSource(
    t: BindingTarget,
    mounts: Record<string, any>,
    formVals: any,
    varCtx?: Record<string, any>
): any {
  if (!t.from) return t.value; // Static value

  // Case: Mount Path (e.g. /opt/data)
  if (t.from.type === 'mountPath') {
    const m = mounts?.[t.from.mountKey];
    const base = m?.mountPath || '/data';
    return base + (t.from.suffix || '');
  }

  // Case: Direct Form Field copy.
  //
  // Preserve the form field's original type when no suffix is set, so a
  // number-typed form field (e.g. `replicas`) doesn't get stringified to
  // "1" and then rejected by helm values.schema.json with
  // "Expected: integer, given: string". Same for booleans, arrays, objects.
  //
  // When a suffix is set the binding is explicitly building a string
  // (e.g. "http://" + host + ":80"), so coerce to string in that path only.
  //
  // null/undefined form values become undefined so the binding is skipped
  // (rather than writing "" to a non-string-typed helm path — same schema
  // failure mode otherwise).
  if (t.from.type === 'form') {
    const v = getAtStr(formVals, t.from.field);
    if (t.from.suffix) {
      return (v == null ? '' : String(v)) + t.from.suffix;
    }
    return v == null ? undefined : v;
  }

  // Case: Variable Indirection (look up in varCtx)
  if ((t.from as any).type === 'var') {
    const name = (t.from as any).name;
    return varCtx ? varCtx[name] : undefined;
  }

  // Case: Template (Complex logic)
  if ((t.from as any).type === 'template') {
    const tpl = (t.from as any).template;

    // Build a context for interpolation (Form + Variables)
    const composed: Record<string, any> = {};
    deepMerge(composed, formVals || {});
    if (varCtx) deepMerge(composed, varCtx);

    // Recursive helper to interpolate strings inside objects/arrays
    const interpDeep = (node: any): any => {
      if (node == null) return node;
      if (typeof node === 'string') return interpolate(node, composed);
      if (Array.isArray(node)) return node.map(interpDeep);
      if (typeof node === 'object') {
        const out: any = Array.isArray(node) ? [] : {};
        for (const k of Object.keys(node)) out[k] = interpDeep(node[k]);
        return out;
      }
      return node;
    };

    // If template is a string, try to parse it as JSON (e.g. "true", "123"), else return string
    if (typeof tpl === 'string') {
      const out = interpolate(tpl, composed);
      try { return JSON.parse(out); } catch { return out; }
    }
    // If template is already an object/array, interpolate recursively
    return interpDeep(tpl);
  }
  return undefined;
}

/* ==============================================================================================
   TARGET PATCH GENERATION
   These functions apply the logic defined in charts.json "bindings" to create the final YAML.
   ============================================================================================== */

/** `skipIfVarEmpty` gate: skip when the referenced var is empty. Accepts a single var name or an
 *  array (skip if ANY is empty — i.e. all must be non-empty for the target/binding to apply). */
const skipForVars = (checkVar: any, varCtx?: Record<string, any>): boolean => {
  if (!checkVar) return false;
  const names = Array.isArray(checkVar) ? checkVar : [checkVar];
  return names.some((n: string) => !varCtx?.[n]);
};

/** * Creates a "Patch" object. This is a sparse object containing ONLY the changes
 * dictated by the bindings. It does not modify the source object.
 */
export const makeTargetsPatch = (
    bindingList: BindingSpec[],
    mounts: any,
    formVals: any,
    releaseName?: string,
    varCtx?: Record<string, any>
) => {
  const patch: any = {};

  const valueFrom = (t: BindingTarget): any => {
    return valueFromTargetSource(t, mounts, formVals, varCtx);
  };

  for (const b of bindingList || []) {
    if (!Array.isArray((b as any).targets)) continue;

    for (const t of (b as any).targets) {

      // -- CONDITIONAL LOGIC --
      // If "skipIfVarEmpty" is set, we check the variable context.
      // If the variable is empty/false, we skip generating this target entirely.
      if (skipForVars((t as any).skipIfVarEmpty, varCtx)) continue;

      // Handle Arrays (e.g. ingress.hosts[], volumes[])
      if (t.path.endsWith('[]')) {
        const arr = ensureAtStr(patch, parentOfArrayPath(t.path), []);

        // Special handling for Kubernetes Volumes/Mounts
        if (t.kind === 'volume') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const type = String(m?.type || 'emptyDir').toLowerCase();
          const name = key;
          const entry =
              type === 'pvc'
                  ? { name, persistentVolumeClaim: { claimName: `${releaseName ?? ''}-${key}`.replace(/^-/, '') } }
                  : { name, emptyDir: {} };
          pushUniqueBy(arr, (x: any) => String(x?.name), entry);
        } else if (t.kind === 'mount') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const entry = { name: key, mountPath: m?.mountPath || '/data' };
          pushUniqueBy(arr, (x: any) => `${x?.name}@@${x?.mountPath}`, entry);
        } else {
          // Generic Value in Array (e.g. ingress hosts)
          const val = valueFrom(t);
          if (val !== undefined) {
            if (Array.isArray(val)) {
              arr.push(...val);
            } else {
              arr.push(val);
            }
          }
        }
        continue;
      }

      // Handle Scalar/Object Targets
      const val = valueFrom(t);
      if (val === undefined) continue;

      if (t.op === 'merge') {
        const obj = ensureAtStr(patch, t.path, {});
        Object.assign(obj, val);
      } else {
        setAtStr(patch, t.path, val);
      }
    }
  }
  return patch;
};

/**
 * Applies binding targets directly to a `mergedValues` object (In-Place Mutation).
 * Used for generating the final values for Preview and Deployment.
 */
export function applyBindingTargets(
    mergedValues: any,
    bindingList: BindingSpec[] | undefined,
    mounts: Record<string, any>,
    formValues: any,
    releaseName: string,
    varCtx?: Record<string, any>
) {
  if (!bindingList) return;
  for (const b of bindingList) {
    // Binding-level skip
    if (skipForVars((b as any).skipIfVarEmpty, varCtx)) continue;
    if (!Array.isArray(b.targets)) continue;

    for (const t of b.targets) {

      // -- CONDITIONAL LOGIC --
      // Skip this target if the referenced variable is empty
      if (skipForVars((t as any).skipIfVarEmpty, varCtx)) continue;
      // Skip if value already exists and skipIfExists is true
      if ((t as any).skipIfExists) {
        const existing = getAt(mergedValues, toPath(t.path.replace(/\[\]$/, '')));
        if (existing !== undefined && existing !== null && existing !== '') continue;
      }

      // Handle Arrays
      if (t.path.endsWith('[]')) {
        const arr = ensureAtStr(mergedValues, parentOfArrayPath(t.path), []);

        if (t.kind === 'volume') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const type = String(m?.type || 'emptyDir').toLowerCase();
          const name = key;
          const entry =
              type === 'pvc'
                  ? { name, persistentVolumeClaim: { claimName: `${releaseName}-${key}` } }
                  : { name, emptyDir: {} };
          pushUniqueBy(arr, (x: any) => String(x?.name), entry);
        } else if (t.kind === 'mount') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const entry = { name: key, mountPath: m?.mountPath || '/data' };
          pushUniqueBy(arr, (x: any) => `${x?.name}@@${x?.mountPath}`, entry);
        } else {
          const val = valueFromTargetSource(t, mounts, formValues, varCtx);
          if (val !== undefined) {
            if (Array.isArray(val)) {
              arr.push(...val);
            } else {
              arr.push(val);
            }
          }
        }
        continue;
      }

      // Handle Scalar/Object Targets
      const val = valueFromTargetSource(t, mounts, formValues, varCtx);
      if (val === undefined) continue;

      if (t.op === 'merge') {
        const obj = ensureAtStr(mergedValues, t.path, {});
        Object.assign(obj, val);
      } else {
        setAtStr(mergedValues, t.path, val);
      }
    }
  }
}
