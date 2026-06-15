/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Context passed to every tool execution. Contains session info,
 * permission checking, working directory, and cancellation signal.
 */
public class ToolContext {
    private final String sessionId;
    private final AgentConfig agent;
    private final PermissionService permissionService;
    private final Path workingDirectory;
    private final AtomicBoolean aborted;
    private final ToolRegistry toolRegistry;
    private volatile Consumer<String> outputConsumer;
    private volatile boolean autoApproveAll = false;

    public ToolContext(String sessionId, AgentConfig agent,
                       PermissionService permissionService,
                       Path workingDirectory, ToolRegistry toolRegistry) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.permissionService = permissionService;
        this.workingDirectory = workingDirectory;
        this.aborted = new AtomicBoolean(false);
        this.toolRegistry = toolRegistry;
    }

    public String getSessionId() { return sessionId; }
    public AgentConfig getAgent() { return agent; }
    public PermissionService getPermissionService() { return permissionService; }
    public Path getWorkingDirectory() { return workingDirectory; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    public boolean isAborted() { return aborted.get(); }
    public void abort() { aborted.set(true); }
    public AtomicBoolean getAbortSignal() { return aborted; }

    /**
     * Returns the output consumer for streaming progress to the caller, or null if not set.
     */
    public Consumer<String> getOutputConsumer() { return outputConsumer; }

    /**
     * Set a consumer that receives progress/output lines from tool execution.
     */
    public void setOutputConsumer(Consumer<String> outputConsumer) {
        this.outputConsumer = outputConsumer;
    }

    /**
     * Returns true if all permission prompts should be automatically approved.
     */
    public boolean isAutoApproveAll() { return autoApproveAll; }

    /**
     * Set whether to auto-approve all permission requests without prompting.
     */
    public void setAutoApproveAll(boolean autoApproveAll) {
        this.autoApproveAll = autoApproveAll;
    }

    /**
     * Check permission for a tool action. Throws ToolExecutionException
     * if permission is denied.
     */
    public void checkPermission(String permissionKey, String description) throws ToolExecutionException {
        PermissionService.PermissionResult result = permissionService.check(agent, permissionKey, description);
        if (result == PermissionService.PermissionResult.DENIED) {
            throw new ToolExecutionException("Permission denied: " + permissionKey + " - " + description, true);
        }
    }

    /**
     * Resolve a path relative to the working directory.
     * Returns absolute path, ensuring it doesn't escape the working directory
     * without explicit permission.
     */
    public Path resolvePath(String path) throws ToolExecutionException {
        Path resolved = workingDirectory.resolve(path).normalize();
        if (!resolved.startsWith(workingDirectory)) {
            // External directory access - check permission
            checkPermission("external_directory",
                    "Access path outside working directory: " + resolved);
        }
        return resolved;
    }
}
