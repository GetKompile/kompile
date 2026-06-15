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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic tool loading for MCP servers to reduce tool descriptor bloat.
 *
 * <p>Instead of sending all 33+ tools in every {@code tools/list} response (~7000 tokens),
 * tools are organized into groups:
 * <ul>
 *   <li><b>Core tools</b> — always listed (read, write, edit, grep, glob, bash, list)</li>
 *   <li><b>Extended groups</b> — listed only after activation via the {@code activate_tools}
 *       meta-tool</li>
 * </ul>
 *
 * <p>This implements the DYNAMIC/HYBRID MetaToolMode pattern from MCP optimization research.
 * The LLM sees a compact tool list (~2000 tokens for core tools) plus a single
 * {@code activate_tools} meta-tool that describes available groups. When the LLM needs
 * a specialized tool, it activates the relevant group.
 *
 * <p>Typical savings: 60-70% reduction in tool schema tokens for sessions that
 * only use core tools.
 */
public class DynamicToolManager {

    /**
     * Tools that are always included in tools/list.
     * Note: In MCP stdio mode, host-native tools (read, write, edit, bash, grep, glob, list)
     * are NOT registered at all to avoid duplicating the host agent's native tools.
     * This core set covers the kompile-specific tools that are always active.
     */
    private static final Set<String> CORE_TOOLS = Set.of(
            "read", "write", "edit", "grep", "glob", "bash", "list",
            "fetch_result", "activate_tools",
            "patch", "explore", "memory", "transcript_search",
            "conversation_import", "code_search", "code_graph", "local_code_index"
    );

    /** Tool group definitions: group name → set of tool IDs. */
    private static final Map<String, ToolGroup> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("search", new ToolGroup("search",
                "Unified code search (indexing, graph, impact, signatures) and knowledge search (documents, graph, memory)",
                Set.of("code_search", "search", "tool_call_catalog")));

        GROUPS.put("workflow", new ToolGroup("workflow",
                "Todo lists, memory, config archives, test milestones",
                Set.of("todowrite", "todoread", "memory", "config_archive", "test_milestone")));

        GROUPS.put("network", new ToolGroup("network",
                "Web fetch, web search",
                Set.of("webfetch", "websearch")));

        GROUPS.put("delegation", new ToolGroup("delegation",
                "Task delegation, multi-task, quorum task, role/skill management",
                Set.of("task", "quorum_task", "multi_task", "role_manager", "skill_manager")));

        GROUPS.put("knowledge", new ToolGroup("knowledge",
                "Transcript search, conversation import, resume",
                Set.of("transcript_search", "conversation_import", "resume")));

        GROUPS.put("process", new ToolGroup("process",
                "Background process management, edit coordination",
                Set.of("process", "edit_coordinator")));

        GROUPS.put("advanced", new ToolGroup("advanced",
                "Performance harness, patch tool",
                Set.of("performance_harness", "patch")));

