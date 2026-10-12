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

package org.apache.ambari.view.k8s;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.resources.ArgoResource;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Every endpoint that changes something checks the caller's KDPS role before doing anything else.
 *
 * <p>The endpoints are found by reflection, from the view's root resources through every sub-resource locator, so a
 * new endpoint is covered without touching this test. Each one is called as a read-only user with empty arguments
 * on an instance whose dependencies are all missing: only a role check that runs first can answer, with a 403.
 */
class ResourceRoleGuardTest {

  /** The view's REST roots: the {@code api} service of view.xml and the resources it registers directly. */
  private static final List<Class<?>> ROOTS = List.of(KubeService.class, ArgoResource.class);

  /** POSTs that only compute an answer and change nothing; every role needs them. */
  private static final Set<String> READ_ONLY_POSTS = Set.of(
      "ArgoResource.previewApplication",
      "ContextResource.advice",
      "ContextResource.preflight",
      "HelmResource.validate");

  /** GETs that return secrets, configuration or workload internals, and so are role-checked too. */
  private static final Set<String> GUARDED_GETS = Set.of(
      "ConfigurationResource.listConfigs",
      "ConfigurationResource.getConfig",
      "ConfigurationResource.getSettings",
      "ConfigurationResource.getSecurityConfig",
      "ConfigurationResource.getSecurityProfileUsage",
      "ConfigurationResource.getSecuritySchema",
      "DiscoveryResource.discoverK8sSecrets",
      "HelmResource.checkRepo",
      "HelmResource.getValues",
      "KubeService.getProxySettings",
      "KubeService.listKubeconfigContexts",
      "KubeService.preflight",
      "WorkloadsResource.describePod",
      "WorkloadsResource.describeService",
      "WorkloadsResource.getPodLog");

  /** Endpoints reserved to KDPS admins: an operator is refused too. */
  private static final Set<String> ADMIN_ONLY = Set.of(
      "CaRegistryResource.delete",
      "CaRegistryResource.promoteToClusterIssuer",
      "CaRegistryResource.upload",
      "ConfigurationResource.deleteConfig",
      "ConfigurationResource.deleteSecurityProfile",
      "ConfigurationResource.getConfig",
      "ConfigurationResource.getSecurityProfileUsage",
      "ConfigurationResource.getSecuritySchema",
      "ConfigurationResource.getSettings",
      "ConfigurationResource.listConfigs",
      "ConfigurationResource.saveConfig",
      "ConfigurationResource.saveSecurityConfig",
      "ConfigurationResource.saveSettings",
      "ContextResource.delete",
      "ContextResource.probeCdp",
      "ContextResource.probeRemote",
      "ContextResource.save",
      "HelmRepoResource.delete",
      "HelmRepoResource.save",
      "KubeService.createTruststore",
      "KubeService.deleteTruststore",
      "KubeService.getProxySettings",
      "KubeService.listKubeconfigContexts",
      "KubeService.preflight",
      "KubeService.saveOpenShiftLogin",
      "KubeService.saveProxySettings",
      "KubeService.selectKubeconfigContext",
      "KubeService.setAuthMode",
      "KubeService.setTruststoreDefault",
      "KubeService.testOpenShiftLogin",
      "KubeService.uploadKubeconfig");

  @Test
  void aReadOnlyUserIsRefusedEveryEndpointThatChangesSomething() {
    List<String> unguarded = new ArrayList<>();
    for (Map.Entry<String, Endpoint> e : endpoints().entrySet()) {
      Endpoint endpoint = e.getValue();
      boolean mustRefuse = endpoint.httpMethod.equals("GET")
          ? GUARDED_GETS.contains(endpoint.name)
          : !READ_ONLY_POSTS.contains(endpoint.name);
      if (mustRefuse && !refuses(endpoint, "audit")) {
        unguarded.add(e.getKey());
      }
    }
    assertTrue(unguarded.isEmpty(), "Endpoints that do not check the KDPS role first: " + unguarded);
  }

  @Test
  void anOperatorIsRefusedTheConfigurationEndpoints() {
    Map<String, Endpoint> endpoints = endpoints();
    List<String> open = new ArrayList<>();
    for (Map.Entry<String, Endpoint> e : endpoints.entrySet()) {
      if (ADMIN_ONLY.contains(e.getValue().name) && !refuses(e.getValue(), "ops")) {
        open.add(e.getKey());
      }
    }
    assertTrue(open.isEmpty(), "Configuration endpoints an operator can call: " + open);
  }

  @Test
  void everyRoleCheckSeesTheCallerCredentials() {
    // With no admin list, the role check asks Ambari whether the caller is an Ambari administrator, forwarding the
    // caller's credentials; a resource that cannot see the request headers would turn an administrator who calls the
    // API with a user name and password into a read-only user.
    List<String> blind = new ArrayList<>();
    for (Map.Entry<String, Endpoint> e : endpoints().entrySet()) {
      Endpoint endpoint = e.getValue();
      boolean checked = endpoint.httpMethod.equals("GET")
          ? GUARDED_GETS.contains(endpoint.name)
          : !READ_ONLY_POSTS.contains(endpoint.name);
      if (checked && !endpoint.seesHeaders) {
        blind.add(e.getKey());
      }
    }
    assertTrue(blind.isEmpty(), "Role-checked endpoints that cannot forward the caller's credentials: " + blind);
  }

