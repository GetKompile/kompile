/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.gateway.core.service;

import java.util.List;
import java.util.Set;

/**
 * Service for managing command execution permissions.
 * Implements an allowlist pattern with approval gates.
 */
public interface PermissionService {

    /**
     * Check if a command is allowed to execute.
     *
     * @param command The command to check
     * @return "allowed", "denied", or "needs_approval"
     */
    String checkCommandSafety(String command);

    /**
     * Check if a command is in the allowlist.
     *
     * @param command The command to check
     * @return true if the command is allowed
     */
    boolean isAllowed(String command);

    /**
     * Check if a command is explicitly denied.
     *
     * @param command The command to check
     * @return true if the command is denied
     */
    boolean isDenied(String command);

    /**
     * Add a command to the allowlist.
     *
     * @param command The command pattern to allow
     */
    void allowCommand(String command);

    /**
     * Add a command prefix to the allowlist (e.g., "git*" for all git commands).
     *
     * @param prefix The command prefix to allow
     */
    void allowCommandPrefix(String prefix);

    /**
     * Remove a command from the allowlist.
     *
     * @param command The command to remove
     */
    void denyCommand(String command);

    /**
     * Approve a previously pending command.
     *
     * @param command The command to approve
     */
    void approveCommand(String command);

    /**
     * Reject a previously pending command.
     *
     * @param command The command to reject
     */
    void rejectCommand(String command);

    /**
     * Get all allowed commands.
     *
     * @return Set of allowed command patterns
     */
    Set<String> getAllowedCommands();

    /**
     * Get all denied commands.
     *
     * @return Set of denied command patterns
     */
    Set<String> getDeniedCommands();

    /**
     * Get commands pending approval.
     *
     * @return List of commands awaiting approval
     */
    List<String> getPendingCommands();

    /**
     * Check if a path is allowed for file operations.
     *
     * @param path The path to check
     * @return true if the path is accessible
     */
    boolean isPathAllowed(String path);

    /**
     * Add a path to the allowed paths list.
     *
     * @param path The path prefix to allow
     */
    void allowPath(String path);
}
