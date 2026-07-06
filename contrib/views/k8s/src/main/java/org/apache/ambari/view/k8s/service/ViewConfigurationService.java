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
import java.util.Base64;
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
    private static final String KUBECONFIG_CONTEXT_KEY = "kubeconfig.context";

    // Auth mode: "kubeconfig" (default, uploaded file) or "openshift-login" (API URL + user/password,
    // token minted + renewed on demand — for clusters where only console login is available).
    private static final String AUTH_MODE_KEY = "auth.mode";
    public static final String AUTH_MODE_KUBECONFIG = "kubeconfig";
    public static final String AUTH_MODE_OPENSHIFT_LOGIN = "openshift-login";
    private static final String OS_API_URL_KEY = "openshift.apiUrl";
    private static final String OS_USERNAME_KEY = "openshift.username";
    private static final String OS_PASSWORD_ENC_KEY = "openshift.password.enc";
    private static final String OS_CA_KEY = "openshift.caData";
    private static final String OS_INSECURE_KEY = "openshift.insecure";

    // View-wide outbound proxy for reaching internet Helm/Git repositories (password encrypted at rest).
    private static final String PROXY_ENABLED_KEY = "proxy.enabled";
    private static final String PROXY_URL_KEY = "proxy.url";
    private static final String PROXY_USER_KEY = "proxy.username";
    private static final String PROXY_PASSWORD_ENC_KEY = "proxy.password.enc";
    private static final String PROXY_NOPROXY_KEY = "proxy.noProxy";

    private final ViewContext viewContext;
    private final EncryptionService encryptionService;
    // Cached per-instance so the minted token is reused across calls (rebuilding would drop the cache).
    private volatile OpenShiftLoginProvider openShiftLoginProvider;

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
            // Uploading a kubeconfig switches the view back to kubeconfig auth mode (away from openshift-login).
            viewContext.putInstanceData(AUTH_MODE_KEY, AUTH_MODE_KUBECONFIG);
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

    /** @return the active auth mode ("kubeconfig" default, or "openshift-login"). */
    public String getAuthMode() {
        String mode = viewContext.getInstanceData(AUTH_MODE_KEY);
        return (mode == null || mode.isBlank()) ? AUTH_MODE_KUBECONFIG : mode;
    }

    /** Switch the active auth mode (e.g. back to "kubeconfig" without re-uploading). */
    public void setAuthMode(String mode) {
        viewContext.putInstanceData(AUTH_MODE_KEY, AUTH_MODE_OPENSHIFT_LOGIN.equals(mode) ? AUTH_MODE_OPENSHIFT_LOGIN : AUTH_MODE_KUBECONFIG);
        this.openShiftLoginProvider = null;
    }

    /** @return true if the view is configured with OpenShift username/password login. */
    public boolean isOpenShiftLogin() {
        return AUTH_MODE_OPENSHIFT_LOGIN.equals(getAuthMode())
                && viewContext.getInstanceData(OS_API_URL_KEY) != null
                && viewContext.getInstanceData(OS_USERNAME_KEY) != null;
    }

    /**
     * Persist an OpenShift username/password login. The password is encrypted with the same service used
     * for the kubeconfig file. Switches the view to openshift-login mode and marks it configured.
     */
    public void saveOpenShiftLogin(String apiUrl, String username, String password, String caData, boolean insecure) {
        try {
            viewContext.putInstanceData(OS_API_URL_KEY, apiUrl);
            viewContext.putInstanceData(OS_USERNAME_KEY, username);
            if (password != null && !password.isEmpty()) {
                String enc = Base64.getEncoder().encodeToString(
                        encryptionService.encrypt(password.getBytes(StandardCharsets.UTF_8)));
                viewContext.putInstanceData(OS_PASSWORD_ENC_KEY, enc);
            }
            viewContext.putInstanceData(OS_CA_KEY, caData != null ? caData : "");
            viewContext.putInstanceData(OS_INSECURE_KEY, Boolean.toString(insecure));
            viewContext.putInstanceData(AUTH_MODE_KEY, AUTH_MODE_OPENSHIFT_LOGIN);
            viewContext.putInstanceData(KUBECONFIG_UPLOADED, "true");
            this.openShiftLoginProvider = null; // rebuild with new creds on next use
            LOG.info("Saved OpenShift login for user '{}' at {}", username, apiUrl);
        } catch (Exception e) {
            LOG.error("Failed to save OpenShift login: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save OpenShift login", e);
        }
    }

    /** Build (once) and return the token provider for the stored OpenShift login, or null if not set. */
    public synchronized OpenShiftLoginProvider getOpenShiftLoginProvider() {
        if (!isOpenShiftLogin()) return null;
        if (openShiftLoginProvider != null) return openShiftLoginProvider;
        String apiUrl = viewContext.getInstanceData(OS_API_URL_KEY);
        String username = viewContext.getInstanceData(OS_USERNAME_KEY);
        String enc = viewContext.getInstanceData(OS_PASSWORD_ENC_KEY);
        String password = "";
        if (enc != null && !enc.isBlank()) {
            try {
                password = new String(encryptionService.decrypt(Base64.getDecoder().decode(enc)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.error("Failed to decrypt stored OpenShift password: {}", e.getMessage());
            }
        }
        String caData = viewContext.getInstanceData(OS_CA_KEY);
        boolean insecure = Boolean.parseBoolean(viewContext.getInstanceData(OS_INSECURE_KEY));
        openShiftLoginProvider = new OpenShiftLoginProvider(apiUrl, username, password, caData, insecure);
        return openShiftLoginProvider;
    }

    /** Persist the view-wide outbound proxy settings (password encrypted). */
    public void saveProxySettings(boolean enabled, String url, String username, String password, String noProxy) {
        try {
            viewContext.putInstanceData(PROXY_ENABLED_KEY, Boolean.toString(enabled));
            viewContext.putInstanceData(PROXY_URL_KEY, url == null ? "" : url);
            viewContext.putInstanceData(PROXY_USER_KEY, username == null ? "" : username);
            if (password != null && !password.isEmpty()) {
                viewContext.putInstanceData(PROXY_PASSWORD_ENC_KEY,
                        Base64.getEncoder().encodeToString(encryptionService.encrypt(password.getBytes(StandardCharsets.UTF_8))));
            }
            viewContext.putInstanceData(PROXY_NOPROXY_KEY, noProxy == null ? "" : noProxy);
            LOG.info("Saved outbound proxy settings (enabled={}, url={})", enabled, url);
        } catch (Exception e) {
            LOG.error("Failed to save proxy settings: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save proxy settings", e);
        }
    }

    public boolean isProxyEnabled() { return Boolean.parseBoolean(viewContext.getInstanceData(PROXY_ENABLED_KEY)); }
    public String getProxyUrl() { return viewContext.getInstanceData(PROXY_URL_KEY); }
    public String getProxyUsername() { return viewContext.getInstanceData(PROXY_USER_KEY); }
    public String getProxyNoProxy() { return viewContext.getInstanceData(PROXY_NOPROXY_KEY); }

    /** Build a {@link ProxySupport} from the stored settings, keeping {@code alwaysDirect} hosts direct. */
    public ProxySupport buildProxySupport(String... alwaysDirect) {
        String encPw = viewContext.getInstanceData(PROXY_PASSWORD_ENC_KEY);
        String password = "";
        if (encPw != null && !encPw.isBlank()) {
            try {
                password = new String(encryptionService.decrypt(Base64.getDecoder().decode(encPw)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("Failed to decrypt stored proxy password: {}", e.getMessage());
            }
        }
        return ProxySupport.from(getProxyUrl(), getProxyUsername(), password, getProxyNoProxy(), isProxyEnabled(), alwaysDirect);
    }

    public String getOpenShiftApiUrl() { return viewContext.getInstanceData(OS_API_URL_KEY); }
    public String getOpenShiftCaData() { return viewContext.getInstanceData(OS_CA_KEY); }
    public boolean isOpenShiftInsecure() { return Boolean.parseBoolean(viewContext.getInstanceData(OS_INSECURE_KEY)); }

    /**
     * Synthesize a kubeconfig (with a freshly-minted token) for the stored OpenShift login. Used by the
     * helm client and anywhere a kubeconfig string is expected.
     */
    private String synthesizeOpenShiftKubeconfig() {
        OpenShiftLoginProvider provider = getOpenShiftLoginProvider();
        String token = provider != null ? provider.getToken() : null;
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Could not obtain an OpenShift token from the stored username/password.");
        }
        String apiUrl = getOpenShiftApiUrl();
        String caData = getOpenShiftCaData();
        boolean insecure = isOpenShiftInsecure();
        String clusterTls;
        if (!insecure && caData != null && !caData.isBlank()) {
            String b64 = caData.trim().startsWith("-----BEGIN")
                    ? Base64.getEncoder().encodeToString(caData.trim().getBytes(StandardCharsets.UTF_8))
                    : caData.trim().replaceAll("\\s", "");
            clusterTls = "    certificate-authority-data: " + b64 + "\n";
        } else {
            clusterTls = "    insecure-skip-tls-verify: true\n";
        }
        return "apiVersion: v1\n"
                + "kind: Config\n"
                + "clusters:\n"
                + "- name: openshift\n"
                + "  cluster:\n"
                + "    server: " + apiUrl + "\n"
                + clusterTls
                + "users:\n"
                + "- name: openshift-user\n"
                + "  user:\n"
                + "    token: " + token + "\n"
                + "contexts:\n"
                + "- name: openshift\n"
                + "  context:\n"
                + "    cluster: openshift\n"
                + "    user: openshift-user\n"
                + "current-context: openshift\n";
    }

    /**
     * Returns the plain YAML content as a string.
     */
    public String getKubeconfigContents() {
        if (isOpenShiftLogin()) {
            return synthesizeOpenShiftKubeconfig();
        }
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
     * Persist the operator-selected kubeconfig context — the single cluster/context this view
     * instance targets. When unset, the client falls back to the kubeconfig's {@code current-context}.
     *
     * @param contextName the kubeconfig context name to use, or {@code null}/blank to clear it
     */
    public void saveSelectedContext(String contextName) {
        try {
            if (contextName == null || contextName.isBlank()) {
                viewContext.removeInstanceData(KUBECONFIG_CONTEXT_KEY);
                LOG.info("Cleared selected kubeconfig context (will use current-context).");
            } else {
                viewContext.putInstanceData(KUBECONFIG_CONTEXT_KEY, contextName);
                LOG.info("Saved selected kubeconfig context '{}'.", contextName);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist selected kubeconfig context", e);
        }
    }

    /**
     * The operator-selected kubeconfig context for this view instance, or {@code null} to use the
     * kubeconfig's {@code current-context}.
     */
    public String getSelectedContext() {
        return viewContext.getInstanceData(KUBECONFIG_CONTEXT_KEY);
    }

    public void removeSelectedContext() {
        try {
            viewContext.removeInstanceData(KUBECONFIG_CONTEXT_KEY);
        } catch (Exception e) {
            LOG.error("Failed to remove selected kubeconfig context", e);
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
     * Returns the directory where Ambari's ViewExtractor unpacked this JAR.
     * Used for read-only bundled assets (CRD YAMLs, etc.) that are refreshed on every upgrade.
     * This path is NOT configurable — it is always derived from the view name and version.
     */
    public String getExtractDir() {
        String name    = viewContext.getViewDefinition().getViewName();
        String version = viewContext.getViewDefinition().getVersion();
        return "/var/lib/ambari-server/resources/views/work/" + name + "{" + version + "}";
    }

    /**
     * Persistent data directory — survives JAR upgrades and Ambari restarts.
     * Configurable via the {@code k8s.view.working.dir} view instance property.
     * Default: {@code /var/lib/ambari-server/resources/views/k8s-view-data/<instanceName>}
     */
    private String defaultWorkDir() {
        String instanceName = viewContext.getInstanceName() != null ? viewContext.getInstanceName() : "default";
        return "/var/lib/ambari-server/resources/views/k8s-view-data/" + instanceName;
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


