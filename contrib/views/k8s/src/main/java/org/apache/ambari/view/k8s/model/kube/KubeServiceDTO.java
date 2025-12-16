package org.apache.ambari.view.k8s.model.kube;

import java.util.Map;

public class KubeServiceDTO {
    public String name;
    public String namespace;
    public String type;
    public Map<String, Integer> ports; // name -> port
    public Map<String, String> labels;
    public String clusterIP;
}
