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

package org.apache.ambari.view.k8s.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Files only the account running Ambari can read: files {@code 0600} in directories {@code 0700}, whatever the
 * umask (a stock Ambari server sets none, so a plain write gives {@code 0644}). For what the view keeps on disk that
 * may hold a secret, such as the parameters of its operations and the chart values cached for them.
 *
 * <p>On a file system without POSIX permissions the owner-only read and write flags of {@link File} are used
 * instead.
 */
public final class OwnerOnlyFiles {

    private static final Logger LOG = LoggerFactory.getLogger(OwnerOnlyFiles.class);

    static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");

    private OwnerOnlyFiles() {
    }

    /**
     * Creates the directory when missing (with its parents) and makes it owner-only. Its parents keep their
     * permissions. A directory whose permissions cannot be changed (it belongs to another account) is used as it
     * is, with a warning: the files written in it are owner-only anyway.
     *
     * @param dir the directory
     * @return the directory
     * @throws IOException when it cannot be created
     */
    public static Path directory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            restrict(dir);
        } catch (IOException e) {
            LOG.warn("Could not restrict {} to the account running Ambari: {}", dir, e.toString());
        }
        return dir;
    }

    /**
     * Writes a file only its owner can read, replacing the file if it exists. The text is written to a temporary
     * file of the same directory first, then moved in place: a reader never sees half a file, and a file an earlier
     * version wrote with wider permissions is replaced, not reused.
     *
     * @param file    the file; its directory must exist
     * @param content the text, written as UTF-8
     * @throws IOException when it cannot be written
     */
    public static void write(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        try {
            restrict(temp);
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Makes an existing file {@code 0600}, or a directory {@code 0700}.
     *
     * @param path the file or directory
     * @throws IOException when its permissions cannot be changed, e.g. it belongs to another account
     */
    public static void restrict(Path path) throws IOException {
        boolean dir = Files.isDirectory(path);
        try {
            Files.setPosixFilePermissions(path, dir ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
        } catch (UnsupportedOperationException notPosix) {
            File f = path.toFile();
            boolean restricted = f.setReadable(false, false) & f.setReadable(true, true)
                    & f.setWritable(false, false) & f.setWritable(true, true)
                    & f.setExecutable(false, false) & (!dir || f.setExecutable(true, true));
            if (!restricted) {
                LOG.debug("Could not restrict the permissions of {} to its owner", path);
            }
        }
    }

    /**
     * Whether only the owner can read or write the file or directory. Always true on a file system without POSIX
     * permissions, where they cannot be read.
     */
    public static boolean isOwnerOnly(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return (Files.isDirectory(path) ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS).containsAll(permissions);
        } catch (UnsupportedOperationException notPosix) {
            return true;
        }
    }
}
