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

import java.io.File;
import java.util.Arrays;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.slf4j.LoggerFactory;

/** Exercises the generated configuration with a real Logback runtime. */
public class LogbackProbe {
  public static void main(String[] args) throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.reset();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    configurator.doConfigure(args[0]);
    ch.qos.logback.classic.Logger root = context.getLogger("ROOT");
    boolean server = args.length == 2;
    if ((root.getAppender("ROLLINGFILE") != null) != server) {
      throw new AssertionError("Wrong appender selection for " + args[0]);
    }
    if (server) {
      char[] chars = new char[1024];
      Arrays.fill(chars, 'x');
      String message = new String(chars);
      for (int i = 0; i < 1500; i++) {
        root.info(message);
      }
      // Logback 1.5 throttles size checks for up to one minute by default.
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(65);
      while (!new File(args[1] + ".1").isFile() && System.nanoTime() < deadline) {
        Thread.sleep(250);
        root.info("WAITING_FOR_ROLLOVER");
      }
      root.info("AFTER_ROLLOVER");
    } else {
      root.info("CLIENT_CONSOLE_OK");
    }
    context.stop();
    for (Status status : context.getStatusManager().getCopyOfStatusList()) {
      if (status.getEffectiveLevel() >= Status.ERROR) {
        throw new AssertionError(status.toString());
      }
    }
    if (server && (!new File(args[1]).isFile() || !new File(args[1] + ".1").isFile())) {
      throw new AssertionError("The daemon log and its rotated backup must exist");
    }
    System.out.println("LOGBACK_PROBE_OK");
  }
}
