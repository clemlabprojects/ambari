import type { BindingSpec, BindingTarget } from './types';

/* ==============================================================================================
   PATH & OBJECT HELPERS
   These functions handle dot-notation access (e.g. "server.port") and safe object mutation.
   Crucially, they support escaped dots (e.g. "node\.data-dir") which are common in Helm keys.
   ============================================================================================== */

/** * Splits a path string into parts, respecting escaped dots.
 * Example: "server.node\.data-dir" -> ["server", "node.data-dir"]
 */
export const pathToParts = (p: string) =>
    p.replace(/\\\./g, '__DOT__').split('.').map(s => s.replace(/__DOT__/g, '.'));

/** * Safely retrieves a value from a nested object using a dot-notation string.
 * Returns undefined if the path doesn't exist.
 */
export const getAtStr = (obj: any, path: string) =>
    pathToParts(path).reduce((o, k) => (o ? o[k] : undefined), obj);

/**
 * Basic string interpolation. Replaces ${key} with value from ctx.
 * Used primarily for simple string replacements.
 */
export const interpolateStr = (tpl: string, ctx: any) =>
    String(tpl).replace(/\$\{([^}]+)\}/g, (_m, p) => {
      const v = getAtStr(ctx, p.trim());
      return v == null ? '' : String(v);
    });

/**
 * Navigates to a path in an object, creating nested objects if they don't exist.
 * If the leaf node doesn't exist, initializes it with `init`.
 * Returns the leaf node.
 */
export const ensureAtStr = (obj: any, path: string, init: any) => {
  const parts = pathToParts(path);
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
    cur = cur[k];
  }
  const leaf = parts[parts.length - 1];
  if (cur[leaf] == null) cur[leaf] = typeof init === 'function' ? init() : init;
  return cur[leaf];
};

/**
 * Sets a value at a specific dot-notation path, creating intermediate objects as needed.
 */
export const setAtStr = (obj: any, path: string, value: any) => {
  const parts = pathToParts(path);
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
    cur = cur[k];
  }
  cur[parts[parts.length - 1]] = value;
};

/**
 * Helper to delete a nested key based on a dot-notation path.
 * Used to clean up "Transient" form fields (excludeFromValues) before final render.
 */
export const deleteAtStr = (obj: any, path: string) => {
  const parts = pathToParts(path);
  const leaf = parts.pop();
  if (!leaf) return;

  let cur = obj;
  // Navigate to the parent object
  for (const p of parts) {
    if (!cur || typeof cur !== 'object') return;
    cur = cur[p];
  }

  // Delete the leaf key from the parent
  if (cur && typeof cur === 'object') {
    delete cur[leaf];
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
export const getAt = (obj: any, path: string[]): any => path.reduce((o, k) => (o ? o[k] : undefined), obj);
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
    | { name: string; template: string };

/**
 * Builds a dictionary of variables (key-value pairs) based on charts.json definitions.
 * Process runs in two phases to allow variables to depend on form values or other variables.
 */
export const buildVarContext = (
    vars: VariableSpec[] | undefined,
    formVals: any,
    mounts: Record<string, any>
): Record<string, any> => {
  const ctx: Record<string, any> = {};

  // PHASE 1: Materialize direct values (Form fields and Mount paths)
  (vars || []).forEach(v => {
    if ('from' in v) {
      if (v.from.type === 'form') {
        setAtStr(ctx, v.name, getAtStr(formVals, v.from.field));
      } else if (v.from.type === 'mountPath') {
        const m = mounts?.[v.from.mountKey];
        const base = m?.mountPath || '/data';
        setAtStr(ctx, v.name, base + (v.from.suffix || ''));
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

  // Case: Direct Form Field copy
  if (t.from.type === 'form') {
    const v = getAtStr(formVals, t.from.field);
    return (v == null ? '' : String(v)) + (t.from.suffix || '');
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
      if ((t as any).skipIfVarEmpty) {
        const checkVar = (t as any).skipIfVarEmpty;
        const checkVal = varCtx?.[checkVar];
        if (!checkVal) continue;
      }

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
    if ((b as any).skipIfVarEmpty) {
      const checkVar = (b as any).skipIfVarEmpty;
      const checkVal = varCtx?.[checkVar];
      if (!checkVal) continue;
    }
    if (!Array.isArray(b.targets)) continue;

    for (const t of b.targets) {

      // -- CONDITIONAL LOGIC --
      // Skip this target if the referenced variable is empty
      if ((t as any).skipIfVarEmpty) {
        const checkVar = (t as any).skipIfVarEmpty;
        const checkVal = varCtx?.[checkVar];
        if (!checkVal) continue;
      }
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
