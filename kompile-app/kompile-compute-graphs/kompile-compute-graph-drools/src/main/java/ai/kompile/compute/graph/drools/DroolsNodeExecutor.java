package ai.kompile.compute.graph.drools;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Executes Drools rules on compute graph nodes.
 * Supports two modes:
 * - DROOLS_RULE: Fires a specific rule or rule group (targeted execution)
 * - DROOLS_INFERENCE: Fires all rules with forward chaining (full inference)
 *
 * Node inputs are inserted as facts into working memory.
 * Rule consequences can modify facts or insert new ones, which become outputs.
 */
@Slf4j
public class DroolsNodeExecutor implements NodeExecutor {

    private final DroolsRuleCompiler ruleCompiler;
    private final DroolsDecisionTableCompiler decisionTableCompiler;
    private final int maxRuleFirings;

    public DroolsNodeExecutor(DroolsRuleCompiler ruleCompiler) {
        this(ruleCompiler, null, 1000);
    }

    public DroolsNodeExecutor(DroolsRuleCompiler ruleCompiler, int maxRuleFirings) {
        this(ruleCompiler, null, maxRuleFirings);
    }

    public DroolsNodeExecutor(DroolsRuleCompiler ruleCompiler, DroolsDecisionTableCompiler decisionTableCompiler, int maxRuleFirings) {
        this.ruleCompiler = ruleCompiler;
        this.decisionTableCompiler = decisionTableCompiler;
        this.maxRuleFirings = maxRuleFirings;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();

        try {
            // Compile DRL or decision table for this node
            KieBase kieBase;
            if (node.getExecutionType() == NodeExecutionType.DROOLS_DECISION_TABLE) {
                if (decisionTableCompiler == null) {
                    throw new IllegalStateException("Decision table compiler not available — add drools-decisiontables dependency");
                }
                kieBase = decisionTableCompiler.compile(node);
            } else {
                kieBase = ruleCompiler.compile(node);
            }
            KieSession session = kieBase.newKieSession();

            try {
                // Insert inputs as facts
                NodeFacts facts = new NodeFacts(node.getId(), context.getExecutionId());
                facts.getInputs().putAll(inputs);
                if (node.getParameters() != null) {
                    facts.getParameters().putAll(node.getParameters());
                }
                facts.getGlobalState().putAll(context.getGlobalState());

                session.insert(facts);

                // Insert individual input values as separate facts for fine-grained matching
                for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                    session.insert(new NamedFact(entry.getKey(), entry.getValue()));
                }

                // Set globals if the session supports them
                try {
                    session.setGlobal("_context", context);
                } catch (Exception e) {
                    // Global not defined in rules — that's fine
                }

                // Fire rules
                int firingLimit = resolveMaxRuleFirings(node);
                int rulesFired;
                if (node.getExecutionType() == NodeExecutionType.DROOLS_RULE) {
                    // Targeted: only fire rules in the specific agenda group
                    String agendaGroup = node.getParameters() != null
                            ? (String) node.getParameters().get("agendaGroup")
                            : null;
                    if (agendaGroup != null) {
                        session.getAgenda().getAgendaGroup(agendaGroup).setFocus();
                    }
                    rulesFired = session.fireAllRules(firingLimit);
                } else {
                    // Full inference: fire all rules until stable state
                    rulesFired = session.fireAllRules(firingLimit);
                }

                log.debug("Node '{}': {} rules fired", node.getName(), rulesFired);

                // Extract outputs from the modified facts
                Map<String, Object> outputs = new HashMap<>(facts.getOutputs());
                outputs.put("_rulesFired", rulesFired);

                // Also collect any new NamedFact objects inserted by rules
                Collection<?> allFacts = session.getObjects();
                for (Object fact : allFacts) {
                    if (fact instanceof NamedFact namedFact) {
                        if (!inputs.containsKey(namedFact.getName())) {
                            outputs.putIfAbsent(namedFact.getName(), namedFact.getValue());
                        }
                    }
                }

                Instant completedAt = Instant.now();
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.COMPLETED)
                        .outputs(outputs)
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .duration(Duration.between(startedAt, completedAt))
                        .build();

            } finally {
                session.dispose();
            }

        } catch (Exception e) {
            log.error("Drools execution failed on node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.DROOLS_RULE, NodeExecutionType.DROOLS_INFERENCE, NodeExecutionType.DROOLS_DECISION_TABLE);
    }

    private int resolveMaxRuleFirings(ComputeNode node) {
        if (node.getParameters() == null) {
            return maxRuleFirings;
        }
        Object value = node.getParameters().get("maxFirings");
        if (value == null) {
            value = node.getParameters().get("maxRuleFirings");
        }
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException e) {
                log.warn("Invalid maxFirings value '{}' on node '{}'; using default {}",
                        value, node.getName(), maxRuleFirings);
            }
        }
        return maxRuleFirings;
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return node.getExecutionType() == NodeExecutionType.DROOLS_DECISION_TABLE
                    ? "Decision table content is empty"
                    : "DRL rule definition is empty";
        }
        try {
            if (node.getExecutionType() == NodeExecutionType.DROOLS_DECISION_TABLE) {
                if (decisionTableCompiler == null) {
                    return "Decision table compiler not available";
                }
                decisionTableCompiler.compile(node);
            } else {
                ruleCompiler.compile(node);
            }
            return null;
        } catch (Exception e) {
            return (node.getExecutionType() == NodeExecutionType.DROOLS_DECISION_TABLE
                    ? "Decision table compilation error: "
                    : "DRL compilation error: ") + e.getMessage();
        }
    }
}
