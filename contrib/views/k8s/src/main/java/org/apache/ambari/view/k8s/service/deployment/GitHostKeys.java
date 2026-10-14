/*
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
package org.apache.ambari.view.k8s.service.deployment;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The SSH host keys a {@link GitClient} accepts from its Git server: the known_hosts lines configured for the
 * repository, and nothing else. A server that presents another key is refused, and the refusal names the key it
 * presented ({@link #rejection}) so the operator can check its fingerprint with the Git server's administrator and
 * configure it.
 *
 * <p>With no host key configured, the connection is refused too, unless trust on first use was chosen: the first key
 * the server presents is then accepted and kept ({@link #captured}), for the caller to record and enforce from then on.
 * There is never a mode that accepts whatever key is presented.
 */
public final class GitHostKeys implements HostKeyRepository {

    /**
     * Longest host key configuration taken, comments and blank lines left out: it is recorded with the release, whose
     * record has little room. Enough for an RSA 4096, an ECDSA and an Ed25519 key of one server.
     */
    public static final int MAX_LENGTH = 2000;

    /** Key types of known_hosts lines, which a line without a host starts with. */
    private static final List<String> KEY_TYPE_PREFIXES = List.of("ssh-", "ecdsa-", "sk-");

    /** The configured host keys. */
    private final HostKeyRepository pinned;
    private final boolean trustOnFirstUse;
    private final boolean anyPinned;
    private final Set<String> pinnedTypes;

    /** The key the server presented last, and how it compared with the configured ones. */
    private HostKey presented;
    private int lastResult = OK;
    /** The key accepted on first use, when no host key is configured and trust on first use was chosen. */
    private HostKey captured;

