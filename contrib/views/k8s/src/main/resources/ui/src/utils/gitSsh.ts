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

/*
 * GitOps over SSH, as the deploy forms and the Git repositories page explain it. KDPS pushes with the deploy key given
 * and only connects to a Git server presenting a host key configured for the repository; without one the deploy is
 * refused, and its message shows the key the server presented. The server applies the same rules.
 */

export const SSH_KEY_LABEL = 'SSH private key';

export const SSH_KEY_PLACEHOLDER =
  '-----BEGIN OPENSSH PRIVATE KEY-----\n(Ed25519, ECDSA or RSA, without a passphrase)\n-----END OPENSSH PRIVATE KEY-----';

export const SSH_KEY_TOOLTIP =
  'The deploy key KDPS pushes with over SSH, without a passphrase: Ed25519 (recommended), ECDSA or RSA, in OpenSSH'
  + ' format as ssh-keygen writes it (ssh-keygen -t ed25519 -N "" -f deploy-key) or in PEM format. Its public key is'
  + ' added to the repository as a deploy key with write access. KDPS keeps it for this release only.';

export const HOST_KEY_LABEL = 'Git server host key (known_hosts line)';

export const HOST_KEY_PLACEHOLDER = 'git.example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...';

export const HOST_KEY_TOOLTIP =
  'Over SSH, KDPS only connects to a Git server presenting this key, so no other server can answer in its place.'
  + ' Paste the output of ssh-keyscan [-p port] host after checking its fingerprint with the server\'s administrator,'
  + ' or the server\'s /etc/ssh/ssh_host_ed25519_key.pub. Left empty, the deploy is refused and its message shows the'
  + ' key the server presented, to check and paste here. Recorded for the release once the deploy reached Git.';

export const TRUST_ON_FIRST_USE_LABEL = 'No host key: trust the key the Git server presents on this deploy';

export const TRUST_ON_FIRST_USE_TOOLTIP =
  'Only when no host key is given or recorded for the release. The key the Git server presents to this deploy is'
  + ' accepted without being checked, then recorded and required for every later operation of the release.'
  + ' Checking the fingerprint and pasting the host key is safer.';

/** Longest host key configuration the server takes, comments and blank lines left out. */
export const HOST_KEY_MAX_LENGTH = 2000;

const KEY_TYPE = /^(ssh-|ecdsa-|sk-)/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/**
 * Whether a Git repository URL is reached over SSH, read as Git reads it when it picks a transport (and as the server
 * does): ssh://, git+ssh:// or ssh+git://, or the scp-like [user@]host:path. A one-letter host is a Windows drive.
 */
export function isSshRepoUrl(url?: string): boolean {
  const value = url?.trim() || '';
  if (/^(ssh|git\+ssh|ssh\+git):\/\/[^/]/.test(value)) return true;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(value) || /^file:/i.test(value)) return false;
  return /^(?:[^\s/@]+@)?[^\s/:@\\]{2,}:[^\s]/.test(value);
}

/**
 * The git options a saved repository sets when it is selected in a deploy form. The host key is always replaced, by
 * nothing when the repository has none, so the key of a previously selected server is never sent for this one.
 */
export function savedRepoGitFields(repo: { url: string; hostKey?: string }): { repoUrl: string; sshKnownHosts?: string } {
  return { repoUrl: repo.url, sshKnownHosts: repo.hostKey || undefined };
}

/**
 * Why host key lines cannot be used, or undefined: each line other than a comment must be a known_hosts line,
 * `host type key` (or `type key` for the repository's own host), as the server reads it.
 */
export function hostKeyProblem(text?: string): string | undefined {
  const lines = (text || '').split(/\r?\n/).map(line => line.trim());
  const keys = lines.filter(line => line && !line.startsWith('#'));
  if (keys.join('\n').length > HOST_KEY_MAX_LENGTH) {
    return `At most ${HOST_KEY_MAX_LENGTH} characters: give only the keys of the Git server, one line per key type.`;
  }
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i] || lines[i].startsWith('#')) continue;
    const fields = lines[i].split(/\s+/);
    // The server refuses marked lines: SSH would read a key marked @revoked as a trusted one.
    if (fields[0].startsWith('@')) {
      return `Line ${i + 1} starts with the marker ${fields[0]}. Markers such as @revoked or @cert-authority are not`
        + ' supported: give only the Git server\'s own host keys, and leave out any revoked key.';
    }
    // A host name may itself start like a key type (ssh-git.example.com), so a line only lacks a host when its key
    // type is followed by the key.
    const hostless = KEY_TYPE.test(fields[0]) && fields.length > 1 && BASE64.test(fields[1]);
    const typeAt = hostless ? 0 : 1;
    if (fields.length < typeAt + 2 || !KEY_TYPE.test(fields[typeAt]) || !BASE64.test(fields[typeAt + 1])) {
      return `Line ${i + 1} is not a known_hosts line such as "git.example.com ssh-ed25519 AAAAC3Nza...".`;
    }
  }
  return undefined;
}
