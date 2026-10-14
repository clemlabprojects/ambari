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

package org.apache.ambari.view.k8s.service.helm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A helm CLI command (rollback, history) run by an operation that is canceled meanwhile: the cancel interrupts the
 * thread but not the process, so the command's real outcome is reported and the cancel is left for the caller.
 */
class HelmClientDefaultCliTest {

    @BeforeEach
    void needsAShell() {
        assumeTrue(Files.isExecutable(Paths.get("/bin/sh")), "needs /bin/sh");
    }

    /** Prints its output, closes it, then goes on running: its output is read before it has exited. */
    private static List<String> commandStillRunningAfterItsOutput(String output, int exitCode) {
        return List.of("/bin/sh", "-c", "echo " + output + "; exec >&- 2>&-; sleep 0.3; exit " + exitCode);
    }

    @Test
    void aCommandThatSucceedsIsNotReportedAsFailedWhenTheOperationIsCanceled() {
        String out;
        Thread.currentThread().interrupt(); // what canceling the operation does to the thread running the command
        try {
            out = HelmClientDefault.runHelmCli(commandStillRunningAfterItsOutput("rolled-back", 0), false);
            assertTrue(Thread.currentThread().isInterrupted(), "the cancel is left for the caller to see");
        } finally {
            Thread.interrupted();
        }
        assertEquals("rolled-back", out.trim());
    }

    @Test
    void aCommandThatFailsIsStillReportedWithItsExitCodeWhenTheOperationIsCanceled() {
        RuntimeException e;
        Thread.currentThread().interrupt();
        try {
            e = assertThrows(RuntimeException.class,
                    () -> HelmClientDefault.runHelmCli(commandStillRunningAfterItsOutput("timed-out", 3), false));
            assertTrue(Thread.currentThread().isInterrupted(), "the cancel is left for the caller to see");
        } finally {
            Thread.interrupted();
        }
        assertTrue(e.getMessage().contains("helm command failed (3)"), e.getMessage());
        assertTrue(e.getMessage().contains("timed-out"), e.getMessage());
    }
}
