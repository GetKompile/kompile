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
import ai.kompile.cli.main.chat.agent.DirectSubagentSupervision;
import ai.kompile.cli.main.chat.permission.PermissionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Context passed to every tool execution. Contains session info,
 * permission checking, working directory, and cancellation signal.
 */
public class ToolContext {
    private static final Set<String> PROVIDER_MEMORY_DIRS = Set.of(
            ".claude", ".codex", ".gemini", ".qwen", ".opencode");

    private final String sessionId;
    private final AgentConfig agent;
    private final PermissionService permissionService;
    private final Path workingDirectory;
    private volatile AtomicBoolean aborted;
    private volatile BooleanSupplier additionalAbortCheck;
    private final ToolRegistry toolRegistry;
    private volatile Consumer<String> outputConsumer;
    private volatile boolean autoApproveAll = false;
    private DirectSubagentSupervision.Contract subagentSupervision;
    private boolean supervisedChild;
    private ai.kompile.cli.main.chat.enforcer.JudgeControl judgeControl;
    private boolean judgeToolCallScoped;

    public boolean isSupervisedChild() { return supervisedChild; }
    public ai.kompile.cli.main.chat.enforcer.JudgeControl getJudgeControl() { return judgeControl; }
    public boolean isJudgeToolCallScoped() { return judgeToolCallScoped; }
    public void bindJudgeControl(ai.kompile.cli.main.chat.enforcer.JudgeControl control, boolean toolCallScoped) {
        this.judgeControl = control;
        this.judgeToolCallScoped = toolCallScoped;
    }

    public DirectSubagentSupervision.Contract getSubagentSupervision() {
        return subagentSupervision;
    }

    public void setSubagentSupervision(
            DirectSubagentSupervision.Contract contract) {
        this.subagentSupervision = contract;
    }

    public void markSupervisedChild() { supervisedChild = true; }

