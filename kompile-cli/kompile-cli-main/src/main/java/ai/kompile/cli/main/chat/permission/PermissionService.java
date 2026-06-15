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
    private final Set<String> sessionAllowed = new HashSet<>();
    /** Permissions that have been permanently denied for this session. */
    private final Set<String> sessionDenied = new HashSet<>();
    /** When true, all permission checks return ALLOWED without prompting. */
    private volatile boolean autoApproveAll = false;

    private final Map<String, PermissionLevel> defaults;
    private final Map<String, PermissionLevel> userOverrides;

    public enum PermissionLevel {
        ALLOW, DENY, ASK
    }

    public PermissionService() {
        this.defaults = buildDefaults();
        this.userOverrides = new HashMap<>();
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

        // File modification requires asking
        d.put("edit", PermissionLevel.ASK);
        d.put("write", PermissionLevel.ASK);
        d.put("patch", PermissionLevel.ASK);

        // Shell execution — tiered by command risk level
        d.put("bash", PermissionLevel.ASK);
        d.put("bash.readonly", PermissionLevel.ALLOW);
        d.put("bash.write", PermissionLevel.ASK);
        d.put("bash.destructive", PermissionLevel.ASK);

        // External directory access requires asking
        d.put("external_directory", PermissionLevel.ASK);

        // Subagent spawning is allowed
        d.put("task", PermissionLevel.ALLOW);

        return d;
    }

    /**
     * Set a user-level permission override.
     */
    public void setUserOverride(String key, PermissionLevel level) {
        userOverrides.put(key, level);
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

        // Unknown permission keys default to ASK
        return PermissionLevel.ASK;
    }

    private PermissionResult askUser(String permissionKey, String description) {
        System.out.println();
        System.out.println("Permission required: " + permissionKey);
        if (description != null && !description.isEmpty()) {
            System.out.println("  " + description);
        }
        System.out.print("Allow? [y]es / [n]o / [a]lways / ne[v]er: ");
        System.out.flush();

        Console console = System.console();
        String input;
        if (console != null) {
            input = console.readLine();
        } else {
            try {
                // Fallback for non-console environments
                byte[] buf = new byte[64];
                int len = System.in.read(buf);
                if (len <= 0) return PermissionResult.DENIED;
                input = new String(buf, 0, len).trim();
            } catch (Exception e) {
                return PermissionResult.DENIED;
            }
        }

        if (input == null) {
            return PermissionResult.DENIED;
        }

        input = input.trim().toLowerCase();
        switch (input) {
            case "y":
            case "yes":
                return PermissionResult.ASKED_AND_ALLOWED;
            case "a":
            case "always":
                sessionAllowed.add(permissionKey);
                System.out.println("  (allowed for this session)");
                return PermissionResult.ASKED_AND_ALLOWED;
            case "v":
            case "never":
                sessionDenied.add(permissionKey);
                System.out.println("  (denied for this session)");
                return PermissionResult.DENIED;
            case "n":
            case "no":
            default:
                return PermissionResult.ASKED_AND_DENIED;
        }
    }

    /**
     * Allow all permissions without prompting (e.g. for --yes-all mode).
     */
    public void allowAll() {
        for (String key : defaults.keySet()) {
            sessionAllowed.add(key);
        }
    }
}
