package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class ViewConfigurationService {
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig.path";
    private static final String KUBECONFIG_UPLOADED = "kubeconfig.uploaded";
    private final ViewContext viewContext;
    private final EncryptionService encryptionService;
    private static final Logger LOG = LoggerFactory.getLogger(ViewConfigurationService.class);

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
        }else {
            LOG.info("View is not configured. No kubeconfig path set.");
    
        }
    }

    public File saveKubeconfigFile(InputStream inputStream, String fileName) throws IOException {
        String workingDirPath = viewContext.getProperties().get("k8s.view.working.dir");
        
        if (workingDirPath == null || workingDirPath.trim().isEmpty()) {
            workingDirPath = "/var/lib/ambari-server/resources/views/work/k8s-view";
            LOG.warn("Working directory property 'k8s.view.working.dir' is not set. Using default: {}", workingDirPath);
        } else {
            LOG.info("Using configured working directory: {}", workingDirPath);
        }
        
        File workingDir = new File(workingDirPath);
        if (!workingDir.exists()) {
            LOG.info("Working directory does not exist. Creating it at: {}", workingDirPath);
            if (!workingDir.mkdirs()) {
                throw new IOException("Could not create working directory: " + workingDirPath);
            }
        }
        
        String sanitizedFileName = new File(fileName).getName();
        File configFile = new File(workingDir, sanitizedFileName + ".enc");
        LOG.info("Preparing to save uploaded file to: {}", configFile.getAbsolutePath());

        byte[] contentBytes = inputStream.readAllBytes();
        byte[] encryptedBytes = encryptionService.encrypt(contentBytes);

        try (OutputStream outputStream = new FileOutputStream(configFile)) {
            outputStream.write(encryptedBytes);
        }
        LOG.info("Successfully wrote {} encrypted bytes to {}", encryptedBytes.length, configFile.getAbsolutePath());
        return configFile;
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

    public String getKubeconfigPath() {
        LOG.debug("Attempting to retrieve instance data for key: '{}'", KUBECONFIG_PATH_KEY);
        String path = viewContext.getInstanceData(KUBECONFIG_PATH_KEY);
        if (path == null) {
            LOG.debug("No value found for key '{}' in instance data.", KUBECONFIG_PATH_KEY);
        } else {
            LOG.debug("Found value for key '{}': '{}'", KUBECONFIG_PATH_KEY, path);
        }
        return path;
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
        String uploaded = viewContext.getInstanceData(KUBECONFIG_UPLOADED);
        if (!"true".equalsIgnoreCase(uploaded)) {
            LOG.debug("Kubeconfig not uploaded or flag not set to true.");
            return false;
        }
        LOG.info("isConfigured() check result: {}", uploaded);
        return "true".equalsIgnoreCase(uploaded);
    }
}
