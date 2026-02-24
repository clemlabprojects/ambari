/*
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

package org.apache.ambari.server.api;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationProperties;
import org.apache.ambari.server.security.authentication.jwt.JwtAuthenticationPropertiesProvider;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.Gson;

public class AmbariErrorHandlerTest {
  private final Gson gson = new Gson();

  @Test
  public void testHandleInternalServerErrorUsesReason() throws Exception {
    JwtAuthenticationPropertiesProvider propertiesProvider = Mockito.mock(JwtAuthenticationPropertiesProvider.class);
    Mockito.when(propertiesProvider.get()).thenReturn(null);

    HttpConnection httpConnection = Mockito.mock(HttpConnection.class);
    HttpChannel httpChannel = Mockito.mock(HttpChannel.class);
    Request baseRequest = Mockito.mock(Request.class);
    Response response = Mockito.mock(Response.class);
    HttpServletRequest httpServletRequest = Mockito.mock(HttpServletRequest.class);
    HttpServletResponse httpServletResponse = Mockito.mock(HttpServletResponse.class);

    Mockito.when(httpConnection.getHttpChannel()).thenReturn(httpChannel);
    Mockito.when(httpChannel.getRequest()).thenReturn(baseRequest);
    Mockito.when(httpChannel.getResponse()).thenReturn(response);
    Mockito.when(response.getStatus()).thenReturn(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    Mockito.when(response.getReason()).thenReturn("Internal Server Error");

    StringWriter writer = new StringWriter();
    Mockito.when(httpServletResponse.getWriter()).thenReturn(new PrintWriter(writer));

    try (MockedStatic<HttpConnection> httpConnectionStatic = Mockito.mockStatic(HttpConnection.class)) {
      httpConnectionStatic.when(HttpConnection::getCurrentConnection).thenReturn(httpConnection);

      AmbariErrorHandler ambariErrorHandler = new AmbariErrorHandler(gson, propertiesProvider);
      ambariErrorHandler.handle("target", baseRequest, httpServletRequest, httpServletResponse);
    }

    Mockito.verify(baseRequest).setHandled(true);
    Mockito.verify(httpServletResponse).setContentType(MimeTypes.Type.TEXT_PLAIN.asString());

    Map<String, Object> responseMap = gson.fromJson(writer.toString(), Map.class);
    Assert.assertEquals(Double.valueOf(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), responseMap.get("status"));
    Assert.assertEquals("Internal Server Error", responseMap.get("message"));
  }

  @Test
  public void testHandleUnauthorizedAddsJwtProviderUrl() throws Exception {
    JwtAuthenticationPropertiesProvider propertiesProvider = Mockito.mock(JwtAuthenticationPropertiesProvider.class);
    JwtAuthenticationProperties jwtProperties = Mockito.mock(JwtAuthenticationProperties.class);
    Mockito.when(propertiesProvider.get()).thenReturn(jwtProperties);
    Mockito.when(jwtProperties.isEnabledForAmbari()).thenReturn(true);
    Mockito.when(jwtProperties.getAuthenticationProviderUrl()).thenReturn("https://idp.example.com/auth");
    Mockito.when(jwtProperties.getOriginalUrlQueryParam()).thenReturn("orig");

    HttpConnection httpConnection = Mockito.mock(HttpConnection.class);
    HttpChannel httpChannel = Mockito.mock(HttpChannel.class);
    Request baseRequest = Mockito.mock(Request.class);
    Response response = Mockito.mock(Response.class);
    HttpServletRequest httpServletRequest = Mockito.mock(HttpServletRequest.class);
    HttpServletResponse httpServletResponse = Mockito.mock(HttpServletResponse.class);

    Mockito.when(httpConnection.getHttpChannel()).thenReturn(httpChannel);
    Mockito.when(httpChannel.getRequest()).thenReturn(baseRequest);
    Mockito.when(httpChannel.getResponse()).thenReturn(response);
    Mockito.when(response.getStatus()).thenReturn(HttpServletResponse.SC_UNAUTHORIZED);
    Mockito.when(response.getReason()).thenReturn("Unauthorized");

    StringWriter writer = new StringWriter();
    Mockito.when(httpServletResponse.getWriter()).thenReturn(new PrintWriter(writer));

    try (MockedStatic<HttpConnection> httpConnectionStatic = Mockito.mockStatic(HttpConnection.class)) {
      httpConnectionStatic.when(HttpConnection::getCurrentConnection).thenReturn(httpConnection);

      AmbariErrorHandler ambariErrorHandler = new AmbariErrorHandler(gson, propertiesProvider);
      ambariErrorHandler.handle("target", baseRequest, httpServletRequest, httpServletResponse);
    }

    Map<String, Object> responseMap = gson.fromJson(writer.toString(), Map.class);
    Assert.assertEquals(Double.valueOf(HttpServletResponse.SC_UNAUTHORIZED), responseMap.get("status"));
    Assert.assertEquals("Unauthorized", responseMap.get("message"));
    Assert.assertEquals("https://idp.example.com/auth?orig=", responseMap.get("jwtProviderUrl"));
  }
}
