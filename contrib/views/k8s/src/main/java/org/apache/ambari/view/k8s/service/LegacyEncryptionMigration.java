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

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptedEnvelope;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.store.CommandEntity;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.utils.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Once the Ambari master key is found, encrypts again with it everything an earlier version encrypted with the
 * passphrase built into the view, whether it is ever read or not: the secrets of the view settings, the kubeconfig
 * file, and the parameters and cached chart values of operations. What is read is encrypted again as it is read
 * anyway, but a secret nobody reads (a platform context nobody deploys to, the OIDC client secret of an idle release,
 * the token of an unused chart repository) would otherwise stay encrypted with a passphrase that is public, and become
 * unreadable once the view stops reading it.
 *
 * <p>Done on a start of the view until everything was encrypted again ({@link #MARKER_KEY}); what cannot be
 * decrypted (encrypted with another master key) is logged, and looked at again on the next start.</p>
 */
final class LegacyEncryptionMigration {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyEncryptionMigration.class);

    /** View setting written once nothing encrypted without the master key is left. */
    static final String MARKER_KEY = "encryption.master-key.migrated";

    /**
     * What a run did, for its log line and for tests.
     *
     * @param encryptedAgain how many secrets and files were encrypted again with the master key
     * @param unreadable     the settings keys and files that could not be decrypted
     * @param done           whether nothing encrypted without the master key is left
     */
    record Result(int encryptedAgain, List<String> unreadable, boolean done) {
    }

    private final ViewContext ctx;
    private final DataStore dataStore;
    private final EncryptionService encryption;

    /**
     * @param ctx        the view instance
     * @param dataStore  its DataStore
     * @param encryption encrypts with the master key; {@code null} for that of this Ambari server
     */
    LegacyEncryptionMigration(ViewContext ctx, DataStore dataStore, EncryptionService encryption) {
        this.ctx = ctx;
        this.dataStore = dataStore;
        this.encryption = encryption;
    }

    /**
     * Encrypts again what is left, unless it was done already or there is no master key yet.
     *
     * @param now the current time
     * @return what was done
     */
    Result run(Instant now) {
        EncryptionService service = encryption != null ? encryption : EncryptionService.forServer(ctx);
        InstanceSettings settings = new InstanceSettings(dataStore, service);
        if (!service.isMasterKeyAvailable() || settings.get(MARKER_KEY) != null) {
            return new Result(0, List.of(), false);
        }
        int encryptedAgain = 0;
        List<String> unreadable = new ArrayList<>();

        InstanceSettings.LegacySecrets secrets = settings.encryptLegacySecretsAgain();
        encryptedAgain += secrets.encryptedAgain().size();
        unreadable.addAll(secrets.unreadable());

        String kubeconfig = settings.get(ViewConfigurationService.KUBECONFIG_PATH_KEY);
        if (kubeconfig != null && !kubeconfig.isBlank() && Files.isRegularFile(Paths.get(kubeconfig))) {
            Path file = Paths.get(kubeconfig);
            try {
                byte[] read = Files.readAllBytes(file);
                EncryptionService.Decrypted decrypted = service.decryptStored(read);
                if (decrypted.reencrypt()) {
                    String again = new String(service.encrypt(decrypted.plain()), StandardCharsets.UTF_8);
                    if (writeIfUnchanged(file, read, again)) {
                        encryptedAgain++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                unreadable.add(file.toString());
            }
        }

        for (Path dir : List.of(CommandParamsStore.directory(ctx), ViewConfigurationService.cacheDirectory(ctx))) {
            for (Path file : sealedFiles(dir, unreadable)) {
                try {
                    byte[] read = Files.readAllBytes(file);
                    String text = new String(read, StandardCharsets.UTF_8);
                    EncryptedEnvelope.Opened opened = EncryptedEnvelope.openStored(text);
                    if (opened.reseal() && writeIfUnchanged(file, read, EncryptedEnvelope.sealToJson(opened.text()))) {
                        encryptedAgain++;
                    }
                } catch (NoSuchFileException deletedMeanwhile) {
                    // The operation it belonged to ended meanwhile.
                } catch (IOException | RuntimeException e) {
                    unreadable.add(file.toString());
                }
            }
        }
        encryptedAgain += encryptInlineParametersAgain(unreadable);

        boolean done = unreadable.isEmpty();
        if (done) {
            settings.put(MARKER_KEY, now.toString());
        } else {
            LOG.warn("These credentials cannot be decrypted with the Ambari master key; they were encrypted with "
                    + "another key and must be entered again: {}", String.join(", ", unreadable));
        }
        LOG.info("Credentials stored without the Ambari master key by an earlier version: {} encrypted again with it",
                encryptedAgain);
        return new Result(encryptedAgain, List.copyOf(unreadable), done);
    }

    /**
     * Replaces a file with what was made of it, unless it was written meanwhile (a kubeconfig uploaded, an operation
     * resumed while the view starts): what was written then is encrypted with the master key already.
     *
     * @return whether the file was replaced
     */
    private static boolean writeIfUnchanged(Path file, byte[] read, String replacement) throws IOException {
        synchronized (LegacyEncryptionMigration.class) {
            if (!Files.isRegularFile(file) || !Arrays.equals(read, Files.readAllBytes(file))) {
                return false;
            }
            OwnerOnlyFiles.write(file, replacement);
            return true;
        }
    }

    /** The encrypted files of a directory; listed first, since encrypting one again writes a temporary file there. */
    private static List<Path> sealedFiles(Path dir, List<String> unreadable) {
        List<Path> sealed = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return sealed;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String text;
                try {
                    text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
                } catch (IOException notOurs) {
                    // Deleted meanwhile, or not text: not something KDPS encrypted.
                    continue;
                }
                if (EncryptedEnvelope.isSealed(text)) {
                    sealed.add(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            unreadable.add(dir.toString());
        }
        return sealed;
    }

    /** Parameters versions older still kept in the view's database rather than in a file. */
    private int encryptInlineParametersAgain(List<String> unreadable) {
        Collection<CommandEntity> commands;
        try {
            commands = dataStore.findAll(CommandEntity.class, null);
        } catch (Exception e) {
            unreadable.add("the parameters of the operations");
            return 0;
        }
        int encryptedAgain = 0;
        for (CommandEntity cmd : commands == null ? List.<CommandEntity>of() : commands) {
            String stored = cmd.getParamsJson();
            if (!EncryptedEnvelope.isSealed(stored)) {
                continue;
            }
            try {
                EncryptedEnvelope.Opened opened = EncryptedEnvelope.openStored(stored);
                if (opened.reseal()) {
                    // Read again: a command resumed meanwhile is stored with its other fields changed.
                    CommandEntity current = dataStore.find(CommandEntity.class, cmd.getId());
                    if (current != null && stored.equals(current.getParamsJson())) {
                        current.setParamsJson(EncryptedEnvelope.sealToJson(opened.text()));
                        dataStore.store(current);
                        encryptedAgain++;
                    }
                }
            } catch (Exception e) {
                unreadable.add("the parameters of command " + cmd.getId());
            }
        }
        return encryptedAgain;
    }
}
