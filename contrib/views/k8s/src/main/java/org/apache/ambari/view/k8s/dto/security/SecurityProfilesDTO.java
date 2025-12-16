package org.apache.ambari.view.k8s.dto.security;

import java.util.HashMap;
import java.util.Map;

/**
 * Container for multiple named security profiles.
 */
public class SecurityProfilesDTO {
    public String defaultProfile;
    public Map<String, SecurityConfigDTO> profiles = new HashMap<>();
}