  @Test
  void theListsOnlyNameExistingEndpoints() {
    Set<String> names = new TreeSet<>();
    endpoints().values().forEach(e -> names.add(e.name));
    for (Set<String> list : List.of(READ_ONLY_POSTS, GUARDED_GETS, ADMIN_ONLY)) {
      Set<String> unknown = new TreeSet<>(list);
      unknown.removeAll(names);
      assertTrue(unknown.isEmpty(), "Unknown endpoints in the test lists: " + unknown);
    }
  }

  @Test
  void theGuardDoesNotRefuseAnAdmin() {
    // The refusals above come from the role check, not from the empty arguments: an admin gets past it.
    Endpoint upload = endpoints().values().stream()
        .filter(e -> e.name.equals("ConfigurationResource.saveSecurityConfig")).findFirst().orElseThrow();
    assertFalse(refuses(upload, "lead"));
  }

  // ------------------------------------------------------------------------------------------------------------

  private static final class Endpoint {
    final Class<?> resource;
    final Method method;
    final String httpMethod;
    final String name;
    /** Whether the request headers reach the endpoint, as a parameter of its own or of the locator leading to it. */
    final boolean seesHeaders;

    Endpoint(Class<?> resource, Method method, String httpMethod, boolean locatorHasHeaders) {
      this.resource = resource;
      this.method = method;
      this.httpMethod = httpMethod;
      this.name = resource.getSimpleName() + "." + method.getName();
      this.seesHeaders = locatorHasHeaders || takesHeaders(method);
    }
  }

  private static boolean takesHeaders(Method m) {
    return Arrays.asList(m.getParameterTypes()).contains(HttpHeaders.class);
  }

  /** Every endpoint reachable from the roots, keyed "HTTP Class.method(params)". */
  private static Map<String, Endpoint> endpoints() {
    Map<String, Endpoint> out = new LinkedHashMap<>();
    Deque<Class<?>> pending = new ArrayDeque<>(ROOTS);
    Set<Class<?>> seen = new LinkedHashSet<>();
    Set<Class<?>> locatedWithHeaders = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      Class<?> resource = pending.poll();
      if (!seen.add(resource)) continue;
      for (Method m : resource.getDeclaredMethods()) {
        if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
        String httpMethod = httpMethodOf(m);
        if (httpMethod != null) {
          List<String> params = new ArrayList<>();
          for (Class<?> p : m.getParameterTypes()) params.add(p.getSimpleName());
          out.put(httpMethod + " " + resource.getSimpleName() + "." + m.getName() + params,
              new Endpoint(resource, m, httpMethod, locatedWithHeaders.contains(resource)));
        } else if (m.isAnnotationPresent(Path.class)) {
          pending.add(m.getReturnType()); // sub-resource locator
          if (takesHeaders(m)) locatedWithHeaders.add(m.getReturnType());
        }
      }
    }
    assertTrue(out.size() > 50, "endpoint discovery found too few endpoints: " + out.size());
    return out;
  }

  private static String httpMethodOf(Method m) {
    for (Annotation a : m.getAnnotations()) {
      HttpMethod http = a.annotationType().getAnnotation(HttpMethod.class);
      if (http != null) return http.value();
    }
    return null;
  }

  /** Calls the endpoint as {@code user} (lead = admin, ops = operator, anyone else = read-only). */
  private static boolean refuses(Endpoint endpoint, String user) {
    ViewContext ctx = mock(ViewContext.class);
    when(ctx.getProperties()).thenReturn(Map.of(
        AuthHelper.ADMIN_USERS_PROPERTY, "lead",
        AuthHelper.OPERATOR_USERS_PROPERTY, "ops"));
    when(ctx.getUsername()).thenReturn(user);
    when(ctx.getLoggedinUser()).thenReturn(user);

    Object instance = new ObjenesisStd().newInstance(endpoint.resource);
    inject(instance, ctx);
    Object[] args = new Object[endpoint.method.getParameterCount()];
    Class<?>[] types = endpoint.method.getParameterTypes();
    for (int i = 0; i < args.length; i++) args[i] = emptyValue(types[i]);
    try {
      Object result = endpoint.method.invoke(instance, args);
      return result instanceof Response && ((Response) result).getStatus() == 403;
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      return cause instanceof WebApplicationException && ((WebApplicationException) cause).getResponse().getStatus() == 403;
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /** Gives the instance the view context and a role checker bound to it; every other dependency stays missing. */
  private static void inject(Object instance, ViewContext ctx) {
    for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        Object value = null;
        if (f.getType() == ViewContext.class) value = ctx;
        else if (f.getType() == AuthHelper.class) value = new AuthHelper(ctx);
        if (value == null) continue;
        try {
          f.setAccessible(true);
          f.set(instance, value);
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
  }

  private static Object emptyValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == long.class) return 0L;
    if (type == double.class) return 0d;
    if (type == float.class) return 0f;
    if (type == short.class) return (short) 0;
    if (type == byte.class) return (byte) 0;
    if (type == char.class) return '\0';
    return 0;
  }
}
