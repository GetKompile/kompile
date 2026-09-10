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

package ai.kompile.cli.main.chat.permission;

import ai.kompile.cli.main.chat.agent.AgentConfig;

import java.io.Console;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Three-tier permission system comparable to OpenCode's permission model.
 *
 * Resolution order (highest priority first):
 * 1. Agent-specific rules
 * 2. User configuration overrides
 * 3. Hardcoded defaults
 *
 * Each permission key resolves to ALLOW, DENY, or ASK.
 */
public class PermissionService {

    public enum PermissionResult {
        ALLOWED, DENIED, ASKED_AND_ALLOWED, ASKED_AND_DENIED
    }

    /** Permissions that have been permanently allowed for this session. */
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();
    /** Permissions that have been permanently denied for this session. */
    private final Set<String> sessionDenied = ConcurrentHashMap.newKeySet();
    /** When true, all permission checks return ALLOWED without prompting. */
    private volatile boolean autoApproveAll = false;

    /**
     * Prompt requests produced by background tool execution. The JLine thread owns
     * terminal input, so it consumes the user's next line and completes the oldest
     * request instead of letting a model thread read System.in concurrently.
     */
    private final Queue<PendingPrompt> pendingPrompts = new ConcurrentLinkedQueue<>();
    private volatile Consumer<PermissionPrompt> promptListener;

    private final Map<String, PermissionLevel> defaults;
    private final Map<String, PermissionLevel> userOverrides;

    public record PermissionPrompt(String permissionKey, String description) {}

    private record PendingPrompt(PermissionPrompt prompt, CompletableFuture<String> response) {}

    public enum PermissionLevel {
        ALLOW, DENY, ASK
    }

    public PermissionService() {
        this.defaults = buildDefaults();
        this.userOverrides = new ConcurrentHashMap<>();
    }

    private static Map<String, PermissionLevel> buildDefaults() {
        Map<String, PermissionLevel> d = new HashMap<>();
        // File reading is always allowed
        d.put("read", PermissionLevel.ALLOW);
        d.put("grep", PermissionLevel.ALLOW);
        d.put("glob", PermissionLevel.ALLOW);
        d.put("list", PermissionLevel.ALLOW);
        d.put("todoread", PermissionLevel.ALLOW);
        d.put("todowrite", PermissionLevel.ALLOW);
        d.put("webfetch", PermissionLevel.ALLOW);
        d.put("websearch", PermissionLevel.ALLOW);
        d.put("crawl_discover", PermissionLevel.ALLOW);
        d.put("crawl_result", PermissionLevel.ALLOW);
        d.put("knowledge_status", PermissionLevel.ALLOW);
        d.put("knowledge_search", PermissionLevel.ALLOW);
        d.put("graph_reasoning_query", PermissionLevel.ALLOW);

        // Standard chat is permissive by default. The enforcer and explicit
        // agent/user rules own policy; this transport-level service must not
        // unexpectedly stall local MCP execution waiting for terminal input.
        d.put("crawl_documents", PermissionLevel.ALLOW);
        d.put("crawl_source", PermissionLevel.ALLOW);
        d.put("crawl_control", PermissionLevel.ALLOW);
        d.put("ask_graph_assert", PermissionLevel.ALLOW);
        d.put("ask_graph_retract", PermissionLevel.ALLOW);
        d.put("edit", PermissionLevel.ALLOW);
        d.put("write", PermissionLevel.ALLOW);
        d.put("patch", PermissionLevel.ALLOW);
        d.put("bash", PermissionLevel.ALLOW);
        d.put("bash.readonly", PermissionLevel.ALLOW);
        d.put("bash.write", PermissionLevel.ALLOW);
        d.put("bash.destructive", PermissionLevel.ALLOW);
        d.put("external_directory", PermissionLevel.ALLOW);
        // Operator control of supervision is not an ordinary auto-approved tool mutation.
        d.put("judge_control", PermissionLevel.ASK);

        // Subagent spawning is allowed
        d.put("task", PermissionLevel.ALLOW);

        return d;
    }

    /**
     * Set a user-level permission override.
     */
    public synchronized void setUserOverride(String key, PermissionLevel level) {
        autoApproveAll = false;
        sessionAllowed.remove(key);
        sessionDenied.remove(key);
        userOverrides.put(key, level);
    }

    /** Return the effective level shown by the interactive permission manager. */
    public PermissionLevel getEffectiveLevel(AgentConfig agent, String permissionKey) {
        if (autoApproveAll || sessionAllowed.contains(permissionKey)) {
            return PermissionLevel.ALLOW;
        }
        if (sessionDenied.contains(permissionKey)) {
            return PermissionLevel.DENY;
        }
        return resolve(agent, permissionKey);
    }

    /** Install the asynchronous REPL prompt bridge. Null restores console input. */
    public void setPromptListener(Consumer<PermissionPrompt> promptListener) {
        this.promptListener = promptListener;
    }

    /** True while a background tool is waiting for a permission answer. */
    public boolean hasPendingPrompt() {
        return !pendingPrompts.isEmpty();
    }

