package org.apache.ambari.view.k8s.security;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.UserPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthHelperTest {

  @Mock ViewContext ctx;
  AuthHelper auth;

  @BeforeEach
  void setUpProps() {
    when(ctx.getProperties()).thenReturn(Map.of(
        "view.admin.users",    "alice",
        "view.operator.users", "bob"
    ));
  }

  @Test
  void adminUserGetsAdminRole() {
    when(ctx.getUsername()).thenReturn("alice");
    auth = new AuthHelper(ctx);

    UserPermissions p = auth.getPermissions();
    assertEquals("ADMIN",  p.getRole());
    assertTrue (p.isCanConfigure());
    assertTrue (p.isCanWrite());
  }

  @Test
  void operatorUserGetsOperatorRole() {
    when(ctx.getUsername()).thenReturn("bob");
    auth = new AuthHelper(ctx);

    UserPermissions p = auth.getPermissions();
    assertEquals("OPERATOR", p.getRole());
    assertFalse(p.isCanConfigure());
    assertTrue (p.isCanWrite());
  }

  @Test
  void unknownUserGetsViewerRole() {
    when(ctx.getUsername()).thenReturn("charlie");
    auth = new AuthHelper(ctx);

    UserPermissions p = auth.getPermissions();
    assertEquals("VIEWER", p.getRole());
    assertFalse(p.isCanConfigure());
    assertFalse(p.isCanWrite());
  }
}
