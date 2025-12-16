package org.apache.ambari.view.k8s.dto.argocd;

import java.util.Map;

/**
 * Request payload to build an Argo CD Application manifest from a Helm source.
 */
public class ArgoApplicationRequest {
    public String repoUrl;          // Helm repo URL (can be OCI: oci://…)
    public String chart;            // Chart name
    public String targetRevision;   // Chart version (or semver range)
    public String releaseName;      // Application name
    public String namespace;        // Destination namespace
    public Map<String, Object> values; // Helm values overrides
}
