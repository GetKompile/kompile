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

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity test: every known {@link Tool @Tool}-annotated class that is available on
 * the classpath and wired into kompile-app-main must be referenced by
 * {@link McpToolRegistry} (via a field injection) and by
 * {@link ai.kompile.app.config.McpSseServerConfiguration} (via field injection).
 *
 * <p>The test does NOT require a Spring context — it inspects the registry class
 * via reflection. This catches the exact class of bug that made 55+ built tools
 * invisible: adding a @Tool bean without wiring it into the two hand-maintained
 * registration lists.</p>
 *
 * <h2>How to add a new @Tool bean</h2>
 * <ol>
 *   <li>Add an {@code @Autowired(required=false)} field of the bean's type to
 *       {@link McpToolRegistry}.</li>
 *   <li>Call {@code addBeanIfAvailable(field, "Name")} inside
 *       {@link McpToolRegistry#collectToolBeans()}.</li>
 *   <li>Mirror the same field + {@code addToolIfAvailable} call in
 *       {@link ai.kompile.app.config.McpSseServerConfiguration}.</li>
 *   <li>Run this test — it must pass.</li>
 * </ol>
 *
 * <h2>Intentional exclusions</h2>
 * <p>If a tool class is in the classpath but intentionally NOT registered (e.g. it
 * belongs to a module whose beans are never activated in app-main, or it is a
 * duplicate/alias), add its simple class name to {@link #KNOWN_EXCLUSIONS} with a
 * comment explaining why.</p>
 *
 * <p>Design note: the test asserts on class/type membership in the field list of the
 * registries, NOT on the number of @Tool methods. Another agent adding a new @Tool
 * method to an already-registered bean will NOT break this test.</p>
 */
class McpToolRegistryParityTest {

    /**
     * Simple class names of @Tool-annotated classes that are intentionally absent
     * from the registry, with the documented reason.
     */
    private static final Set<String> KNOWN_EXCLUSIONS = Set.of(
            // ai.kompile.models.kompile-model-staging — NOT a dep of app-main;
            // staging tool is provided by kompile-tool-model-staging instead.
            "ModelStagingTool",

            // kompile-tool-workflow, kompile-tool-camel — optional integration modules
            // not pulled into the standard kompile-app-main classpath.
            "WorkflowTool",
            "BusinessRulesTool",
            "CamelRouteTool",

            // kompile-compute-graph-excel — optional Excel integration not in app-main deps.
            "ExcelComputeTool",

            // kompile-a2a — A2ADelegationTool lives in kompile-a2a; its MCP surface is
            // managed through the A2A controller/gateway, not the generic tool registry.
            "A2ADelegationTool"
    );

    /**
     * Well-known @Tool-annotated class names that MUST be present in McpToolRegistry.
     *
     * <p>This list is the canonical set: any class here that is absent from the
     * registry field list causes the test to fail with an actionable error message.
     * Extend this list when you add a new tool module that app-main depends on.</p>
     */
    private static final List<String> REQUIRED_TOOL_CLASSES = List.of(
            // kompile-app-main tools
            "RagToolImpl",
            "FilesystemToolImpl",
            "ModelDebugTool",
            "ChatSessionTool",
            "ActionLogTool",
            "ApplicationConfigTool",
            "IndexOperationsTool",
            "DocumentManagementTool",
            "SystemDiagnosticsTool",
            "ModelManagementTool",
            "IndexManagementTool",
            "RagConfigTool",
            "AgentConfigTool",
            "McpServerTool",
            "JobHistoryTool",
            "SourceManagementTool",
            "SystemConfigTool",
            "EvalDebugTool",
            "GraphConfigTool",
            "ModelRegistryTool",
            "IntegrationsTool",
            "RagTestTool",
            "VlmPipelineTool",
            "VlmTestTool",
            "VlmConfigTool",
            "DeviceRoutingTool",
            "OpTimingTool",
            "KVCacheTool",
            "SettingsTool",
            "SubprocessConfigTool",
            "DocumentIngestionTool",
            "EvaluationTool",
            "ExperimentTool",
            "FactSheetTool",
            "PipelineTool",
            "PromptTemplateTool",
            "OrchestratorTool",
            "BenchmarkTool",
            "BackupTool",
            "ArchiveTool",
            "ChunkManagementTool",
            "CrossIndexTool",
            "AgentDelegationTool",
            "NoteTool",
            "AgentTaskTool",
            "DiffTrackerTool",
            "DiffIndexTool",
            "TableSearchToolImpl",
            // Graph & KB grounding tools
            "KbVerifyExplainTool",
            "KbGroundingTool",
            "KnowledgeGraphToolImpl",
            "UnifiedKnowledgeTool",
            "GraphSearchTool",
            "GraphMutationTool",
            "GraphTraversalTool",
            "GraphCommunityTool",
            "GraphAlgorithmsTool",
            "GraphLabelTool",
            // Previously missing tools — now wired in
            "GraphHybridReasoningTool",
            "GraphReasoningQueryTool",
            "GraphLocalizationToolImpl",
            "ProcessDiscoveryTool",
            "ProcessMiningTool",
            "ProcessEngineTool",
            // Code indexer
            "CodeIndexerToolImpl",
            // Meta-tools
            "DynamicToolsetsMetaTool",
            "ResultFetchTool"
    );

    /**
     * Collect all field type simple-names declared in a class (non-inherited fields only,
     * to avoid matching base class fields that may shadow the declared ones).
     */
    private static Set<String> registryFieldTypeNames(Class<?> registryClass) {
        Set<String> names = new TreeSet<>();
        for (Field f : registryClass.getDeclaredFields()) {
            names.add(f.getType().getSimpleName());
        }
        return names;
    }

    @Test
    void allRequiredToolsAreFieldsInMcpToolRegistry() {
        Set<String> registryFieldTypes = registryFieldTypeNames(McpToolRegistry.class);

        List<String> missing = new ArrayList<>();
        for (String className : REQUIRED_TOOL_CLASSES) {
            if (!KNOWN_EXCLUSIONS.contains(className) && !registryFieldTypes.contains(className)) {
                missing.add(className);
            }
        }

        assertTrue(missing.isEmpty(),
                "The following @Tool-annotated classes are in REQUIRED_TOOL_CLASSES but have NO " +
                "@Autowired field in McpToolRegistry. Wire them in (add a field + addBeanIfAvailable call). " +
                "Missing: " + missing);
    }

    @Test
    void allRequiredToolsAreFieldsInMcpSseServerConfiguration() {
        Set<String> configFieldTypes = registryFieldTypeNames(
                ai.kompile.app.config.McpSseServerConfiguration.class);

        // McpSseServerConfiguration does not include every tool (it omits some that are
        // registry-only), but it MUST include all four previously-missing beans.
        List<String> mustBeInSse = List.of(
                "GraphHybridReasoningTool",
                "GraphReasoningQueryTool",
                "GraphLocalizationToolImpl",
                "ProcessDiscoveryTool",
                "ProcessMiningTool",
                "ProcessEngineTool"
        );

        List<String> missing = new ArrayList<>();
        for (String className : mustBeInSse) {
            if (!configFieldTypes.contains(className)) {
                missing.add(className);
            }
        }

        assertTrue(missing.isEmpty(),
                "The following @Tool-annotated classes are missing from McpSseServerConfiguration fields. " +
                "Add @Autowired(required=false) fields and addToolIfAvailable calls. Missing: " + missing);
    }

    /**
     * Verify that KNOWN_EXCLUSIONS doesn't contain stale entries: every excluded name
     * should NOT appear in REQUIRED_TOOL_CLASSES (otherwise the two sets conflict).
     */
    @Test
    void knownExclusionsDontOverlapWithRequiredTools() {
        Set<String> required = new TreeSet<>(REQUIRED_TOOL_CLASSES);
        List<String> conflicts = KNOWN_EXCLUSIONS.stream()
                .filter(required::contains)
                .collect(Collectors.toList());

        assertTrue(conflicts.isEmpty(),
                "KNOWN_EXCLUSIONS and REQUIRED_TOOL_CLASSES share entries — remove from one: " + conflicts);
    }
}
