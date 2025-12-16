package org.apache.ambari.view.k8s.model.kube;

import java.util.Map;

public class KubeNamespace {
    public String name;
    public String status;
    public String createdAt;
    public Map<String, String> labels;
}
