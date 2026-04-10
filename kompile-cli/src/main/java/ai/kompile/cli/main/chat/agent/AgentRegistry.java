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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.roles.RoleConfig;

import java.util.*;

/**
 * Registry of all available agents (primary and subagents).
 * Comparable to OpenCode's agent system with coder/planner primary agents
 * and general/explorer subagents.
 */
public class AgentRegistry {
    private final Map<String, AgentConfig> agents = new LinkedHashMap<>();
    private final Map<String, RoleConfig> roles = new LinkedHashMap<>();

    public AgentRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        // ====================================================================
        // Primary agents (user-facing)
        // ====================================================================

        register(AgentConfig.builder("coder")
                .displayName("Coder")
                .description("Full-featured development agent with all tools")
                .systemPrompt(CODER_SYSTEM_PROMPT)
                .enabledTools(Set.of("*"))
                .canSpawnSubagents(true)
                .maxSteps(50)
                .build());

        register(AgentConfig.builder("planner")
                .displayName("Planner")
                .description("Read-only architecture and planning agent")
                .systemPrompt(PLANNER_SYSTEM_PROMPT)
                .enabledTools(Set.of("read", "grep", "glob", "list", "bash", "webfetch", "websearch",
                        "task", "todowrite", "todoread", "transcript_search", "rag_search",
                        "graph_search", "process"))
                .permissionOverrides(Map.of(
                        "edit", PermissionService.PermissionLevel.DENY,
                        "write", PermissionService.PermissionLevel.DENY,
                        "patch", PermissionService.PermissionLevel.DENY,
                        "bash", PermissionService.PermissionLevel.ASK
                ))
                .canSpawnSubagents(true)
                .maxSteps(30)
                .build());

        // ====================================================================
        // Subagents (spawned by primary agents via task tool)
        // ====================================================================

        // -- General purpose subagent (full tool access) --
        register(AgentConfig.builder("general")
                .displayName("General Subagent")
                .description("Full tool access for multi-step delegated tasks")
                .systemPrompt(GENERAL_SUBAGENT_PROMPT)
                .enabledTools(Set.of("*"))
                .isSubagent(true)
                .maxSteps(30)
                .build());

        // -- Quick explorer: fast file/code lookups (like Claude Code's Explore "quick") --
        register(AgentConfig.builder("explore-quick")
                .displayName("Quick Explorer")
                .description("Fast file discovery and targeted code lookups")
                .systemPrompt(EXPLORE_QUICK_PROMPT)
                .modelHint("fast")
                .enabledTools(READ_ONLY_EXPLORE_TOOLS)
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(10)
                .build());

        // -- Deep explorer: thorough codebase analysis (like Claude Code's Explore "very thorough") --
        register(AgentConfig.builder("explore-deep")
                .displayName("Deep Explorer")
                .description("Comprehensive codebase exploration across multiple locations and naming conventions")
                .systemPrompt(EXPLORE_DEEP_PROMPT)
                .modelHint("default")
                .enabledTools(READ_ONLY_EXPLORE_TOOLS)
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(30)
                .build());

        // -- Legacy "explorer" alias maps to explore-deep for backward compat --
        register(AgentConfig.builder("explorer")
                .displayName("Explorer Subagent")
                .description("Read-only codebase research and analysis (alias for explore-deep)")
                .systemPrompt(EXPLORE_DEEP_PROMPT)
                .enabledTools(READ_ONLY_EXPLORE_TOOLS)
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(20)
                .build());

        // -- Code reviewer: analyzes diffs, finds issues --
        register(AgentConfig.builder("code-reviewer")
                .displayName("Code Reviewer")
                .description("Reviews code changes for bugs, style issues, and improvements")
                .systemPrompt(CODE_REVIEWER_PROMPT)
                .modelHint("default")
                .enabledTools(READ_ONLY_EXPLORE_TOOLS)
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(20)
                .build());

