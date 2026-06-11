package ai.kompile.compute.graph.drools;

import ai.kompile.compute.graph.engine.ComputeGraphEngine;
import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.ArtifactStore;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Results;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * A graph execution engine that compiles ALL rules from ALL nodes in the graph
 * into a single Drools knowledge base, enabling cross-node rule chaining and inference.
 *
 * Unlike the standard executor (which executes nodes in topological order),
 * this engine lets Drools' RETE network determine execution order via forward chaining.
 * Rules from different nodes can interact with each other's facts.
 *
 * Use this when:
 * - Rules in one node depend on facts produced by rules in another node
 * - You need backward chaining or complex inference patterns
 * - The execution order should be determined by data dependencies, not graph topology
 */
@Slf4j
public class DroolsInferenceEngine implements ComputeGraphEngine {

    private final DroolsRuleCompiler ruleCompiler;
    private final ArtifactStore artifactStore;
    private final int maxRuleFirings;

    public DroolsInferenceEngine(DroolsRuleCompiler ruleCompiler, ArtifactStore artifactStore) {
        this(ruleCompiler, artifactStore, 10000);
    }

    public DroolsInferenceEngine(DroolsRuleCompiler ruleCompiler, ArtifactStore artifactStore, int maxRuleFirings) {
        this.ruleCompiler = ruleCompiler;
        this.artifactStore = artifactStore;
        this.maxRuleFirings = maxRuleFirings;
    }

