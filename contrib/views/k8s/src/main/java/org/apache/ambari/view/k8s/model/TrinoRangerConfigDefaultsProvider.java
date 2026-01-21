package org.apache.ambari.view.k8s.model;

import java.util.Map;

/**
 * Ranger defaults provider for Trino.
 */
public final class TrinoRangerConfigDefaultsProvider implements RangerConfigDefaultsProvider {

    @Override
    public String serviceType() {
        return "trino";
    }

    @Override
    public Map<String, String> pluginProperties(String serviceName) {
        return RangerTrinoConfigDefaults.pluginProperties(serviceName);
    }

    @Override
    public Map<String, String> security(String serviceName) {
        return RangerTrinoConfigDefaults.security(serviceName);
    }

    @Override
    public Map<String, String> audit() {
        return RangerTrinoConfigDefaults.audit();
    }

    @Override
    public Map<String, String> policymgrSsl() {
        return RangerTrinoConfigDefaults.policymgrSsl();
    }
}
