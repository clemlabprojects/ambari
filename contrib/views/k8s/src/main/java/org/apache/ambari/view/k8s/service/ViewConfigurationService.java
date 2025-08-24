package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Service for managing view configuration including kubeconfig file handling
 */
public class ViewConfigurationService {
    private static final Logger LOG = LoggerFactory.getLogger(ViewConfigurationService.class);
    
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig.path";
    private static final String KUBECONFIG_UPLOADED = "kubeconfig.uploaded";
    
    private final ViewContext viewContext;
    private final EncryptionService encryptionService;

    public ViewConfigurationService(ViewContext viewContext) {
        this.viewContext = viewContext;
        this.encryptionService = new EncryptionService();
        
        // Check if the view is configured and if the kubeconfig file exists
        if (isConfigured()) {
            String kubeconfigPath = getKubeconfigPath();
            if (kubeconfigPath != null) {
                File kubeconfigFile = new File(kubeconfigPath);
                if (!kubeconfigFile.exists()) {
                    LOG.warn("Kubeconfig file does not exist at path: {}. Setting '{}' to false.", kubeconfigPath, KUBECONFIG_UPLOADED);
                    viewContext.putInstanceData(KUBECONFIG_UPLOADED, "false");
                } else {
                    LOG.info("Kubeconfig file exists at path: {}. No action needed.", kubeconfigPath);
                }
            }
        } else {
            LOG.info("View is not configured. No kubeconfig path set.");
        }
    }

    public File saveKubeconfigFile(InputStream inputStream, String fileName) throws IOException {
        String workingDirectoryPath = viewContext.getProperties().get("k8s.view.working.dir");
        
        if (workingDirectoryPath == null || workingDirectoryPath.trim().isEmpty()) {
            workingDirectoryPath = "/var/lib/ambari-server/resources/views/work/k8s-view";
            LOG.warn("Working directory property 'k8s.view.working.dir' is not set. Using default: {}", workingDirectoryPath);
        } else {
            LOG.info("Using configured working directory: {}", workingDirectoryPath);
        }
        
        File workingDirectory = new File(workingDirectoryPath);
        if (!workingDirectory.exists()) {
            LOG.info("Working directory does not exist. Creating it at: {}", workingDirectoryPath);
            if (!workingDirectory.mkdirs()) {
                throw new IOException("Could not create working directory: " + workingDirectoryPath);
            }
        }
        
        String sanitizedFileName = new File(fileName).getName();
        File configurationFile = new File(workingDirectory, sanitizedFileName + ".enc");
        LOG.info("Preparing to save uploaded file to: {}", configurationFile.getAbsolutePath());

        byte[] fileContentBytes = inputStream.readAllBytes();
        byte[] encryptedContentBytes = encryptionService.encrypt(fileContentBytes);

        try (OutputStream fileOutputStream = new FileOutputStream(configurationFile)) {
            fileOutputStream.write(encryptedContentBytes);
        }
        LOG.info("Successfully wrote {} encrypted bytes to {}", encryptedContentBytes.length, configurationFile.getAbsolutePath());
        saveKubeconfigPath(configurationFile.getAbsolutePath());
        return configurationFile;
    }

    public void saveKubeconfigPath(String kubeconfigPath) {
        LOG.info("Attempting to save instance data. Key: '{}', Value: '{}'", KUBECONFIG_PATH_KEY, kubeconfigPath);
        try {
            viewContext.putInstanceData(KUBECONFIG_PATH_KEY, kubeconfigPath);
            LOG.info("Successfully called putInstanceData for key '{}'", KUBECONFIG_PATH_KEY);
            
            // Verify that the data is correctly stored by retrieving it
            String retrievedPath = getKubeconfigPath();
            if (!kubeconfigPath.equals(retrievedPath)) {
                LOG.warn("Verification failed: stored kubeconfig path does not match the provided value. Provided: '{}', Retrieved: '{}'", kubeconfigPath, retrievedPath);
            } else {
                LOG.info("Verification successful: stored kubeconfig path matches the provided value.");
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
        Path encryptedKubeconfigFilePath = Paths.get(getKubeconfigPath());
        if (encryptedKubeconfigFilePath == null) {
            LOG.error("kubeconfig.path is not set for this view instance");
            throw new IllegalStateException("Kubeconfig not configured");
        }
        if (!Files.exists(encryptedKubeconfigFilePath)) {
            LOG.error("Kubeconfig file does not exist at path: {}", encryptedKubeconfigFilePath);
            throw new IllegalStateException("Kubeconfig not configured");
        }
        try {
            byte[] encryptedBytes = Files.readAllBytes(encryptedKubeconfigFilePath);
            byte[] plainTextBytes = encryptionService.decrypt(encryptedBytes);
            LOG.info("Loaded kubeconfig contents ({} bytes) from {}", plainTextBytes.length, encryptedKubeconfigFilePath);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read encrypted kubeconfig", e);
        }
    }

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

    public void removeKubeconfigPath() {
        LOG.warn("Removing stale kubeconfig path entry. Key: '{}'", KUBECONFIG_PATH_KEY);
        try {
            viewContext.removeInstanceData(KUBECONFIG_PATH_KEY);
            LOG.info("Successfully removed instance data for key '{}'", KUBECONFIG_PATH_KEY);
        } catch (Exception e) {
            LOG.error("An error occurred while calling removeInstanceData", e);
        }
    }

    public boolean isConfigured() {
        LOG.info("Checking if view is configured...");
        String uploadedFlag = viewContext.getInstanceData(KUBECONFIG_UPLOADED);
        if (!"true".equalsIgnoreCase(uploadedFlag)) {
            LOG.debug("Kubeconfig not uploaded or flag not set to true.");
            return false;
        }
        LOG.info("isConfigured() check result: {}", uploadedFlag);
        return "true".equalsIgnoreCase(uploadedFlag);
    }
}