    @Override
    public GraphExecutionResult execute(ComputeGraph graph, Map<String, Object> inputs) {
        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        try {
            // Compile all Drools nodes into a single knowledge base
            KieBase unifiedKieBase = compileAllRules(graph);
            KieSession session = unifiedKieBase.newKieSession();

            try {
                // Insert all inputs as facts
                NodeFacts rootFacts = new NodeFacts("_root", executionId);
                rootFacts.getInputs().putAll(inputs);
                session.insert(rootFacts);

                for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                    session.insert(new NamedFact(entry.getKey(), entry.getValue()));
                }

                // Insert graph global parameters
                if (graph.getGlobalParameters() != null) {
                    for (Map.Entry<String, Object> entry : graph.getGlobalParameters().entrySet()) {
                        session.insert(new NamedFact("global_" + entry.getKey(), entry.getValue()));
                    }
                }

                // Insert per-node facts containers so rules can write to specific nodes
                Map<String, NodeFacts> nodeFacts = new HashMap<>();
                for (ComputeNode node : graph.getNodes()) {
                    if (node.getExecutionType() == NodeExecutionType.DROOLS_RULE
                            || node.getExecutionType() == NodeExecutionType.DROOLS_INFERENCE) {
                        NodeFacts nf = new NodeFacts(node.getId(), executionId);
                        nf.getInputs().putAll(inputs);
                        if (node.getParameters() != null) {
                            nf.getParameters().putAll(node.getParameters());
                        }
                        session.insert(nf);
                        nodeFacts.put(node.getId(), nf);
                    }
                }

                // Fire all rules — let RETE determine order
                int totalFired = session.fireAllRules(maxRuleFirings);
                log.info("Drools inference engine fired {} rules for graph '{}'", totalFired, graph.getName());

                // Collect outputs from all node facts
                Map<String, ExecutionResult> nodeResults = new LinkedHashMap<>();
                Map<String, Object> finalOutputs = new HashMap<>();

                for (Map.Entry<String, NodeFacts> entry : nodeFacts.entrySet()) {
                    NodeFacts nf = entry.getValue();
                    ExecutionResult result = ExecutionResult.builder()
                            .nodeId(entry.getKey())
                            .executionId(executionId)
                            .status(ExecutionStatus.COMPLETED)
                            .outputs(new HashMap<>(nf.getOutputs()))
                            .build();
                    nodeResults.put(entry.getKey(), result);
                    finalOutputs.putAll(nf.getOutputs());
                }

                // Also collect any new NamedFacts produced by rules
                for (Object fact : session.getObjects()) {
                    if (fact instanceof NamedFact nf) {
                        finalOutputs.putIfAbsent(nf.getName(), nf.getValue());
                    }
                }

                finalOutputs.put("_totalRulesFired", totalFired);

                Instant completedAt = Instant.now();
                return GraphExecutionResult.builder()
                        .executionId(executionId)
                        .graphId(graph.getId())
                        .status(ExecutionStatus.COMPLETED)
                        .nodeResults(nodeResults)
                        .finalOutputs(finalOutputs)
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .totalDuration(Duration.between(startedAt, completedAt))
                        .build();

            } finally {
                session.dispose();
            }

        } catch (Exception e) {
            log.error("Drools inference execution failed for graph '{}'", graph.getName(), e);
            return GraphExecutionResult.builder()
                    .executionId(executionId)
                    .graphId(graph.getId())
                    .status(ExecutionStatus.FAILED)
                    .error(e.getMessage())
                    .startedAt(startedAt)
                    .completedAt(Instant.now())
                    .totalDuration(Duration.between(startedAt, Instant.now()))
                    .build();
        }
    }

    @Override
    public GraphExecutionResult executeSingleNode(ComputeGraph graph, String nodeId, Map<String, Object> inputs) {
        // For single node execution, delegate to the standard per-node executor
        ComputeNode node = graph.findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        DroolsNodeExecutor executor = new DroolsNodeExecutor(ruleCompiler);
        ExecutionContext context = new ExecutionContext(
                UUID.randomUUID().toString(), graph, artifactStore);
        ExecutionResult result = executor.execute(node, inputs, context);

        return GraphExecutionResult.builder()
                .executionId(context.getExecutionId())
                .graphId(graph.getId())
                .status(result.getStatus())
                .nodeResults(Map.of(nodeId, result))
                .finalOutputs(result.getOutputs())
                .executionOrder(List.of(nodeId))
                .build();
    }

    @Override
    public String validate(ComputeGraph graph) {
        List<String> errors = new ArrayList<>();
        for (ComputeNode node : graph.getNodes()) {
            if (node.getExecutionType() == NodeExecutionType.DROOLS_RULE
                    || node.getExecutionType() == NodeExecutionType.DROOLS_INFERENCE) {
                if (node.getScript() == null || node.getScript().isBlank()) {
                    errors.add("Node '" + node.getName() + "' has no DRL definition");
                }
            }
        }
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }

        // Try to compile all rules together
        try {
            compileAllRules(graph);
        } catch (Exception e) {
            return "Unified DRL compilation failed: " + e.getMessage();
        }

        return null;
    }

    /**
     * Compile all Drools-type nodes in the graph into a single unified KieBase.
     */
    private KieBase compileAllRules(ComputeGraph graph) {
        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kfs = kieServices.newKieFileSystem();

        int ruleCount = 0;
        for (ComputeNode node : graph.getNodes()) {
            if (node.getExecutionType() == NodeExecutionType.DROOLS_RULE
                    || node.getExecutionType() == NodeExecutionType.DROOLS_INFERENCE) {
                String drl = buildDrl(node);
                String path = "src/main/resources/rules/node_" + ruleCount + "_" + sanitize(node.getId()) + ".drl";
                Resource resource = kieServices.getResources()
                        .newByteArrayResource(drl.getBytes())
                        .setResourceType(org.kie.api.io.ResourceType.DRL)
                        .setSourcePath(path);
                kfs.write(path, resource);
                ruleCount++;
            }
        }

        if (ruleCount == 0) {
            throw new IllegalArgumentException("No Drools nodes found in graph");
        }

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();
        Results results = kieBuilder.getResults();

        if (results.hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
            StringBuilder sb = new StringBuilder("DRL compilation errors:\n");
            for (org.kie.api.builder.Message msg : results.getMessages(org.kie.api.builder.Message.Level.ERROR)) {
                sb.append("  ").append(msg.getText()).append("\n");
            }
            throw new IllegalArgumentException(sb.toString());
        }

        KieContainer container = kieServices.newKieContainer(
                kieServices.getRepository().getDefaultReleaseId());
        return container.getKieBase();
    }

    private String buildDrl(ComputeNode node) {
        String script = node.getScript();
        if (script.trim().startsWith("package ") || script.trim().startsWith("import ")) {
            return script;
        }

        StringBuilder drl = new StringBuilder();
        drl.append("package ai.kompile.compute.graph.drools.inference_").append(sanitize(node.getId())).append(";\n\n");
        drl.append("import ai.kompile.compute.graph.drools.NodeFacts;\n");
        drl.append("import ai.kompile.compute.graph.drools.NamedFact;\n");
        drl.append("import java.util.Map;\n");
        drl.append("import java.util.List;\n\n");

        if (!script.trim().startsWith("rule ")) {
            drl.append("rule \"").append(sanitize(node.getName())).append("\"\n");
            drl.append("  when\n");
            drl.append("    $facts : NodeFacts(nodeId == \"").append(node.getId()).append("\")\n");
            drl.append("  then\n");
            drl.append("    ").append(script).append("\n");
            drl.append("end\n");
        } else {
            drl.append(script);
        }

        return drl.toString();
    }

    private String sanitize(String input) {
        if (input == null) return "unnamed";
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
