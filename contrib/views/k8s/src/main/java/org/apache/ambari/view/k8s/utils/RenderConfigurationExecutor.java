package org.apache.ambari.view.k8s.utils;

import org.apache.ambari.view.k8s.model.RenderItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility that merges and renders {@code *-site.xml} content into
 * payloads ready to be created as ConfigMaps or Secrets.
 *
 * <p>There is no knowledge of "core-site" or "hdfs-site" here.
 * The caller provides {@code siteMapsByType} where keys correspond exactly
 * to the RenderItem.sourceType strings.</p>
 */
public final class RenderConfigurationExecutor {

    private RenderConfigurationExecutor() {}

    /**
     * Output holder:
     * <ul>
     *   <li>{@code configMaps}: name -> (filename -> xml)</li>
     *   <li>{@code secrets}:    name -> (filename -> xml)</li>
     * </ul>
     */
    public static final class RenderOutput {
        public final Map<String, Map<String, String>> configMaps = new LinkedHashMap<>();
        public final Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
    }

    /**
     * Renders and merges files according to the given items.
     *
     * @param namespace          target namespace (not used in the merge, provided for context/logging)
     * @param items              render instructions from charts.json
     * @param siteMapsByType     map: sourceType -> (propertyName -> value),
     *                           where sourceType keys match {@link RenderItem#sourceType}
     * @return merged output maps to turn into K8s objects
     * @throws IllegalStateException if a required site map is missing/empty
     */
    public static RenderOutput build(String namespace,
                                     List<RenderItem> items,
                                     Map<String, Map<String, String>> siteMapsByType) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(siteMapsByType, "siteMapsByType must not be null");

        RenderOutput out = new RenderOutput();

        for (RenderItem item : items) {
            Map<String, String> siteMap = siteMapsByType.get(item.sourceType);
            if (siteMap == null || siteMap.isEmpty()) {
                throw new IllegalStateException("Missing or empty site map for sourceType=" + item.sourceType);
            }

            String xml = HadoopSiteXml.render(siteMap);
            String fileName = item.resolveFileName();

            Map<String, Map<String, String>> targetCollection = item.isSecret() ? out.secrets : out.configMaps;
            Map<String, String> files = targetCollection.computeIfAbsent(item.targetConfigMapName, k -> new LinkedHashMap<>());

            // last write wins if same filename is declared twice for the same target
            files.put(fileName, xml);
        }

        return out;
    }
}