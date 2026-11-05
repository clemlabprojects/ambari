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

import { monacoPath } from '../monacoLocal';

describe('monacoLocal', () => {
  it('loads the editor from the copy shipped beside the assets, wherever Ambari serves the view', () => {
    expect(monacoPath('https://ambari.example.com:8442/views/K8S-VIEW/1.0.0.10/K8S_VIEW_INSTANCE/assets/index-abc.js'))
      .toBe('https://ambari.example.com:8442/views/K8S-VIEW/1.0.0.10/K8S_VIEW_INSTANCE/monaco/vs');
  });

  it('never points at a CDN', () => {
    expect(monacoPath('http://localhost:5173/assets/main.js')).not.toContain('jsdelivr');
  });
});
