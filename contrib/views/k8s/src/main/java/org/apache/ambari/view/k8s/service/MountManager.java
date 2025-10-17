package org.apache.ambari.view.k8s.service;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Ensure Kubernetes resources exist for top-level "mounts" sent by the UI.
 *
 * Expected payload fragment (already on the request you receive):
 *   "mounts": {
 *     "data": {
 *       "type": "pvc" | "emptyDir",
 *       "mountPath": "/data",
 *       "size": "20Gi",                   // pvc only
 *       "storageClass": "fast",           // pvc optional
 *       "accessModes": ["ReadWriteOnce"]  // pvc optional
 *     }
 *   }
 *
 * Naming convention for PVCs:
 *   <releaseName>-<mountKey>
 * e.g. "trinodb-release-test-1-data"
 */
public class MountManager {

    private static final Logger LOG = LoggerFactory.getLogger(MountManager.class);

    private final KubernetesClient client;

    public MountManager(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Ensure all resources required by the mounts exist in the target namespace.
     * EmptyDir => no resources to create.
     * PVC      => ensure PVC exists (idempotent).
     *
     * @param namespace   target namespace
     * @param releaseName Helm release name (used to build PVC names)
     * @param mounts      top-level "mounts" object from the UI payload
     * @return list of PVC names that were created or already existed
     */
    @SuppressWarnings("unchecked")
    public List<String> ensureMounts(String namespace, String releaseName, Map<String, Object> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            LOG.info("No mounts declared (release={}). Nothing to ensure.", releaseName);
            return List.of();
        }

        final List<String> ensured = new ArrayList<>();

        for (Map.Entry<String, Object> e : mounts.entrySet()) {
            final String mountKey = e.getKey();
            final Map<String, Object> spec = asMap(e.getValue());

            final String type = asString(spec.get("type"), "emptyDir").trim().toLowerCase(Locale.ROOT);
            switch (type) {
                case "pvc": {
                    final String pvcName = releaseName + "-" + mountKey;
                    final String size = asString(spec.get("size"), "10Gi");
                    final String storageClass = asString(spec.get("storageClass"), null);
                    final List<String> accessModes =
                            asStringList(spec.getOrDefault("accessModes", List.of("ReadWriteOnce")));
                    ensurePVC(namespace, pvcName, size, storageClass, accessModes);
                    ensured.add(pvcName);
                    break;
                }
                case "emptydir":
                    LOG.debug("Mount '{}' is emptyDir → no cluster resources to create.", mountKey);
                    break;
                default:
                    LOG.warn("Unsupported mount type '{}' for key '{}' (release={}), skipping.", type, mountKey, releaseName);
            }
        }
        return ensured;
    }

    /** Create PVC if absent. If present, leave as-is (idempotent). */
    private void ensurePVC(String namespace, String name, String size,
                           @javax.annotation.Nullable String storageClass,
                           List<String> accessModes) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");

        var pvcClient = client.persistentVolumeClaims().inNamespace(namespace);
        if (pvcClient.withName(name).get() != null) {
            LOG.info("PVC {}/{} already exists", namespace, name);
            return;
        }

        // Fallbacks
        final List<String> modes = (accessModes == null || accessModes.isEmpty())
                ? java.util.Collections.singletonList("ReadWriteOnce")
                : accessModes;

        // Build PVC in a single fluent chain; set storageClass only if provided
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewSpec()
                .withAccessModes(modes)
                .withNewResources()
                .addToRequests("storage", new io.fabric8.kubernetes.api.model.Quantity(
                        (size == null || size.isBlank()) ? "10Gi" : size
                ))
                .endResources()
                // Only set storageClassName if non-null/non-blank
                .withStorageClassName(
                        (storageClass != null && !storageClass.isBlank()) ? storageClass : null
                )
                .endSpec()
                .build();

        pvcClient.resource(pvc).create();
        LOG.info("Created PVC {}/{} size={} storageClass={} modes={}", namespace, name, size, storageClass, modes);
    }


    /* ----------------- helpers ----------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new HashMap<>();
    }

    private static String asString(Object o, String def) {
        return (o == null) ? def : String.valueOf(o);
    }

    private static List<String> asStringList(Object o) {
        if (o instanceof List<?>) {
            return ((List<?>) o).stream().map(String::valueOf).collect(Collectors.toList());
        }
        if (o instanceof String s) {
            // allow comma-separated string just in case
            return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toList());
        }
        return List.of();
    }

    private static Quantity parseQuantity(String s) {
        return new Quantity((s == null || s.isBlank()) ? "1Gi" : s);
    }
}
