package org.apache.ambari.view.k8s.security;

import java.util.Base64;

/**
 * AVERTISSEMENT : Service de chiffrement de base.
 * Pour une utilisation en production, il est IMPÉRATIF de remplacer cette
 * implémentation par une solution robuste utilisant le Credential Store d'Ambari
 * pour stocker la clé de chiffrement.
 */
public class EncryptionService {
    public byte[] encrypt(byte[] data) {
        // Ceci est une simple obfuscation, PAS un chiffrement sécurisé.
        return Base64.getEncoder().encode(data);
    }

    public byte[] decrypt(byte[] encryptedData) {
        return Base64.getDecoder().decode(encryptedData);
    }
}