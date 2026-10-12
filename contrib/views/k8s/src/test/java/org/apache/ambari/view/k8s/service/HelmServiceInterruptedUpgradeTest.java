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

package org.apache.ambari.view.k8s.service;

import com.marcnuri.helm.Release;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.service.helm.HelmClient;
import org.apache.ambari.view.k8s.service.helm.HelmOperationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An upgrade Helm refuses because of the release's state is recovered only as far as the release's history makes
 * safe. A release that has served is never uninstalled: an upgrade cut short (server restart, cancel) is rolled
 * back to the revision still deployed and retried once; a first install that never completed is reinstalled; a
 * history that cannot be read changes nothing. A dry run never recovers.
 */
class HelmServiceInterruptedUpgradeTest {

  /** Helm 3.16's errPending, as helm-java raises it. */
  private static final String ERR_PENDING = "UPGRADE FAILED: another operation (install/upgrade/rollback) is in progress";
  private static final String NS = "analytics";

  private final ExecutorService pool = Executors.newCachedThreadPool();
  private HelmClient helm;
  private HelmService service;
  private final List<String> operationLog = new ArrayList<>();
  private String rel;

  @BeforeEach
  void setUp() {
    ViewContext ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    when(ctx.getProperties()).thenReturn(Map.of("k8s.view.working.dir", System.getProperty("java.io.tmpdir") + "/k8s-ut"));
    helm = mock(HelmClient.class);
    service = new HelmService(ctx, helm, () -> mock(NamespaceScope.class));
    // A release of its own per test: the in-flight registry is shared by the whole JVM.
    rel = "superset-" + Long.toHexString(System.nanoTime());
    Release existing = mock(Release.class);
    when(existing.getName()).thenReturn(rel);
    when(helm.list(NS, "KC", false)).thenReturn(List.of(existing));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private static Map<String, Object> rev(int revision, String status) {
    Map<String, Object> m = new HashMap<>();
    m.put("revision", revision);
    m.put("status", status);
    return m;
  }

  @SafeVarargs
  private void history(Map<String, Object>... revisions) {
    when(helm.history(rel, NS, "KC")).thenReturn(List.of(revisions));
  }

  private Release upgradeCall() {
    return helm.upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  private Release installCall() {
    return helm.install(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  private Release deploy(boolean dryRun) {
    return service.deployOrUpgrade("superset", rel, NS, Map.of("replicaCount", 1), Map.of(), "KC", null, null,
        dryRun, operationLog::add);
  }

  private void verifyNothingRemovedOrReinstalled() {
    verify(helm, never()).uninstall(anyString(), anyString(), anyString());
    verify(helm, never()).install(anyString(), any(), anyBoolean(), anyString(), anyString(), any(Path.class),
        anyString(), ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
        anyBoolean());
  }

  @Test
  void anInterruptedUpgradeIsRolledBackAndRetriedNeverUninstalled() {
    Release upgraded = mock(Release.class);
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING)).thenReturn(upgraded);
    history(rev(1, "superseded"), rev(2, "deployed"), rev(3, "pending-upgrade"));

    assertSame(upgraded, deploy(false));

    InOrder order = inOrder(helm);
    order.verify(helm).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    order.verify(helm).history(rel, NS, "KC");
    order.verify(helm).rollback(rel, NS, 2, "KC");
    order.verify(helm).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
    assertTrue(operationLog.stream().anyMatch(l -> l.contains("rolling back to revision 2")), operationLog.toString());
    assertTrue(operationLog.stream().anyMatch(l -> l.contains("Rolled back release " + NS + "/" + rel + " to revision 2")),
        operationLog.toString());
  }

  @Test
  void anInterruptedUpgradeFailsTheDryRunAndChangesNothing() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(4, "deployed"), rev(5, "pending-upgrade"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(true));

    assertTrue(e.getMessage().contains("interrupted upgrade (revision 5 pending-upgrade)"), e.getMessage());
    assertTrue(e.getMessage().contains("roll back to revision 4 or wait"), e.getMessage());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void anInterruptedUpgradeIsNotRolledBackByACallerWithNoOperationLog() {
    // A catalog saved to the release, a REST deploy or the monitoring bootstrap: nowhere to record a rollback.
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(4, "deployed"), rev(5, "pending-upgrade"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.deployOrUpgrade("trino", rel,
        NS, Map.of("catalogs", Map.of()), Map.of(), "KC", null, null, 600, true, false, false));

    assertTrue(e.getMessage().contains("interrupted upgrade (revision 5 pending-upgrade)"), e.getMessage());
    assertTrue(e.getMessage().contains("roll back to revision 4 or wait"), e.getMessage());
    assertTrue(e.getMessage().contains("Nothing was changed"), e.getMessage());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verify(helm, times(1)).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
    assertFalse(HelmOperationRegistry.isRunning(NS, rel), "the release is free once the call returns");
  }

  @Test
  void anUpgradeThatFailsAgainAfterTheRollbackIsRetriedOnlyOnce() {
    when(upgradeCall())
        .thenThrow(new IllegalStateException(ERR_PENDING))
        .thenThrow(new IllegalStateException("UPGRADE FAILED: context deadline exceeded"));
    history(rev(7, "deployed"), rev(8, "pending-rollback"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(false));

    assertTrue(e.getMessage().contains("failed again after rolling back to revision 7"), e.getMessage());
    verify(helm, times(1)).rollback(rel, NS, 7, "KC");
    verify(helm, times(2)).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aRollbackThatFailsStopsThereWithoutRetryingOrUninstalling() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(2, "deployed"), rev(3, "pending-upgrade"));
    doThrow(new RuntimeException("helm command failed (1): timed out waiting for the condition"))
        .when(helm).rollback(rel, NS, 2, "KC");

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(false));

    assertTrue(e.getMessage().contains("back to revision 2 failed, so the upgrade was not retried"), e.getMessage());
    verify(helm, times(1)).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aFirstInstallThatNeverCompletedIsUninstalledThenInstalled() {
    Release installed = mock(Release.class);
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    when(installCall()).thenReturn(installed);
    history(rev(1, "pending-install"));

    assertSame(installed, deploy(false));

    InOrder order = inOrder(helm);
    order.verify(helm).history(rel, NS, "KC");
    order.verify(helm).uninstall(rel, NS, "KC");
    order.verify(helm).install(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), eq(false));
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    assertTrue(operationLog.stream().anyMatch(l -> l.contains("has never been deployed")), operationLog.toString());
  }

  @Test
  void aFailedFirstInstallWithNoDeployedRevisionIsReinstalled() {
    Release installed = mock(Release.class);
    when(upgradeCall()).thenThrow(new IllegalStateException("UPGRADE FAILED: \"" + rel + "\" has no deployed releases"));
    when(installCall()).thenReturn(installed);
    history(rev(1, "failed"));

    assertSame(installed, deploy(false));

    verify(helm).uninstall(rel, NS, "KC");
  }

  @Test
  void aFailedFirstInstallIsValidatedInADryRunAsTheInstallTheRealStepWillRun() {
    when(upgradeCall()).thenThrow(new IllegalStateException("UPGRADE FAILED: \"" + rel + "\" has no deployed releases"));
    when(installCall()).thenReturn(mock(Release.class));
    history(rev(1, "failed"));

    deploy(true);

    verify(helm).install(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), eq(true));
    verify(helm, never()).uninstall(anyString(), anyString(), anyString());
  }