    /**
     * Route one JLine input line to the oldest permission request. Returns false
     * when no request is pending, allowing the line to continue as normal chat.
     */
    public boolean submitPromptResponse(String response) {
        PendingPrompt pending = pendingPrompts.poll();
        if (pending == null) {
            return false;
        }
        pending.response().complete(response);
        return true;
    }

    /** Unblock any tool threads when the REPL is shutting down. */
    public void cancelPendingPrompts() {
        PendingPrompt pending;
        while ((pending = pendingPrompts.poll()) != null) {
            pending.response().complete(null);
        }
    }

    /** Clear all session permission choices and return to configured defaults. */
    public synchronized void resetSessionOverrides() {
        autoApproveAll = false;
        sessionAllowed.clear();
        sessionDenied.clear();
        userOverrides.clear();
    }

    /**
     * Enable or disable auto-approval of all permission requests.
     * When true, all {@link #check} calls return ALLOWED without prompting the user.
     * Useful for non-interactive runs such as eval harnesses.
     */
    public void setAutoApproveAll(boolean autoApproveAll) {
        this.autoApproveAll = autoApproveAll;
    }

    /**
     * Check permission for a tool action. May prompt the user interactively.
     */
    public PermissionResult check(AgentConfig agent, String permissionKey, String description) {
        // Auto-approve mode: skip all prompting
        if (autoApproveAll) {
            return PermissionResult.ALLOWED;
        }
        // Check session-level overrides first
        if (sessionAllowed.contains(permissionKey)) {
            return PermissionResult.ALLOWED;
        }
        if (sessionDenied.contains(permissionKey)) {
            return PermissionResult.DENIED;
        }

        // Resolve through the three-tier hierarchy
        PermissionLevel level = resolve(agent, permissionKey);

        switch (level) {
            case ALLOW:
                return PermissionResult.ALLOWED;
            case DENY:
                return PermissionResult.DENIED;
            case ASK:
                return askUser(permissionKey, description);
            default:
                return PermissionResult.DENIED;
        }
    }

    private PermissionLevel resolve(AgentConfig agent, String permissionKey) {
        // 1. Agent-specific rules (highest priority)
        if (agent != null && agent.getPermissionOverrides() != null) {
            PermissionLevel agentLevel = agent.getPermissionOverrides().get(permissionKey);
            if (agentLevel != null) {
                return agentLevel;
            }
        }

        // 2. User configuration overrides
        PermissionLevel userLevel = userOverrides.get(permissionKey);
        if (userLevel != null) {
            return userLevel;
        }

        // 3. Hardcoded defaults
        PermissionLevel defaultLevel = defaults.get(permissionKey);
        if (defaultLevel != null) {
            return defaultLevel;
        }

        // Future MCP tools inherit the permissive standard-chat default. Explicit
        // agent/user rules can still set ASK or DENY for any permission key.
        return PermissionLevel.ALLOW;
    }

    private PermissionResult askUser(String permissionKey, String description) {
        Consumer<PermissionPrompt> listener = promptListener;
        String input;
        if (listener != null) {
            CompletableFuture<String> response = new CompletableFuture<>();
            PendingPrompt pending = new PendingPrompt(
                    new PermissionPrompt(permissionKey, description), response);
            pendingPrompts.add(pending);
            try {
                listener.accept(pending.prompt());
                input = response.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendingPrompts.remove(pending);
                return PermissionResult.DENIED;
            } catch (Exception e) {
                pendingPrompts.remove(pending);
                return PermissionResult.DENIED;
            }
        } else {
            input = readConsoleResponse(permissionKey, description);
        }
        return applyResponse(permissionKey, input);
    }

    private String readConsoleResponse(String permissionKey, String description) {
        System.out.println();
        System.out.println("Permission required: " + permissionKey);
        if (description != null && !description.isEmpty()) {
            System.out.println("  " + description);
        }
        System.out.print("Allow? [y]es / [n]o / [a]llow session / ne[v]er this session: ");
        System.out.flush();

        Console console = System.console();
        if (console != null) {
            return console.readLine();
        }
        try {
            byte[] buf = new byte[64];
            int len = System.in.read(buf);
            return len <= 0 ? null : new String(buf, 0, len).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private PermissionResult applyResponse(String permissionKey, String input) {
        if (input == null) {
            return PermissionResult.DENIED;
        }
        return switch (input.trim().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "allow", "once", "allow-once" ->
                    PermissionResult.ASKED_AND_ALLOWED;
            case "a", "always", "allow-session", "session" -> {
                sessionAllowed.add(permissionKey);
                yield PermissionResult.ASKED_AND_ALLOWED;
            }
            case "v", "never", "deny-session" -> {
                sessionDenied.add(permissionKey);
                yield PermissionResult.DENIED;
            }
            case "n", "no", "deny", "deny-once" -> PermissionResult.ASKED_AND_DENIED;
            default -> PermissionResult.ASKED_AND_DENIED;
        };
    }

    /**
     * Allow all permissions without prompting (e.g. for --yes-all mode).
     */
    public void allowAll() {
        autoApproveAll = true;
    }
}
