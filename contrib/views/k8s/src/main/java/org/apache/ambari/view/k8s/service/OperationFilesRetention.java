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

package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.utils.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deletes what an operation leaves in the view's working directory once nothing needs it any more: the files holding
 * the parameters of the operation and of its steps ({@link CommandParamsStore}) and the chart values cached for it
 * ({@link ViewConfigurationService#writeCacheFile}). Both carry the secrets of the deploy.
 * <ul>
 *   <li>An operation whose steps all succeeded loses them as soon as it ends ({@link #purgeIfSucceeded}).</li>
 *   <li>A failed or canceled operation keeps them, since Resume runs its steps again from them, until it ended more
 *       than {@value #DEFAULT_RETENTION_DAYS} days ago or {@value #DEFAULT_RETENTION_COUNT} more recent operations
 *       ended that way too (Ambari properties {@value #RETENTION_DAYS_PROPERTY} and
 *       {@value #RETENTION_COUNT_PROPERTY}).</li>
 *   <li>When the view starts ({@link #sweep}), these rules are applied to every operation, the files no operation
 *       refers to are deleted, and, once, the files an earlier version wrote in clear are encrypted and made
 *       owner-only.</li>
 * </ul>
 * The operation stays in the history; without its parameters it can no longer be resumed.
 */
final class OperationFilesRetention {

    private static final Logger LOG = LoggerFactory.getLogger(OperationFilesRetention.class);

    static final String RETENTION_DAYS_PROPERTY = "k8s.view.operations.retention.days";
    static final String RETENTION_COUNT_PROPERTY = "k8s.view.operations.retention.count";
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int DEFAULT_RETENTION_COUNT = 50;
    /** A file no operation refers to may belong to one being planned at that moment: it is left alone this long. */
    static final Duration UNREFERENCED_GRACE = Duration.ofHours(1);
    /** Written in the working directory once the files of earlier versions are encrypted. */
    static final String ENCRYPTED_MARKER = ".operation-files-encrypted";

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /** What a sweep did, for its log line and for tests. */
    record SweepResult(int operationsPurged, int filesDeleted, int filesEncrypted) {
    }

    private final ViewContext ctx;
    private final DataStore dataStore;

    OperationFilesRetention(ViewContext ctx, DataStore dataStore) {
        this.ctx = ctx;
        this.dataStore = dataStore;
    }

    /**
     * Deletes the files of an operation that ended with all its steps succeeded. Those of an operation with a failed
     * or canceled step are kept: it can be resumed.
     *
     * @param rootId the operation
     * @return whether its files were deleted
     */
    boolean purgeIfSucceeded(String rootId) {
        try {
            CommandEntity root = dataStore.find(CommandEntity.class, rootId);
            if (root == null) {
                return false;
            }
            List<CommandEntity> tree = tree(root, null);
            if (!allSucceeded(tree, null)) {
                return false;
            }
            int deleted = purge(tree);
            LOG.info("Operation {} succeeded: deleted its {} parameter and values file(s)", rootId, deleted);
            return true;
        } catch (Exception e) {
            LOG.warn("Could not delete the files of operation {}: {}", rootId, e.toString());
            return false;
        }
    }

    /**
     * Applies the retention rules to every operation of the view instance, deletes the files no operation refers to
     * and, the first time, encrypts the files an earlier version wrote in clear.
     *
     * @param now the current time
     * @return what was done
     */
    SweepResult sweep(Instant now) {
        Collection<CommandEntity> all;
        Collection<CommandStatusEntity> allStatuses;
        try {
            all = dataStore.findAll(CommandEntity.class, null);
            allStatuses = dataStore.findAll(CommandStatusEntity.class, null);
        } catch (Exception e) {
            LOG.warn("Could not read the operations to apply their file retention: {}", e.toString());
            return new SweepResult(0, 0, 0);
        }
        Map<String, CommandEntity> byId = new HashMap<>();
        Set<String> children = new HashSet<>();
        for (CommandEntity cmd : all) {
            byId.put(cmd.getId(), cmd);
            children.addAll(childIds(cmd));
        }
        Map<String, CommandStatusEntity> statuses = new HashMap<>();
        for (CommandStatusEntity st : allStatuses) {
            statuses.put(st.getId(), st);
        }

        int purged = 0;
        int deleted = 0;
        List<List<CommandEntity>> kept = new ArrayList<>();
        List<List<CommandEntity>> ended = new ArrayList<>();
        for (CommandEntity cmd : all) {
            if (children.contains(cmd.getId())) {
                continue;
            }
            List<CommandEntity> tree = tree(cmd, byId);
            CommandStatusEntity rootStatus = statuses.get(cmd.getCommandStatusId());
            if (allSucceeded(tree, statuses)) {
                if (hasFiles(tree)) {
                    deleted += purge(tree);
                    purged++;
                }
            } else if (rootStatus != null && isEnded(rootStatus.getState())) {
                ended.add(tree);
            } else {
                kept.add(tree);
            }
        }

        // Failed and canceled operations, most recent first: kept within the age and count limits.
        ended.sort(Comparator.comparing((List<CommandEntity> tree) -> endedAt(tree.get(0), statuses)).reversed());
        Instant oldest = now.minus(Duration.ofDays(retentionDays()));
        int maxCount = retentionCount();
        for (int i = 0; i < ended.size(); i++) {
            List<CommandEntity> tree = ended.get(i);
            if (i >= maxCount || endedAt(tree.get(0), statuses).isBefore(oldest)) {
                if (hasFiles(tree)) {
                    deleted += purge(tree);
                    purged++;
                }
            } else {
                kept.add(tree);
            }
        }

        int encrypted = 0;
        Path marker = Paths.get(ViewConfigurationService.workingDirectory(ctx), ENCRYPTED_MARKER);
        boolean encryptEarlierFiles = !Files.exists(marker);
        Set<Path> referenced = new HashSet<>();
        for (List<CommandEntity> tree : kept) {
            for (CommandEntity cmd : tree) {
                encrypted += keep(cmd, encryptEarlierFiles, referenced);
            }
        }
        deleted += deleteUnreferenced(CommandParamsStore.directory(ctx), referenced, now);
        deleted += deleteUnreferenced(ViewConfigurationService.cacheDirectory(ctx), referenced, now);
        // A working directory that does not exist yet holds no file of an earlier version.
        if (encryptEarlierFiles && Files.isDirectory(marker.getParent())) {
            try {
                OwnerOnlyFiles.write(marker, "Files of operations encrypted on " + now + "\n");
            } catch (IOException e) {
                LOG.warn("Could not record that the operation files are encrypted ({}): {}", marker, e.toString());
            }
        }
        SweepResult result = new SweepResult(purged, deleted, encrypted);
        LOG.info("Operation files: {} operation(s) purged, {} file(s) deleted, {} file(s) encrypted",
                result.operationsPurged(), result.filesDeleted(), result.filesEncrypted());
        return result;
    }

    /**
     * Keeps the files of a command that may still run: encrypts what an earlier version wrote in clear (when
     * {@code encrypt}), makes the files owner-only, and records them as referenced.
     *
     * @return how many files were encrypted
     */
    private int keep(CommandEntity cmd, boolean encrypt, Set<Path> referenced) {
        String stored = cmd.getParamsJson();
        if (stored == null || CommandParamsStore.REMOVED.equals(stored)) {
            return 0;
        }
        int encrypted = 0;
        try {
            String params = CommandParamsStore.read(stored);
            if (encrypt && params != null && !alreadySealed(stored)) {
                String rewritten = CommandParamsStore.persist(ctx, cmd.getId(), params);
                if (!rewritten.equals(stored)) {
                    // Kept elsewhere before (inline, or another working directory): only the new copy remains.
                    CommandParamsStore.deleteFile(cmd.getId(), stored);
                    cmd.setParamsJson(rewritten);
                    dataStore.store(cmd);
                }
                stored = rewritten;
                encrypted++;
            }
            Path file = CommandParamsStore.fileOf(stored);
            if (file != null && Files.exists(file)) {
                OwnerOnlyFiles.restrict(file);
                referenced.add(file.toAbsolutePath().normalize());
            }
            Path values = cacheFile(valuesPathOf(params));
            if (values != null && Files.exists(values)) {
                if (encrypt) {
                    String text = Files.readString(values, StandardCharsets.UTF_8);
                    if (!EncryptedEnvelope.isSealed(text)) {
                        OwnerOnlyFiles.write(values, EncryptedEnvelope.sealToJson(text));
                        encrypted++;
                    }
                }
                OwnerOnlyFiles.restrict(values);
                referenced.add(values);
            }
        } catch (Exception e) {
            LOG.warn("Could not encrypt or restrict the files of command {}: {}", cmd.getId(), e.toString());
        }
        return encrypted;
    }

    /** Whether the stored parameters are encrypted already, inline or in their file. */
    private static boolean alreadySealed(String stored) throws IOException {
        Path file = CommandParamsStore.fileOf(stored);
        if (file == null) {
            return EncryptedEnvelope.isSealed(stored);
        }
        return !Files.exists(file) || EncryptedEnvelope.isSealed(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Deletes the parameter files and the cached values of the commands, and marks their parameters as removed.
     *
     * @return how many files were deleted
     */
    private int purge(List<CommandEntity> tree) {
        int deleted = 0;
        Set<Path> values = new LinkedHashSet<>();
        for (CommandEntity cmd : tree) {
            String stored = cmd.getParamsJson();
            if (stored == null || CommandParamsStore.REMOVED.equals(stored)) {
                continue;
            }
            Path file = CommandParamsStore.fileOf(stored);
            if (file == null || Files.exists(file)) {
                Path cached = cacheFile(valuesPathOf(CommandParamsStore.read(stored)));
                if (cached != null) {
                    values.add(cached);
                }
            }
            if (CommandParamsStore.deleteFile(cmd.getId(), stored)) {
                deleted++;
            }
            // Ambari's DataStore keeps a property set to null, so the parameters are replaced, not cleared.
            cmd.setParamsJson(CommandParamsStore.REMOVED);
            try {
                dataStore.store(cmd);
            } catch (Exception e) {
                LOG.warn("Could not record that the parameters of command {} were removed: {}", cmd.getId(), e.toString());
            }
        }
        for (Path cached : values) {
            try {
                if (Files.deleteIfExists(cached)) {
                    deleted++;
                }
            } catch (IOException e) {
                LOG.warn("Could not delete the cached values {}: {}", cached, e.toString());
            }
        }
        return deleted;
    }

    /** Whether some command of the tree still has parameters to delete. */
    private static boolean hasFiles(List<CommandEntity> tree) {
        return tree.stream().anyMatch(cmd -> cmd.getParamsJson() != null
                && !CommandParamsStore.REMOVED.equals(cmd.getParamsJson()));
    }

    /**
     * Deletes the files of the directory that no kept command refers to and that were last written before the grace
     * period: those of purged or deleted operations, of a deploy refused after its values were cached, and
     * temporary files a crash left behind.
     *
     * @return how many files were deleted
     */
    private static int deleteUnreferenced(Path dir, Set<Path> referenced, Instant now) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        Instant before = now.minus(UNREFERENCED_GRACE);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!Files.isRegularFile(file) || referenced.contains(normalized)
                        || !Files.getLastModifiedTime(file).toInstant().isBefore(before)) {
                    continue;
                }
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
            OwnerOnlyFiles.restrict(dir);
        } catch (IOException e) {
            LOG.warn("Could not clean up {}: {}", dir, e.toString());
        }
        return deleted;
    }

    /** The cached values file a command's parameters refer to, when it is in this instance's cache. */
    private Path cacheFile(String valuesPath) {
        if (valuesPath == null || valuesPath.isBlank()) {
            return null;
        }
        Path cache = ViewConfigurationService.cacheDirectory(ctx).toAbsolutePath().normalize();
        Path file = Paths.get(valuesPath).toAbsolutePath().normalize();
        return file.startsWith(cache) ? file : null;
    }

    private static String valuesPathOf(String paramsJson) {
        if (paramsJson == null || !paramsJson.contains("\"valuesPath\"")) {
            return null;
        }
        try {
            Map<String, Object> params = GSON.fromJson(paramsJson, MAP_TYPE);
            Object path = params == null ? null : params.get("valuesPath");
            return path instanceof String s ? s : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The command and its steps, at every level. */
    private List<CommandEntity> tree(CommandEntity root, Map<String, CommandEntity> byId) {
        List<CommandEntity> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<CommandEntity> todo = new ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            CommandEntity cmd = todo.poll();
            if (!seen.add(cmd.getId())) {
                continue;
            }
            out.add(cmd);
            for (String id : childIds(cmd)) {
                CommandEntity child = byId != null ? byId.get(id) : find(id);
                if (child != null) {
                    todo.add(child);
                }
            }
        }
        return out;
    }

    private boolean allSucceeded(List<CommandEntity> tree, Map<String, CommandStatusEntity> statuses) {
        for (CommandEntity cmd : tree) {
            CommandStatusEntity st = statuses != null ? statuses.get(cmd.getCommandStatusId()) : status(cmd);
            if (st == null || !CommandState.SUCCEEDED.name().equals(st.getState())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEnded(String state) {
        return CommandState.SUCCEEDED.name().equals(state) || CommandState.FAILED.name().equals(state)
                || CommandState.CANCELED.name().equals(state);
    }

    /** When the operation last changed; one whose date cannot be read counts as just ended. */
    private static Instant endedAt(CommandEntity root, Map<String, CommandStatusEntity> statuses) {
        CommandStatusEntity st = statuses.get(root.getCommandStatusId());
        String at = st == null ? null : (st.getUpdatedAt() != null ? st.getUpdatedAt() : st.getCreatedAt());
        try {
            return at == null ? Instant.now() : Instant.parse(at);
        } catch (RuntimeException unreadable) {
            return Instant.now();
        }
    }

    private static List<String> childIds(CommandEntity cmd) {
        String json = cmd.getChildListJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = GSON.fromJson(json, LIST_TYPE);
            return ids == null ? List.of() : ids;
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    private CommandEntity find(String id) {
        try {
            return id == null || id.isBlank() ? null : dataStore.find(CommandEntity.class, id);
        } catch (Exception e) {
            return null;
        }
    }

    private CommandStatusEntity status(CommandEntity cmd) {
        try {
            return cmd.getCommandStatusId() == null ? null
                    : dataStore.find(CommandStatusEntity.class, cmd.getCommandStatusId());
        } catch (Exception e) {
            return null;
        }
    }

    private int retentionDays() {
        return positiveProperty(RETENTION_DAYS_PROPERTY, DEFAULT_RETENTION_DAYS);
    }

    private int retentionCount() {
        return positiveProperty(RETENTION_COUNT_PROPERTY, DEFAULT_RETENTION_COUNT);
    }

    private int positiveProperty(String name, int defaultValue) {
        try {
            String value = ctx.getAmbariProperty(name);
            int parsed = value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (RuntimeException unreadable) {
            LOG.warn("Ignoring the Ambari property {}: not a number of days or operations", name);
            return defaultValue;
        }
    }
}
