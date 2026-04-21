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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.apache.ambari.view.k8s.utils.WebHookBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.PrivilegedExceptionAction;
import java.security.AccessController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Service for managing view configuration including kubeconfig file handling
 */
public class ViewConfigurationService {
    private static final Logger LOG = LoggerFactory.getLogger(ViewConfigurationService.class);
    
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig.path";
    private static final String KUBECONFIG_UPLOADED = "kubeconfig.uploaded";
    
    private final ViewContext viewContext;
    private final EncryptionService encryptionService;

    /**
     * Constructs a {@code ViewConfigurationService} and validates the kubeconfig state on startup.
     * If the stored kubeconfig path no longer points to an existing file, the uploaded flag is cleared.
     *
     * @param viewContext the Ambari view context providing instance data and properties
     */
    public ViewConfigurationService(ViewContext viewContext) {
        LOG.info("Initializing ViewConfigurationService for view context: {}", viewContext.getInstanceName());
        this.viewContext = viewContext;
        this.encryptionService = new EncryptionService();
        LOG.debug("EncryptionService initialized successfully");
        
        // Check if the view is configured and if the kubeconfig file exists
        if (isConfigured()) {
            LOG.info("View is configured, checking kubeconfig file existence");
            String kubeconfigPath = getKubeconfigPath();
            if (kubeconfigPath != null) {
                LOG.debug("Kubeconfig path found: {}", kubeconfigPath);
                File kubeconfigFile = new File(kubeconfigPath);
                if (!kubeconfigFile.exists()) {
                    LOG.warn("Kubeconfig file does not exist at path: {}. Setting '{}' to false.", kubeconfigPath, KUBECONFIG_UPLOADED);
                    viewContext.putInstanceData(KUBECONFIG_UPLOADED, "false");
                } else {
                    LOG.info("Kubeconfig file exists at path: {}. No action needed.", kubeconfigPath);
                }
            } else {
                LOG.warn("View is configured but no kubeconfig path is set");
            }
        } else {
            LOG.info("View is not configured. No kubeconfig path set.");
        }
        LOG.info("ViewConfigurationService initialization completed");
    }

