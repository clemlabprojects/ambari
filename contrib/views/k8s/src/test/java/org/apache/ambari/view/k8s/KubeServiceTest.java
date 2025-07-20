package org.apache.ambari.view.k8s;

import org.apache.ambari.view.SecurityException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class KubeServiceTest {

    @Mock
    private ViewContext viewContext;

    @InjectMocks
    private KubeService kubeService;

    private Map<String, String> instanceData;

    @BeforeEach
    void setUp() {
        instanceData = new HashMap<>();
        when(viewContext.getUsername()).thenReturn("testuser");
        when(viewContext.getInstanceData()).thenReturn(instanceData);
    }

    private void mockPermissions(String permission) throws SecurityException {
        String username = "testuser";
        doThrow(new SecurityException("Permission denied")).when(viewContext).hasPermission(eq(username), anyString());
        if (permission != null) {
            doNothing().when(viewContext).hasPermission(eq(username), eq(permission));
        }
    }

    @Test
    void testGetPermissions_Admin() throws SecurityException {
        mockPermissions("AMBARI.ADMINISTRATOR");
        Response response = kubeService.getCurrentUserPermissions();
        UserPermissions permissions = (UserPermissions) response.getEntity();
        assertEquals(200, response.getStatus());
        assertEquals("ADMIN", permissions.getRole());
        assertTrue(permissions.isCanConfigure());
        assertTrue(permissions.isCanWrite());
    }

    @Test
    void uploadKubeconfig_asAdmin_shouldSucceed() throws Exception {
        mockPermissions("AMBARI.ADMINISTRATOR");
        String fakeKubeconfig = "apiVersion: v1";
        InputStream inputStream = new ByteArrayInputStream(fakeKubeconfig.getBytes(StandardCharsets.UTF_8));
        
        // On simule les propriétés et la sauvegarde
        when(viewContext.getProperties()).thenReturn(Collections.singletonMap("k8s.view.working.dir", "/tmp"));
        doNothing().when(viewContext).putInstanceData(anyString(), anyString());

        // On appelle la méthode avec sa nouvelle signature
        Response response = kubeService.uploadKubeconfig(inputStream);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        
        verify(viewContext, times(1)).putInstanceData(eq("kubeconfig.path"), anyString());
    }

    @Test
    void uploadKubeconfig_asViewer_shouldFail() throws Exception {
        mockPermissions(null); // Viewer
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());

        assertThrows(ForbiddenException.class, () -> {
            kubeService.uploadKubeconfig(inputStream);
        });
        
        verify(viewContext, never()).putInstanceData(anyString(), anyString());
    }
}
