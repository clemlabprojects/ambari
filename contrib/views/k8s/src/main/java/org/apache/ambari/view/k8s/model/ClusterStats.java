package org.apache.ambari.view.k8s.model;

public class ClusterStats {

    private ResourceStat cpu;
    private ResourceStat memory;
    private ResourceStat pods;
    private ResourceStat nodes;
    private HelmStat helm;

    public static class ResourceStat {
        private double used;
        private double total;

        public ResourceStat(double used, double total) {
            this.used = used;
            this.total = total;
        }

        public double getUsed() { return used; }
        public double getTotal() { return total; }
    }

    public static class HelmStat {
        private int deployed;
        private int pending;
        private int failed;
        private int total;

        public HelmStat(int deployed, int pending, int failed, int total) {
            this.deployed = deployed;
            this.pending = pending;
            this.failed = failed;
            this.total = total;
        }

        public int getDeployed() { return deployed; }
        public int getPending() { return pending; }
        public int getFailed() { return failed; }
        public int getTotal() { return total; }
    }

    public ClusterStats(ResourceStat cpu, ResourceStat memory, ResourceStat pods, ResourceStat nodes, HelmStat helm) {
        this.cpu = cpu;
        this.memory = memory;
        this.pods = pods;
        this.nodes = nodes;
        this.helm = helm;
    }
    
    // Getters pour chaque stat
    public ResourceStat getCpu() { return cpu; }
    public ResourceStat getMemory() { return memory; }
    public ResourceStat getPods() { return pods; }
    public ResourceStat getNodes() { return nodes; }
    public HelmStat getHelm() { return helm; }
}