  @Test
  void aPendingFirstInstallFailsTheDryRun() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(1, "pending-install"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(true));

    assertTrue(e.getMessage().contains("still pending (revision 1 pending-install)"), e.getMessage());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aHistoryThatCannotBeReadChangesNothing() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    when(helm.history(rel, NS, "KC")).thenThrow(new RuntimeException("helm command failed (1): Kubernetes cluster unreachable"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(false));

    assertTrue(e.getMessage().contains("history could not be read"), e.getMessage());
    assertTrue(e.getMessage().contains("cluster unreachable"), e.getMessage());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void otherFailuresThatMentionPendingAreNotRecovered() {
    IllegalStateException helmError = new IllegalStateException(
        "UPGRADE FAILED: timed out waiting for the condition: 2 pods pending, rollout in progress");
    when(upgradeCall()).thenThrow(helmError);

    assertSame(helmError, assertThrows(IllegalStateException.class, () -> deploy(false)));

    verify(helm, never()).history(anyString(), anyString(), anyString());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aReleaseThatServedIsNeverUninstalledByKdpsOnItsOwn() {
    when(upgradeCall()).thenThrow(new IllegalStateException("UPGRADE FAILED: \"" + rel + "\" has no deployed releases"));
    history(rev(1, "superseded"), rev(2, "failed"), rev(3, "uninstalled"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(false));

    assertTrue(e.getMessage().contains("nothing was changed"), e.getMessage());
    assertTrue(e.getMessage().contains("Releases page"), e.getMessage());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aCanceledUpgradeStartsNoRecovery() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(2, "deployed"), rev(3, "pending-upgrade"));

    Thread.currentThread().interrupt(); // what cancel(true) does to the Helm task's thread
    try {
      IllegalStateException e = assertThrows(IllegalStateException.class, () -> deploy(false));
      assertTrue(e.getMessage().contains("was canceled"), e.getMessage());
    } finally {
      Thread.interrupted();
    }
    verify(helm, never()).history(anyString(), anyString(), anyString());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aCancelDuringTheRollbackStopsTheUpgradeFromBeingRetried() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING)).thenReturn(mock(Release.class));
    history(rev(2, "deployed"), rev(3, "pending-upgrade"));
    // The operator cancels while the rollback waits for the release: the rollback itself runs to its end.
    doAnswer(invocation -> {
      Thread.currentThread().interrupt();
      return null;
    }).when(helm).rollback(rel, NS, 2, "KC");

    IllegalStateException e;
    try {
      e = assertThrows(IllegalStateException.class, () -> deploy(false));
      assertTrue(Thread.currentThread().isInterrupted(), "the cancel is left for the caller to see");
    } finally {
      Thread.interrupted();
    }

    assertTrue(e.getMessage().contains("Rolled back release " + NS + "/" + rel + " to revision 2"), e.getMessage());
    assertTrue(e.getMessage().contains("the upgrade was not retried because the operation was canceled"), e.getMessage());
    verify(helm, times(1)).rollback(rel, NS, 2, "KC");
    verify(helm, times(1)).upgrade(anyString(), any(), anyBoolean(), eq(rel), eq(NS), any(Path.class), eq("KC"),
        ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
    assertTrue(operationLog.stream().anyMatch(l -> l.contains("not retried because the operation was canceled")),
        operationLog.toString());
    assertFalse(operationLog.stream().anyMatch(l -> l.contains("retrying the upgrade.")), operationLog.toString());
  }

  @Test
  void aCancelDuringTheUninstallOfANeverDeployedReleaseStopsTheReinstall() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(1, "pending-install"));
    doAnswer(invocation -> {
      Thread.currentThread().interrupt();
      return null;
    }).when(helm).uninstall(rel, NS, "KC");

