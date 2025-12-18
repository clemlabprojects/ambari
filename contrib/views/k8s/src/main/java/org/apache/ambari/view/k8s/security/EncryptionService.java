package org.apache.ambari.view.k8s.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Service de chiffrement utilisant AES-256 avec une clé maître.
 * Utilise MasterKeyService pour obtenir la clé de chiffrement de manière sécurisée.
 */
public class EncryptionService {
    private static final Logger LOG = LoggerFactory.getLogger(EncryptionService.class);
    
    private final AESEncryptor encryptor;
    private final MasterKeyService masterKeyService;
    
    /**
     * Constructeur par défaut utilisant MasterKeyServiceImpl.
     */
    public EncryptionService() {
        this(new MasterKeyServiceImpl());
    }
    
    /**
     * Constructeur avec injection de MasterKeyService (pour les tests).
     */
    public EncryptionService(MasterKeyService masterKeyService) {
        this.masterKeyService = masterKeyService;
        
        if (!masterKeyService.isMasterKeyInitialized()) {
            LOG.warn("Master key not initialized. Encryption will use a default passphrase (NOT SECURE FOR PRODUCTION)");
            // Fallback to default passphrase if master key is not available
            // This should only happen in development/testing environments
            this.encryptor = new AESEncryptor("default-passphrase-change-in-production");
        } else {
            char[] masterSecret = masterKeyService.getMasterSecret();
            String passPhrase = new String(masterSecret);
            this.encryptor = new AESEncryptor(passPhrase);
            // Clear the char array from memory
            java.util.Arrays.fill(masterSecret, '\0');
        }
    }
    
    /**
     * Chiffre les données en utilisant AES.
     * 
     * @param data Les données à chiffrer
     * @return Les données chiffrées encodées en Base64 (format: salt:iv:ciphertext)
     */
    public byte[] encrypt(byte[] data) {
        try {
            EncryptionResult result = encryptor.encrypt(data);
            // Format: salt:iv:ciphertext (all Base64 encoded)
            String saltB64 = Base64.getEncoder().encodeToString(result.getSalt());
            String ivB64 = Base64.getEncoder().encodeToString(result.getIv());
            String cipherB64 = Base64.getEncoder().encodeToString(result.getCipher());
            String combined = saltB64 + ":" + ivB64 + ":" + cipherB64;
            return combined.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt data", e);
        }
    }

    /**
     * Déchiffre les données chiffrées.
     * 
     * @param encryptedData Les données chiffrées au format Base64 (salt:iv:ciphertext)
     * @return Les données déchiffrées
     */
    public byte[] decrypt(byte[] encryptedData) {
        try {
            String combined = new String(encryptedData, StandardCharsets.UTF_8);
            String[] parts = combined.split(":", 3);

            if (parts.length != 3) {
                // Try to decode as legacy Base64-only format for backward compatibility
                LOG.warn("Detected legacy Base64-only format, attempting backward-compatible decode");
                return Base64.getDecoder().decode(encryptedData);
            }

            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] cipher = Base64.getDecoder().decode(parts[2]);

            return encryptor.decrypt(salt, iv, cipher);
        } catch (IllegalArgumentException iae) {
            // Preserve IllegalArgumentException for invalid Base64 to satisfy callers/tests
            throw iae;
        } catch (Exception e) {
            LOG.error("Decryption failed. Attempting legacy Base64 decode for backward compatibility", e);
            // Fallback to Base64 decode for backward compatibility with old data
            try {
                return Base64.getDecoder().decode(encryptedData);
            } catch (IllegalArgumentException iae2) {
                throw iae2;
            } catch (Exception e2) {
                LOG.error("Both AES and Base64 decryption failed", e2);
                throw new RuntimeException("Failed to decrypt data", e2);
            }
        }
    }
}