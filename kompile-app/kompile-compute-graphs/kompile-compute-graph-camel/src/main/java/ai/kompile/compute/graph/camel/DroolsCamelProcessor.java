package ai.kompile.compute.graph.camel;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A Camel Processor that bridges to the Drools rule engine.
 * <p>
 * This processor can be used within Camel routes to invoke Drools rules
 * as part of an integration flow. The exchange body and headers are inserted
 * as facts into Drools working memory, rules are fired, and the results
 * are set back on the exchange.
 * <p>
 * Usage in a Camel route (XML DSL):
 * <pre>{@code
 * <route>
 *   <from uri="direct:evaluate-rules"/>
 *   <process ref="droolsCamelProcessor"/>
 *   <to uri="direct:next-step"/>
 * </route>
 * }</pre>
 * <p>
 * The processor uses reflection to access the Drools module (optional dependency)
 * so this class compiles without Drools on the classpath.
 */
@Slf4j
public class DroolsCamelProcessor implements Processor {

    private final Object droolsNodeExecutor;
    private final String drlScript;
    private final String agendaGroup;

    /**
     * @param droolsNodeExecutor An instance of DroolsNodeExecutor (or null to skip)
     * @param drlScript          The DRL rule definition to execute
     * @param agendaGroup        Optional agenda group to focus on (null for all rules)
     */
    public DroolsCamelProcessor(Object droolsNodeExecutor, String drlScript, String agendaGroup) {
        this.droolsNodeExecutor = droolsNodeExecutor;
        this.drlScript = drlScript;
        this.agendaGroup = agendaGroup;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        if (droolsNodeExecutor == null) {
            log.warn("DroolsNodeExecutor not available — skipping rule evaluation");
            return;
        }

        Map<String, Object> facts = new HashMap<>();

        // Exchange body as input
        Object body = exchange.getIn().getBody();
        if (body instanceof Map) {
            facts.putAll((Map<String, Object>) body);
        } else {
            facts.put("body", body);
        }

        // All headers as additional facts
        facts.putAll(exchange.getIn().getHeaders());

        try {
            // Use reflection to invoke DroolsNodeExecutor without compile-time dependency
            // Build a minimal ComputeNode-like object
            Class<?> nodeClass = Class.forName("ai.kompile.compute.graph.model.ComputeNode");
            Object nodeBuilder = nodeClass.getMethod("builder").invoke(null);
            Class<?> builderClass = nodeBuilder.getClass();

            nodeBuilder = builderClass.getMethod("id", String.class)
                    .invoke(nodeBuilder, "camel-drools-" + exchange.getExchangeId());
            nodeBuilder = builderClass.getMethod("name", String.class)
                    .invoke(nodeBuilder, "Camel-Drools Bridge");
            nodeBuilder = builderClass.getMethod("script", String.class)
                    .invoke(nodeBuilder, drlScript);

            Class<?> execType = Class.forName("ai.kompile.compute.graph.model.NodeExecutionType");
            Object droolsRule = Enum.valueOf((Class<Enum>) execType, "DROOLS_RULE");
            nodeBuilder = builderClass.getMethod("executionType", execType)
                    .invoke(nodeBuilder, droolsRule);

            if (agendaGroup != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("agendaGroup", agendaGroup);
                nodeBuilder = builderClass.getMethod("parameters", Map.class)
                        .invoke(nodeBuilder, params);
            }

            Object node = builderClass.getMethod("build").invoke(nodeBuilder);

            // Create a minimal ExecutionContext
            Class<?> contextClass = Class.forName("ai.kompile.compute.graph.engine.ExecutionContext");

            // Invoke execute via reflection
            Method executeMethod = droolsNodeExecutor.getClass().getMethod("execute",
                    nodeClass, Map.class, contextClass);

            // We pass null for context — DroolsNodeExecutor handles null gracefully for bridge calls
            // Actually, we need a real context. Let's use the node executor's simpler path.
            Class<?> resultClass = Class.forName("ai.kompile.compute.graph.model.ExecutionResult");

            // For the bridge, invoke the compiler directly instead
            Class<?> compilerClass = Class.forName("ai.kompile.compute.graph.drools.DroolsRuleCompiler");
            Object compiler = findField(droolsNodeExecutor, "ruleCompiler");
            if (compiler != null) {
                Method compileMethod = compilerClass.getMethod("compile", nodeClass);
                Object kieBase = compileMethod.invoke(compiler, node);

                // Execute rules using KIE API directly
                Method newSessionMethod = kieBase.getClass().getMethod("newKieSession");
                Object session = newSessionMethod.invoke(kieBase);

                try {
                    // Insert facts
                    Method insertMethod = session.getClass().getMethod("insert", Object.class);
                    Class<?> namedFactClass = Class.forName("ai.kompile.compute.graph.drools.NamedFact");
                    var namedFactCtor = namedFactClass.getConstructor(String.class, Object.class);

                    for (Map.Entry<String, Object> entry : facts.entrySet()) {
                        Object namedFact = namedFactCtor.newInstance(entry.getKey(), entry.getValue());
                        insertMethod.invoke(session, namedFact);
                    }

                    // Fire rules
                    Method fireMethod = session.getClass().getMethod("fireAllRules", int.class);
                    int rulesFired = (int) fireMethod.invoke(session, 1000);
                    log.debug("Camel-Drools bridge fired {} rules", rulesFired);

                    // Collect outputs from NamedFacts
                    Method getObjectsMethod = session.getClass().getMethod("getObjects");
                    Iterable<?> allFacts = (Iterable<?>) getObjectsMethod.invoke(session);
                    Map<String, Object> outputs = new HashMap<>();

                    Method getNameMethod = namedFactClass.getMethod("getName");
                    Method getValueMethod = namedFactClass.getMethod("getValue");

                    for (Object fact : allFacts) {
                        if (namedFactClass.isInstance(fact)) {
                            String name = (String) getNameMethod.invoke(fact);
                            Object value = getValueMethod.invoke(fact);
                            if (!facts.containsKey(name)) {
                                outputs.put(name, value);
                            }
                        }
                    }

                    // Set outputs on the exchange
                    exchange.getIn().setBody(outputs.isEmpty() ? body : outputs);
                    exchange.getIn().setHeader("kompile_drools_rulesFired", rulesFired);
                    for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                        exchange.getIn().setHeader("kompile_output_" + entry.getKey(), entry.getValue());
                    }

                } finally {
                    Method disposeMethod = session.getClass().getMethod("dispose");
                    disposeMethod.invoke(session);
                }
            }

        } catch (ClassNotFoundException e) {
            log.warn("Drools classes not available on classpath — skipping rule evaluation");
        } catch (Exception e) {
            log.error("Drools rule evaluation failed in Camel bridge", e);
            exchange.setException(e);
        }
    }

    private Object findField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            log.debug("Could not access field '{}' on {}", fieldName, target.getClass().getSimpleName());
            return null;
        }
    }
}
