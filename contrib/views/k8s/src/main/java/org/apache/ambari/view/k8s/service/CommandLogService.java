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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Lightweight per-command log sink with size-based rotation.
 * Logs are stored under workDir/logs/commands/<id>.log
 */
public class CommandLogService {
    private static final Logger LOG = LoggerFactory.getLogger(CommandLogService.class);
    private static final long MAX_LOG_BYTES = 2 * 1024 * 1024; // 2 MB
    private static final int MAX_ROTATIONS = 2;

    private final Path logDir;
    private final java.util.concurrent.BlockingQueue<LogEvent> logQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final Thread logWriterThread;

    public CommandLogService(ViewContext ctx) {
        PathConfig pathConfig = new PathConfig(ctx);
        this.logDir = pathConfig.workDir().resolve("logs").resolve("commands");
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            LOG.warn("Failed to create command log directory {}: {}", logDir, e.toString());
        }
        logWriterThread = new Thread(this::drainLoop, "command-log-writer");
        logWriterThread.setDaemon(true);
        logWriterThread.start();
    }

    public void append(String commandId, String message) {
        if (commandId == null || commandId.isBlank() || message == null) return;
        try {
            logQueue.offer(new LogEvent(commandId, message));
        } catch (Exception e) {
            LOG.debug("Failed to enqueue log for {}: {}", commandId, e.toString());
        }
    }

    private void rotateIfNeeded(Path file) {
        try {
            if (!Files.exists(file)) return;
            long size = Files.size(file);
            if (size < MAX_LOG_BYTES) return;

            // Rotate: file -> file.1, file.1 -> file.2 (keep up to MAX_ROTATIONS)
            for (int i = MAX_ROTATIONS; i >= 1; i--) {
                Path src = i == 1 ? file : logDir.resolve(file.getFileName().toString() + "." + (i - 1));
                Path dst = logDir.resolve(file.getFileName().toString() + "." + i);
                if (Files.exists(src)) {
                    try {
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed log rotation for {}: {}", file, e.toString());
        }
    }

    public LogChunk read(String commandId, long offset, int limit) {
        if (commandId == null || commandId.isBlank()) return new LogChunk("", 0, true, 0);
        try {
            Path file = logDir.resolve(commandId + ".log");
            if (!Files.exists(file)) return new LogChunk("", 0, true, 0);
            long size = Files.size(file);
            long start = Math.max(0, Math.min(offset, size));
            int readLen = (int)Math.min(limit, size - start);
            byte[] buf = new byte[readLen];
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                channel.position(start);
                int n = channel.read(java.nio.ByteBuffer.wrap(buf));
                if (n < 0) n = 0;
                String content = new String(buf, 0, n, StandardCharsets.UTF_8);
                long next = start + n;
                boolean eof = next >= size;
                return new LogChunk(content, next, eof, size);
            }
        } catch (Exception e) {
            LOG.debug("Failed to read log for {}: {}", commandId, e.toString());
            return new LogChunk("", 0, true, 0);
        }
    }

    private void drainLoop() {
        while (true) {
            try {
                LogEvent evt = logQueue.take();
                writeEvent(evt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                LOG.debug("Log writer encountered an error: {}", ex.toString());
            }
        }
    }

    private void writeEvent(LogEvent evt) {
        try {
            Path file = logDir.resolve(evt.commandId + ".log");
            rotateIfNeeded(file);
            String line = String.format("%s %s%n", Instant.now().toString(), evt.message);
            Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.debug("Failed to write log event for {}: {}", evt.commandId, e.toString());
        }
    }

    private static class LogEvent {
        final String commandId;
        final String message;
        LogEvent(String commandId, String message) {
            this.commandId = commandId;
            this.message = message;
        }
    }

    public static class LogChunk {
        public final String content;
        public final long nextOffset;
        public final boolean eof;
        public final long size;

        public LogChunk(String content, long nextOffset, boolean eof, long size) {
            this.content = content;
            this.nextOffset = nextOffset;
            this.eof = eof;
            this.size = size;
        }
    }
}
