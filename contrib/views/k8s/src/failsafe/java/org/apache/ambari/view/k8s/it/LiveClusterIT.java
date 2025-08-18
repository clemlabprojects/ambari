package org.apache.ambari.view.k8s.it;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.k8s.model.HelmDeployRequest;
import org.apache.ambari.view.k8s.model.HelmRelease;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("livecluster")
@ExtendWith(LiveClusterExtension.class)
class LiveClusterIT {

  @Test
  @Timeout(300)  // plenty for first‑time chart pull
  void helmDeployAndDeleteWorks(KubernetesClient client) {
    KubernetesService svc = new KubernetesService(client, /*isConfigured=*/true);

    String ns   = "it-" + UUID.randomUUID().toString().substring(0, 5);
    String name = "spark-" + UUID.randomUUID().toString().substring(0, 5);

    svc.createNamespace(ns);

    HelmDeployRequest req = HelmDeployRequest.builder()
        .releaseName(name)
        .chartName("bitnami/spark")
        .namespace(ns)
        .version("1.2.3")
        .build();

    HelmRelease rel = svc.deployHelmChart(req);
    assertEquals("deployed", rel.getStatus());

    svc.uninstallHelmChart(name, ns);
    svc.deleteNamespace(ns);
  }
}
