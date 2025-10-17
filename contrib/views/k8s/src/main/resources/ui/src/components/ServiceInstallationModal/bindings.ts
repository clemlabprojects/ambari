import type { BindingSpec, BindingTarget } from './types';

/** Escaped-dot aware path helpers (support "node\.data-dir") */
export const pathToParts = (p: string) => p.replace(/\\\./g, '__DOT__').split('.').map(s => s.replace(/__DOT__/g, '.'));
export const getAtStr = (obj: any, path: string) => pathToParts(path).reduce((o, k) => (o ? o[k] : undefined), obj);
export const  interpolateStr = (tpl: string, ctx: any) =>
    String(tpl).replace(/\$\{([^}]+)\}/g, (_m, p) => {
      const v = getAtStr(ctx, p.trim());
      return v == null ? '' : String(v);
    });
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
export const parentOfArrayPath = (p: string) => (p.endsWith('[]') ? p.slice(0, -2) : p);
export const pushUniqueBy = (arr: any[], key: (x: any) => string, entry: any) => {
  if (!arr.some(e => key(e) === key(entry))) arr.push(entry);
};

/** Helpers used by the form-field sync half (existing behavior) */
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

/** Small deep merge for sparse patches */
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

/** simple ${path} interpolator against a context object (supports dotted paths) */
export const interpolate = (tpl: string, ctx: Record<string, any>): string =>
  String(tpl).replace(/\$\{([^}]+)\}/g, (_m, p) => {
    const key = p.trim();
    const v = getAtStr(ctx, key);
    return v == null ? '' : String(v);
  });

/** VariableSpec runtime: build a variable context from charts.json "variables" */
export type VariableSpec =
  | { name: string; from: { type: 'form'; field: string } }
  | { name: string; from: { type: 'mountPath'; mountKey: string; suffix?: string } }
  | { name: string; template: string };

export const buildVarContext = (
  vars: VariableSpec[] | undefined,
  formVals: any,
  mounts: Record<string, any>
): Record<string, any> => {
  const ctx: Record<string, any> = {};

  // 1) First, materialize non-template vars into a nested object
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

  // 2) Then resolve template vars with fallback to form values + already computed vars
  (vars || []).forEach(v => {
    if ('template' in v) {
       // composed = formVals (fallback) + ctx (overrides)
       const composed: Record<string, any> = {};
        deepMerge(composed, formVals || {});
        deepMerge(composed, ctx);
        const val = interpolate(v.template, composed);
        setAtStr(ctx, v.name, val);
      }
    });

    return ctx;
};

export function valueFromTargetSource(
  t: BindingTarget,
  mounts: Record<string, any>,
  formVals: any,
  varCtx?: Record<string, any>
): any {
  if (!t.from) return t.value;
  if (t.from.type === 'mountPath') {
    const m = mounts?.[t.from.mountKey];
    const base = m?.mountPath || '/data';
    return base + (t.from.suffix || '');
  }
  if (t.from.type === 'form') {
    const v = getAtStr(formVals, t.from.field);
    return (v == null ? '' : String(v)) + (t.from.suffix || '');
  }
  // NEW: variable indirection
  if ((t.from as any).type === 'var') {
    const name = (t.from as any).name;
    return varCtx ? varCtx[name] : undefined;
  }
  // NEW: free-form template (can return JSON object if template renders JSON)
  if ((t.from as any).type === 'template') {
    const tpl = (t.from as any).template;
    // composed = formVals (fallback) + varCtx (overrides)
    const composed: Record<string, any> = {};
    deepMerge(composed, formVals || {});
    if (varCtx) deepMerge(composed, varCtx);

    // support object/array templates and recursively interpolate string leaves
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

    // if user passed a string, keep old behavior (try JSON.parse, else return string)
    if (typeof tpl === 'string') {
      const out = interpolate(tpl, composed);
      try { return JSON.parse(out); } catch { return out; }
    }
    // object/array path
    return interpDeep(tpl);
  }
  return undefined;
}

/** Build a sparse "targets patch" object for current mounts + bindings */
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
      if (t.path.endsWith('[]')) {
        const arr = ensureAtStr(patch, parentOfArrayPath(t.path), []);
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
          const val = valueFrom(t);
          if (val !== undefined) pushUniqueBy(arr, (x: any) => JSON.stringify(x), val);
        }
        continue;
      }

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
    if (!Array.isArray(b.targets)) continue;
    for (const t of b.targets) {
      if (t.path.endsWith('[]')) {
        const arr = ensureAtStr(mergedValues, parentOfArrayPath(t.path), []);
        if (t.kind === 'volume') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const type = String(m?.type || 'emptyDir').toLowerCase();
          const name = key;
          const entry = type === 'pvc' ? { name, persistentVolumeClaim: { claimName: `${releaseName}-${key}` } } : { name, emptyDir: {} };
          pushUniqueBy(arr, (x: any) => String(x?.name), entry);
        } else if (t.kind === 'mount') {
          const key = t.mountKey!;
          const m = mounts?.[key] || {};
          const entry = { name: key, mountPath: m?.mountPath || '/data' };
          pushUniqueBy(arr, (x: any) => `${x?.name}@@${x?.mountPath}`, entry);
        } else {
          const val = valueFromTargetSource(t, mounts, formValues, varCtx);
          if (val !== undefined) pushUniqueBy(arr, (x: any) => JSON.stringify(x), val);
        }
        continue;
      }

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
