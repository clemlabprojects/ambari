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
package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.ViewContext;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

/**
 * Streams the view's own server-side logging into the per-command log so the operator sees, in the
 * Background Operations modal, everything Ambari logged while running a command — not just the
 * hand-picked {@code appendCommandLog} lines (mirrors how ODP operation logs surface script output).
 *
 * <p>How it is scoped and made safe:
 * <ul>
 *   <li>Attached ONLY to the view's own logger namespace ({@code org.apache.ambari.view.k8s}), and
 *       the view ships its own log4j 1.2 in an isolated Ambari view classloader — so this never
 *       touches ambari-server's own logging or other views.</li>
 *   <li>Captures an event ONLY when the executing thread has tagged itself with the command id via
 *       {@code MDC[COMMAND_MDC_KEY]} (see {@link #COMMAND_MDC_KEY}); untagged threads are ignored, so
 *       background/idle logging is never captured.</li>
 *   <li>A per-thread re-entrancy guard prevents any logging done <em>inside</em> the append path from
 *       recursing back into the appender.</li>
 *   <li>Everything is best-effort: any failure is swallowed so logging can never break a command.</li>
 * </ul>
 */
public class CommandLogAppender extends AppenderSkeleton {

    /** MDC key a worker thread sets (to the root command id) so its logs are captured for that command. */
    public static final String COMMAND_MDC_KEY = "kdpsCommandId";

    private static final String VIEW_LOGGER = "org.apache.ambari.view.k8s";
    private static final String APPENDER_NAME = "kdps-command-log-appender";
    private static final ThreadLocal<Boolean> IN_APPEND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final CommandLogService logService;

    private CommandLogAppender(CommandLogService logService) {
        this.logService = logService;
        setName(APPENDER_NAME);
        // Capture INFO and above by default — enough to explain a command without flooding the log
        // with framework DEBUG noise. Explicit appendCommandLog() lines are unaffected.
        setThreshold(Level.INFO);
    }

    /**
     * Idempotently attach the appender to the view logger. Safe to call on every CommandService
     * construction — a second call is a no-op.
     */
    public static synchronized void install(ViewContext ctx) {
        try {
            Logger viewLogger = Logger.getLogger(VIEW_LOGGER);
            if (viewLogger.getAppender(APPENDER_NAME) != null) return; // already installed
            viewLogger.addAppender(new CommandLogAppender(CommandLogService.get(ctx)));
        } catch (Throwable t) {
            // Never let logging setup break the view.
            org.slf4j.LoggerFactory.getLogger(CommandLogAppender.class)
                    .warn("Could not install command-log appender: {}", t.toString());
        }
    }

    @Override
    protected void append(LoggingEvent event) {
        if (Boolean.TRUE.equals(IN_APPEND.get())) return; // re-entrancy guard
        Object cmdId = event.getMDC(COMMAND_MDC_KEY);
        if (cmdId == null) return; // only capture threads tagged with a command id
        IN_APPEND.set(Boolean.TRUE);
        try {
            StringBuilder line = new StringBuilder();
            line.append('[').append(event.getLevel()).append("] ");
            String logger = event.getLoggerName();
            int dot = logger.lastIndexOf('.');
            line.append(dot >= 0 ? logger.substring(dot + 1) : logger).append(": ");
            line.append(event.getRenderedMessage());
            ThrowableInformation ti = event.getThrowableInformation();
            if (ti != null && ti.getThrowableStrRep() != null) {
                String[] rep = ti.getThrowableStrRep();
                for (int i = 0; i < rep.length && i < 12; i++) { // cap stack depth in the UI log
                    line.append('\n').append(rep[i]);
                }
            }
            logService.append(String.valueOf(cmdId), line.toString());
        } catch (Throwable ignore) {
            // best effort — logging must never throw into the caller
        } finally {
            IN_APPEND.set(Boolean.FALSE);
        }
    }

    @Override
    public void close() { /* no resources to release */ }

    @Override
    public boolean requiresLayout() { return false; }
}
