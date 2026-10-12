/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { ResourceCheck } from '../../types/ServiceTypes';
import { getAtStr } from './bindings';

/** The parts of a service form field these checks read (a group holds its fields in `fields`). */
export type FormFieldDef = {
  name?: string;
  label?: string;
  type?: string;
  defaultValue?: unknown;
  fields?: FormFieldDef[];
  resourceCheck?: ResourceCheck;
  omitWhenBlank?: boolean;
  excludeFromValues?: boolean;
  /** What the cluster needs for the field (or group) to be shown, e.g. `kubernetes` (see fieldCapabilityAvailable). */
  capability?: string;
  /** Still sent where `capability` hides it: the wizard fills it in, or the chart uses it on both platforms. */
  keepWhenHidden?: boolean;
  /** Value paths an upgrade fills the field from when it is blank (see seedFieldsFromValues). */
  seedFromValues?: string[];
};

/**
 * An explicit Java heap must stay below this share of the pod memory limit. Java also uses memory outside the heap
 * (code cache, metaspace, thread stacks, direct buffers, agents), and the kernel kills the container as soon as the
 * whole process goes over the limit, before Java can report an OutOfMemoryError.
 */
export const MAX_HEAP_SHARE = 0.85;

/**
 * The share of the memory limit the chart gives the heap when none is set, and the wizard when the chart is too old
 * to do it (see blankValues.ts); also the value error messages suggest.
 */
export const DEFAULT_HEAP_SHARE = 0.75;

const BINARY_SUFFIX: Record<string, number> = {
  Ki: 2 ** 10, Mi: 2 ** 20, Gi: 2 ** 30, Ti: 2 ** 40, Pi: 2 ** 50, Ei: 2 ** 60,
};
const DECIMAL_SUFFIX: Record<string, number> = { '': 1, k: 1e3, M: 1e6, G: 1e9, T: 1e12, P: 1e15, E: 1e18 };
// Divided rather than multiplied, so "1100m" compares equal to "1.1" instead of being off by a rounding error.
const FRACTION_SUFFIX: Record<string, number> = { n: 1e9, u: 1e6, m: 1e3 };
const QUANTITY = /^(\+?(?:\d+(?:\.\d*)?|\.\d+))(?:[eE]([+-]?\d+)|(Ki|Mi|Gi|Ti|Pi|Ei|n|u|m|k|M|G|T|P|E))?$/;

/**
 * A Kubernetes quantity ("8Gi", "512Mi", "8G", "2", "500m", "1e9") in base units: bytes for memory, cores for CPU.
 * Null when Kubernetes would not accept it.
 */
export function parseQuantity(raw: unknown): number | null {
  if (typeof raw === 'number') return Number.isFinite(raw) && raw >= 0 ? raw : null;
  if (typeof raw !== 'string') return null;
  const m = QUANTITY.exec(raw.trim());
  if (!m) return null;
  const n = Number(m[1]);
  if (m[2] !== undefined) {
    const exp = Number(m[2]);
    return exp < 0 ? n / 10 ** -exp : n * 10 ** exp;
  }
  const suffix = m[3] ?? '';
  if (suffix in FRACTION_SUFFIX) return n / FRACTION_SUFFIX[suffix];
  return n * (BINARY_SUFFIX[suffix] ?? DECIMAL_SUFFIX[suffix]);
}

const JAVA_SIZE = /^(\d+)([kKmMgGtT]?)$/;
const JAVA_UNIT: Record<string, number> = { '': 1, k: 2 ** 10, m: 2 ** 20, g: 2 ** 30, t: 2 ** 40 };

/**
 * A Java heap size the way -Xmx reads it ("6G", "6144m", "6442450944") in bytes. Java counts K, M, G and T in
 * powers of 1024 and takes no decimals and no Kubernetes suffix: null for anything it would refuse to start with.
 */
export function parseJavaSize(raw: unknown): number | null {
  if (raw == null) return null;
  const m = JAVA_SIZE.exec(String(raw).trim());
  if (!m) return null;
  const bytes = Number(m[1]) * JAVA_UNIT[m[2].toLowerCase()];
  return bytes > 0 ? bytes : null;
}

/** A byte count written as an operator would type a heap: whole gigabytes when exact, otherwise megabytes. */
export function formatJavaSize(bytes: number): string {
  const mib = Math.floor(bytes / 2 ** 20);
  return mib % 1024 === 0 ? `${mib / 1024}G` : `${mib}M`;
}

const isBlank = (v: unknown) => v == null || String(v).trim() === '';

const CHART_VERSION = /^v?(\d+(?:\.\d+)*)/;

/**
 * Whether chart `version` is `first` or later, comparing the numbers of "major.minor.patch" ("v1.43.15" and
 * "1.43.15-rc.1" count as 1.43.15). Null when `version` is not a chart version (blank, a range such as ">=1.43",
 * "latest"): which chart will be deployed is then unknown.
 */
