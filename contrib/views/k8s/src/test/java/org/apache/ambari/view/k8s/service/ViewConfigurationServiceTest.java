package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ViewConfigurationServiceTest {

  @Test
  void roundTripKubeconfig() throws Exception {
    ViewContext ctx = mock(ViewContext.class);

    // ---- stub des propriétés ----
    String tmp = System.getProperty("java.io.tmpdir") + "/k8s-ut";
    Map <String, String> properties = Map.of(
      "k8s.view.working.dir", tmp,
      "kubeconfig.path", tmp + "/config.yaml.enc",
      "kubeconfig.uploaded", "true"
    );
    when(ctx.getProperties()).thenReturn(properties);

    // ---- stub InstanceData (clé/valeur) ----
    Map<String,String> instanceData = new java.util.HashMap<>();
    when(ctx.getInstanceData(anyString()))
        .thenAnswer(inv -> instanceData.get(inv.getArgument(0)));
    doAnswer(inv -> { instanceData.put(inv.getArgument(0), inv.getArgument(1)); return null; })
        .when(ctx).putInstanceData(anyString(), anyString());

    ViewConfigurationService svc = new ViewConfigurationService(ctx);

    String yaml = "apiVersion: v1\nclusters: []\ncontexts: []\nusers: []\n";
    svc.saveKubeconfigFile(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
        "config.yaml"
    );

    assertEquals(yaml, svc.getKubeconfigContents()); // OK
    assertNotNull(svc.getKubeconfigPath());          // OK
  }
}
