package org.apache.ambari.view.k8s.model.kube;

public class KubePodContainerStatus {
    public String name;
    public boolean ready;
    public int restartCount;
    public String state;
}