    /**
     * Encrypts and saves the uploaded kubeconfig file to the view's working directory.
     * The resulting path is also persisted via {@link #saveKubeconfigPath}.
     *
     * @param inputStream the raw kubeconfig content to encrypt and save
     * @param fileName    the original filename; used to derive the on-disk name
     * @return the saved (encrypted) {@link File}
     * @throws IOException if the working directory cannot be created or the file cannot be written
     */
    public File saveKubeconfigFile(InputStream inputStream, String fileName) throws IOException {
        LOG.info("Starting saveKubeconfigFile operation for file: {}", fileName);
        
        final String workingDirectoryPath = getConfigurationDirectoryPath();
        LOG.debug("Working directory path: {}", workingDirectoryPath);
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<File>() {
                @Override
                public File run() throws IOException {
                    LOG.debug("Executing saveKubeconfigFile in privileged context");
                    
                    File workingDirectory = new File(workingDirectoryPath);
                    if (!workingDirectory.exists()) {
                        LOG.info("Working directory does not exist. Creating it at: {}", workingDirectoryPath);
                        if (!workingDirectory.mkdirs()) {
                            LOG.error("Failed to create working directory: {}", workingDirectoryPath);
                            throw new IOException("Could not create working directory: " + workingDirectoryPath);
                        }
                        LOG.info("Successfully created working directory: {}", workingDirectoryPath);
                    } else {
                        LOG.debug("Working directory already exists: {}", workingDirectoryPath);
                    }
                    
                    String sanitizedFileName = new File(fileName).getName();
                    File configurationFile = new File(workingDirectory, sanitizedFileName + ".enc");
                    LOG.info("Preparing to save uploaded file to: {}", configurationFile.getAbsolutePath());

                    byte[] fileContentBytes = inputStream.readAllBytes();
                    LOG.debug("Read {} bytes from input stream", fileContentBytes.length);
                    
                    byte[] encryptedContentBytes = encryptionService.encrypt(fileContentBytes);
                    LOG.debug("Encrypted content to {} bytes", encryptedContentBytes.length);

                    try (OutputStream fileOutputStream = new FileOutputStream(configurationFile)) {
                        fileOutputStream.write(encryptedContentBytes);
                        fileOutputStream.flush();
                        LOG.debug("Successfully wrote encrypted content to file");
                    }
                    
                    LOG.info("Successfully wrote {} encrypted bytes to {}", encryptedContentBytes.length, configurationFile.getAbsolutePath());
                    return configurationFile;
                }
            });
        } catch (Exception e) {
            LOG.error("Error saving kubeconfig file: {}", e.getMessage(), e);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to save kubeconfig file", e);
        } finally {
            // Save the path after successful file creation
            LOG.debug("Attempting to save kubeconfig path to instance data");
            try {
                File savedFile = new File(workingDirectoryPath, new File(fileName).getName() + ".enc");
                saveKubeconfigPath(savedFile.getAbsolutePath());
                LOG.info("Kubeconfig file save operation completed successfully");
            } catch (Exception e) {
                LOG.error("Error saving kubeconfig path to instance data: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Persists the kubeconfig file path into view instance data and marks the view as configured.
     *
     * @param kubeconfigPath the absolute path to the encrypted kubeconfig file to store
     */
    public void saveKubeconfigPath(String kubeconfigPath) {
        LOG.info("Attempting to save instance data. Key: '{}', Value: '{}'", KUBECONFIG_PATH_KEY, kubeconfigPath);
        try {
            viewContext.putInstanceData(KUBECONFIG_PATH_KEY, kubeconfigPath);
            LOG.debug("Successfully called putInstanceData for key '{}'", KUBECONFIG_PATH_KEY);
            
            // Verify that the data is correctly stored by retrieving it
            String retrievedPath = getKubeconfigPath();
            if (!kubeconfigPath.equals(retrievedPath)) {
                LOG.warn("Verification failed: stored kubeconfig path does not match the provided value. Provided: '{}', Retrieved: '{}'", kubeconfigPath, retrievedPath);
            } else {
                LOG.debug("Verification successful: stored kubeconfig path matches the provided value.");
                viewContext.putInstanceData(KUBECONFIG_UPLOADED, "true");
                LOG.info("Set '{}' to true in instance data.", KUBECONFIG_UPLOADED);
            }
        } catch (Exception e) {
            LOG.error("An error occurred while calling putInstanceData", e);
        }
    }

    /**
     * Returns the plain YAML content as a string.
     */
    public String getKubeconfigContents() {
        LOG.info("Attempting to retrieve kubeconfig contents from encrypted file.");
        
        final String kubeconfigPath = getKubeconfigPath();
        if (kubeconfigPath == null) {
            LOG.error("kubeconfig.path is not set for this view instance");
            throw new IllegalStateException("Kubeconfig not configured");
        }
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<String>() {
                @Override
                public String run() throws IOException {
                    LOG.debug("Executing getKubeconfigContents in privileged context");
                    
                    Path encryptedKubeconfigFilePath = Paths.get(kubeconfigPath);
                    LOG.debug("Reading from kubeconfig path: {}", encryptedKubeconfigFilePath);
                    
                    if (!Files.exists(encryptedKubeconfigFilePath)) {
                        LOG.error("Kubeconfig file does not exist at path: {}", encryptedKubeconfigFilePath);
                        throw new IllegalStateException("Kubeconfig not configured");
                    }
                    
                    byte[] encryptedBytes = Files.readAllBytes(encryptedKubeconfigFilePath);
                    LOG.debug("Read {} encrypted bytes from file", encryptedBytes.length);
                    
                    byte[] plainTextBytes = encryptionService.decrypt(encryptedBytes);
                    LOG.info("Loaded kubeconfig contents ({} bytes) from {}", plainTextBytes.length, encryptedKubeconfigFilePath);
                    
                    return new String(plainTextBytes, StandardCharsets.UTF_8);
                }
            });
        } catch (Exception e) {
            LOG.error("Error reading kubeconfig contents: {}", e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new UncheckedIOException("Failed to read encrypted kubeconfig", new IOException(e));
        }
    }

    /**
     * Returns the stored path to the encrypted kubeconfig file.
     *
     * @return the kubeconfig file path, or {@code null} if not yet configured
     */
    public String getKubeconfigPath() {
        LOG.debug("Attempting to retrieve instance data for key: '{}'", KUBECONFIG_PATH_KEY);
        String configurationPath = viewContext.getInstanceData(KUBECONFIG_PATH_KEY);
        if (configurationPath == null) {
            LOG.debug("No value found for key '{}' in instance data.", KUBECONFIG_PATH_KEY);
        } else {
            LOG.debug("Found value for key '{}': '{}'", KUBECONFIG_PATH_KEY, configurationPath);
        }
        return configurationPath;
    }

    /**
     * Removes the stored kubeconfig path from view instance data, typically called when the stored path is stale.
     */
    public void removeKubeconfigPath() {
        LOG.warn("Removing stale kubeconfig path entry. Key: '{}'", KUBECONFIG_PATH_KEY);
        try {
            viewContext.removeInstanceData(KUBECONFIG_PATH_KEY);
            LOG.info("Successfully removed instance data for key '{}'", KUBECONFIG_PATH_KEY);
        } catch (Exception e) {
            LOG.error("An error occurred while calling removeInstanceData", e);
        }
    }

    /**
     * Returns whether the view has been configured with an uploaded kubeconfig file.
     *
     * @return {@code true} if a kubeconfig has been successfully uploaded and validated
     */
    public boolean isConfigured() {
        LOG.debug("Checking if view is configured...");
        String uploadedFlag = viewContext.getInstanceData(KUBECONFIG_UPLOADED);
        boolean isConfigured = "true".equalsIgnoreCase(uploadedFlag);
        LOG.debug("isConfigured() check result: {} (flag value: {})", isConfigured, uploadedFlag);
        return isConfigured;
    }
    
    /**
     * Returns the base working directory path for this view instance.
     * Falls back to a version-derived default when the {@code k8s.view.working.dir} property is not set.
     *
     * @return the absolute path to the view's working directory
     */
    public String getConfigurationDirectoryPath(){
        LOG.debug("Reading Configuration Working Dir...");
        String workingDirectoryPath = viewContext.getProperties().get("k8s.view.working.dir");

        if (workingDirectoryPath == null || workingDirectoryPath.trim().isEmpty()) {
            workingDirectoryPath = defaultWorkDir();
            LOG.warn("Working directory property 'k8s.view.working.dir' is not set. Using default for this view version: {}", workingDirectoryPath);
        } else {
            LOG.info("Using configured working directory: {}", workingDirectoryPath);
        }

        return workingDirectoryPath;
    }
    
    /**
     * Returns the resource path for this view instance.
     * Falls back to a version-derived default when the {@code k8s.view.resource.path} property is not set.
     *
     * @return the absolute path to the view's resource directory
     */
    public String getViewResourcePath(){
        LOG.debug("Reading View Resource Path...");
        String resourcePath = viewContext.getProperties().get("k8s.view.resource.path");

        if (resourcePath == null || resourcePath.trim().isEmpty()) {
            resourcePath = defaultWorkDir();
            LOG.warn("View resource path property 'k8s.view.resource.path' is not set. Using default for this view version: {}", resourcePath);
        } else {
            LOG.info("Using configured view resource path: {}", resourcePath);
        }

        return resourcePath;
    }

    /**
     * Build the default work/resource directory based on the current view version.
     */
    private String defaultWorkDir() {
        String version = viewContext.getViewDefinition() != null ? viewContext.getViewDefinition().getVersion() : "1.0.0.5";
        return "/var/lib/ambari-server/resources/views/work/K8S-VIEW{" + version + "}";
    }

    // helper methods in order to cache values.yml file
    private static final String CACHE_DIR_NAME = "cache";

    public String getCacheDirectoryPath() {
        LOG.debug("getCacheDirectoryPath() called");
        final String base = getConfigurationDirectoryPath();
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<String>() {
                @Override
                public String run() throws IOException {
                    LOG.debug("Executing getCacheDirectoryPath in privileged context");
                    
                    File cache = new File(base, CACHE_DIR_NAME);
                    if (!cache.exists()) {
                        LOG.info("Cache directory does not exist; attempting to create: {}", cache.getAbsolutePath());
                        if (!cache.mkdirs()) {
                            LOG.error("Failed to create cache directory: {}", cache.getAbsolutePath());
                            throw new IllegalStateException("Cannot create cache dir: " + cache.getAbsolutePath());
                        }
                        LOG.info("Cache directory created: {}", cache.getAbsolutePath());
                    } else {
                        LOG.debug("Cache directory already exists: {}", cache.getAbsolutePath());
                    }
                    return cache.getAbsolutePath();
                }
            });
        } catch (Exception e) {
            LOG.error("Error getting cache directory path: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get cache directory path", e);
        }
    }

    /** Writes plain text to a unique file in the cache and returns the ABSOLUTE PATH. */
    public String writeCacheFile(String prefix, String content) {
        LOG.debug("writeCacheFile(prefix={}, contentLength={}) called", prefix, content == null ? 0 : content.length());
        Objects.requireNonNull(content, "content");
        
        final String safePrefix = prefix == null || prefix.isBlank() ? "payload" : prefix.replaceAll("[^a-zA-Z0-9._-]", "_");
        final String fileName = safePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json";
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<String>() {
                @Override
                public String run() throws IOException {
                    LOG.debug("Executing writeCacheFile in privileged context");
                    
                    File f = new File(getCacheDirectoryPath(), fileName);
                    LOG.info("Preparing to write cache file: {}", f.getAbsolutePath());
                    
                    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    
                    // best-effort restrictive perms
                    boolean rSet = f.setReadable(true, true);
                    boolean wSet = f.setWritable(true, true);
                    LOG.debug("File permissions set: readable={}, writable={}", rSet, wSet);
                    LOG.info("Successfully wrote {} bytes to cache file {}", content.getBytes(StandardCharsets.UTF_8).length, f.getAbsolutePath());
                    
                    return f.getAbsolutePath();
                }
            });
        } catch (Exception e) {
            LOG.error("Error writing cache file: {}", e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new UncheckedIOException("Failed to write cache file", new IOException(e));
        }
    }

    /** Reads a JSON file into a String. */
    public String readCacheFile(String absolutePath) {
        LOG.debug("readCacheFile({}) called", absolutePath);
        requirePathInsideCache(absolutePath);
        
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<String>() {
                @Override
                public String run() throws IOException {
                    LOG.debug("Executing readCacheFile in privileged context");
                    
                    String s = Files.readString(Paths.get(absolutePath), StandardCharsets.UTF_8);
                    LOG.info("Read {} chars from cache file {}", s.length(), absolutePath);
                    return s;
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to read cache file {}: {}", absolutePath, e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new UncheckedIOException("Failed to read cache file: " + absolutePath, new IOException(e));
        }
    }

    /** Deletes a cache file if present (best effort). */
    public void deleteCacheFileQuiet(String absolutePath) {
        LOG.debug("deleteCacheFileQuiet({}) called", absolutePath);
        if (absolutePath == null || absolutePath.isBlank()) {
            LOG.trace("deleteCacheFileQuiet: nothing to do for null/blank path");
            return;
        }
        
        try {
            requirePathInsideCache(absolutePath);
            
            AccessController.doPrivileged(new PrivilegedExceptionAction<Void>() {
                @Override
                public Void run() throws IOException {
                    LOG.debug("Executing deleteCacheFileQuiet in privileged context");
                    
                    boolean deleted = Files.deleteIfExists(Paths.get(absolutePath));
                    if (deleted) {
                        LOG.info("Deleted cache file {}", absolutePath);
                    } else {
                        LOG.debug("Cache file did not exist, nothing to delete: {}", absolutePath);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            LOG.warn("Could not delete cache file {}: {}", absolutePath, e.getMessage());
        }
    }

    /** Prevent path traversal / foreign paths; only allow inside our cache dir. */
    private void requirePathInsideCache(String absolutePath) {
        LOG.trace("requirePathInsideCache({}) called", absolutePath);
        if (absolutePath == null) {
            LOG.error("Path is null in requirePathInsideCache");
            throw new IllegalArgumentException("path is null");
        }
        
        Path base = Paths.get(getCacheDirectoryPath()).toAbsolutePath().normalize();
        Path target = Paths.get(absolutePath).toAbsolutePath().normalize();
        LOG.debug("Cache base path: {}, target path: {}", base, target);
        
        if (!target.startsWith(base)) {
            LOG.error("Path not under cache dir: base={}, target={}", base, target);
            throw new SecurityException("Path not under cache dir: " + absolutePath);
        }
        LOG.trace("Path is inside cache directory");
    }
}


