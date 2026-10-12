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
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.utils.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the parameters of an operation and of its steps are kept, from the moment the operation is planned until its
 * steps have run, or are resumed: a file per command in the {@code params} directory of the view's working directory,
 * which the command refers to as {@code @@file:<path>}.
 *
 * <p>Those parameters carry secrets: the security profile's passwords applied to the chart, the passwords and keys
 * typed in the wizard that a step needs, the operator's Ambari session. So the file is encrypted with Ambari's master
 * key ({@link EncryptedEnvelope}) and only the account running Ambari can read it ({@link OwnerOnlyFiles}); the
 * secrets of the wizard's form that no step reads are not kept at all ({@link #withoutUnreadSecrets}).
 */
public final class CommandParamsStore {

    private static final Logger LOG = LoggerFactory.getLogger(CommandParamsStore.class);

    /** How a command refers to the file holding its parameters. */
    public static final String FILE_REFERENCE = "@@file:";
    static final String PARAMS_DIRECTORY = "params";
    static final String FILE_SUFFIX = "-params.json";
    /**
     * What a command keeps once its parameters are deleted ({@link OperationFilesRetention}): Ambari's DataStore
     * does not clear a property set to null.
     */
    static final String REMOVED = "{\"_removed\":true}";

    /** Keeps numbers as they are written: a parameter rewritten without a secret must not turn 600 into 600.0. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private CommandParamsStore() {
    }

    /** Whether a command's stored parameters are a reference to a file ({@link #persist}). */
    public static boolean isFileReference(String stored) {
        return stored != null && stored.startsWith(FILE_REFERENCE);
    }

    /** Whether parameters as read ({@link #read}) are those of a command whose parameters were deleted. */
    public static boolean wasRemoved(String paramsJson) {
        return REMOVED.equals(paramsJson);
    }

    /** The directory of the parameter files of a view instance. */
    static Path directory(ViewContext ctx) {
        return Paths.get(ViewConfigurationService.workingDirectory(ctx), PARAMS_DIRECTORY);
    }

    /**
     * Keeps a command's parameters: without the secrets of the wizard's form that no step reads, encrypted, in the
     * command's file ({@code 0600} in a {@code 0700} directory), which is replaced if it exists.
     *
     * @param ctx        the view instance, whose working directory holds the file
     * @param commandId  the command
     * @param paramsJson its parameters, as JSON (or as an earlier call of this method left them inline)
     * @return what the command keeps: the reference to the file, or the encrypted parameters themselves when the
     *         file cannot be written
     */
    public static String persist(ViewContext ctx, String commandId, String paramsJson) {
        String sealed = EncryptedEnvelope.sealToJson(withoutUnreadSecrets(EncryptedEnvelope.openIfSealed(paramsJson)));
        try {
            Path file = OwnerOnlyFiles.directory(directory(ctx)).resolve(commandId + FILE_SUFFIX);
            OwnerOnlyFiles.write(file, sealed);
            LOG.info("Persisting params for command {} to file {}", commandId, file);
            return FILE_REFERENCE + file;
        } catch (Exception ioEx) {
            // Still encrypted: kept in the view's database instead of a file.
            LOG.warn("Could not externalize paramsJson for {}: {}", commandId, ioEx.toString());
            return sealed;
        }
    }

    /**
     * A command's parameters as JSON: read from its file when it refers to one, decrypted when encrypted, as they
     * are when written by a version that kept them in clear.
     *
     * @param stored what the command keeps ({@link #persist}), may be {@code null}
     * @return the parameters, or {@code null} when there are none or they cannot be read (file deleted, or
     *         encrypted with another master key)
     */
    public static String read(String stored) {
        if (stored == null) {
            return null;
        }
        String text = stored;
        if (isFileReference(stored)) {
            Path path = fileOf(stored);
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (NoSuchFileException missing) {
                // Removed once the operation ended (see OperationFilesRetention), or by hand.
                LOG.info("Params payload file missing (likely purged): {}", path);
                return null;
            } catch (Exception ex) {
                LOG.warn("Failed to read params payload from {}: {}", path, ex.toString());
                return null;
            }
        }
        try {
            return EncryptedEnvelope.openIfSealed(text);
        } catch (RuntimeException ex) {
            LOG.warn("Could not decrypt the parameters {}: {}", isFileReference(stored) ? fileOf(stored) : "kept inline",
                    ex.toString());
            return null;
        }
    }

    /** The file a command's stored parameters refer to, or {@code null} when they are kept inline. */
    static Path fileOf(String stored) {
        return isFileReference(stored) ? Paths.get(stored.substring(FILE_REFERENCE.length())) : null;
    }

    /**
     * Deletes the file a command's parameters are kept in. Only a parameter file of that command is deleted, never
     * another file a corrupted reference would point to.
     *
     * @param commandId the command
     * @param stored    what the command keeps
     * @return whether a file was deleted
     */
    static boolean deleteFile(String commandId, String stored) {
        Path file = fileOf(stored);
        if (file == null || commandId == null || !file.getFileName().toString().equals(commandId + FILE_SUFFIX)) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (Exception e) {
            LOG.warn("Could not delete the parameters of command {} ({}): {}", commandId, file, e.toString());
            return false;
        }
    }

    /**
     * The parameters without the secrets of the wizard's form ({@code formValues}) that no step reads once the
     * operation is planned: the password, pasted and sensitive fields of the service's form
     * ({@link SensitiveFields}), and any value holding a PEM private key. Planning reads them from the request itself,
     * and a step that needs one gets it in a parameter of its own (e.g. the static S3 keys of the Polaris step).
     *
     * <p>A GitOps deploy keeps the password fields: its commit looks for them in the chart values to keep each copy
     * a binding made of them out of Git.
     *
     * @param paramsJson parameters as JSON
     * @return the parameters without those values; the same text when there is nothing to remove or it is not a
     *         JSON object
     */
    @SuppressWarnings("unchecked")
    static String withoutUnreadSecrets(String paramsJson) {
        if (paramsJson == null || !paramsJson.contains("\"formValues\"")) {
            return paramsJson;
        }
        Map<String, Object> params;
        try {
            params = GSON.fromJson(paramsJson, MAP_TYPE);
        } catch (RuntimeException notAnObject) {
            return paramsJson;
        }
        if (params == null || !(params.get("formValues") instanceof Map<?, ?> form)) {
            return paramsJson;
        }
        String serviceKey = params.get("serviceKey") instanceof String s ? s : null;
        Set<String> unread = new LinkedHashSet<>(SensitiveFields.ofService(serviceKey));
        if (params.containsKey(CommandService.GITOPS_RELEASE_PARAM)) {
            unread.removeAll(SensitiveFields.passwordsOfService(serviceKey));
        }
        collectPrivateKeys((Map<String, Object>) form, "", unread);
        if (unread.isEmpty()) {
            return paramsJson;
        }
        params.put("formValues", SensitiveFields.without((Map<String, Object>) form, unread));
        return GSON.toJson(params);
    }

    /** The dotted paths of the form values that hold a PEM private key, whatever the field. */
    private static void collectPrivateKeys(Map<String, Object> values, String prefix, Set<String> paths) {
        values.forEach((key, value) -> {
            String path = prefix + key;
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) nested;
                collectPrivateKeys(map, path + ".", paths);
            } else if (value instanceof String s && HelmSetValues.containsPrivateKey(s)) {
                paths.add(path);
            } else if (value instanceof List<?> list
                    && list.stream().anyMatch(v -> v instanceof String s && HelmSetValues.containsPrivateKey(s))) {
                paths.add(path);
            }
        });
    }
}