        // Custom tools loaded from ~/.kompile/tools/ and .kompile/tools/ — inactive by default.
        // Group is populated dynamically at startup; tool IDs follow the "custom_<name>" convention.
        GROUPS.put("custom", new ToolGroup("custom",
                "User-defined custom tools loaded from ~/.kompile/tools/ and .kompile/tools/",
                ConcurrentHashMap.newKeySet()));
    }

    /** Currently activated groups (per session). */
    private final Set<String> activatedGroups = ConcurrentHashMap.newKeySet();

    /** All registered tools (full set). */
    private final Map<String, ToolInfo> allTools = new LinkedHashMap<>();

    private boolean dynamicMode = true;

    /**
     * Register a tool with the manager.
     */
    public void register(String id, String description, JsonNode schema) {
        allTools.put(id, new ToolInfo(id, description, schema));
    }

    /**
     * Enable or disable dynamic mode. When disabled, all tools are always listed.
     */
    public void setDynamicMode(boolean enabled) {
        this.dynamicMode = enabled;
    }

    public boolean isDynamicMode() {
        return dynamicMode;
    }

    /**
     * Get the set of tool IDs that should appear in the current tools/list response.
     */
    public Set<String> getActiveToolIds() {
        if (!dynamicMode) {
            return allTools.keySet();
        }

        Set<String> active = new LinkedHashSet<>(CORE_TOOLS);

        // Add tools from activated groups
        for (String groupName : activatedGroups) {
            ToolGroup group = GROUPS.get(groupName);
            if (group != null) {
                active.addAll(group.toolIds);
            }
        }

        // Also include any tools not in any group (ungrouped tools are always active)
        Set<String> groupedTools = new HashSet<>();
        for (ToolGroup group : GROUPS.values()) {
            groupedTools.addAll(group.toolIds);
        }
        for (String id : allTools.keySet()) {
            if (!groupedTools.contains(id) && !CORE_TOOLS.contains(id)) {
                // Ungrouped and not core — include it
                active.add(id);
            }
        }

        return active;
    }

    /**
     * Activate a tool group, making its tools visible in subsequent tools/list calls.
     *
     * @param groupName the group to activate
     * @return list of tool IDs that were added, or empty if group unknown
     */
    public List<String> activateGroup(String groupName) {
        ToolGroup group = GROUPS.get(groupName);
        if (group == null) return List.of();

        activatedGroups.add(groupName);
        List<String> added = new ArrayList<>();
        for (String toolId : group.toolIds) {
            if (allTools.containsKey(toolId)) {
                added.add(toolId);
            }
        }
        return added;
    }

    /**
     * Activate all tool groups at once.
     */
    public List<String> activateAll() {
        List<String> added = new ArrayList<>();
        for (String groupName : GROUPS.keySet()) {
            added.addAll(activateGroup(groupName));
        }
        return added;
    }

    /**
     * Get descriptions of available (not yet activated) tool groups.
     */
    public String describeAvailableGroups() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tool groups:\n\n");
        boolean anyAvailable = false;

        for (Map.Entry<String, ToolGroup> entry : GROUPS.entrySet()) {
            String name = entry.getKey();
            ToolGroup group = entry.getValue();
            boolean activated = activatedGroups.contains(name);

            // Count how many tools in this group are actually registered
            long registered = group.toolIds.stream()
                    .filter(allTools::containsKey).count();
            if (registered == 0) continue;

            sb.append("- **").append(name).append("**");
            if (activated) {
                sb.append(" [active]");
            } else {
                anyAvailable = true;
            }
            sb.append(": ").append(group.description);
            sb.append(" (").append(registered).append(" tools)\n");
        }

        if (!anyAvailable) {
            sb.append("\nAll groups are already activated.\n");
        } else {
            sb.append("\nUse activate_tools with group name to enable. ");
            sb.append("Use 'all' to activate everything.\n");
        }

        return sb.toString();
    }

    /**
     * Describe the tools in a specific group.
     */
    public String describeGroup(String groupName) {
        ToolGroup group = GROUPS.get(groupName);
        if (group == null) {
            return "Unknown group: " + groupName + ". " + describeAvailableGroups();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Group '").append(groupName).append("': ").append(group.description).append("\n\n");
        sb.append("Tools:\n");
        for (String toolId : group.toolIds) {
            ToolInfo info = allTools.get(toolId);
            if (info != null) {
                String shortDesc = info.description.length() > 80
                        ? info.description.substring(0, 80) + "..."
                        : info.description;
                sb.append("  - ").append(toolId).append(": ").append(shortDesc).append("\n");
            }
        }
        boolean activated = activatedGroups.contains(groupName);
        sb.append("\nStatus: ").append(activated ? "active" : "inactive").append("\n");
        return sb.toString();
    }

    /**
     * Register a tool ID into the "custom" group so it participates in
     * dynamic activation. Called during custom tool loading at startup.
     */
    public void registerCustomToolId(String toolId) {
        ToolGroup customGroup = GROUPS.get("custom");
        if (customGroup != null) {
            customGroup.toolIds().add(toolId);
        }
    }

    /** Get a registered tool's info. */
    public ToolInfo getToolInfo(String id) {
        return allTools.get(id);
    }

    /** Check if a tool ID is currently active. */
    public boolean isActive(String toolId) {
        return getActiveToolIds().contains(toolId);
    }

    public record ToolGroup(String name, String description, Set<String> toolIds) {}
    public record ToolInfo(String id, String description, JsonNode schema) {}
}
