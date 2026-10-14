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
import { deleteAtStr, getAtStr, setAtStr } from './bindings';
import { DEFAULT_HEAP_SHARE, chartVersionAtLeast, formatJavaSize, parseJavaSize, parseQuantity } from './resourceChecks';
import type { FormFieldDef } from './resourceChecks';

/**
 * The Java heap the wizard sends in place of a blank one, or null when the chart can be left to size it.
 *
 * A chart from `resourceCheck.blankSizedByChartFrom` on sizes a blank heap from the memory limit itself. An older
 * chart (an Upgrade / Config of a release still on it, or an older Version override) falls back to its own fixed
 * heap, which can be as large as the memory limit and gets the pods killed under load: the wizard then sends the
 * share of the limit the newer chart would have used. Nothing is filled without a limit to size it from.
 */
function heapForOlderChart(field: FormFieldDef, values: unknown, chartVersion: unknown): string | null {
  const check = field.resourceCheck;
  if (check?.kind !== 'javaHeap' || !check.blankSizedByChartFrom || !check.limitField) return null;
  if (chartVersionAtLeast(chartVersion, check.blankSizedByChartFrom) === true) return null;
  const limit = parseQuantity(getAtStr(values, check.limitField));
  if (limit == null || limit <= 0) return null;
  const heap = formatJavaSize(DEFAULT_HEAP_SHARE * limit);
  return parseJavaSize(heap) == null ? null : heap;
}

/**
 * Applies to `values` (in place) what a blank field means for the chart about to be deployed (`chartVersion`, the
 * version the deploy will ask for):
 * - an `omitWhenBlank` field left blank is removed, so the chart's own default applies;
 * - a blank Java heap the chart in use cannot size from the memory limit is filled with 75% of that limit (see
 *   `heapForOlderChart`).
 *
 * Sending the empty string instead would only work with charts that understand it. An empty Trino heap size, for
 * instance, means "size the heap from the memory limit" for a recent chart, while an older one renders a bare -Xmx
 * that stops Java from starting.
 */
export function applyBlankValues(fields: FormFieldDef[] | undefined, values: unknown, chartVersion?: unknown): void {
  for (const f of fields || []) {
    if (f?.type === 'group' && Array.isArray(f.fields)) {
      applyBlankValues(f.fields, values, chartVersion);
      continue;
    }
    if (!f?.name) continue;
    const v = getAtStr(values, f.name);
    if (v != null && String(v).trim() !== '') continue;
    const heap = heapForOlderChart(f, values, chartVersion);
    if (heap) setAtStr(values, f.name, heap);
    else if (f.omitWhenBlank) deleteAtStr(values, f.name);
  }
}
