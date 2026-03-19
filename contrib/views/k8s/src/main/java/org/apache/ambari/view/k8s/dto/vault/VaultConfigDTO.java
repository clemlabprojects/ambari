package org.apache.ambari.view.k8s.dto.vault;

import java.util.Map;

/**
 * Global Vault configuration profile.
 */
public class VaultConfigDTO {
    public Boolean enabled;
    public String address;
    public String namespace;
    public VaultAuthConfig auth;
    public VaultCsiConfig csi;
    public Map<String, Object> extraProperties;

    public static class VaultAuthConfig {
        public String method; // kubernetes | approle
        public String mountPath;
        public VaultKubernetesAuthConfig kubernetes;
        public VaultAppRoleAuthConfig approle;
    }

    public static class VaultKubernetesAuthConfig {
        public String role;
    }

    public static class VaultAppRoleAuthConfig {
        public String roleId;
        public String secretId;
    }

    public static class VaultCsiConfig {
        public String caCertSecret;
    }
}
