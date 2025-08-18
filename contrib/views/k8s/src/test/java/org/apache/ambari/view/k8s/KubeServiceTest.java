package org.apache.ambari.view.k8s;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.ClusterStats;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KubeServiceTest {

  @Mock KubernetesService k8s;
  @Mock ViewContext ctx;

  @Test
  void getClusterStatsReturns200AndBody() throws Exception {
    ClusterStats stats = new ClusterStats(null,null,null,null,null);
    when(k8s.getClusterStats()).thenReturn(stats);

    KubeService api = new KubeService();
    api.setKubernetesService(k8s);

    // inject mocked ViewContext via reflection (field is @Inject but no setter)
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);

    Response r = api.getClusterStats();
    assertEquals(200, r.getStatus());
    assertEquals(stats, r.getEntity());

    verify(k8s).getClusterStats();
  }

  @Test
  void permissionsEndpointUsesAuthHelper() throws Exception {
    when(ctx.getProperties()).thenReturn(Map.of());
    when(ctx.getUsername()).thenReturn("dave");

    KubeService api = new KubeService();
    api.setKubernetesService(k8s);
    Field f = KubeService.class.getDeclaredField("viewContext");
    f.setAccessible(true);
    f.set(api, ctx);

    Response r = api.getCurrentUserPermissions();
    assertEquals(200, r.getStatus());
    assertNotNull(r.getEntity());
  }
}
