/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
