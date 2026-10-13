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

import {
  HOST_KEY_MAX_LENGTH, SSH_KEY_PLACEHOLDER, SSH_KEY_TOOLTIP, hostKeyProblem, isSshRepoUrl, savedRepoGitFields,
} from '../gitSsh';

const ED25519 = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl';

// The forms check the host key as the server reads it, and describe the SSH keys that work.
describe('gitSsh', () => {
  it('tells SSH repository URLs from HTTPS ones', () => {
    expect(isSshRepoUrl('git@gitlab.example.com:org/repo.git')).toBe(true);
    expect(isSshRepoUrl('ssh://git@gitea.example.com:2222/org/repo.git')).toBe(true);
    expect(isSshRepoUrl('https://gitlab.example.com/org/repo.git')).toBe(false);
    expect(isSshRepoUrl('https://user@gitlab.example.com/org/repo.git')).toBe(false);
    // Git also reaches these over SSH, and the server then requires the host key.
    expect(isSshRepoUrl('git.example.org:org/repo.git')).toBe(true);
    expect(isSshRepoUrl('git+ssh://git@git.example.org/org/repo.git')).toBe(true);
    expect(isSshRepoUrl('ssh+git://git.example.org:2222/org/repo.git')).toBe(true);
    expect(isSshRepoUrl('git://git.example.org/org/repo.git')).toBe(false);
    expect(isSshRepoUrl('file:///srv/git/repo.git')).toBe(false);
    expect(isSshRepoUrl('/srv/git/repo.git')).toBe(false);
    expect(isSshRepoUrl('C:/repos/repo.git')).toBe(false);
    expect(isSshRepoUrl(undefined)).toBe(false);
    expect(isSshRepoUrl('')).toBe(false);
  });

  it('takes known_hosts lines, ssh-keyscan output and bare public keys', () => {
    expect(hostKeyProblem(undefined)).toBeUndefined();
    expect(hostKeyProblem('')).toBeUndefined();
    expect(hostKeyProblem(`gitea.example.com ${ED25519}`)).toBeUndefined();
    expect(hostKeyProblem(`# gitea.example.com:2222 SSH-2.0-OpenSSH_9.7\n[gitea.example.com]:2222 ${ED25519}\n`))
      .toBeUndefined();
    expect(hostKeyProblem(`${ED25519} root@gitea`)).toBeUndefined();
    expect(hostKeyProblem(`|1|c2FsdA==|aGFzaA== ${ED25519}`)).toBeUndefined();
    // A host name that starts like a key type is still the host.
    expect(hostKeyProblem(`ssh-gateway.example.com ${ED25519}`)).toBeUndefined();
    expect(hostKeyProblem(`[ssh-gateway.example.com]:2222 ${ED25519}`)).toBeUndefined();
  });

  it('refuses what is not a host key, naming the line', () => {
    expect(hostKeyProblem('SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG')).toMatch(/^Line 1 is not a known_hosts line/);
    expect(hostKeyProblem(`gitea.example.com ${ED25519}\n\ngitea.example.com ssh-ed25519 not-base64!`))
      .toMatch(/^Line 3 /);
    expect(hostKeyProblem('-----BEGIN OPENSSH PRIVATE KEY-----')).toMatch(/not a known_hosts line/);
    // A revoked key must never become the trusted one, so markers are refused rather than skipped.
    expect(hostKeyProblem(`@revoked gitea.example.com ${ED25519}`))
      .toMatch(/^Line 1 starts with the marker @revoked/);
    expect(hostKeyProblem(`gitea.example.com ${ED25519}\n@cert-authority *.example.com ${ED25519}`))
      .toMatch(/^Line 2 starts with the marker @cert-authority/);
    expect(hostKeyProblem(`gitea.example.com ${ED25519}\n`.repeat(30)))
      .toContain(`At most ${HOST_KEY_MAX_LENGTH} characters`);
  });

  it('gives a selected saved repository its own host key, or none', () => {
    const withKey = { url: 'git@gitea.example.com:org/a.git', hostKey: `gitea.example.com ${ED25519}` };
    const withoutKey = { url: 'git@gitlab.example.com:org/b.git' };
    expect(savedRepoGitFields(withKey))
      .toEqual({ repoUrl: withKey.url, sshKnownHosts: withKey.hostKey });
    // Spread over the form after repository A, B must clear A's key rather than leave it to be sent for B.
    const afterA = { ...{ sshKey: 'k' }, ...savedRepoGitFields(withKey) };
    const afterB = { ...afterA, ...savedRepoGitFields(withoutKey) };
    expect(afterB).toEqual({ sshKey: 'k', repoUrl: withoutKey.url, sshKnownHosts: undefined });
    expect(savedRepoGitFields({ url: withoutKey.url, hostKey: '' }).sshKnownHosts).toBeUndefined();
  });

  it('describes the private key formats that work', () => {
    expect(SSH_KEY_PLACEHOLDER).toContain('BEGIN OPENSSH PRIVATE KEY');
    expect(SSH_KEY_TOOLTIP).toMatch(/Ed25519/);
    expect(SSH_KEY_TOOLTIP).toMatch(/without a passphrase/);
    expect(SSH_KEY_TOOLTIP).toMatch(/PEM/);
  });
});
