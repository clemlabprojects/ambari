package org.apache.ambari.view.k8s.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry for Ranger config defaults providers, keyed by service type.
 */
public final class RangerConfigDefaultsRegistry {

    private static final Map<String, RangerConfigDefaultsProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new TrinoRangerConfigDefaultsProvider());
    }

    private RangerConfigDefaultsRegistry() {}

    /**
     * Resolve a provider for the given service type.
     *
     * @param serviceType service type (case-insensitive)
     * @return provider or null when none registered
     */
    public static RangerConfigDefaultsProvider resolve(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return null;
        }
        return PROVIDERS.get(serviceType.toLowerCase(Locale.ROOT));
    }

    /**
     * Register a provider for its service type.
     *
     * @param provider provider to register
     */
    public static void register(RangerConfigDefaultsProvider provider) {
        if (provider == null || provider.serviceType() == null || provider.serviceType().isBlank()) {
            return;
        }
        PROVIDERS.put(provider.serviceType().toLowerCase(Locale.ROOT), provider);
    }
}
