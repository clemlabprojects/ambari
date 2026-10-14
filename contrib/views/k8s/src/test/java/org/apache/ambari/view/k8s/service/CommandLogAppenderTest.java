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

import org.apache.ambari.view.ViewContext;
import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The command log of an operation captures what the view logs while it runs. That capture is a log4j 1.2 appender, and
 * the view ships reload4j as its only log4j 1.2 API, so these tests log through it and read the command log back.
 */
class CommandLogAppenderTest {

  private static final String VIEW_LOGGER = "org.apache.ambari.view.k8s";
  private static final String APPENDER_NAME = "kdps-command-log-appender";

  @TempDir
  Path workDir;

  private ViewContext ctx;
  private Appender displaced;

  @BeforeEach
  void setUp() {
    ctx = mock(ViewContext.class, RETURNS_DEEP_STUBS);
    // A view instance of its own, so this test reads a command log no other test writes to.
    doReturn("log-capture-" + UUID.randomUUID()).when(ctx).getInstanceName();
    doReturn(Map.of("k8s.view.working.dir", workDir.toString())).when(ctx).getProperties();
    // The appender is installed once per class loader; another test may have bound it to its own view instance.
    Logger viewLogger = Logger.getLogger(VIEW_LOGGER);
    displaced = viewLogger.getAppender(APPENDER_NAME);
    viewLogger.removeAppender(APPENDER_NAME);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(CommandLogAppender.COMMAND_MDC_KEY);
    Logger viewLogger = Logger.getLogger(VIEW_LOGGER);
    viewLogger.removeAppender(APPENDER_NAME);
    if (displaced != null) {
      viewLogger.addAppender(displaced);
    }
  }

  @Test
  void capturesWhatTheViewLogsOnACommandThread() throws Exception {
    CommandLogAppender.install(ctx);
    assertNotNull(Logger.getLogger(VIEW_LOGGER).getAppender(APPENDER_NAME), "the appender must be installed");

    MDC.put(CommandLogAppender.COMMAND_MDC_KEY, "cmd-1");
    Logger.getLogger(VIEW_LOGGER + ".service.SomeStep").info("Installing the chart");
    Logger.getLogger(VIEW_LOGGER + ".service.SomeStep").debug("too detailed for the operator");
    Logger.getLogger(VIEW_LOGGER + ".service.SomeStep").warn("Retrying", new IllegalStateException("boom"));

    String log = awaitLog("cmd-1", "IllegalStateException");
    assertTrue(log.contains("[INFO] SomeStep: Installing the chart"), log);
    assertTrue(log.contains("[WARN] SomeStep: Retrying"), log);
    assertTrue(log.contains("java.lang.IllegalStateException: boom"), log);
    assertFalse(log.contains("too detailed"), "DEBUG stays out of the command log");
  }

  @Test
  void ignoresThreadsThatRunNoCommand() throws Exception {
    CommandLogAppender.install(ctx);

    Logger.getLogger(VIEW_LOGGER + ".service.Idle").info("background refresh");
    MDC.put(CommandLogAppender.COMMAND_MDC_KEY, "cmd-2");
    Logger.getLogger(VIEW_LOGGER + ".service.SomeStep").info("marker");

    String log = awaitLog("cmd-2", "marker");
    assertFalse(log.contains("background refresh"), log);
  }

  @Test
  void installingTwiceAttachesOneAppender() {
    CommandLogAppender.install(ctx);
    Appender first = Logger.getLogger(VIEW_LOGGER).getAppender(APPENDER_NAME);
    CommandLogAppender.install(ctx);

    assertSame(first, Logger.getLogger(VIEW_LOGGER).getAppender(APPENDER_NAME));
  }

  /** The command log is written by a background thread; waits until it holds {@code expected}. */
  private String awaitLog(String commandId, String expected) throws InterruptedException {
    CommandLogService logs = CommandLogService.get(ctx);
    String content = "";
    for (int i = 0; i < 100 && !content.contains(expected); i++) {
      content = logs.read(commandId, 0, 64 * 1024).content;
      if (!content.contains(expected)) {
        Thread.sleep(50);
      }
    }
    assertTrue(content.contains(expected), "the command log never received '" + expected + "': " + content);
    return content;
  }
}
