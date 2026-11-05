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

import { loader } from '@monaco-editor/react';

/**
 * Where the code editor (Monaco) loads from: its own copy, shipped with the view under monaco/vs next to assets/
 * (vite.config.ts copies it from the pinned monaco-editor package), not the jsdelivr CDN the loader uses by default,
 * which a cluster without Internet access, or a proxy that holds the request, never answers. Still loaded on demand,
 * when an editor first shows.
 *
 * @param moduleUrl the URL of a module of the built UI (in assets/)
 */
export function configureLocalMonaco(moduleUrl: string): void {
  loader.config({ paths: { vs: monacoPath(moduleUrl) } });
}

/** monaco/vs beside the assets/ folder the module was loaded from, without a trailing slash. */
export function monacoPath(moduleUrl: string): string {
  return new URL('../monaco/vs', moduleUrl).href;
}
