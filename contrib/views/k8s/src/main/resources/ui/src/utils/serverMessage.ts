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

/**
 * The sentence the server gave for a failed call. The view's API errors read "HTTP <code> – <body>" (or just the
 * body), the body being JSON with an `error` (or `message`) field; anything else is shown as it came.
 *
 * @param e        the error a call threw
 * @param fallback what to say when the error carries no text
 */
export const serverMessage = (e: unknown, fallback = 'The request failed.'): string => {
  const text = String((e as any)?.message ?? e ?? '').trim();
  const start = text.indexOf('{');
  if (start >= 0) {
    try {
      const body = JSON.parse(text.slice(start));
      const reason = body?.error || body?.message;
      if (typeof reason === 'string' && reason.trim()) return reason;
    } catch { /* not JSON: keep the text */ }
  }
  return text.replace(/^HTTP \d+ – /, '') || fallback;
};