    /**
     * @param knownHosts      known_hosts lines ({@code host type key}); a line without a host ({@code type key}) is
     *                        for {@code host} on {@code port}. {@code null} or blank when none is configured.
     * @param host            the Git server
     * @param port            its SSH port, 22 when not positive
     * @param trustOnFirstUse whether the first key the server presents is accepted when no host key is configured
     * @throws IllegalArgumentException when a line is not a host key
     */
    public GitHostKeys(String knownHosts, String host, int port, boolean trustOnFirstUse) {
        this.trustOnFirstUse = trustOnFirstUse;
        String lines = normalise(knownHosts, host, port);
        JSch jsch = new JSch();
        try {
            jsch.setKnownHosts(new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)));
        } catch (JSchException e) {
            throw new IllegalArgumentException("The Git server host key could not be read: " + e.getMessage(), e);
        }
        this.pinned = jsch.getHostKeyRepository();
        HostKey[] keys = pinned.getHostKey();
        this.anyPinned = keys != null && keys.length > 0;
        this.pinnedTypes = new LinkedHashSet<>();
        if (keys != null) {
            for (HostKey key : keys) {
                pinnedTypes.add(key.getType());
            }
        }
    }

    /**
     * The host pattern of a Git server in known_hosts: the host alone on port 22, {@code [host]:port} otherwise, as
     * OpenSSH writes it.
     */
    static String hostPattern(String host, int port) {
        String name = host == null ? "" : host;
        return port <= 0 || port == 22 ? name : "[" + name + "]:" + port;
    }

    /**
     * Checks configured known_hosts lines and gives a host pattern to the lines that have none.
     *
     * @return the lines, one per key; empty when none is configured
     * @throws IllegalArgumentException naming the line that is not a host key
     */
    static String normalise(String knownHosts, String host, int port) {
        if (knownHosts == null || knownHosts.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int number = 0;
        for (String raw : knownHosts.split("\\r?\\n")) {
            number++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            // JSch reads a marked line as a plain host key: a key marked @revoked would be trusted, the opposite of
            // what the line says, and an @cert-authority key would look accepted yet never match the server.
            if (fields[0].startsWith("@")) {
                throw new IllegalArgumentException("Line " + number + " of the Git server host key starts with the"
                        + " marker " + fields[0] + ". Markers such as @revoked or @cert-authority are not supported:"
                        + " give only the Git server's own host keys, and leave out any revoked key.");
            }
            // A host name may itself start like a key type (ssh-git.example.org), so a line only lacks a host when
            // its key type is followed by the key.
            boolean hostless = startsWithKeyType(fields[0]) && fields.length > 1 && isBase64(fields[1]);
            int typeAt = hostless ? 0 : 1;
            if (fields.length < typeAt + 2 || !startsWithKeyType(fields[typeAt]) || !isBase64(fields[typeAt + 1])) {
                throw new IllegalArgumentException("Line " + number + " of the Git server host key is not a known_hosts"
                        + " line. Give lines such as 'git.example.org ssh-ed25519 AAAAC3Nza...', as printed by"
                        + " 'ssh-keyscan git.example.org', or the content of the server's /etc/ssh/ssh_host_*_key.pub.");
            }
            if (typeAt == 0) {
                out.append(hostPattern(host, port)).append(' ');
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Refuses known_hosts lines a {@link GitClient} could not use, before anything is sent to the Git server.
     *
     * @param knownHosts the lines; {@code null} or blank passes
     * @throws IllegalArgumentException naming the line that is not a host key
     */
    public static void check(String knownHosts) {
        String kept = compact(knownHosts);
        if (kept != null && kept.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The Git server host key is too long (" + kept.length() + " characters,"
                    + " at most " + MAX_LENGTH + "): give only the keys of the Git server, one line per key type.");
        }
        normalise(knownHosts, "git.example.org", 22);
    }

    /**
     * Known_hosts lines as they are recorded: trimmed, without comments and blank lines.
     *
     * @return the lines, or {@code null} when there are none
     */
    public static String compact(String knownHosts) {
        if (knownHosts == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String raw : knownHosts.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.append(out.length() == 0 ? "" : "\n").append(line);
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static boolean startsWithKeyType(String field) {
        return KEY_TYPE_PREFIXES.stream().anyMatch(field::startsWith);
    }

    private static boolean isBase64(String field) {
        try {
            return Base64.getDecoder().decode(field).length > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether at least one host key is configured. */
    public boolean anyPinned() {
        return anyPinned;
    }

    /**
     * The server's host key algorithms in the order a session should offer them: those of the configured keys (or of
     * the key accepted on first use) first, so the server signs with a key that can be checked rather than with
     * another of its keys, which would be refused as unknown. An RSA key is checked through its SHA-2 signatures.
     *
     * @param current the algorithms the session offers, comma separated; {@code null} when unknown
     * @return the algorithms to offer; {@code current} when there is nothing to put first
     */
    public synchronized String preferredAlgorithms(String current) {
        Set<String> known = new LinkedHashSet<>(pinnedTypes);
        if (captured != null) {
            known.add(captured.getType());
        }
        if (known.isEmpty() || current == null || current.isBlank()) {
            return current;
        }
        List<String> offered = List.of(current.split(","));
        Set<String> ordered = new LinkedHashSet<>();
        for (String type : known) {
            List<String> algorithms = "ssh-rsa".equals(type)
                    ? List.of("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") : List.of(type);
            algorithms.stream().filter(offered::contains).forEach(ordered::add);
        }
        ordered.addAll(offered);
        return String.join(",", ordered);
    }

    @Override
    public synchronized int check(String host, byte[] key) {
        HostKey offered;
        try {
            offered = new HostKey(host, key);
        } catch (JSchException e) {
            presented = null;
            lastResult = NOT_INCLUDED;
            return NOT_INCLUDED;
        }
        presented = offered;
        int result = pinned.check(host, key);
        if (result != OK && !anyPinned && trustOnFirstUse) {
            if (captured == null) {
                captured = offered;
                result = OK;
            } else {
                result = sameKey(captured, offered) ? OK : CHANGED;
            }
        }
        lastResult = result;
        return result;
    }

    /** Forgets the key the server presented last, before a new connection. */
    synchronized void forgetLastCheck() {
        presented = null;
        lastResult = OK;
    }

    private static boolean sameKey(HostKey a, HostKey b) {
        return a.getType().equals(b.getType()) && a.getKey().equals(b.getKey());
    }

    /** Keys are only added from the configuration: never from a session. */
    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        // Not called with StrictHostKeyChecking=yes; a key is never learnt silently.
    }

    @Override
    public void remove(String host, String type) {
        // The configuration is not changed from a session.
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        // The configuration is not changed from a session.
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return null;
    }

    @Override
    public HostKey[] getHostKey() {
        return pinned.getHostKey();
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return pinned.getHostKey(host, type);
    }

    /**
     * The key accepted on first use, as a known_hosts line to record for the repository, or {@code null} when none
     * was: a host key was configured, trust on first use was not chosen, or the server was not reached.
     */
    public synchronized String captured() {
        return captured == null ? null : knownHostsLine(captured);
    }

    /**
     * Why the last connection was refused because of the server's host key, for the operator: the key the server
     * presented, its fingerprint and the known_hosts line to configure once it is checked.
     *
     * @param server the Git server as the operator knows it, e.g. {@code git.example.org:2222}
     * @return the message, or {@code null} when the host key was not the reason
     */
    public synchronized String rejection(String server) {
        if (lastResult == OK || presented == null) {
            return null;
        }
        String key = presented.getType() + " key " + fingerprint(presented);
        String line = knownHostsLine(presented);
        if (lastResult == CHANGED) {
            return "The SSH host key of the Git server " + server + " does not match the configured host key, so KDPS"
                    + " did not connect: another server may be answering for it. The server presented the " + key
                    + ". If the Git server's host key was replaced on purpose, check this fingerprint with its"
                    + " administrator, then set the Git server host key to: " + line;
        }
        if (!anyPinned) {
            return "No SSH host key is configured for the Git server " + server + ", so KDPS did not connect: it only"
                    + " connects to a server whose host key it can check. The server presented the " + key + ". Check"
                    + " this fingerprint with the Git server's administrator (ssh-keygen -lf on the server's"
                    + " /etc/ssh/ssh_host_*_key.pub), then give this line as the Git server host key: " + line;
        }
        return "The SSH host key the Git server " + server + " presented is not one of the configured host keys, so"
                + " KDPS did not connect. The server presented the " + key + ". If it is the Git server's key, check"
                + " this fingerprint with its administrator, then add this line to the Git server host key: " + line;
    }

    /** A host key as a known_hosts line: host pattern, type and key. */
    static String knownHostsLine(HostKey key) {
        return key.getHost() + " " + key.getType() + " " + key.getKey();
    }

    /** The SHA-256 fingerprint of a host key, as {@code ssh-keygen -l} prints it. */
    static String fingerprint(HostKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key.getKey()));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
