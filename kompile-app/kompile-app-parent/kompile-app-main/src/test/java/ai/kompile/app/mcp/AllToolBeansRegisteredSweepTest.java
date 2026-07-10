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

package ai.kompile.app.mcp;

import ai.kompile.app.config.McpSseServerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * META PARITY SWEEP — catches any new {@code @Tool}-annotated class that lands on
 * the compile classpath but is NOT yet wired into the two hand-maintained
 * registration lists.
 *
 * <p>Discovery approach: Spring's {@link ClassPathScanningCandidateComponentProvider}
 * scans the known tool base-packages for classes that carry at least one method
 * annotated with {@link Tool}. Any discovered class whose simple name is absent from
 * both {@link McpToolRegistry} and {@link McpSseServerConfiguration} field lists
 * (and not in {@link #KNOWN_EXCLUSIONS}) causes the test to fail with an actionable
 * message.</p>
 *
 * <h2>When this test fails</h2>
 * <p>Add the missing class to:</p>
 * <ol>
 *   <li>{@link McpToolRegistry} — {@code @Autowired(required=false)} field +
 *       {@code addBeanIfAvailable} call inside {@code collectToolBeans()}.</li>
 *   <li>{@link McpSseServerConfiguration} — same field + {@code addToolIfAvailable}
 *       call inside {@code kompileToolCallbackProvider()}.</li>
 *   <li>{@link McpToolRegistryParityTest#REQUIRED_TOOL_CLASSES} — add the simple
 *       class name so the point-in-time parity test also tracks it.</li>
 * </ol>
 * <p>If the class is intentionally absent (wrong module, duplicate, not activated
 * in app-main), add its simple name to {@link #KNOWN_EXCLUSIONS} with a reason.</p>
 */
class AllToolBeansRegisteredSweepTest {

    /**
     * Base packages to scan. Covers every module that app-main transitively depends
     * on and that is known to contribute {@code @Tool}-annotated beans.
     *
     * <p>Add an entry here when a new tool module is added as a dependency of
     * kompile-app-main.</p>
     */
    private static final List<String> SCAN_PACKAGES = List.of(
            // app-main's own tools
            "ai.kompile.app.tools",
            // kompile-tool-* middleware modules
            "ai.kompile.tool.graph",
            "ai.kompile.tool.graphlocalization",
            "ai.kompile.tool.knowledge",
            "ai.kompile.tool.rag",
            "ai.kompile.tool.filesystem",
            "ai.kompile.tool.tablesearch",
            "ai.kompile.tool.crawler",
            // process-mining modules
            "ai.kompile.process.discovery",
            "ai.kompile.process.tool",
            // knowledge-graph and code-indexer modules
            "ai.kompile.knowledgegraph.tool",
            "ai.kompile.codeindexer.tool"
    );

    /**
     * Simple class names of {@code @Tool}-annotated classes that are intentionally
     * absent from the registry, with documented justification.
     *
     * <p>NEVER add a class here just to silence the test. The correct fix is to wire
     * the class into both registries.</p>
     */
    private static final Map<String, String> KNOWN_EXCLUSIONS = Map.ofEntries(
            // kompile-tool-model-staging — NOT a compile dep of app-main; staging tool
            // is managed through the model-staging sidecar, not the generic registry.
            Map.entry("ModelStagingTool",
                    "kompile-tool-model-staging is not a compile dep of app-main"),

            // kompile-tool-workflow / kompile-tool-camel — optional integration modules
            // not included in the standard kompile-app-main build.
            Map.entry("WorkflowTool",
                    "kompile-tool-workflow is an optional integration module"),
            Map.entry("BusinessRulesTool",
                    "kompile-tool-camel is an optional integration module"),
            Map.entry("CamelRouteTool",
                    "kompile-tool-camel is an optional integration module"),

            // kompile-compute-graph-excel — optional Excel integration not in app-main.
            Map.entry("ExcelComputeTool",
                    "kompile-compute-graph-excel is an optional module"),

            // kompile-a2a — its MCP surface goes through the A2A controller, not here.
            Map.entry("A2ADelegationTool",
                    "A2A surface managed through A2A controller/gateway, not generic registry"),

            // ai.kompile.app.tools — these carry @Tool methods used for internal wiring
            // or legacy purposes and are intentionally not exposed as MCP endpoints.
            Map.entry("TestMilestoneTool",
                    "Internal test-milestone tracking tool; not part of the public MCP surface"),
            Map.entry("ComputeGraphTool",
                    "Internal SameDiff compute-graph inspection tool; not in public registry"),
            Map.entry("SdxInferenceTool",
                    "Internal SDX inference tool; not in public MCP registry"),
            Map.entry("ServingInfrastructureTool",
                    "Internal serving infrastructure tool; not in public MCP registry"),
            Map.entry("ConfigArchiveMcpTool",
                    "Config-archive tool exposed via dedicated CLI command; not in generic registry"),
            Map.entry("ToolCallCatalogMcpTool",
                    "Tool-call catalog exposed via dedicated CLI command; not in generic registry"),

            // crawler tools — managed through the crawl pipeline, not the MCP registry.
            Map.entry("CrawlerToolImpl",
                    "Crawler tool managed through crawl pipeline, not generic MCP registry"),
            Map.entry("EntityResolutionTool",
                    "Entity resolution used internally in crawl pipeline, not MCP registry"),
            Map.entry("UnifiedCrawlGraphTool",
                    "Unified crawl graph tool used in crawl pipeline, not MCP registry")
    );

    /**
     * Discover every class in {@link #SCAN_PACKAGES} that has at least one
     * {@link Tool}-annotated method.
     */
    private static Set<String> discoverToolClassNames() {
        // We need to find classes with @Tool methods — ClassPathScanningCandidateComponentProvider
        // supports class-level annotation filters. @Tool is a method annotation so we
        // use INCLUDE_ALL and post-filter. Use includeAnnotationTypeFilter = false and
        // scan all components, then check methods.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // Accept everything concrete (we'll post-filter for @Tool methods).
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        Set<String> toolClassNames = new TreeSet<>();
        for (String pkg : SCAN_PACKAGES) {
            try {
                scanner.findCandidateComponents(pkg).forEach(bd -> {
                    String className = bd.getBeanClassName();
                    if (className == null) return;
                    try {
                        Class<?> cls = Class.forName(className,
                                false,
                                AllToolBeansRegisteredSweepTest.class.getClassLoader());
                        for (Method m : cls.getDeclaredMethods()) {
                            if (m.isAnnotationPresent(Tool.class)) {
                                toolClassNames.add(cls.getSimpleName());
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        // Class not resolvable in test classpath — skip
                    }
                });
            } catch (Throwable ignored) {
                // Package not on classpath — skip
            }
        }
        return toolClassNames;
    }

    private static Set<String> registryFieldSimpleNames(Class<?> cls) {
        Set<String> names = new TreeSet<>();
        for (Field f : cls.getDeclaredFields()) {
            names.add(f.getType().getSimpleName());
        }
        return names;
    }

    @Test
    void everyToolClassIsRegisteredInMcpToolRegistry() {
        Set<String> discovered = discoverToolClassNames();
        Set<String> registryFields = registryFieldSimpleNames(McpToolRegistry.class);
        Set<String> exclusions = KNOWN_EXCLUSIONS.keySet();

        List<String> unregistered = new ArrayList<>();
        for (String name : discovered) {
            if (!exclusions.contains(name) && !registryFields.contains(name)) {
                unregistered.add(name);
            }
        }

        String exclusionList = exclusions.stream().sorted().collect(Collectors.joining(", "));
        assertTrue(unregistered.isEmpty(),
                "The following @Tool-annotated classes are discoverable on the classpath but NOT " +
                "wired into McpToolRegistry. Add an @Autowired(required=false) field and a " +
                "addBeanIfAvailable call inside collectToolBeans(). Also mirror in " +
                "McpSseServerConfiguration and add to McpToolRegistryParityTest.REQUIRED_TOOL_CLASSES. " +
                "If intentionally absent, add to AllToolBeansRegisteredSweepTest.KNOWN_EXCLUSIONS " +
                "with a reason. Known exclusions are: [" + exclusionList + "]. " +
                "UNREGISTERED: " + unregistered);
    }

    @Test
    void everyToolClassIsRegisteredInMcpSseServerConfiguration() {
        Set<String> discovered = discoverToolClassNames();
        Set<String> sseFields = registryFieldSimpleNames(McpSseServerConfiguration.class);
        Set<String> exclusions = KNOWN_EXCLUSIONS.keySet();

        // Some tools are McpToolRegistry-only (e.g. CodeIndexerToolImpl which is wired
        // in registry but not SSE — allowed as long as it's in registry).
        // The sweep here only flags classes absent from BOTH lists.
        Set<String> registryFields = registryFieldSimpleNames(McpToolRegistry.class);

        List<String> unregistered = new ArrayList<>();
        for (String name : discovered) {
            if (!exclusions.contains(name)
                    && !sseFields.contains(name)
                    && !registryFields.contains(name)) {
                unregistered.add(name);
            }
        }

        assertTrue(unregistered.isEmpty(),
                "The following @Tool-annotated classes are absent from BOTH McpToolRegistry AND " +
                "McpSseServerConfiguration. They are fully invisible to MCP clients. " +
                "Wire them into at least McpToolRegistry (and ideally both). " +
                "UNREGISTERED in both: " + unregistered);
    }

    /**
     * Verify KNOWN_EXCLUSIONS entries are documented — each must have a non-blank reason.
     */
    @Test
    void knownExclusionsAllHaveReasons() {
        List<String> blanks = KNOWN_EXCLUSIONS.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        assertTrue(blanks.isEmpty(),
                "KNOWN_EXCLUSIONS entries must have non-blank reason strings. " +
                "Fix these entries: " + blanks);
    }
}
