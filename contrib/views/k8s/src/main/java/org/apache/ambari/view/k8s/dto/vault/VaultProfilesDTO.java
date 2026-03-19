package org.apache.ambari.view.k8s.dto.vault;

import java.util.HashMap;
import java.util.Map;

/**
 * Container for multiple named Vault profiles.
 */
public class VaultProfilesDTO {
    public String defaultProfile;
    public Map<String, VaultConfigDTO> profiles = new HashMap<>();
}
