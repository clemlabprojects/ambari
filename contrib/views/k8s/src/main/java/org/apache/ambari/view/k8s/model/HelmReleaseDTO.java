package org.apache.ambari.view.k8s.model;

import com.marcnuri.helm.Release;

/**
 * Thin JSON-safe DTO for Helm releases.
 * Keep only simple types (String/Integer) to avoid Gson + JDK 17 reflection issues.
 */
public class HelmReleaseDTO {
    // Keep these public to make Gson serialization trivial
    public String id;        // namespace/name
    public String name;
    public String namespace;
    public String chart;     // e.g. "prometheus-community/prometheus"
    public String version;   // appVersion if available
    public String status;    // deployed/failed/pending, etc.
    public Integer revision; // parsed from helm "revision"
    public String created;   // keep null (or set ISO-8601 string yourself)
    public String updated;   // keep null (or set ISO-8601 string yourself)

    public Boolean managedByUi; // null/false if unknown
    public String serviceKey;   // ex: "trino"
    public String repoId;
    public String chartRef;     // ex: "prometheus-community/prometheus"
    
    public HelmReleaseDTO() {
        // no-arg ctor for Gson
    }

    /** Map from helm-java Release to our DTO (no java.time surfaces). */
    public static HelmReleaseDTO from(Release r) {
        HelmReleaseDTO d = new HelmReleaseDTO();
        d.name = r.getName();
        d.namespace = r.getNamespace();
        d.chart = r.getChart();
        d.version = r.getAppVersion();
        d.status = r.getStatus();

        // Build a stable row key for the UI
        d.id = (d.namespace != null ? d.namespace : "") + "/" + d.name;

        // Normalize revision (helm-java may return Integer/String depending on version)
        try {
            Object rawRev = r.getRevision();
            if (rawRev != null) {
                d.revision = Integer.valueOf(String.valueOf(rawRev));
            }
        } catch (Exception ignore) {
            d.revision = null;
        }

        // Don't expose java.time to Gson/Ambari; keep as null or set string yourself later
        d.created = null;
        d.updated = null;
        return d;
    }
}
