package org.apache.ambari.view.k8s.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class HelmReleasesResponse {
    @JsonProperty("items")
    public List<HelmReleaseDTO> items;

    @JsonProperty("total")
    public int total;

    public HelmReleasesResponse(List<HelmReleaseDTO> items, int total) {
        this.items = items;
        this.total = total;
    }
}