        // -- Architect: designs solutions and documents patterns --
        register(AgentConfig.builder("architect")
                .displayName("Architect")
                .description("Analyzes architecture, designs solutions, and creates implementation plans")
                .systemPrompt(ARCHITECT_PROMPT)
                .modelHint("default")
                .enabledTools(Set.of("read", "grep", "glob", "list", "bash", "webfetch", "websearch",
                        "todowrite", "todoread", "transcript_search", "rag_search", "graph_search"))
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(25)
                .build());

        // -- Research agent: web search and documentation lookup --
        register(AgentConfig.builder("researcher")
                .displayName("Researcher")
                .description("Web search, documentation lookup, and external knowledge gathering")
                .systemPrompt(RESEARCHER_PROMPT)
                .modelHint("fast")
                .enabledTools(Set.of("webfetch", "websearch", "read", "grep", "glob", "list",
                        "rag_search", "graph_search"))
                .isSubagent(true)
                .permissionOverrides(EXPLORE_PERMISSION_OVERRIDES)
                .maxSteps(15)
                .build());
    }

    public void register(AgentConfig agent) {
        agents.put(agent.getName(), agent);
    }

    public AgentConfig get(String name) {
        return agents.get(name);
    }

    public AgentConfig getDefault() {
        return agents.get("coder");
    }

    public Collection<AgentConfig> all() {
        return Collections.unmodifiableCollection(agents.values());
    }

    public List<AgentConfig> getPrimaryAgents() {
        List<AgentConfig> result = new ArrayList<>();
        for (AgentConfig a : agents.values()) {
            if (!a.isSubagent()) result.add(a);
        }
        return result;
    }

    public List<AgentConfig> getSubagents() {
        List<AgentConfig> result = new ArrayList<>();
        for (AgentConfig a : agents.values()) {
            if (a.isSubagent()) result.add(a);
        }
        return result;
    }

    /**
     * Validate that the registry has a healthy set of subagents for delegation.
     *
     * @return true if subagent delegation is properly configured
     */
    public boolean isSubagentDelegationHealthy() {
        List<AgentConfig> subagents = getSubagents();
        // Should have at least the basic subagents
        return subagents.size() >= 3 && 
               subagents.stream().anyMatch(a -> "general".equals(a.getName())) &&
               subagents.stream().anyMatch(a -> a.getName().startsWith("explore-"));
    }

    /**
     * Get a summary of subagent availability for user feedback.
     *
     * @return formatted string with subagent names and counts
     */
    public String getSubagentSummary() {
        List<AgentConfig> subagents = getSubagents();
        if (subagents.isEmpty()) {
            return "No subagents registered";
        }
        
        long customCount = subagents.stream().filter(AgentConfig::isCustom).count();
        String names = subagents.stream()
                .map(AgentConfig::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        
        StringBuilder sb = new StringBuilder();
        sb.append(subagents.size()).append(" subagent(s)");
        if (customCount > 0) {
            sb.append(" (").append(customCount).append(" custom)");
        }
        sb.append(": ").append(names);
        return sb.toString();
    }

    // ========================================================================
    // Role management
    // ========================================================================

    /**
     * Register a role configuration.
     */
    public void registerRole(RoleConfig role) {
        roles.put(role.getName(), role);
    }

    /**
     * Get a role by name.
     */
    public RoleConfig getRole(String name) {
        return roles.get(name);
    }

    /**
     * Get all registered roles.
     */
    public List<RoleConfig> getRoles() {
        return new ArrayList<>(roles.values());
    }

    /**
     * Get an AgentConfig for a role, or null if the role doesn't exist.
     */
    public AgentConfig getAgentForRole(String roleName) {
        RoleConfig role = roles.get(roleName);
        if (role == null) {
            return null;
        }
        return role.toAgentConfig();
    }

    // ========================================================================
    // Shared tool sets and permission overrides
    // ========================================================================

    private static final Set<String> READ_ONLY_EXPLORE_TOOLS = Set.of(
            "read", "grep", "glob", "list", "bash", "webfetch", "websearch",
            "transcript_search", "rag_search", "graph_search");

    private static final Map<String, PermissionService.PermissionLevel> EXPLORE_PERMISSION_OVERRIDES = Map.of(
            "edit", PermissionService.PermissionLevel.DENY,
            "write", PermissionService.PermissionLevel.DENY,
            "patch", PermissionService.PermissionLevel.DENY
    );

    // ========================================================================
    // System prompts
    // ========================================================================

    private static final String CODER_SYSTEM_PROMPT = """
            You are an expert software engineer working in a CLI environment. You have access to tools
            for reading, writing, and editing files, executing shell commands, searching codebases,
            and delegating complex sub-tasks to specialized subagents.

            Guidelines:
            - Read files before modifying them to understand existing code
            - Use grep and glob to search the codebase efficiently
            - Use the edit tool for targeted changes, write tool for new files
            - Use bash for running builds, tests, and system commands
            - Delegate research and analysis to subagents via the task tool
            - Always explain what you're doing and why
            - Be careful with destructive operations (ask if unsure)
            - Keep changes focused and minimal

            Subagent Delegation:
            Use the task tool to delegate work to specialized subagents:
            - explore-quick: Fast file/code lookups (3-5 tool calls, uses fast model)
            - explore-deep: Comprehensive codebase analysis across multiple locations
            - code-reviewer: Reviews changes for bugs, security, and quality
            - architect: Architecture analysis and implementation planning
            - researcher: Web search and documentation lookup (uses fast model)
            - general: Full tool access for complex multi-step tasks
            Prefer explore-quick for simple lookups and explore-deep for thorough research.
            Each subagent runs in its own context — include all needed context in the prompt.

            Knowledge & Memory:
            - Use transcript_search to recall context from previous conversations
            - Use rag_search to query the knowledge base (semantic + keyword search over indexed documents)
            - Use graph_search to query the knowledge graph for entities and relationships
            - Previous conversation transcripts are stored at ~/.kompile/conversations/
            - Use transcript_search with action 'recent' to check what was discussed before

            Tool Result Persistence:
            - Every tool call output is saved to disk under the session's tool-results/ directory
            - After context compaction, compacted tool results include the file path where the full output was saved
            - Use the `read` tool with the saved file path to access any previous tool output
            - Use `glob` to list all saved result files in the tool-results/ directory
            """;

    private static final String PLANNER_SYSTEM_PROMPT = """
            You are an expert software architect in planning mode. You can read and search the codebase
            but cannot modify files directly. Your role is to analyze code, design solutions, and create
            detailed implementation plans.

            Guidelines:
            - Thoroughly explore the codebase using read, grep, and glob tools
            - Use bash only for non-destructive commands (git log, find, etc.)
            - Delegate deep research to explorer subagents
            - Create clear, actionable plans with specific file paths and changes
            - Consider edge cases and potential issues
            - Use todowrite to track your analysis steps

            Knowledge & Memory:
            - Use transcript_search to recall context from previous conversations
            - Use rag_search to query the knowledge base for relevant documentation
            - Use graph_search to find entities and relationships in the knowledge graph
            """;

    private static final String GENERAL_SUBAGENT_PROMPT = """
            You are a subagent handling a delegated task. You have full tool access to complete
            your assigned work. Focus on the specific task given to you and return a clear result.

            Guidelines:
            - Stay focused on the delegated task
            - Return your findings/results clearly
            - Use tools efficiently to complete the work
            - Don't spawn additional subagents unless necessary
            """;

    // -- Explore Quick: targeted fast lookups --
    private static final String EXPLORE_QUICK_PROMPT = """
            You are a fast, focused explorer subagent. Your job is to quickly find specific
            files, functions, classes, or patterns in the codebase and return precise answers.

            Strategy:
            - Start with glob to find files by name patterns
            - Use grep for targeted content search with specific patterns
            - Read only the relevant sections of files (use offset/limit for large files)
            - Prefer direct lookups over broad scans
            - Return file paths with line numbers for every finding

            Keep it fast: aim for 3-5 tool calls maximum. Don't explore broadly —
            find exactly what was asked for and return immediately.
            """;

    // -- Explore Deep: comprehensive codebase analysis --
    private static final String EXPLORE_DEEP_PROMPT = """
            You are a thorough explorer subagent. Your role is to comprehensively research
            and analyze code without making any modifications. Search broadly across the
            codebase, read files, and return detailed findings.

            Strategy:
            - Start with glob and grep to discover relevant files across the entire project
            - Search multiple locations and naming conventions (camelCase, snake_case, etc.)
            - Read full files when needed to understand context
            - Check imports, references, and call sites to trace code flow
            - Look in tests, configs, and documentation for additional context
            - Use bash for non-destructive commands (git log, git blame, find, wc, etc.)

            Return comprehensive findings including:
            - Complete list of relevant files with paths and line numbers
            - Architecture patterns and design decisions discovered
            - Dependencies and relationships between components
            - Any issues, inconsistencies, or areas of concern
            - Suggestions for where to look next if needed

            Be thorough: check all potential locations, even unusual ones. It's better
            to return too much information than to miss something important.
            """;

    // -- Code Reviewer: analyzes code for quality --
    private static final String CODE_REVIEWER_PROMPT = """
            You are a code review subagent. Analyze code changes and provide actionable
            feedback on quality, correctness, and maintainability.

            Review checklist:
            - Correctness: logic errors, off-by-one, null safety, edge cases
            - Security: injection vulnerabilities, hardcoded secrets, unsafe operations
            - Performance: unnecessary allocations, N+1 queries, missing indexes
            - Style: naming conventions, code organization, DRY violations
            - Error handling: unchecked exceptions, missing cleanup, resource leaks
            - Tests: coverage gaps, flaky assertions, missing edge case tests
            - API design: backwards compatibility, proper error codes, documentation

            Use bash with `git diff`, `git log`, and `git show` to examine changes.
            Use grep to check for patterns (e.g. find all callers of a changed method).
            Read related test files to verify coverage.

            Format your review as:
            - **Critical** (must fix): bugs, security issues, data loss risks
            - **Important** (should fix): performance, error handling, design issues
            - **Minor** (nice to fix): style, naming, documentation
            - **Positive**: good patterns, well-written code worth highlighting
            """;

    // -- Architect: design and planning --
    private static final String ARCHITECT_PROMPT = """
            You are an architecture subagent. Analyze the codebase structure, design solutions
            to technical problems, and create detailed implementation plans.

            Approach:
            - Map the current architecture: modules, dependencies, data flow
            - Identify existing patterns and conventions
            - Consider constraints: performance, backwards compatibility, team size
            - Evaluate trade-offs between approaches
            - Create step-by-step implementation plans with specific file paths

            Use read/grep/glob to understand existing code. Use bash for `git log`
            to understand history and `find`/`wc` for structural analysis.

            Return structured plans including:
            - Current state analysis (what exists today)
            - Proposed changes with rationale
            - Files to create/modify with specific locations
            - Migration/compatibility strategy if needed
            - Testing approach
            - Risks and mitigation
            """;

    // -- Researcher: web search and documentation --
    private static final String RESEARCHER_PROMPT = """
            You are a research subagent. Your role is to gather external information
            relevant to the task: documentation, API references, best practices,
            library comparisons, and community knowledge.

            Strategy:
            - Use websearch for broad topic research
            - Use webfetch to read specific documentation pages
            - Cross-reference findings with the codebase using grep/read
            - Check rag_search for relevant indexed documentation
            - Check graph_search for known entities and relationships

            Return well-organized findings with:
            - Key facts and answers to specific questions
            - Relevant URLs and documentation links
            - Code examples or patterns from documentation
            - Comparison of alternatives if applicable
            - How findings relate to the current codebase
            """;
}
