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
 * <p>Instead of sending every registered tool in each {@code tools/list} response,
 * tools are organized into groups:
 * <ul>
 *   <li><b>Core tools</b> — a small set for ordinary project work</li>
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
    private static final List<String> CORE_TOOL_ORDER = List.of(
            "read", "file_context", "write", "edit", "grep", "glob", "list", "bash",
            "fetch_result", "poll", "mcp_tool_search", "mcp_tool_call",
            "activate_tools", "exit_plan_mode");
    private static final Set<String> CORE_TOOLS =
            Collections.unmodifiableSet(new LinkedHashSet<>(CORE_TOOL_ORDER));

    /** Tool group definitions: group name → set of tool IDs. */
    private static final Map<String, ToolGroup> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("files", new ToolGroup("files",
                "Batch file reads, searches, edits, patches, result retrieval, and file activity",
                Set.of("read_batch", "edit_batch", "edit_patch", "patch", "grep_batch",
                        "fetch_result_batch", "explore", "file_activity", "file_note")));

        GROUPS.put("code", new ToolGroup("code",
                "Semantic code search, indexes, dependency graphs, language-server queries, and edit history",
                Set.of("code_search", "code_graph", "local_code_index", "lsp", "diff_index",
                        "graph_search", "graph_reasoning_query")));

        GROUPS.put("workflow", new ToolGroup("workflow",
                "Todo tracking, project/enforcer configuration, archives, side panels, and ambient capture",
                Set.of("todowrite", "todoread", "project_config", "enforcer_config", "judge_control",
                        "config_archive", "side_panel", "dictation", "ambient_garden")));

        GROUPS.put("web", new ToolGroup("web",
                "Web search, page retrieval, and interactive browsing",
                Set.of("webfetch", "websearch", "browser")));

        GROUPS.put("integrations", new ToolGroup("integrations",
                "Provider login, non-secret channel status, and allowlisted delivery",
                Set.of("channel")));

        GROUPS.put("process", new ToolGroup("process",
                "Background processes, subprocess memory watchdog, result polling, server control, process mining, and agent coordination",
                Set.of("process", "subprocess_watchdog", "poll", "server_mode", "process_mining",
                        "edit_coordinator", "sessions")));

        GROUPS.put("delegation", new ToolGroup("delegation",
                "Subagents, parallel/quorum tasks, roles, and skills",
                Set.of("task", "multi_task", "quorum_task", "role_manager", "skill_manager")));

        GROUPS.put("history", new ToolGroup("history",
                "Conversation search, import/resume, and prior tool-call lookup",
                Set.of("transcript_search", "conversation_import", "resume", "tool_call_catalog")));

        GROUPS.put("memory", new ToolGroup("memory",
                "Persistent memory plus semantic, RAG, and knowledge-base search",
                Set.of("memory", "semantic_memory", "rag_search", "knowledge_search")));

        GROUPS.put("crawl", new ToolGroup("crawl",
                "Build and maintain pipelines, then run and inspect folder-local model-backed crawls",
                Set.of("crawl_discover", "model_runtime", "pipeline", "crawl_documents", "crawl_source",
                        "crawl_control", "crawl_result", "knowledge_status")));

        GROUPS.put("graph_query", new ToolGroup("graph_query",
                "Search and reason over the knowledge graph without mutating it",
                Set.of("knowledge_graph", "graph_search", "graph_reason", "graph_reasoning_query",
                        "ask_graph_query", "ask_graph_verify", "ask_graph_explain",
                        "ask_graph_explain_fused", "ask_graph_synthesize", "ask_graph_claim")));

        GROUPS.put("graph_analysis", new ToolGroup("graph_analysis",
                "Graph aggregation, embeddings, centrality, simulation, forecasting, and Bayesian analysis",
                Set.of("graph_aggregate", "graph_bayes", "graph_centrality", "graph_embeddings",
                        "graph_forecast", "graph_simulate", "ask_graph_mebn")));

        GROUPS.put("graph_write", new ToolGroup("graph_write",
                "Assert, retract, subscribe, import, or export graph state",
                Set.of("ask_graph_assert", "ask_graph_retract", "ask_graph_subscribe",
                        "graph_import", "graph_export")));

        GROUPS.put("evaluation", new ToolGroup("evaluation",
                "Test milestones, performance harnesses, and agent evaluation",
                Set.of("test_milestone", "performance_harness", "eval")));

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

    /** Remove a tool from the live catalog. */
    public void unregister(String id) {
        allTools.remove(id);
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

        Set<String> active = new LinkedHashSet<>();
        for (String id : CORE_TOOL_ORDER) {
            if (allTools.containsKey(id)) {
                active.add(id);
            }
        }

        // Add tools from activated groups
        for (Map.Entry<String, ToolGroup> entry : GROUPS.entrySet()) {
            if (activatedGroups.contains(entry.getKey())) {
                entry.getValue().toolIds.stream().sorted().forEach(active::add);
            }
        }

        if (activatedGroups.contains("other")) {
            active.addAll(ungroupedToolIds());
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
        if ("other".equals(groupName)) {
            activatedGroups.add(groupName);
            return new ArrayList<>(ungroupedToolIds());
        }
        ToolGroup group = GROUPS.get(groupName);
        if (group == null) return List.of();

        activatedGroups.add(groupName);
        List<String> added = new ArrayList<>();
        for (String toolId : group.toolIds.stream().sorted().toList()) {
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
        added.addAll(activateGroup("other"));
        return added;
    }

    // ── Activation loop tracking ─────────────────────────────────────────────

    /**
     * Activation attempts per session: sessionId → (group → count). The group key uses the
     * canonical name, with {@code "all"} for activate-all attempts. Bounds the harness state
     * that backs the activate_tools loop guard; sessions are transcript-scoped and tiny.
     */
    private final Map<String, Map<String, Integer>> activationCounts = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_SESSIONS = 1024;

    /** Outcome of an activation attempt against a specific group. */
    public enum ActivationStatus {
        /** The group was inactive and is now active; {@code added} lists newly visible tools. */
        ACTIVATED,
        /** The group was already active (host-seeded or previously activated); no state changed. */
        ALREADY_ACTIVE,
        /** The group is known but registers no tools in this session; no state changed. */
        NO_TOOLS_IN_SESSION,
        /** The group name is not one of the known groups. */
        UNKNOWN
    }

    /**
     * Rich activation outcome. Distinguishing ALREADY_ACTIVE from ACTIVATED is what lets the
     * activate_tools meta-tool answer honestly instead of re-announcing tools that were
     * already visible — the false progress report that drove activation loops.
     */
    public record ActivationResult(ActivationStatus status, String groupName, List<String> added) {}

    public boolean isGroupKnown(String groupName) {
        return "other".equals(groupName) || GROUPS.containsKey(groupName);
    }

    public boolean isGroupActive(String groupName) {
        return activatedGroups.contains(groupName);
    }

    /**
     * Activate a group and report whether anything actually changed. Marks the group active as
     * a side effect, exactly like {@link #activateGroup(String)}.
     *
     * <p>When dynamic mode is off (MCP stdio), every registered tool is already listed, so
     * activation never changes the visible surface and is reported as {@link
     * ActivationStatus#ALREADY_ACTIVE} instead of a false "Activated N tools" progress report.
     */
    public ActivationResult activateGroupWithStatus(String groupName) {
        if (!isGroupKnown(groupName)) {
            return new ActivationResult(ActivationStatus.UNKNOWN, groupName, List.of());
        }
        boolean wasActive = activatedGroups.contains(groupName);
        List<String> added = activateGroup(groupName);
        if (wasActive || !dynamicMode) {
            return new ActivationResult(ActivationStatus.ALREADY_ACTIVE, groupName, List.of());
        }
        if (added.isEmpty()) {
            return new ActivationResult(ActivationStatus.NO_TOOLS_IN_SESSION, groupName, List.of());
        }
        return new ActivationResult(ActivationStatus.ACTIVATED, groupName, added);
    }

    /**
     * Activate every group and report whether anything actually changed.
     *
     * <p>When dynamic mode is off, the full catalog is always visible; this reports
     * {@link ActivationStatus#ALREADY_ACTIVE} instead of a false progress report.
     */
    public ActivationResult activateAllWithStatus() {
        if (!dynamicMode) {
            activateAll();
            return new ActivationResult(ActivationStatus.ALREADY_ACTIVE, "all", List.of());
        }
        boolean anyInactive = GROUPS.keySet().stream().anyMatch(g -> !activatedGroups.contains(g))
                || !activatedGroups.contains("other");
        List<String> added = activateAll();
        if (!anyInactive) {
            return new ActivationResult(ActivationStatus.ALREADY_ACTIVE, "all", List.of());
        }
        List<String> visible = new ArrayList<>(getActiveToolIds());
        List<String> newlyAdded = added.stream().filter(visible::contains).toList();
        if (newlyAdded.isEmpty()) {
            return new ActivationResult(ActivationStatus.NO_TOOLS_IN_SESSION, "all", List.of());
        }
        return new ActivationResult(ActivationStatus.ACTIVATED, "all", newlyAdded);
    }

    /**
     * Record one activation attempt for this session and return the session's new total
     * activation count (across all groups). Called by the activate_tools meta-tool so the
     * harness can block endless repeated activations.
     */
    public int recordActivation(String sessionId, String groupName) {
        if (activationCounts.size() > MAX_TRACKED_SESSIONS) {
            activationCounts.clear();
        }
        var counts = activationCounts.computeIfAbsent(
                sessionId == null ? "(anonymous)" : sessionId, k -> new ConcurrentHashMap<>());
        counts.merge(groupName == null || groupName.isBlank() ? "(unknown)" : groupName, 1, Integer::sum);
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** How many times this session has already activated the given group. */
    public int activationCount(String sessionId, String groupName) {
        var counts = activationCounts.get(sessionId == null ? "(anonymous)" : sessionId);
        return counts == null ? 0 : counts.getOrDefault(groupName, 0);
    }

    /** Total activation attempts made by this session across all groups. */
    public int totalActivations(String sessionId) {
        var counts = activationCounts.get(sessionId == null ? "(anonymous)" : sessionId);
        return counts == null ? 0 : counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Sessions whose activation budget is spent; further activate calls are hard-blocked. */
    private final Set<String> frozenActivationSessions = ConcurrentHashMap.newKeySet();

    /**
     * Freeze all activation for this session. Called once the activate_tools meta-tool has
     * sent its explicit stop instruction; afterwards the harness blocks the calls instead of
     * trusting the model to obey.
     */
    public void freezeActivations(String sessionId) {
        if (frozenActivationSessions.size() > MAX_TRACKED_SESSIONS) {
            frozenActivationSessions.clear();
        }
        frozenActivationSessions.add(sessionId == null ? "(anonymous)" : sessionId);
    }

    /** True once this session ignored the activation stop instruction. */
    public boolean isActivationFrozen(String sessionId) {
        return frozenActivationSessions.contains(sessionId == null ? "(anonymous)" : sessionId);
    }

    /**
     * Get descriptions of available (not yet activated) tool groups.
     */
    public String describeAvailableGroups() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool groups:\n");
        boolean anyAvailable = false;

        for (Map.Entry<String, ToolGroup> entry : GROUPS.entrySet()) {
            String name = entry.getKey();
            ToolGroup group = entry.getValue();
            boolean activated = activatedGroups.contains(name);

            // Count how many tools in this group are actually registered
            long registered = group.toolIds.stream()
                    .filter(allTools::containsKey).count();
            if (registered == 0) continue;

            sb.append("- ").append(name);
            if (activated) {
                sb.append(" [active]");
            } else {
                anyAvailable = true;
            }
            sb.append(": ").append(group.description);
            sb.append(" (").append(registered).append(" tools)\n");
        }
        Set<String> other = ungroupedToolIds();
        if (!other.isEmpty()) {
            boolean activated = activatedGroups.contains("other");
            sb.append("- other");
            if (activated) {
                sb.append(" [active]");
            } else {
                anyAvailable = true;
            }
            sb.append(": Newly registered or uncategorized tools (")
                    .append(other.size()).append(" tools)\n");
        }

        if (!anyAvailable) {
            sb.append("\nAll groups are already activated.\n");
        } else {
            sb.append("\nCall activate_tools with action=activate and one group name. ");
            sb.append("Activated tools appear on the next model step.\n");
        }

        return sb.toString();
    }

    /**
     * Describe the tools in a specific group.
     */
    public String describeGroup(String groupName) {
        if ("other".equals(groupName)) {
            return describeTools(groupName,
                    "Newly registered or uncategorized tools", ungroupedToolIds());
        }
        ToolGroup group = GROUPS.get(groupName);
        if (group == null) {
            return "Unknown group: " + groupName + ". " + describeAvailableGroups();
        }

        return describeTools(groupName, group.description, group.toolIds);
    }

    private String describeTools(String groupName, String description, Collection<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Group '").append(groupName).append("': ").append(description).append("\n\n");
        sb.append("Tools:\n");
        for (String toolId : toolIds.stream().sorted().toList()) {
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

    /** Registered group names suitable for an enum in the activation tool schema. */
    public List<String> availableGroupNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, ToolGroup> entry : GROUPS.entrySet()) {
            if (entry.getValue().toolIds.stream().anyMatch(allTools::containsKey)) {
                names.add(entry.getKey());
            }
        }
        if (!ungroupedToolIds().isEmpty()) {
            names.add("other");
        }
        return names;
    }

    private Set<String> ungroupedToolIds() {
        Set<String> grouped = new HashSet<>(CORE_TOOLS);
        for (ToolGroup group : GROUPS.values()) {
            grouped.addAll(group.toolIds);
        }
        Set<String> ungrouped = new LinkedHashSet<>();
        for (String id : allTools.keySet()) {
            if (!grouped.contains(id)) {
                ungrouped.add(id);
            }
        }
        return ungrouped;
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