    /**
     * File-read snapshots keyed by sessionId + path, shared process-wide. Deliberately static:
     * the MCP servers construct short-lived ToolContext instances (McpStdioCommand caches one
     * per pool thread, McpSocketSession builds one per tool call), so instance-scoped snapshots
     * were lost between a read and the following edit/write/patch — every mutation was rejected
     * with "has not been read in this tool session". The sessionId in the key preserves
     * per-session isolation; the access-order LRU bounds memory in long-lived daemons.
     */
    private static final int MAX_FILE_READ_SNAPSHOTS = 8192;
    private static final Map<String, FileSnapshot> FILE_READ_SNAPSHOTS =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FileSnapshot> eldest) {
                    return size() > MAX_FILE_READ_SNAPSHOTS;
                }
            });

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

    public boolean isAborted() {
        BooleanSupplier additional = additionalAbortCheck;
        return aborted.get() || (additional != null && additional.getAsBoolean());
    }
    public void abort() { aborted.set(true); }
    public AtomicBoolean getAbortSignal() { return aborted; }

    /**
     * Makes this context observe the caller's cancellation signal. Child contexts
     * use the exact same flag so an Escape cancellation reaches running tools and
     * nested subagents instead of stopping only the outer agent loop.
     */
    public void linkAbortSignal(AtomicBoolean sharedAbortSignal) {
        if (sharedAbortSignal != null) {
            this.aborted = sharedAbortSignal;
        }
    }

    /** Observe another cancellation source without sharing mutation ownership. */
    public void linkAbortCheck(BooleanSupplier additionalAbortCheck) {
        this.additionalAbortCheck = additionalAbortCheck;
    }

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
     * Create an execution-local view for one synchronous tool call. It shares
     * the turn abort signal and immutable session services, but its output sink
     * can be detached independently if the worker outlives a cancelled owner.
     */
    public ToolContext forkForToolExecution() {
        ToolContext child = new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);
        child.linkAbortSignal(aborted);
        child.linkAbortCheck(additionalAbortCheck);
        child.setAutoApproveAll(autoApproveAll);
        child.setOutputConsumer(outputConsumer);
        child.subagentSupervision = subagentSupervision;
        child.supervisedChild = supervisedChild;
        child.bindJudgeControl(judgeControl, judgeToolCallScoped);
        return child;
    }

    /**
     * Emit one display/progress entry through the caller-owned output channel.
     * Interactive standard chat installs a JLine-safe consumer so asynchronous
     * tool and subagent output is inserted above the live input editor. Headless
     * and legacy callers retain stdout as a compatibility fallback.
     */
    public void emitOutput(String output) {
        String line = output == null ? "" : output;
        Consumer<String> consumer = outputConsumer;
        if (consumer != null) {
            consumer.accept(line);
        } else {
            System.out.println(line);
        }
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
        if (supervisedChild && subagentSupervision != null && subagentSupervision.ceiling() != null) {
            var level = subagentSupervision.ceiling().permissions().get(permissionKey);
            if (level != PermissionService.PermissionLevel.ALLOW) {
                throw new ToolExecutionException("Inherited parent permission ceiling: " + permissionKey
                        + " (" + level + "); obtain parent approval before delegating this action", true);
            }
        }
        if (autoApproveAll) {
            return;
        }
        PermissionService.PermissionResult result = permissionService.check(agent, permissionKey, description);
        if (result == PermissionService.PermissionResult.DENIED
                || result == PermissionService.PermissionResult.ASKED_AND_DENIED) {
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

    /**
     * Resolve a path for a generic file mutation. Managed memory is not an ordinary
     * project file: its indexes, typed records, and graph state must stay consistent,
     * so only the {@code memory} tool (or a specialized state tool such as
     * {@code todowrite}) may change it.
     */
    public Path resolveMutationPath(String path) throws ToolExecutionException {
        Path candidate = workingDirectory.resolve(path).normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (supervisedChild && (isSupervisionConfiguration(normalized)
                || isSupervisionConfiguration(resolveThroughExistingAncestors(normalized)))) {
            throw new ToolExecutionException("Subagents cannot modify enforcement or configuration: "
                    + normalized, true);
        }
        if (isManagedMemoryPath(normalized)
                || isManagedMemoryPath(resolveThroughExistingAncestors(normalized))) {
            throw new ToolExecutionException(
                    "Direct file mutation of managed memory is blocked: " + normalized
                            + ". Use the `memory` MCP tool instead"
                            + " (`todowrite` for transcript-session task state).",
                    true);
        }
        return resolvePath(path);
    }

    private boolean isSupervisionConfiguration(Path path) {
        Path project = workingDirectory.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        // Generated-project managed config is rooted at the project, not any source package named config.
        if (path.startsWith(project.resolve("config")) || path.startsWith(home.resolve(".kompile"))) return true;
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (PROVIDER_MEMORY_DIRS.contains(name) || name.equals(".kompile")
                    || name.equals("agents.md")
                    || name.equals("claude.md") || name.equals(".mcp.json")
                    || name.equals("gemini.md") || name.equals(".cursorrules")
                    || name.equals(".windsurfrules") || name.equals("copilot-instructions.md")
                    || name.equals("kompile.project.json")
                    || name.equals("system-prompt.md") || name.equals("system-prompts")) return true;
        }
        return false;
    }

    private static boolean isManagedMemoryPath(Path path) {
        if (path == null) return false;
        String previous = null;
        boolean insideProviderState = false;
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if ("memory".equals(name)
                    && (".kompile".equals(previous) || insideProviderState)) {
                return true;
            }
            if (PROVIDER_MEMORY_DIRS.contains(name)) {
                insideProviderState = true;
            }
            previous = name;
        }
        return false;
    }

    /** Follow an existing symlink ancestor so aliases cannot bypass the memory boundary. */
    private static Path resolveThroughExistingAncestors(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) return path;
        try {
            return existing.toRealPath().resolve(existing.relativize(path)).normalize();
        } catch (IOException ignored) {
            return path;
        }
    }

    /**
     * Record the current file metadata after a successful read or write. Edit/write tools
     * use this to reject stale writes when the file changed after the agent last saw it.
     */
    public void recordFileRead(Path path) {
        try {
            FILE_READ_SNAPSHOTS.put(snapshotKey(path), FileSnapshot.from(path));
        } catch (IOException ignored) {
            FILE_READ_SNAPSHOTS.remove(snapshotKey(path));
        }
    }

    /** Returns true when the file was read in this session and still has the same metadata. */
    public boolean hasFreshFileRead(Path path) {
        FileSnapshot previous = FILE_READ_SNAPSHOTS.get(snapshotKey(path));
        if (previous == null) {
            return false;
        }
        try {
            return previous.equals(FileSnapshot.from(normalizeFilePath(path)));
        } catch (IOException e) {
            return false;
        }
    }

    public String staleReadMessage(Path path, String operation) {
        return "Refusing to " + operation + " " + path
                + " because it has not been read in this tool session or changed since it was read. "
                + "Call read on the file before modifying it.";
    }

    /** Newline separator: cannot occur in a session id or a sane absolute path. */
    private String snapshotKey(Path path) {
        return sessionId + "\n" + normalizeFilePath(path);
    }

    private Path normalizeFilePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private record FileSnapshot(FileTime lastModifiedTime, long size, Object fileKey) {
        static FileSnapshot from(Path path) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileSnapshot(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
        }
    }
}
