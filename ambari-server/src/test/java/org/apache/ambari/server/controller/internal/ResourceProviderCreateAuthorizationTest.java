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

package org.apache.ambari.server.controller.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.junit.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.security.core.Authentication;

/**
 * A {@code POST} on a REST resource reaches {@link ResourceProvider#createResources(Request)}.
 * {@link AbstractAuthorizedResourceProvider} checks the caller's role there before any work is
 * done, and a provider that overrides that method itself skips the check unless it repeats it.
 * That check only means something when the provider names the roles it requires, or decides
 * itself in {@code isAuthorizedToCreateResources}: with no required role, any signed-in user gets
 * in. These tests make sure no new provider takes either shortcut unnoticed, and that the
 * providers which act with credentials Ambari holds keep the check. What each of those
 * providers lets in is tested by its own test ({@link RangerPluginResourceProviderTest},
 * {@link RangerPolicyResourceProviderTest}, {@link AdhocKeytabResourceProviderTest}).
 */
public class ResourceProviderCreateAuthorizationTest {

  /**
   * Providers that already created resources without going through
   * {@link AbstractAuthorizedResourceProvider#createResources(Request)} when this check was added:
   * the Ambari providers this server is based on, and {@link JavaRuntimeResourceProvider}, which
   * refuses every create. A new provider does not belong here: it implements
   * {@code createResourcesAuthorized} instead. This list may only shrink.
   */
  private static final Set<String> OWN_CREATE_RESOURCES = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
      "org.apache.ambari.server.controller.internal.ActionResourceProvider",
      "org.apache.ambari.server.controller.internal.ActiveWidgetLayoutResourceProvider",
      "org.apache.ambari.server.controller.internal.AlertDefinitionResourceProvider",
      "org.apache.ambari.server.controller.internal.AlertGroupResourceProvider",
      "org.apache.ambari.server.controller.internal.AlertHistoryResourceProvider",
      "org.apache.ambari.server.controller.internal.AlertNoticeResourceProvider",
      "org.apache.ambari.server.controller.internal.ArtifactResourceProvider",
      "org.apache.ambari.server.controller.internal.BlueprintResourceProvider",
      "org.apache.ambari.server.controller.internal.ClientConfigResourceProvider",
      "org.apache.ambari.server.controller.internal.ClusterControllerImpl$ExtendedResourceProviderWrapper",
      "org.apache.ambari.server.controller.internal.ConfigurationResourceProvider",
      "org.apache.ambari.server.controller.internal.ExtensionLinkResourceProvider",
      "org.apache.ambari.server.controller.internal.FeedResourceProvider",
      "org.apache.ambari.server.controller.internal.HostStackVersionResourceProvider",
      "org.apache.ambari.server.controller.internal.InstanceResourceProvider",
      "org.apache.ambari.server.controller.internal.JavaRuntimeResourceProvider",
      "org.apache.ambari.server.controller.internal.JobResourceProvider",
      "org.apache.ambari.server.controller.internal.KerberosDescriptorResourceProvider",
      "org.apache.ambari.server.controller.internal.PermissionResourceProvider",
      "org.apache.ambari.server.controller.internal.QuickLinkArtifactResourceProvider",
      "org.apache.ambari.server.controller.internal.ReadOnlyResourceProvider",
      "org.apache.ambari.server.controller.internal.RecommendationResourceProvider",
      "org.apache.ambari.server.controller.internal.RepositoryResourceProvider",
      "org.apache.ambari.server.controller.internal.RequestResourceProvider",
      "org.apache.ambari.server.controller.internal.RequestScheduleResourceProvider",
      "org.apache.ambari.server.controller.internal.ServiceConfigVersionResourceProvider",
      "org.apache.ambari.server.controller.internal.StackArtifactResourceProvider",
      "org.apache.ambari.server.controller.internal.StackDependencyResourceProvider",
      "org.apache.ambari.server.controller.internal.StageResourceProvider",
      "org.apache.ambari.server.controller.internal.TargetClusterResourceProvider",
      "org.apache.ambari.server.controller.internal.TaskAttemptResourceProvider",
      "org.apache.ambari.server.controller.internal.TaskResourceProvider",
      "org.apache.ambari.server.controller.internal.ThemeArtifactResourceProvider",
      "org.apache.ambari.server.controller.internal.UpgradeGroupResourceProvider",
      "org.apache.ambari.server.controller.internal.UpgradeResourceProvider",
      "org.apache.ambari.server.controller.internal.UpgradeSummaryResourceProvider",
      "org.apache.ambari.server.controller.internal.ValidationResourceProvider",
      "org.apache.ambari.server.controller.internal.ViewPermissionResourceProvider",
      "org.apache.ambari.server.controller.internal.ViewVersionResourceProvider",
      "org.apache.ambari.server.controller.internal.WidgetLayoutResourceProvider",
      "org.apache.ambari.server.controller.internal.WidgetResourceProvider",
      "org.apache.ambari.server.controller.internal.WorkflowResourceProvider",
      "org.apache.ambari.server.controller.jdbc.JDBCResourceProvider",
      "org.apache.ambari.server.view.ViewExternalSubResourceProvider",
      "org.apache.ambari.server.view.ViewSubResourceProvider")));

  /**
   * Providers that create resources for any signed-in user on purpose: {@link AuthResourceProvider}
   * is the sign-in itself. This list may only shrink.
   */
  private static final Set<String> OPEN_CREATE = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
      "org.apache.ambari.server.controller.internal.AuthResourceProvider")));

  /**
   * Providers whose create acts with the Ranger administrator or KDC administrator credentials
   * Ambari holds, on the cluster named in the request.
   */
  private static final List<Class<? extends AbstractAuthorizedResourceProvider>> CREDENTIAL_PROVIDERS = Arrays.asList(
      RangerPluginResourceProvider.class,
      RangerPolicyResourceProvider.class,
      AdhocKeytabResourceProvider.class);

  @Test
  public void testNoNewProviderBypassesTheCreateAuthorizationCheck() throws Exception {
    Set<String> bypassing = new TreeSet<>();
    Set<String> overriding = new TreeSet<>();
    for (Class<?> provider : concreteResourceProviders()) {
      Class<?> declaring = createResourcesDeclaringClass(provider);
      if (declaring != AbstractAuthorizedResourceProvider.class) {
        overriding.add(declaring.getName());
        if (!OWN_CREATE_RESOURCES.contains(declaring.getName())) {
          bypassing.add(provider.getName() + " (createResources of " + declaring.getName() + ")");
        }
      }
    }

    assertTrue("These resource providers create resources without the role check of"
        + " AbstractAuthorizedResourceProvider. Implement createResourcesAuthorized and set the required create"
        + " authorizations instead of overriding createResources: " + bypassing, bypassing.isEmpty());

    // An entry that no longer overrides createResources would let a later override in that class through.
    Set<String> stale = new TreeSet<>(OWN_CREATE_RESOURCES);
    stale.removeAll(overriding);
    assertTrue("These providers no longer override createResources, remove them from OWN_CREATE_RESOURCES: " + stale,
        stale.isEmpty());
  }

  @Test
  public void testEveryCreatingProviderRequiresARole() throws Exception {
    Set<String> open = new TreeSet<>();
    for (Class<?> provider : concreteResourceProviders()) {
      // Providers that override createResources are covered by the test above.
      if (createResourcesDeclaringClass(provider) != AbstractAuthorizedResourceProvider.class) {
        continue;
      }
      List<Class<?>> chain = classesBelowTheAuthorizedBase(provider);
      // The base createResourcesAuthorized refuses every create, so there is nothing to guard.
      if (chain.stream().noneMatch(type -> declares(type, "createResourcesAuthorized", Request.class))) {
        continue;
      }
      boolean decidesItself = chain.stream().anyMatch(
          type -> declares(type, "isAuthorizedToCreateResources", Authentication.class, Request.class));
      boolean namesRoles = false;
      for (Class<?> type : chain) {
        namesRoles |= constructorSetsRequiredCreateAuthorizations(type);
      }
      if (!decidesItself && !namesRoles) {
        open.add(provider.getName());
      }
    }

    Set<String> unexpected = new TreeSet<>(open);
    unexpected.removeAll(OPEN_CREATE);
    assertTrue("These resource providers let any signed-in user create resources. Call"
        + " setRequiredCreateAuthorizations in the constructor, or override isAuthorizedToCreateResources: "
        + unexpected, unexpected.isEmpty());

    // An entry that now requires a role would let a later regression in that class through.
    Set<String> stale = new TreeSet<>(OPEN_CREATE);
    stale.removeAll(open);
    assertTrue("These providers now require a role to create, remove them from OPEN_CREATE: " + stale,
        stale.isEmpty());
  }

  @Test
  public void testCredentialProvidersCheckTheCallerBeforeCreating() throws Exception {
    for (Class<? extends AbstractAuthorizedResourceProvider> provider : CREDENTIAL_PROVIDERS) {
      String name = provider.getSimpleName();

      assertEquals(name + " must not override createResources, which holds the role check",
          AbstractAuthorizedResourceProvider.class, createResourcesDeclaringClass(provider));

      assertTrue(name + " must implement createResourcesAuthorized",
          declares(provider, "createResourcesAuthorized", Request.class));

      // With no required create authorization, the base class check lets any signed-in user in.
      // These providers act on the cluster named in the request, so they check that cluster's
      // administrators themselves.
      assertTrue(name + " must check the caller in isAuthorizedToCreateResources",
          declares(provider, "isAuthorizedToCreateResources", Authentication.class, Request.class));
    }
  }

  /**
   * Every concrete {@link ResourceProvider} that ships in the Ambari server, test doubles left out.
   */
  private static Set<Class<?>> concreteResourceProviders() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(ResourceProvider.class));

    Set<Class<?>> providers = new LinkedHashSet<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("org.apache.ambari.server")) {
      Class<?> type = Class.forName(definition.getBeanClassName(), false,
          ResourceProviderCreateAuthorizationTest.class.getClassLoader());
      if (!Modifier.isAbstract(type.getModifiers()) && !type.isInterface() && !isTestClass(type)) {
        providers.add(type);
      }
    }
    assertTrue("The class path scan found no Ambari resource provider",
        providers.contains(RangerPolicyResourceProvider.class));
    return providers;
  }

  private static boolean isTestClass(Class<?> type) {
    CodeSource source = type.getProtectionDomain().getCodeSource();
    URL location = source == null ? null : source.getLocation();
    return location != null && location.getPath().contains("test-classes");
  }

  private static Class<?> createResourcesDeclaringClass(Class<?> provider) throws NoSuchMethodException {
    return provider.getMethod("createResources", Request.class).getDeclaringClass();
  }

  /**
   * The provider and its superclasses up to, not including, {@link AbstractAuthorizedResourceProvider}.
   */
  private static List<Class<?>> classesBelowTheAuthorizedBase(Class<?> provider) {
    List<Class<?>> chain = new ArrayList<>();
    for (Class<?> type = provider; type != AbstractAuthorizedResourceProvider.class; type = type.getSuperclass()) {
      chain.add(type);
    }
    return chain;
  }

  /**
   * Reads the class file rather than building the provider, which would need its injected
   * dependencies, to find a constructor that calls {@code setRequiredCreateAuthorizations}.
   */
  private static boolean constructorSetsRequiredCreateAuthorizations(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream classFile = type.getClassLoader().getResourceAsStream(resource)) {
      assertTrue("Cannot read " + resource, classFile != null);
      boolean[] found = {false};
      new ClassReader(classFile).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
          if (!"<init>".equals(name)) {
            return null;
          }
          return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String methodDescriptor,
                                        boolean isInterface) {
              if ("setRequiredCreateAuthorizations".equals(method)) {
                found[0] = true;
              }
            }
          };
        }
      }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return found[0];
    }
  }

  private static boolean declares(Class<?> type, String method, Class<?>... parameterTypes) {
    try {
      type.getDeclaredMethod(method, parameterTypes);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }
}
