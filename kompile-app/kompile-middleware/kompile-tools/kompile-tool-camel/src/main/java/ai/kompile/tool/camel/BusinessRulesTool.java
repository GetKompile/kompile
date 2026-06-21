package ai.kompile.tool.camel;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.FolNodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * MCP tools for evaluating business rules via Drools.
 * Provides rule execution, decision table evaluation, and rule inspection.
 * Uses reflection to access Drools classes so the module compiles without
 * Drools on the classpath (it's an optional dependency).
 */
@Component
public class BusinessRulesTool {

    private static final Logger log = LoggerFactory.getLogger(BusinessRulesTool.class);

    private final Object droolsNodeExecutor;
    private final Object droolsDecisionTableCompiler;
    private final FolNodeExecutor folNodeExecutor;

    public BusinessRulesTool(
            @Autowired(required = false) @Qualifier("droolsNodeExecutor") Object droolsNodeExecutor,
            @Autowired(required = false) @Qualifier("droolsDecisionTableCompiler") Object droolsDecisionTableCompiler) {
        // Filter to only accept actual Drools types
        this.droolsNodeExecutor = isDroolsExecutor(droolsNodeExecutor) ? droolsNodeExecutor : null;
        this.droolsDecisionTableCompiler = isDroolsDecisionTableCompiler(droolsDecisionTableCompiler) ? droolsDecisionTableCompiler : null;
        // Native executor is always available — bundled in kompile-compute-graph-core
        this.folNodeExecutor = new FolNodeExecutor();
    }

    // ---- Input Records ----

    public record EvaluateRulesInput(
            String drl,
            Map<String, Object> facts,
            String agendaGroup,
            int maxFirings
    ) {}

    public record EvaluateDecisionTableInput(
            String decisionTable,
            String inputType,
            Map<String, Object> facts,
            String worksheetName
    ) {}

    public record InspectDecisionTableInput(
            String decisionTable,
            String inputType
    ) {}

    // ---- Tools ----

    @Tool(name = "rules_evaluate",
          description = "Evaluate Drools business rules (DRL) against a set of facts. " +
                  "The 'drl' parameter contains the rule definition. " +
                  "The 'facts' parameter is a map of named facts to insert into working memory. " +
                  "Returns rule outputs and the number of rules fired.")
    public Map<String, Object> evaluateRules(EvaluateRulesInput input) {
        if (droolsNodeExecutor == null) {
            return Map.of("error", "Drools engine not available. Ensure kompile-compute-graph-drools is on the classpath.");
        }

        try {
            ComputeNode node = ComputeNode.builder()
                    .id("tool-rules-" + UUID.randomUUID())
                    .name("Rule Evaluation")
                    .executionType(NodeExecutionType.DROOLS_RULE)
                    .script(input.drl())
                    .parameters(input.agendaGroup() != null
                            ? Map.of("agendaGroup", input.agendaGroup())
                            : Map.of())
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("tool-graph")
                    .name("Rule Eval")
                    .nodes(List.of(node))
                    .edges(List.of())
                    .build();

            Map<String, Object> facts = input.facts() != null ? input.facts() : Map.of();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

            // Invoke via the NodeExecutor interface
            ExecutionResult result = invokeExecutor(node, facts, context);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getStatus().name());
            response.put("outputs", result.getOutputs());
            if (result.getError() != null) {
                response.put("error", result.getError());
            }
            return response;

        } catch (Exception e) {
            log.error("Rule evaluation failed", e);
            return Map.of("error", "Rule evaluation failed: " + e.getMessage());
        }
    }

    @Tool(name = "rules_evaluate_decision_table",
          description = "Evaluate a Drools decision table (spreadsheet-based rules) against facts. " +
                  "The 'decisionTable' contains CSV content or Base64-encoded XLS content. " +
                  "Set 'inputType' to 'CSV' or 'XLS' (auto-detected if omitted). " +
                  "Returns rule outputs from the decision table evaluation.")
    public Map<String, Object> evaluateDecisionTable(EvaluateDecisionTableInput input) {
        if (droolsNodeExecutor == null) {
            return Map.of("error", "Drools engine not available.");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            if (input.inputType() != null) {
                params.put("inputType", input.inputType());
            }
            if (input.worksheetName() != null) {
                params.put("worksheetName", input.worksheetName());
            }

            ComputeNode node = ComputeNode.builder()
                    .id("tool-dt-" + UUID.randomUUID())
                    .name("Decision Table Evaluation")
                    .executionType(NodeExecutionType.DROOLS_DECISION_TABLE)
                    .script(input.decisionTable())
                    .parameters(params)
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("tool-graph")
                    .name("DT Eval")
                    .nodes(List.of(node))
                    .edges(List.of())
                    .build();

            Map<String, Object> facts = input.facts() != null ? input.facts() : Map.of();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

            ExecutionResult result = invokeExecutor(node, facts, context);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getStatus().name());
            response.put("outputs", result.getOutputs());
            if (result.getError() != null) {
                response.put("error", result.getError());
            }
            return response;

        } catch (Exception e) {
            log.error("Decision table evaluation failed", e);
            return Map.of("error", "Decision table evaluation failed: " + e.getMessage());
        }
    }

    @Tool(name = "rules_inspect_decision_table",
          description = "Convert a decision table to DRL without executing, for inspection. " +
                  "Useful for debugging or understanding what rules a spreadsheet generates. " +
                  "Returns the generated DRL source code.")
    public Map<String, Object> inspectDecisionTable(InspectDecisionTableInput input) {
        if (droolsDecisionTableCompiler == null) {
            return Map.of("error", "Decision table compiler not available.");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            if (input.inputType() != null) {
                params.put("inputType", input.inputType());
            }

            ComputeNode node = ComputeNode.builder()
                    .id("inspect-dt")
                    .name("Decision Table Inspection")
                    .executionType(NodeExecutionType.DROOLS_DECISION_TABLE)
                    .script(input.decisionTable())
                    .parameters(params)
                    .build();

            // Call toDrl via reflection
            Method toDrlMethod = droolsDecisionTableCompiler.getClass().getMethod("toDrl", ComputeNode.class);
            String drl = (String) toDrlMethod.invoke(droolsDecisionTableCompiler, node);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generatedDrl", drl);
            result.put("drlLength", drl.length());
            result.put("ruleCount", countRules(drl));
            return result;

        } catch (Exception e) {
            log.error("Decision table inspection failed", e);
            return Map.of("error", "Inspection failed: " + e.getMessage());
        }
    }

    // ─── Native FOL/PSL/Tabular tools (no Drools dependency) ─────────────────

    public record EvaluateFolRulesInput(String ruleScript, Map<String, Object> facts, String agendaGroup) {}
    public record EvaluatePslRulesInput(String ruleScript, Map<String, Object> facts) {}
    public record EvaluateTabularRuleInput(String tableScript, Map<String, Object> facts, String hitPolicy, Double weight) {}

    @Tool(name = "rules_evaluate_fol",
          description = "Evaluate FOL/PSL rules using the native probabilistic soft-logic engine (no Drools). " +
                  "Syntax: 'weight: Body(X) -> Head(X) ^2' per line. " +
                  "Returns inferred atom truth values and _inferredFacts list.")
    public Map<String, Object> evaluateFolRules(EvaluateFolRulesInput input) {
        try {
            ComputeNode node = ComputeNode.builder()
                    .id("tool-fol-" + UUID.randomUUID())
                    .name("FOL Rule Evaluation")
                    .executionType(NodeExecutionType.FOL_RULE)
                    .script(input.ruleScript())
                    .parameters(input.agendaGroup() != null ? Map.of("agendaGroup", input.agendaGroup()) : Map.of())
                    .build();
            ComputeGraph graph = ComputeGraph.builder().id("tool-graph").name("FOL Eval")
                    .nodes(List.of(node)).edges(List.of()).build();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());
            ExecutionResult result = folNodeExecutor.execute(node, input.facts() != null ? input.facts() : Map.of(), context);
            return Map.of("status", result.getStatus().name(), "outputs", result.getOutputs(),
                    "error", result.getError() != null ? result.getError() : "");
        } catch (Exception e) {
            log.error("FOL rule evaluation failed", e);
            return Map.of("error", "FOL rule evaluation failed: " + e.getMessage());
        }
    }

    @Tool(name = "rules_evaluate_psl",
          description = "Evaluate PSL rules with full forward-chaining inference (crisp Datalog fixpoint + MAP). " +
                  "Replaces DROOLS_INFERENCE without any KIE dependency. " +
                  "Syntax same as rules_evaluate_fol.")
    public Map<String, Object> evaluatePslRules(EvaluatePslRulesInput input) {
        try {
            ComputeNode node = ComputeNode.builder()
                    .id("tool-psl-" + UUID.randomUUID())
                    .name("PSL Rule Evaluation")
                    .executionType(NodeExecutionType.PSL_RULE)
                    .script(input.ruleScript())
                    .build();
            ComputeGraph graph = ComputeGraph.builder().id("tool-graph").name("PSL Eval")
                    .nodes(List.of(node)).edges(List.of()).build();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());
            ExecutionResult result = folNodeExecutor.execute(node, input.facts() != null ? input.facts() : Map.of(), context);
            return Map.of("status", result.getStatus().name(), "outputs", result.getOutputs(),
                    "error", result.getError() != null ? result.getError() : "");
        } catch (Exception e) {
            log.error("PSL rule evaluation failed", e);
            return Map.of("error", "PSL rule evaluation failed: " + e.getMessage());
        }
    }

    @Tool(name = "rules_evaluate_tabular",
          description = "Evaluate a CSV decision table via the native TableDecisionCompiler → PSL pipeline. " +
                  "Header row declares CONDITION/CONCLUSION roles (e.g. 'Age:CONDITION,Risk:CONCLUSION'). " +
                  "Returns inferred atom truth values. hitPolicy: FIRST (default), ALL, PRIORITY.")
    public Map<String, Object> evaluateTabularRule(EvaluateTabularRuleInput input) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            if (input.hitPolicy() != null) params.put("hitPolicy", input.hitPolicy());
            if (input.weight() != null) params.put("weight", String.valueOf(input.weight()));
            ComputeNode node = ComputeNode.builder()
                    .id("tool-tabular-" + UUID.randomUUID())
                    .name("Tabular Rule Evaluation")
                    .executionType(NodeExecutionType.TABULAR_RULE)
                    .script(input.tableScript())
                    .parameters(params)
                    .build();
            ComputeGraph graph = ComputeGraph.builder().id("tool-graph").name("Tabular Eval")
                    .nodes(List.of(node)).edges(List.of()).build();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());
            ExecutionResult result = folNodeExecutor.execute(node, input.facts() != null ? input.facts() : Map.of(), context);
            return Map.of("status", result.getStatus().name(), "outputs", result.getOutputs(),
                    "error", result.getError() != null ? result.getError() : "");
        } catch (Exception e) {
            log.error("Tabular rule evaluation failed", e);
            return Map.of("error", "Tabular rule evaluation failed: " + e.getMessage());
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private ExecutionResult invokeExecutor(ComputeNode node, Map<String, Object> facts, ExecutionContext context) throws Exception {
        Method executeMethod = droolsNodeExecutor.getClass().getMethod("execute",
                ComputeNode.class, Map.class, ExecutionContext.class);
        return (ExecutionResult) executeMethod.invoke(droolsNodeExecutor, node, facts, context);
    }

    private boolean isDroolsExecutor(Object obj) {
        return obj != null && obj.getClass().getName().contains("DroolsNodeExecutor");
    }

    private boolean isDroolsDecisionTableCompiler(Object obj) {
        return obj != null && obj.getClass().getName().contains("DroolsDecisionTableCompiler");
    }

    private int countRules(String drl) {
        int count = 0;
        int idx = 0;
        while ((idx = drl.indexOf("rule ", idx)) != -1) {
            count++;
            idx += 5;
        }
        return count;
    }
}
