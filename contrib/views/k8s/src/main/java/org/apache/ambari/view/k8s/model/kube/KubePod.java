package org.apache.ambari.view.k8s.model.kube;

import java.util.List;
import java.util.Map;

public class KubePod {
    public String name;
    public String namespace;
    public String phase;
    public String nodeName;
    public String podIP;
    public String startTime;
    public Map<String, String> labels;
    public List<KubePodContainerStatus> containers;
}