    IllegalStateException e;
    try {
      e = assertThrows(IllegalStateException.class, () -> deploy(false));
    } finally {
      Thread.interrupted();
    }

    assertTrue(e.getMessage().contains("not installed again because the operation was canceled"), e.getMessage());
    verify(helm).uninstall(rel, NS, "KC");
    verify(helm, never()).install(anyString(), any(), anyBoolean(), anyString(), anyString(), any(Path.class),
        anyString(), ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
        anyBoolean());
    assertTrue(operationLog.stream().anyMatch(l -> l.contains("Uninstalled the never-deployed release")),
        operationLog.toString());
  }

  @Test
  void aCancelWhileTheHistoryIsReadStartsNoRollback() {
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    when(helm.history(rel, NS, "KC")).thenAnswer(invocation -> {
      Thread.currentThread().interrupt();
      return List.of(rev(2, "deployed"), rev(3, "pending-upgrade"));
    });

    IllegalStateException e;
    try {
      e = assertThrows(IllegalStateException.class, () -> deploy(false));
    } finally {
      Thread.interrupted();
    }

    assertTrue(e.getMessage().contains("was canceled; nothing was rolled back or uninstalled"), e.getMessage());
    verify(helm, never()).rollback(anyString(), anyString(), anyInt(), anyString());
    verifyNothingRemovedOrReinstalled();
  }

  @Test
  void aSecondDeployWhileTheFirstIsInFlightIsRefused() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch helmReturns = new CountDownLatch(1);
    Future<?> first = HelmOperationRegistry.submit(NS, rel, pool, () -> {
      running.countDown();
      return helmReturns.await(30, TimeUnit.SECONDS);
    });
    assertTrue(running.await(5, TimeUnit.SECONDS));
    try {
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> deploy(false));
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> deploy(true),
          "a validation against a release about to change is refused too");
      verifyNoInteractions(helm);
    } finally {
      helmReturns.countDown();
      first.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void aRollbackOrUninstallIsRefusedWhileAnOperationOnTheReleaseIsRunning() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch helmReturns = new CountDownLatch(1);
    Future<?> upgrade = HelmOperationRegistry.submit(NS, rel, pool, () -> {
      running.countDown();
      return helmReturns.await(30, TimeUnit.SECONDS);
    });
    assertTrue(running.await(5, TimeUnit.SECONDS));
    try {
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> service.rollback(NS, rel, 2, "KC"));
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> service.uninstall(NS, rel, "KC"));
      verifyNoInteractions(helm);
    } finally {
      helmReturns.countDown();
      upgrade.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void anUpgradeWhileAnOperatorRollbackRunsIsRefusedInsteadOfRollingBackASecondTime() throws Exception {
    CountDownLatch rollingBack = new CountDownLatch(1);
    CountDownLatch helmReturns = new CountDownLatch(1);
    // The rollback waits for the release, its newest revision pending-rollback all along.
    doAnswer(invocation -> {
      rollingBack.countDown();
      helmReturns.await(30, TimeUnit.SECONDS);
      return null;
    }).when(helm).rollback(rel, NS, 2, "KC");
    when(upgradeCall()).thenThrow(new IllegalStateException(ERR_PENDING));
    history(rev(2, "deployed"), rev(3, "superseded"), rev(4, "pending-rollback"));
    Future<?> operatorRollback = pool.submit(() -> service.rollback(NS, rel, 2, "KC"));
    assertTrue(rollingBack.await(5, TimeUnit.SECONDS));
    try {
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> deploy(false));
      assertThrows(HelmOperationRegistry.ReleaseBusyException.class, () -> deploy(true));
      assertTrue(HelmOperationRegistry.isRunning(NS, rel), "the release stays held while the rollback runs");
    } finally {
      helmReturns.countDown();
      operatorRollback.get(5, TimeUnit.SECONDS);
    }
    verify(helm, times(1)).rollback(anyString(), anyString(), anyInt(), anyString());
    verify(helm, never()).upgrade(anyString(), any(), anyBoolean(), anyString(), anyString(), any(Path.class),
        anyString(), ArgumentMatchers.<Map<String, Object>>any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    verifyNothingRemovedOrReinstalled();
    assertFalse(HelmOperationRegistry.isRunning(NS, rel), "the release is free once the rollback returns");
  }
}
