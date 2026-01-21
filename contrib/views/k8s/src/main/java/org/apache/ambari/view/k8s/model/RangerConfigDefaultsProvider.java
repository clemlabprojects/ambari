package org.apache.ambari.view.k8s.model;

import java.util.Map;

/**
 * Provides default Ranger configuration maps for a specific service type.
 */
public interface RangerConfigDefaultsProvider {

    /**
     * @return the service type this provider supports (lower-case, e.g. "trino")
     */
    String serviceType();

    /**
     * @param serviceName Ranger service name used in property prefixes
     * @return default plugin properties map
     */
    Map<String, String> pluginProperties(String serviceName);

    /**
     * @param serviceName Ranger service name used in property prefixes
     * @return default security properties map
     */
    Map<String, String> security(String serviceName);

    /**
     * @return default audit properties map
     */
    Map<String, String> audit();

    /**
     * @return default policymgr SSL properties map
     */
    Map<String, String> policymgrSsl();
}
