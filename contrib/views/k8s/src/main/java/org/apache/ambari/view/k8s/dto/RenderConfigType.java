package org.apache.ambari.view.k8s.dto;

/**
 * Type of Kubernetes resource to render for multi-file configuration bundles.
 * CONFIG_MAP → stores clear-text string data.
 * SECRET     → stores base64-encoded data (Opaque).
 */
public enum RenderConfigType {
    CONFIG_MAP,
    SECRET
}
