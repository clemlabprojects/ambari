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

import React from 'react';
import { Checkbox, Form, Input } from 'antd';
import {
  HOST_KEY_LABEL, HOST_KEY_PLACEHOLDER, HOST_KEY_TOOLTIP, SSH_KEY_LABEL, SSH_KEY_PLACEHOLDER, SSH_KEY_TOOLTIP,
  TRUST_ON_FIRST_USE_LABEL, TRUST_ON_FIRST_USE_TOOLTIP, hostKeyProblem, isSshRepoUrl,
} from '../../utils/gitSsh';

/**
 * The SSH settings of a GitOps deploy, in the deploy wizard and the install dialog: the deploy key and, for a
 * repository reached over SSH, the Git server's host key, without which the server refuses to connect unless trust on
 * first use is chosen.
 */
export default function GitSshFields() {
  return (
    <>
      <Form.Item name={['git', 'sshKey']} label={SSH_KEY_LABEL} tooltip={SSH_KEY_TOOLTIP}>
        <Input.TextArea rows={3} placeholder={SSH_KEY_PLACEHOLDER} />
      </Form.Item>
      <Form.Item
        noStyle
        shouldUpdate={(prev, next) => prev?.git?.repoUrl !== next?.git?.repoUrl
          || prev?.git?.sshKnownHosts !== next?.git?.sshKnownHosts}
      >
        {({ getFieldValue }) => isSshRepoUrl(getFieldValue(['git', 'repoUrl'])) ? (
          <>
            <Form.Item
              name={['git', 'sshKnownHosts']}
              label={HOST_KEY_LABEL}
              tooltip={HOST_KEY_TOOLTIP}
              rules={[{
                validator: (_, value) => {
                  const problem = hostKeyProblem(value);
                  return problem ? Promise.reject(new Error(problem)) : Promise.resolve();
                },
              }]}
            >
              <Input.TextArea rows={2} placeholder={HOST_KEY_PLACEHOLDER} />
            </Form.Item>
            {/* Only without a host key: one given is always the one required. */}
            {!String(getFieldValue(['git', 'sshKnownHosts']) || '').trim() && (
              <Form.Item
                name={['git', 'trustHostKeyOnFirstUse']}
                valuePropName="checked"
                preserve={false}
                extra={TRUST_ON_FIRST_USE_TOOLTIP}
              >
                <Checkbox>{TRUST_ON_FIRST_USE_LABEL}</Checkbox>
              </Form.Item>
            )}
          </>
        ) : null}
      </Form.Item>
    </>
  );
}
