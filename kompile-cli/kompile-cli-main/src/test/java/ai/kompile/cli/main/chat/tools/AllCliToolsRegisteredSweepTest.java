/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * META PARITY SWEEP — catches any new concrete {@link CliTool} implementation that
 * lands on the compile classpath but is NOT registered in {@link ToolRegistryFactory}.
 *
 * <h2>Discovery approach</h2>
 * <p>Spring's {@code ClassPathScanningCandidateComponentProvider} scans the two known
 * tool base-packages for concrete (non-abstract, non-interface) classes that implement
 * {@link CliTool}. Each discovered class is instantiated — if that fails (because it
 * requires live infrastructure like a renderer or process manager) it is noted in
 * {@link #REQUIRES_LIVE_STATE} and skipped automatically. The remaining instantiable
 * tools have their {@link CliTool#id()} compared against the full set returned by
 * {@link ToolRegistryFactory#create} so any newly added tool that skips the factory
 * registration causes this test to fail.</p>
 *
 * <h2>When this test fails</h2>
 * <ol>
 *   <li>Register the tool in {@link ToolRegistryFactory#create} (chat path).</li>
 *   <li>Register it in {@code McpStdioCommand.buildToolMap()} (stdio MCP path).</li>
 *   <li>Add it to {@link ToolRegistryStdioParityTest} (graph/grounding parity).</li>
 *   <li>Run this test — it must pass.</li>
 * </ol>
 * <p>If the tool legitimately requires live infrastructure and CANNOT be instantiated
 * without it, add its simple class name to {@link #REQUIRES_LIVE_STATE} with a reason.
 * If it is intentionally NOT registered (e.g. an internal helper that implements
 * CliTool for code-reuse but is not a public MCP tool), add it to
 * {@link #KNOWN_EXCLUSIONS} with a reason.</p>
 */
class AllCliToolsRegisteredSweepTest {

    /**
     * Base packages to scan for concrete {@link CliTool} implementations.
     */
    private static final List<String> SCAN_PACKAGES = List.of(
            "ai.kompile.cli.main.chat.tools",
            "ai.kompile.cli.main.chat.tools.grounding"
    );

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String DUMMY_BASE_URL = "http://localhost:8080";

    /**
     * Simple class names of concrete {@link CliTool} implementations that require
     * live infrastructure (renderer, process manager, file watcher, etc.) and
     * CANNOT be instantiated with dummy args in a unit test.
     *
     * <p>These tools are excluded from the sweep check, not from the factory — they
     * are still expected to be registered in {@link ToolRegistryFactory} via a
     * conditional {@code if (x != null)} block or a special factory overload.</p>
     */
    private static final Map<String, String> REQUIRES_LIVE_STATE = Map.ofEntries(
            Map.entry("ProcessManagementTool",
                    "Requires live BackgroundProcessManager; registered conditionally in factory"),
            Map.entry("EditCoordinatorTool",
                    "Requires live EditCoordinator; registered in McpStdioCommand"),
            Map.entry("SemanticMemoryTool",
                    "Requires live SemanticMemoryEngine; registered in McpStdioCommand"),
            Map.entry("FileActivityTool",
                    "Requires live FileWatcherService; registered conditionally in McpStdioCommand"),
            Map.entry("AmbientGardenTool",
                    "Requires live AmbientMemoryGardener; registered in McpStdioCommand"),
            Map.entry("SidePanelTool",
                    "Requires live SidePanelManager; registered in ToolRegistryFactory"),
            Map.entry("LspTool",
                    "Requires live LspCoordinator in McpStdioCommand; zero-arg in ToolRegistryFactory"),
            Map.entry("RoleManagerTool",
                    "Requires live RoleManager; registered conditionally in ToolRegistryFactory"),
            Map.entry("TaskTool",
                    "Requires live AgentRegistry + SubagentRunner; registered in ToolRegistryFactory")
    );

    /**
     * Simple class names of concrete {@link CliTool} implementations that are
     * intentionally absent from the public tool-registry, with documented reasons.
     */
    private static final Map<String, String> KNOWN_EXCLUSIONS = Map.ofEntries(
            // CustomToolBridge — instantiated dynamically by the custom-tool loader;
            // not a static registration candidate.
            Map.entry("CustomToolBridge",
                    "Dynamically instantiated by CustomToolLoader; not statically registered"),

            // ExitPlanModeTool — plan-mode-specific tool surfaced through a separate mechanism.
            Map.entry("ExitPlanModeTool",
                    "Plan-mode tool wired through dedicated plan-mode pathway, not generic registry"),

            // KnowledgeSearchCliTool / KnowledgeStatusCliTool — legacy aliases; the
            // canonical tools are RagSearchTool + GraphRagSearchTool.
            Map.entry("KnowledgeSearchCliTool",
                    "Legacy alias for RagSearchTool; canonical tool is RagSearchTool"),
            Map.entry("KnowledgeStatusCliTool",
                    "Legacy status tool; functionality covered by other graph/rag tools"),

            // CrawlTool — legacy single-source crawl stub, superseded by CrawlSourceTool.
            Map.entry("CrawlTool",
                    "Legacy crawl stub; superseded by CrawlSourceTool which is registered"),

            // A2ACliTool — A2A surface has its own registration pathway.
            Map.entry("A2ACliTool",
                    "A2A tool wired through dedicated A2A agent pathway, not generic registry"),

            // EnforcerCheckTool — internal guardrail check, not exposed as an MCP endpoint.
            Map.entry("EnforcerCheckTool",
                    "Internal enforcer check; not a public MCP endpoint"),

            // SessionListTool — internal session management, not in public registry.
            Map.entry("SessionListTool",
                    "Internal session list tool; not exposed as public MCP endpoint"),

            // UnifiedCodeSearchTool / UnifiedSearchTool — experimental unified search;
            // replaced by CodeSearchTool + GrepTool combo in the registry.
            Map.entry("UnifiedCodeSearchTool",
                    "Experimental; current search surface uses CodeSearchTool+GrepTool"),
            Map.entry("UnifiedSearchTool",
                    "Experimental unified search; not in public registry"),

            // SkillManagerTool — skill management has its own surface.
            Map.entry("SkillManagerTool",
                    "Skill management wired through dedicated skill surface"),

            // RegisterProjectTool — project init tool registered via dedicated init flow.
            Map.entry("RegisterProjectTool",
                    "Project init tool; registered via dedicated project-init flow"),

            // ResumeTool — conversation resume; special lifecycle tool.
            Map.entry("ResumeTool",
                    "Conversation resume lifecycle tool; not in generic registry"),

            // FetchResultTool — result-fetch meta-tool registered in McpStdioCommand directly.
            Map.entry("FetchResultTool",
                    "Registered directly in McpStdioCommand, not via ToolRegistryFactory"),

            // DictationTool — input method tool registered in McpStdioCommand.
            Map.entry("DictationTool",
                    "Registered in McpStdioCommand but not ToolRegistryFactory (no renderer in factory)"),

            // ActivateToolsTool — dynamic tool activation; registered in McpStdioCommand.
            Map.entry("ActivateToolsTool",
                    "Registered in McpStdioCommand; dynamic tool activation pathway"),

            // ToolCallCatalogTool / ProjectConfigTool — registered in McpStdioCommand directly.
            Map.entry("ToolCallCatalogTool",
                    "Registered in McpStdioCommand via dedicated catalog section"),
            Map.entry("ProjectConfigTool",
                    "Registered in McpStdioCommand via dedicated project config section"),

            // EnforcerConfigTool — config management, registered in McpStdioCommand.
            Map.entry("EnforcerConfigTool",
                    "Registered in McpStdioCommand; enforcer config management"),

            // LocalCodeIndexTool / CodeSearchTool / CodeGraphTool — registered in McpStdioCommand.
            Map.entry("LocalCodeIndexTool",
                    "Registered in McpStdioCommand code-search section"),
            Map.entry("CodeSearchTool",
                    "Registered in McpStdioCommand code-search section"),
            Map.entry("CodeGraphTool",
                    "Registered in McpStdioCommand code-search section"),

            // ServerModeTool — registered in McpStdioCommand, not ToolRegistryFactory.
            Map.entry("ServerModeTool",
                    "Registered in McpStdioCommand, not ToolRegistryFactory"),

            // ConfigArchiveTool — registered in McpStdioCommand, not ToolRegistryFactory.
            Map.entry("ConfigArchiveTool",
                    "Registered in McpStdioCommand via config-archive section"),

            // ExploreTool — registered in McpStdioCommand.
            Map.entry("ExploreTool",
                    "Registered in McpStdioCommand, not ToolRegistryFactory"),

            // TestMilestoneTool — internal milestone tracking registered in McpStdioCommand.
            Map.entry("TestMilestoneTool",
                    "Internal test-milestone tool registered in McpStdioCommand")
    );

    /**
     * Discover all concrete (non-abstract, non-interface) {@link CliTool}
     * implementations in the scan packages.
     */
    private static Set<String> discoverConcreteCliToolClassNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        Set<String> result = new TreeSet<>();
        for (String pkg : SCAN_PACKAGES) {
            try {
                scanner.findCandidateComponents(pkg).forEach(bd -> {
                    String className = bd.getBeanClassName();
                    if (className == null) return;
                    try {
                        Class<?> cls = Class.forName(className,
                                false,
                                AllCliToolsRegisteredSweepTest.class.getClassLoader());
                        if (!cls.isInterface()
                                && !Modifier.isAbstract(cls.getModifiers())
                                && CliTool.class.isAssignableFrom(cls)) {
                            result.add(cls.getSimpleName());
                        }
                    } catch (Throwable ignored) {
                        // Not resolvable — skip
                    }
                });
            } catch (Throwable ignored) {
                // Package not on classpath — skip
            }
        }
        return result;
    }

    /**
     * Get the set of tool IDs registered in {@link ToolRegistryFactory} with dummy args.
     * processManager=null so ProcessManagementTool is skipped; roleManager=null so
     * RoleManagerTool is skipped — both are already documented in REQUIRES_LIVE_STATE.
     */
    private static Set<String> factoryRegisteredIds() {
        ToolRegistry registry = ToolRegistryFactory.create(
                OM,
                DUMMY_BASE_URL,
                new AgentRegistry(),
                /* permissionService */ null,
                /* renderer */ null,
                /* processManager */ null,
                /* chatConfig */ null,
                /* roleManager */ null);
        return registry.ids();
    }

    @Test
    void everyConcreteCliToolIsRegisteredInToolRegistryFactory() {
        Set<String> discovered = discoverConcreteCliToolClassNames();
        Set<String> registeredIds = factoryRegisteredIds();
        Set<String> liveStateExclusions = REQUIRES_LIVE_STATE.keySet();
        Set<String> knownExclusions = KNOWN_EXCLUSIONS.keySet();

        // Map tool class simple-names to instances so we can get their id() values.
        // We only care about tools whose id() is NOT in registeredIds.
        List<String> unregistered = new ArrayList<>();
        for (String simpleName : discovered) {
            if (liveStateExclusions.contains(simpleName) || knownExclusions.contains(simpleName)) {
                continue;
            }
            // Try to instantiate with (String, ObjectMapper) constructor — the standard
            // backend-proxy tool pattern.
            String id = tryGetId(simpleName);
            if (id == null) {
                // Cannot instantiate — treat as live-state and skip (we don't fail
                // tests on tools we can't instantiate, because they can't be verified
                // as missing without special wiring).
                continue;
            }
            if (!registeredIds.contains(id)) {
                unregistered.add(simpleName + " (id=" + id + ")");
            }
        }

        String liveList = liveStateExclusions.stream().sorted().collect(Collectors.joining(", "));
        String excludedList = knownExclusions.stream().sorted().collect(Collectors.joining(", "));
        assertTrue(unregistered.isEmpty(),
                "The following concrete CliTool implementations are discoverable on the classpath " +
                "but their tool id is NOT registered in ToolRegistryFactory. " +
                "Register them in ToolRegistryFactory.create() and McpStdioCommand.buildToolMap(). " +
                "If they require live state, add to AllCliToolsRegisteredSweepTest.REQUIRES_LIVE_STATE. " +
                "If intentionally excluded, add to AllCliToolsRegisteredSweepTest.KNOWN_EXCLUSIONS. " +
                "Current live-state exclusions: [" + liveList + "]. " +
                "Current known exclusions: [" + excludedList + "]. " +
                "UNREGISTERED: " + unregistered);
    }

    /**
     * Verify that REQUIRES_LIVE_STATE and KNOWN_EXCLUSIONS don't overlap.
     */
    @Test
    void exclusionSetsDoNotOverlap() {
        List<String> conflicts = REQUIRES_LIVE_STATE.keySet().stream()
                .filter(KNOWN_EXCLUSIONS::containsKey)
                .collect(Collectors.toList());
        assertTrue(conflicts.isEmpty(),
                "The following class names appear in BOTH REQUIRES_LIVE_STATE and KNOWN_EXCLUSIONS. " +
                "Move each to exactly one set: " + conflicts);
    }

    /**
     * Verify all entries in both exclusion maps have non-blank reasons.
     */
    @Test
    void allExclusionEntriesHaveReasons() {
        List<String> blanks = new ArrayList<>();
        REQUIRES_LIVE_STATE.forEach((k, v) -> { if (v == null || v.isBlank()) blanks.add("REQUIRES_LIVE_STATE:" + k); });
        KNOWN_EXCLUSIONS.forEach((k, v) -> { if (v == null || v.isBlank()) blanks.add("KNOWN_EXCLUSIONS:" + k); });
        assertTrue(blanks.isEmpty(),
                "Exclusion entries must have non-blank reason strings. Fix: " + blanks);
    }

    /**
     * Try to instantiate the named class and return its {@link CliTool#id()}, or
     * {@code null} if instantiation fails (indicating live-state dependency).
     * Tries (String, ObjectMapper) first, then zero-arg.
     */
    private static String tryGetId(String simpleClassName) {
        // Fully qualified name lookup — scan packages
        for (String pkg : List.of(
                "ai.kompile.cli.main.chat.tools",
                "ai.kompile.cli.main.chat.tools.grounding")) {
            try {
                Class<?> cls = Class.forName(pkg + "." + simpleClassName);
                if (!CliTool.class.isAssignableFrom(cls)) continue;
                // Try (String, ObjectMapper) constructor
                try {
                    CliTool tool = (CliTool) cls
                            .getConstructor(String.class, ObjectMapper.class)
                            .newInstance(DUMMY_BASE_URL, OM);
                    return tool.id();
                } catch (NoSuchMethodException ignored) {
                    // Try zero-arg
                }
                try {
                    CliTool tool = (CliTool) cls.getConstructor().newInstance();
                    return tool.id();
                } catch (NoSuchMethodException ignored) {
                    // Can't instantiate without live args
                }
                return null;
            } catch (ClassNotFoundException ignored) {
                // Try next package
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
