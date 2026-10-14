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
package org.apache.ambari.view.k8s.service.deployment;

import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * A push the Git server refused: the branch moved on the server since it was fetched, the branch is protected, or a
 * server hook turned the change down. Nothing changed on the server.
 *
 * <p>JGit reports such a refusal in the push result rather than throwing it, so {@link GitClient#push} checks every
 * update and throws this. Pushing the same commit again gets the same answer: it is never retried as it is
 * ({@link GitClient#isRetryable}). Only a branch that moved on the server is worth a new attempt, with the change made
 * again on top of it: a refusal that {@link #mayBeBranchMoved may come from that}, confirmed by the server's branch
 * once fetched ({@link GitClient#branchMovedOnServer}).
 */
public class GitPushRejectedException extends IOException {
    private static final long serialVersionUID = 1L;

    private final RemoteRefUpdate.Status status;
    private final String branch;

    /**
     * @param repository     the repository, without credentials
     * @param branch         the branch the push updated
     * @param status         the status the server gave the update
     * @param reason         the server's reason for that update, may be {@code null}
     * @param serverMessages what the server printed while it handled the push (its hooks' output), may be
     *                       {@code null}
     */
    public GitPushRejectedException(String repository, String branch, RemoteRefUpdate.Status status, String reason,
                                    String serverMessages) {
        super(message(repository, branch, status, reason, serverMessages));
        this.status = status;
        this.branch = branch;
    }

    /** The status the server gave the update. */
    public RemoteRefUpdate.Status getStatus() {
        return status;
    }

    /** The branch the push updated. */
    public String getBranch() {
        return branch;
    }

    /**
     * Whether the refusal may come from the branch moving on the server: another change landed there before this push
     * (refused as not a fast-forward) or while the server received it. Servers word the second case differently (JGit
     * "failed to lock", C git "failed to update ref", with the details only in its messages), and use the same words
     * for refusals that a new attempt cannot fix: a branch name that clashes with another branch, a lock left behind on
     * the server. So the reason is not read here: whether the branch did move is told by the server's branch once
     * fetched ({@link GitClient#branchMovedOnServer}).
     */
    public boolean mayBeBranchMoved() {
        return status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
                || status == RemoteRefUpdate.Status.REJECTED_OTHER_REASON;
    }

    private static String message(String repository, String branch, RemoteRefUpdate.Status status, String reason,
                                  String serverMessages) {
        StringBuilder message = new StringBuilder("Git refused the push to branch ").append(branch)
                .append(" of ").append(repository).append(": ").append(explanation(status));
        if (reason != null && !reason.isBlank()) {
            message.append(" (").append(reason.strip()).append(')');
        }
        String said = serverMessages == null ? "" : serverMessages.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(" "));
        if (!said.isEmpty()) {
            message.append(". The Git server said: ").append(said);
        }
        if (message.charAt(message.length() - 1) != '.') {
            message.append('.');
        }
        return message.toString();
    }

    private static String explanation(RemoteRefUpdate.Status status) {
        if (status == null) {
            return "the server did not confirm the update";
        }
        return switch (status) {
            case REJECTED_NONFASTFORWARD ->
                    "the branch has changes on the server that this push does not include (not a fast-forward)";
            case REJECTED_REMOTE_CHANGED -> "the branch changed on the server during the push";
            case REJECTED_NODELETE -> "the server does not allow deleting the branch";
            case NON_EXISTING -> "the branch does not exist on the server";
            case REJECTED_OTHER_REASON -> "the server rejected the change";
            default -> "the server did not confirm the update";
        };
    }
}
