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

package ai.kompile.app.services;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.process.service.DispatchResult;
import ai.kompile.process.service.StepExecutionDispatcher;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.table.TableGraphAdapter;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link StepExecutionDispatcher} that bridges the process engine
 * to the Spring AI tool ecosystem and HTTP clients.
 *
 * <p>Discovers all {@code @Tool}-annotated methods from Spring beans at startup and
 * can invoke them by name during TOOL_CALL workflow steps. Also provides HTTP call
 * capability for HTTP_CALL steps.
 */
@Service
public class StepExecutionDispatcherImpl implements StepExecutionDispatcher, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StepExecutionDispatcherImpl.class);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** NodeExecutor that handles JAVASCRIPT (GraalVM) — resolved at init from Spring context. */
    private NodeExecutor scriptingExecutor;

    /** NodeExecutor that handles PYTHON (Python4J / CPython) — resolved at init from Spring context. */
    private NodeExecutor pythonExecutor;

    /** NodeExecutor that handles EXCEL — resolved at init from Spring context. */
    private NodeExecutor excelExecutor;

    /** NodeExecutor that handles CAMEL_ROUTE — resolved at init from Spring context. */
    private NodeExecutor camelExecutor;

    /** NodeExecutor that handles Drools rule and decision table execution. */
    private NodeExecutor droolsExecutor;

    /** NodeExecutor that handles XIRCUITS workflows. */
    private NodeExecutor xircuitsExecutor;

    /** NodeExecutor that handles N8N workflows. */
    private NodeExecutor n8nExecutor;

    /** Knowledge graph service — optional, used for resolving Excel graphs from node IDs. */
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    /** toolName → ToolEntry (bean + method + metadata) */
    private final Map<String, ToolEntry> toolRegistry = new ConcurrentHashMap<>();

    @Autowired
    public StepExecutionDispatcherImpl(ApplicationContext applicationContext,
                                        ObjectMapper objectMapper,
                                        @Autowired(required = false) RestTemplate restTemplate) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        if (restTemplate != null) {
            this.restTemplate = restTemplate;
        } else {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            this.restTemplate = new RestTemplate(factory);
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        discoverTools();
        resolveScriptingExecutor();
        resolvePythonExecutor();
        resolveExcelExecutor();
        resolveCamelExecutor();
        resolveDroolsExecutor();
        resolveWorkflowExecutors();
    }

    private void resolveScriptingExecutor() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                if (executor.supportedTypes().contains(NodeExecutionType.JAVASCRIPT)) {
                    this.scriptingExecutor = executor;
                    log.info("Resolved JavaScript executor: {}", executor.getClass().getSimpleName());
                    return;
                }
            }
            log.warn("No NodeExecutor found for JAVASCRIPT — JavaScript SCRIPT steps will fail");
        } catch (Exception e) {
            log.warn("Failed to resolve scripting executor: {}", e.getMessage());
        }
    }

    private void resolvePythonExecutor() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                if (executor.supportedTypes().contains(NodeExecutionType.PYTHON)) {
                    this.pythonExecutor = executor;
                    log.info("Resolved Python executor: {}", executor.getClass().getSimpleName());
                    return;
                }
            }
            log.warn("No NodeExecutor found for PYTHON — Python SCRIPT steps will fail");
        } catch (Exception e) {
            log.warn("Failed to resolve Python executor: {}", e.getMessage());
        }
    }

    private void resolveExcelExecutor() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                if (executor.supportedTypes().contains(NodeExecutionType.EXCEL)) {
                    this.excelExecutor = executor;
                    log.info("Resolved Excel executor: {}", executor.getClass().getSimpleName());
                    return;
                }
            }
            log.info("No NodeExecutor found for EXCEL — EXCEL_COMPUTE steps require an LLM provider");
        } catch (Exception e) {
            log.warn("Failed to resolve Excel executor: {}", e.getMessage());
        }
    }

    private void resolveCamelExecutor() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                if (executor.supportedTypes().contains(NodeExecutionType.CAMEL_ROUTE)) {
                    this.camelExecutor = executor;
                    log.info("Resolved Camel route executor: {}", executor.getClass().getSimpleName());
                    return;
                }
            }
            log.info("No NodeExecutor found for CAMEL_ROUTE — CAMEL_ROUTE steps require kompile-compute-graph-camel");
        } catch (Exception e) {
            log.warn("Failed to resolve Camel route executor: {}", e.getMessage());
        }
    }

    private void resolveDroolsExecutor() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                Set<NodeExecutionType> types = executor.supportedTypes();
                if (types.contains(NodeExecutionType.DROOLS_RULE)
                        || types.contains(NodeExecutionType.DROOLS_INFERENCE)
                        || types.contains(NodeExecutionType.DROOLS_DECISION_TABLE)) {
                    this.droolsExecutor = executor;
                    log.info("Resolved Drools executor: {}", executor.getClass().getSimpleName());
                    return;
                }
            }
            log.info("No NodeExecutor found for Drools — DROOLS_* steps require kompile-compute-graph-drools");
        } catch (Exception e) {
            log.warn("Failed to resolve Drools executor: {}", e.getMessage());
        }
    }

    private void resolveWorkflowExecutors() {
        try {
            Map<String, NodeExecutor> executors = applicationContext.getBeansOfType(NodeExecutor.class);
            for (NodeExecutor executor : executors.values()) {
                Set<NodeExecutionType> types = executor.supportedTypes();
                if (types.contains(NodeExecutionType.XIRCUITS)) {
                    this.xircuitsExecutor = executor;
                    log.info("Resolved Xircuits workflow executor: {}", executor.getClass().getSimpleName());
                }
                if (types.contains(NodeExecutionType.N8N)) {
                    this.n8nExecutor = executor;
                    log.info("Resolved n8n workflow executor: {}", executor.getClass().getSimpleName());
                }
            }
            if (xircuitsExecutor == null && n8nExecutor == null) {
                log.info("No workflow NodeExecutors found — WORKFLOW steps require workflow executor modules or tool-workflow");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve workflow executors: {}", e.getMessage());
        }
    }

    public void discoverTools() {
        // Scan ALL beans for @Tool annotated methods
        String[] allBeanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : allBeanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                // Walk the class hierarchy to find @Tool methods on the actual class (not just proxy)
                Class<?> clazz = bean.getClass();
                // For CGLIB proxies, get the real superclass
                while (clazz != null && clazz.getName().contains("$$")) {
                    clazz = clazz.getSuperclass();
                }
                if (clazz == null) continue;

                for (Method method : clazz.getDeclaredMethods()) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation != null) {
                        String name = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                        String description = toolAnnotation.description();
                        String category = inferCategory(name, description);
                        Map<String, String> inputSchema = buildInputSchemaMap(method);

                        toolRegistry.put(name, new ToolEntry(bean, method, name, description, category, inputSchema));
                    }
                }
            } catch (Throwable e) {
                // Some infrastructure beans can't be inspected — skip them.
                // In native image, UnsupportedFeatureError (extends Error) may be thrown
                // for records missing reflection config.
                log.trace("Skipping bean '{}' during tool scan: {}", beanName, e.getMessage());
            }
        }

        log.info("StepExecutionDispatcher discovered {} tools for workflow steps", toolRegistry.size());
    }

    @Override
    public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
        ToolEntry entry = toolRegistry.get(toolName);
        if (entry == null) {
            throw new IllegalArgumentException("Tool not found: '" + toolName
                    + "'. Available tools: " + String.join(", ", toolRegistry.keySet()));
        }

        try {
            entry.method.setAccessible(true);
            Parameter[] parameters = entry.method.getParameters();
            Object[] invokeArgs = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Class<?> paramType = parameters[i].getType();
                if (paramType.isRecord()) {
                    invokeArgs[i] = createRecordInstance(paramType, arguments != null ? arguments : Map.of());
                } else {
                    String paramName = parameters[i].getName();
                    Object value = arguments != null ? arguments.get(paramName) : null;
                    invokeArgs[i] = objectMapper.convertValue(value, paramType);
                }
            }

            Object result = entry.method.invoke(entry.bean, invokeArgs);

            // Convert result to Map if it isn't already
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapResult = (Map<String, Object>) result;
                return mapResult;
            } else {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("result", result);
                return wrapped;
            }
        } catch (Exception e) {
            log.error("Tool invocation failed for '{}': {}", toolName, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("toolName", toolName);
            return error;
        }
    }

    @Override
    public Map<String, Object> executeHttpCall(String method, String url,
                                                Map<String, String> headers, Object body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach(httpHeaders::set);
            }
            if (body != null && !httpHeaders.containsKey("Content-Type")) {
                httpHeaders.set("Content-Type", "application/json");
            }

            HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());

            ResponseEntity<String> response = restTemplate.exchange(url, httpMethod, entity, String.class);

            result.put("statusCode", response.getStatusCode().value());
            result.put("body", response.getBody());
            result.put("headers", response.getHeaders().toSingleValueMap());

            // Try to parse body as JSON
            if (response.getBody() != null) {
                try {
                    Object parsed = objectMapper.readValue(response.getBody(), Object.class);
                    result.put("bodyParsed", parsed);
                } catch (Exception e) {
                    // Not JSON — leave as string
                }
            }
        } catch (Exception e) {
            log.error("HTTP call failed: {} {}: {}", method, url, e.getMessage());
            result.put("error", e.getMessage());
            result.put("url", url);
            result.put("method", method);
        }
        return result;
    }

    @Override
    public Map<String, Object> executeScript(String language, String scriptBody, Map<String, Object> runData) {
        String lang = language != null ? language.toLowerCase() : "javascript";

        switch (lang) {
            case "python": {
                if (pythonExecutor == null) {
                    throw new IllegalStateException(
                            "Python script execution requires a Python executor but none is available. "
                            + "Ensure python4j-core is on the classpath.");
                }
                return executeWithExecutor(pythonExecutor, NodeExecutionType.PYTHON, scriptBody, runData);
            }
            case "javascript":
            case "js": {
                if (scriptingExecutor == null) {
                    throw new IllegalStateException(
                            "JavaScript script execution requires a NodeExecutor but none is available. "
                            + "Ensure GraalVM Polyglot is on the classpath.");
                }
                return executeWithExecutor(scriptingExecutor, NodeExecutionType.JAVASCRIPT, scriptBody, runData);
            }
            default:
                throw new IllegalArgumentException("Unsupported script language: '" + language
                        + "'. Supported: javascript, python");
        }
    }

    private Map<String, Object> executeWithExecutor(NodeExecutor executor, NodeExecutionType execType,
                                                     String scriptBody, Map<String, Object> runData) {
        String nodeId = "script-" + UUID.randomUUID().toString().substring(0, 8);
        ComputeNode node = ComputeNode.builder()
                .id(nodeId)
                .name("workflow-script")
                .executionType(execType)
                .script(scriptBody)
                .build();

        Map<String, Object> globalParams = runData != null ? new HashMap<>(runData) : new HashMap<>();
        ComputeGraph graph = ComputeGraph.builder()
                .id(nodeId)
                .name("workflow-script-graph")
                .globalParameters(globalParams)
                .build();
        ExecutionContext ctx = new ExecutionContext(nodeId, graph, null);

        ExecutionResult result = executor.execute(node, runData, ctx);

        if (result.getStatus() == ExecutionStatus.FAILED || result.getStatus() == ExecutionStatus.TIMED_OUT) {
            throw new RuntimeException("Script execution " + result.getStatus().name().toLowerCase()
                    + ": " + result.getError());
        }

        Map<String, Object> outputs = result.getOutputs() != null ? new LinkedHashMap<>(result.getOutputs()) : new LinkedHashMap<>();
        if (result.getConsoleOutput() != null && !result.getConsoleOutput().isBlank()) {
            outputs.put("_consoleOutput", result.getConsoleOutput().trim());
        }
        return outputs;
    }

    @Override
    public Map<String, Object> convertExcel(String spreadsheetGraphJson, String targetLanguage) {
        if (excelExecutor == null) {
            throw new IllegalStateException(
                    "Excel conversion requires an ExcelNodeExecutor but none is available. "
                    + "Ensure kompile-compute-graph-excel is on the classpath and an LLM provider is configured.");
        }

        try {
            // Use reflection to call convertOnly — avoids compile-time dependency on kompile-compute-graph-excel
            java.lang.reflect.Method convertOnly = excelExecutor.getClass()
                    .getMethod("convertOnly", String.class, String.class);
            Object result = convertOnly.invoke(excelExecutor, spreadsheetGraphJson, targetLanguage);

            Map<String, Object> output = new LinkedHashMap<>();
            Class<?> resultClass = result.getClass();
            output.put("code", resultClass.getMethod("getCode").invoke(result));
            output.put("language", resultClass.getMethod("getLanguage").invoke(result));
            output.put("workbookName", resultClass.getMethod("getWorkbookName").invoke(result));
            output.put("inputCells", resultClass.getMethod("getInputCells").invoke(result));
            output.put("outputCells", resultClass.getMethod("getOutputCells").invoke(result));
            output.put("formulaCount", resultClass.getMethod("getFormulaCount").invoke(result));
            output.put("dependencyCount", resultClass.getMethod("getDependencyCount").invoke(result));
            return output;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Excel executor does not support convertOnly: " + excelExecutor.getClass(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Excel conversion failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                             Map<String, Object> cellOverrides,
                                             String targetLanguage,
                                             String generatedCode) {
        if (excelExecutor == null) {
            throw new IllegalStateException(
                    "Excel execution requires an ExcelNodeExecutor but none is available. "
                    + "Ensure kompile-compute-graph-excel is on the classpath and an LLM provider is configured.");
        }

        String nodeId = "excel-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> parameters = cellOverrides != null ? new HashMap<>(cellOverrides) : new HashMap<>();
        if (targetLanguage != null) {
            parameters.put("_language", targetLanguage);
        }
        // If user-supplied code is provided, pass it so the executor skips LLM
        if (generatedCode != null && !generatedCode.isBlank()) {
            parameters.put("_generatedCode", generatedCode);
        }

        ComputeNode node = ComputeNode.builder()
                .id(nodeId)
                .name("workflow-excel")
                .executionType(NodeExecutionType.EXCEL)
                .script(spreadsheetGraphJson)
                .parameters(parameters)
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id(nodeId)
                .name("workflow-excel-graph")
                .build();
        ExecutionContext ctx = new ExecutionContext(nodeId, graph, null);

        ExecutionResult result = excelExecutor.execute(node, cellOverrides, ctx);

        if (result.getStatus() == ExecutionStatus.FAILED || result.getStatus() == ExecutionStatus.TIMED_OUT) {
            throw new RuntimeException("Excel execution " + result.getStatus().name().toLowerCase()
                    + ": " + result.getError());
        }

        Map<String, Object> outputs = result.getOutputs() != null
                ? new LinkedHashMap<>(result.getOutputs()) : new LinkedHashMap<>();
        if (result.getConsoleOutput() != null && !result.getConsoleOutput().isBlank()) {
            outputs.put("_consoleOutput", result.getConsoleOutput().trim());
        }
        return outputs;
    }

    @Override
    public DispatchResult executeExcelWithResult(String spreadsheetGraphJson,
                                                   Map<String, Object> cellOverrides,
                                                   String targetLanguage,
                                                   String generatedCode) {
        Map<String, Object> outputs = executeExcel(spreadsheetGraphJson, cellOverrides, targetLanguage, generatedCode);

        // Collect graph node IDs discovered during resolution:
        // the DOCUMENT node and any SHEET/CELL nodes that the formula graph maps to,
        // plus nodes referenced by relationship source/target fields
        List<String> discoveredIds = new ArrayList<>();
        if (knowledgeGraphService != null && spreadsheetGraphJson != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> graph = objectMapper.readValue(spreadsheetGraphJson, Map.class);
                Set<String> seen = new LinkedHashSet<>();

                // Collect entity node IDs
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entities = (List<Map<String, Object>>) graph.get("entities");
                if (entities != null) {
                    for (Map<String, Object> entity : entities) {
                        String extId = (String) entity.get("id");
                        if (extId != null) {
                            String typeStr = (String) entity.getOrDefault("type", "CELL");
                            NodeLevel level = ("SHEET".equals(typeStr) || "TABLE".equals(typeStr))
                                    ? NodeLevel.TABLE : NodeLevel.ENTITY;
                            knowledgeGraphService.getNodeByExternalId(extId, level)
                                    .ifPresent(n -> {
                                        if (seen.add(n.getNodeId())) {
                                            discoveredIds.add(n.getNodeId());
                                        }
                                    });
                        }
                    }
                }

                // Also collect node IDs referenced by relationship source/target
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> relationships = (List<Map<String, Object>>) graph.get("relationships");
                if (relationships != null) {
                    for (Map<String, Object> rel : relationships) {
                        for (String key : List.of("source", "target")) {
                            String refId = (String) rel.get(key);
                            if (refId != null) {
                                // Try ENTITY first (cells), then TABLE (sheets/tables)
                                Optional<GraphNode> nodeOpt = knowledgeGraphService.getNodeByExternalId(refId, NodeLevel.ENTITY);
                                if (nodeOpt.isEmpty()) {
                                    nodeOpt = knowledgeGraphService.getNodeByExternalId(refId, NodeLevel.TABLE);
                                }
                                nodeOpt.ifPresent(n -> {
                                    if (seen.add(n.getNodeId())) {
                                        discoveredIds.add(n.getNodeId());
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to discover graph node IDs from formula graph: {}", e.getMessage());
            }
        }
        return DispatchResult.of(outputs, discoveredIds);
    }

    @Override
    public Map<String, Object> executeCamelRoute(String routeId,
                                                  String inlineScript,
                                                  Map<String, Object> inputs) {
        if (inlineScript != null && !inlineScript.isBlank()) {
            if (camelExecutor == null) {
                throw new IllegalStateException(
                        "Camel route execution requires a Camel NodeExecutor but none is available. "
                        + "Ensure kompile-compute-graph-camel is on the classpath.");
            }
            return executeComputeNode(camelExecutor, NodeExecutionType.CAMEL_ROUTE,
                    "workflow-camel-route", inlineScript, Map.of(), inputs);
        }

        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("Either camelRouteId or camelInlineScript must be provided");
        }

        if (!toolRegistry.containsKey("camel_execute_route")) {
            throw new IllegalStateException(
                    "Saved Camel route execution requires the camel_execute_route tool. "
                    + "Ensure kompile-tool-camel is on the classpath.");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("routeId", routeId);
        args.put("inlineScript", null);
        args.put("inputs", inputs != null ? inputs : Map.of());
        return unwrapToolExecution("camel_execute_route", invokeTool("camel_execute_route", args));
    }

    @Override
    public Map<String, Object> executeDroolsRules(String drl,
                                                   Map<String, Object> facts,
                                                   String agendaGroup,
                                                   Integer maxFirings,
                                                   boolean inference) {
        if (droolsExecutor == null) {
            throw new IllegalStateException(
                    "Drools rule execution requires a Drools NodeExecutor but none is available. "
                    + "Ensure kompile-compute-graph-drools is on the classpath.");
        }
        if (drl == null || drl.isBlank()) {
            throw new IllegalArgumentException("Drools DRL source must not be null or blank");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (agendaGroup != null && !agendaGroup.isBlank()) {
            params.put("agendaGroup", agendaGroup);
        }
        if (maxFirings != null) {
            params.put("maxFirings", maxFirings);
        }

        return executeComputeNode(droolsExecutor,
                inference ? NodeExecutionType.DROOLS_INFERENCE : NodeExecutionType.DROOLS_RULE,
                "workflow-drools-rules", drl, params, facts);
    }

    @Override
    public Map<String, Object> executeDroolsDecisionTable(String decisionTable,
                                                           String inputType,
                                                           Map<String, Object> facts,
                                                           String worksheetName) {
        if (droolsExecutor == null) {
            throw new IllegalStateException(
                    "Drools decision table execution requires a Drools NodeExecutor but none is available. "
                    + "Ensure kompile-compute-graph-drools is on the classpath.");
        }
        if (decisionTable == null || decisionTable.isBlank()) {
            throw new IllegalArgumentException("Drools decision table content must not be null or blank");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (inputType != null && !inputType.isBlank()) {
            params.put("inputType", inputType);
        }
        if (worksheetName != null && !worksheetName.isBlank()) {
            params.put("worksheetName", worksheetName);
        }

        return executeComputeNode(droolsExecutor, NodeExecutionType.DROOLS_DECISION_TABLE,
                "workflow-drools-decision-table", decisionTable, params, facts);
    }

    @Override
    public Map<String, Object> executeWorkflow(String engineType,
                                                String workflowName,
                                                String inlineContent,
                                                Map<String, Object> inputs,
                                                Integer timeoutSeconds) {
        String engine = engineType != null ? engineType.trim().toLowerCase() : "";
        if (engine.isBlank()) {
            throw new IllegalArgumentException("workflowEngineType must be provided");
        }

        if (inlineContent != null && !inlineContent.isBlank()) {
            NodeExecutionType execType = workflowExecutionType(engine);
            NodeExecutor executor = workflowExecutor(engine);
            if (executor == null) {
                throw new IllegalStateException(
                        "Workflow execution for '" + engine + "' requires its NodeExecutor module on the classpath.");
            }

            Map<String, Object> params = new LinkedHashMap<>();
            if (timeoutSeconds != null) {
                params.put("timeoutSeconds", timeoutSeconds);
            }
            return executeComputeNode(executor, execType,
                    "workflow-" + engine, inlineContent, params, inputs);
        }

        if (workflowName == null || workflowName.isBlank()) {
            throw new IllegalArgumentException("Either workflowName or workflowInlineContent must be provided");
        }

        if (!toolRegistry.containsKey("workflow_execute")) {
            throw new IllegalStateException(
                    "Saved workflow execution requires the workflow_execute tool. "
                    + "Ensure kompile-tool-workflow is on the classpath.");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("engineType", engine);
        args.put("name", workflowName);
        args.put("inputs", inputs != null ? inputs : Map.of());
        args.put("timeoutSeconds", timeoutSeconds);
        return unwrapToolExecution("workflow_execute", invokeTool("workflow_execute", args));
    }

    @Override
    public Map<String, Object> executePipeline(String pipelineDefinitionJson, Map<String, Object> inputs) {
        if (pipelineDefinitionJson == null || pipelineDefinitionJson.isBlank()) {
            throw new IllegalArgumentException("Pipeline definition JSON must not be null or blank");
        }

        try {
            // Deserialize pipeline from JSON (uses @JsonTypeInfo for polymorphic dispatch)
            ai.kompile.pipelines.framework.api.Pipeline pipeline =
                    objectMapper.readValue(pipelineDefinitionJson, ai.kompile.pipelines.framework.api.Pipeline.class);
            pipeline.validate();

            // Convert inputs map to pipeline Data
            ai.kompile.pipelines.framework.api.data.Data inputData =
                    ai.kompile.pipelines.framework.api.data.Data.fromMap(inputs != null ? inputs : Map.of());

            // Create executor and run
            try (ai.kompile.pipelines.framework.api.PipelineExecutor executor = pipeline.createExecutor()) {
                ai.kompile.pipelines.framework.api.data.Data outputData = executor.exec(inputData);
                return outputData.toMap();
            }
        } catch (Exception e) {
            throw new RuntimeException("Pipeline execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> listAvailableTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolEntry entry : toolRegistry.values()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", entry.name);
            tool.put("description", entry.description);
            tool.put("category", entry.category);
            tool.put("inputSchema", entry.inputSchema);
            tools.add(tool);
        }
        // Sort by category then name
        tools.sort(Comparator.comparing((Map<String, Object> t) -> (String) t.get("category"))
                .thenComparing(t -> (String) t.get("name")));
        return tools;
    }

    @Override
    public String resolveExcelGraphJson(List<String> graphNodeIds) {
        if (graphNodeIds == null || graphNodeIds.isEmpty() || knowledgeGraphService == null) {
            return null;
        }

        // Strategy: walk the graph node IDs looking for nodes whose metadata
        // contains a serialized graph. persistFormulaGraph stores this under the
        // "formulaGraph" key on DOCUMENT nodes for both Excel (SpreadsheetGraph)
        // and non-Excel (TableCellGraphBuilder) table sources.
        // Also check "tableGraph" in case the raw loader metadata is present.
        for (String nodeId : graphNodeIds) {
            try {
                Optional<GraphNode> nodeOpt = knowledgeGraphService.getNode(nodeId);
                if (nodeOpt.isEmpty()) continue;
                GraphNode node = nodeOpt.get();

                // Check if this node's metadata directly contains a graph
                String resolved = extractGraphJsonFromMeta(node.getMetadataJson());
                if (resolved != null) {
                    log.debug("Resolved graph JSON from node {} metadata", nodeId);
                    return TableGraphAdapter.toSpreadsheetFormat(resolved);
                }

                // If this is a TABLE node (sheet or table subtype), walk up to
                // parent DOCUMENT via incoming CONTAINS edges
                if (node.getNodeType() == NodeLevel.TABLE) {
                    String entityMeta = node.getMetadataJson();
                    if (entityMeta != null && (entityMeta.contains("\"entity_subtype\":\"sheet\"")
                            || entityMeta.contains("\"entity_subtype\":\"table\""))) {
                        try {
                            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(nodeId);
                            if (edges != null) {
                                for (GraphEdge edge : edges) {
                                    // Check both source and target — CONTAINS edges are stored
                                    // as DOCUMENT→TABLE, so the DOCUMENT is the source when
                                    // we're looking from the TABLE side. But other edge types
                                    // or directions may have the DOCUMENT as target.
                                    GraphNode source = edge.getSourceNode();
                                    GraphNode target = edge.getTargetNode();
                                    GraphNode candidate = null;
                                    if (source != null && !source.getNodeId().equals(nodeId)
                                            && source.getNodeType() == NodeLevel.DOCUMENT) {
                                        candidate = source;
                                    } else if (target != null && !target.getNodeId().equals(nodeId)
                                            && target.getNodeType() == NodeLevel.DOCUMENT) {
                                        candidate = target;
                                    }
                                    if (candidate != null) {
                                        String parentResolved = extractGraphJsonFromMeta(candidate.getMetadataJson());
                                        if (parentResolved != null) {
                                            log.debug("Resolved graph JSON from parent DOCUMENT {} via TABLE node {}", candidate.getNodeId(), nodeId);
                                            return TableGraphAdapter.toSpreadsheetFormat(parentResolved);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to walk parent edges for TABLE node {}: {}", nodeId, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve node {}: {}", nodeId, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Extracts graph JSON from a node's metadataJson. Checks both
     * "formulaGraph" (persisted by persistFormulaGraph for all table sources)
     * and "tableGraph" (set directly by loaders on document metadata).
     */
    @SuppressWarnings("unchecked")
    private String extractGraphJsonFromMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) return null;
        try {
            Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
            for (String key : List.of(GraphConstants.META_FORMULA_GRAPH, GraphConstants.META_TABLE_GRAPH)) {
                Object graphObj = meta.get(key);
                if (graphObj instanceof String fg && !fg.isBlank()) {
                    return fg;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse graph JSON from metadata: {}", e.getMessage());
        }
        return null;
    }

    // ---- Private helpers ----

    private Map<String, Object> executeComputeNode(NodeExecutor executor,
                                                    NodeExecutionType execType,
                                                    String name,
                                                    String script,
                                                    Map<String, Object> parameters,
                                                    Map<String, Object> inputs) {
        String nodeId = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        ComputeNode node = ComputeNode.builder()
                .id(nodeId)
                .name(name)
                .executionType(execType)
                .script(script)
                .parameters(parameters != null ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>())
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id(nodeId)
                .name(name + "-graph")
                .globalParameters(inputs != null ? new LinkedHashMap<>(inputs) : new LinkedHashMap<>())
                .build();
        ExecutionContext ctx = new ExecutionContext(nodeId, graph, null);

        ExecutionResult result = executor.execute(node, inputs != null ? inputs : Map.of(), ctx);
        if (result.getStatus() == ExecutionStatus.FAILED || result.getStatus() == ExecutionStatus.TIMED_OUT) {
            throw new RuntimeException(execType + " execution " + result.getStatus().name().toLowerCase()
                    + ": " + result.getError());
        }

        Map<String, Object> outputs = result.getOutputs() != null
                ? new LinkedHashMap<>(result.getOutputs()) : new LinkedHashMap<>();
        if (result.getConsoleOutput() != null && !result.getConsoleOutput().isBlank()) {
            outputs.put("_consoleOutput", result.getConsoleOutput().trim());
        }
        if (result.getDuration() != null) {
            outputs.put("_durationMs", result.getDuration().toMillis());
        }
        outputs.put("_executionType", execType.name());
        return outputs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapToolExecution(String toolName, Map<String, Object> result) {
        if (result == null) {
            return new LinkedHashMap<>();
        }
        if (result.get("error") != null) {
            throw new RuntimeException(toolName + " failed: " + result.get("error"));
        }
        Object status = result.get("status");
        if (status != null && !"success".equalsIgnoreCase(String.valueOf(status))
                && !"COMPLETED".equalsIgnoreCase(String.valueOf(status))) {
            throw new RuntimeException(toolName + " returned status " + status);
        }
        Object outputs = result.get("outputs");
        if (outputs instanceof Map<?, ?> outputMap) {
            Map<String, Object> unwrapped = new LinkedHashMap<>();
            outputMap.forEach((key, value) -> unwrapped.put(String.valueOf(key), value));
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                if (entry.getKey().startsWith("_") || "durationMs".equals(entry.getKey())) {
                    unwrapped.put(entry.getKey(), entry.getValue());
                }
            }
            return unwrapped;
        }
        return new LinkedHashMap<>(result);
    }

    private NodeExecutionType workflowExecutionType(String engineType) {
        return switch (engineType) {
            case "xircuits" -> NodeExecutionType.XIRCUITS;
            case "n8n" -> NodeExecutionType.N8N;
            default -> throw new IllegalArgumentException(
                    "Unsupported workflowEngineType: '" + engineType + "'. Supported: xircuits, n8n");
        };
    }

    private NodeExecutor workflowExecutor(String engineType) {
        return switch (engineType) {
            case "xircuits" -> xircuitsExecutor;
            case "n8n" -> n8nExecutor;
            default -> null;
        };
    }

    private Object createRecordInstance(Class<?> recordClass, Map<String, Object> args) throws Exception {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] paramValues = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            Object value = args.get(component.getName());
            paramValues[i] = value != null ? objectMapper.convertValue(value, component.getType()) : null;
        }

        return recordClass.getDeclaredConstructor(paramTypes).newInstance(paramValues);
    }

    private Map<String, String> buildInputSchemaMap(Method method) {
        Map<String, String> schema = new LinkedHashMap<>();
        for (Parameter param : method.getParameters()) {
            try {
                if (param.getType().isRecord()) {
                    for (RecordComponent rc : param.getType().getRecordComponents()) {
                        schema.put(rc.getName(), rc.getType().getSimpleName());
                    }
                } else {
                    schema.put(param.getName(), param.getType().getSimpleName());
                }
            } catch (Throwable t) {
                // In GraalVM native image, getRecordComponents() throws UnsupportedFeatureError
                // if the record's reflection config is missing. Fall back to parameter name.
                schema.put(param.getName(), param.getType().getSimpleName());
            }
        }
        return schema;
    }

    private String inferCategory(String toolName, String description) {
        String combined = (toolName + " " + description).toLowerCase();
        if (combined.contains("rag") || combined.contains("query") || combined.contains("retrieve")) return "rag";
        if (combined.contains("file") || combined.contains("directory")) return "filesystem";
        if (combined.contains("index")) return "indexing";
        if (combined.contains("model") || combined.contains("embedding")) return "model";
        if (combined.contains("process") || combined.contains("workflow") || combined.contains("ontology")) return "process";
        if (combined.contains("camel") || combined.contains("route")) return "integration";
        if (combined.contains("drools") || combined.contains("rules") || combined.contains("decision table")) return "rules";
        if (combined.contains("config") || combined.contains("setting")) return "config";
        if (combined.contains("eval") || combined.contains("test")) return "evaluation";
        if (combined.contains("ingest") || combined.contains("document")) return "ingestion";
        if (combined.contains("pipeline")) return "pipeline";
        if (combined.contains("agent") || combined.contains("delegat")) return "agent";
        if (combined.contains("graph") || combined.contains("knowledge")) return "graph";
        if (combined.contains("chat") || combined.contains("session")) return "chat";
        if (combined.contains("fact_sheet")) return "factsheet";
        if (combined.contains("prompt") || combined.contains("template")) return "prompt";
        if (combined.contains("backup") || combined.contains("restore")) return "backup";
        return "other";
    }

    /** Internal record holding a discovered tool's bean, method, and metadata. */
    private record ToolEntry(Object bean, Method method, String name,
                             String description, String category,
                             Map<String, String> inputSchema) {}
}