export function chartVersionAtLeast(version: unknown, first: string): boolean | null {
  const actual = version == null ? null : CHART_VERSION.exec(String(version).trim());
  const wanted = CHART_VERSION.exec(first.trim());
  if (!actual || !wanted) return null;
  const a = actual[1].split('.').map(Number);
  const w = wanted[1].split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, w.length); i++) {
    if ((a[i] ?? 0) !== (w[i] ?? 0)) return (a[i] ?? 0) > (w[i] ?? 0);
  }
  return true;
}

/**
 * Why `value` fails its check against `limit`, or null when it passes. A blank value passes (a blank heap is sized
 * from the limit, by the chart or by the wizard for an older chart), and so does a value whose limit cannot be read:
 * there is nothing to compare it with.
 */
export function resourceCheckError(check: ResourceCheck, value: unknown, limit: unknown): string | null {
  if (isBlank(value)) return null;
  const shown = String(value).trim();
  const limitBase = isBlank(limit) ? null : parseQuantity(limit);
  const limitShown = isBlank(limit) ? '' : String(limit).trim();

  if (check.kind === 'javaHeap') {
    const heap = parseJavaSize(value);
    if (heap == null) {
      return `"${shown}" is not a Java heap size. Use a whole number followed by M or G, for example 6G or 6144M `
        + '(Java reads G as GiB: write 6G, not 6Gi).';
    }
    if (limitBase == null || limitBase <= 0 || heap < MAX_HEAP_SHARE * limitBase) return null;
    return `A ${shown} heap leaves too little of the ${limitShown} memory limit for the memory Java uses outside the `
      + `heap, and the pod would be killed under load. Keep the heap below ${MAX_HEAP_SHARE * 100}% of the limit, `
      + `ideally 70-80% (for example ${formatJavaSize(DEFAULT_HEAP_SHARE * limitBase)}), or leave it blank to use `
      + `${DEFAULT_HEAP_SHARE * 100}% of the limit.`;
  }

  if (check.kind === 'memoryRequest' || check.kind === 'cpuRequest') {
    const what = check.kind === 'cpuRequest' ? 'CPU' : 'memory';
    const request = parseQuantity(value);
    if (request == null) {
      const example = check.kind === 'cpuRequest' ? '2 or 500m' : '8Gi or 512Mi';
      return `"${shown}" is not a valid Kubernetes ${what} quantity, for example ${example}.`;
    }
    if (limitBase == null || request <= limitBase) return null;
    return `The ${what} request (${shown}) cannot be higher than the ${what} limit (${limitShown}): Kubernetes would `
      + 'refuse the pod. Set it equal to the limit, as by default, or lower.';
  }
  return null;
}

/**
 * Why an empty Java heap present in the values cannot be sent to the chart about to be deployed, or null when it can.
 * Only a chart from `blankSizedByChartFrom` on reads an empty heap as "size it from the memory limit"; an older one
 * renders a bare -Xmx and Java does not start. The wizard resolves blank heaps before the deploy (see blankValues.ts),
 * so this is the last guard on what is actually sent.
 */
function emptyHeapError(check: ResourceCheck, limit: unknown, chartVersion: string): string | null {
  if (check.kind !== 'javaHeap' || !check.blankSizedByChartFrom) return null;
  if (chartVersionAtLeast(chartVersion, check.blankSizedByChartFrom) === true) return null;
  const version = chartVersion.trim();
  const limitBase = isBlank(limit) ? null : parseQuantity(limit);
  const example = limitBase && limitBase > 0 ? formatJavaSize(DEFAULT_HEAP_SHARE * limitBase) : '6G';
  return `An empty Java heap size is only understood by chart ${check.blankSizedByChartFrom} and later, and `
    + `${version ? `this deploy uses chart ${version}` : 'this deploy does not name a chart version'}: with an older `
    + `chart, Java does not start. Enter a heap size (for example ${example}) or remove the key.`;
}

/**
 * Every resource-check failure of a service form, read from the values about to be deployed, each prefixed with
 * the field (and its group) so the operator knows which one to fix. With `chartVersion`, the chart version the deploy
 * asks for, an empty Java heap present in the values is also refused when that chart may not understand it.
 */
export function resourceCheckErrors(fields: FormFieldDef[] | undefined, values: unknown, chartVersion?: string): string[] {
  const errors: string[] = [];
  const walk = (list: FormFieldDef[], group?: string) => {
    for (const f of list || []) {
      if (f?.type === 'group' && Array.isArray(f.fields)) {
        walk(f.fields, f.label);
        continue;
      }
      const check = f?.resourceCheck;
      if (!check?.kind || !check.limitField || !f.name) continue;
      const value = getAtStr(values, f.name);
      const limit = getAtStr(values, check.limitField);
      // An absent key leaves the chart's own default in place; "" or null (`key:` in YAML) replaces it.
      const error = chartVersion !== undefined && value !== undefined && isBlank(value)
        ? emptyHeapError(check, limit, chartVersion)
        : resourceCheckError(check, value, limit);
      if (error) errors.push(`${group ? `${group} - ` : ''}${f.label}: ${error}`);
    }
  };
  walk(fields || []);
  return errors;
}
