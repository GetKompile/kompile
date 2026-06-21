/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.FolNodeExecutor;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Camel Processor that bridges to the native FOL/PSL/Tabular reasoning engine.
 *
 * <p>Drop-in native replacement for {@link DroolsCamelProcessor}.  Uses
 * {@link FolNodeExecutor} directly (no reflection, no KIE dependency).</p>
 *
 * <p>Usage in a Camel route:</p>
 * <pre>{@code
 * FolCamelProcessor folProcessor = new FolCamelProcessor(
 *     NodeExecutionType.FOL_RULE,
 *     "2.0: High(X) -> Risk(X) ^2\n1e6: Blocked(X) -> !Allowed(X) ^2",
 *     null);
 * }</pre>
 *
 * <p>Exchange body (if a Map) and all headers are merged into the facts map.
 * Results are written back to the exchange body and individual
 * {@code kompile_fol_<key>} headers.</p>
 */
@Slf4j
public class FolCamelProcessor implements Processor {

    private final NodeExecutionType executionType;
    private final String ruleScript;
    private final String agendaGroup;
    private final FolNodeExecutor executor;

    /**
     * @param executionType FOL_RULE, PSL_RULE, or TABULAR_RULE
     * @param ruleScript    PSL rule text (FOL_RULE/PSL_RULE) or CSV table (TABULAR_RULE)
     * @param agendaGroup   optional hint (informational, stored in node params)
     */
    public FolCamelProcessor(NodeExecutionType executionType, String ruleScript, String agendaGroup) {
        if (executionType != NodeExecutionType.FOL_RULE
                && executionType != NodeExecutionType.PSL_RULE
                && executionType != NodeExecutionType.TABULAR_RULE) {
            throw new IllegalArgumentException(
                    "FolCamelProcessor only handles FOL_RULE/PSL_RULE/TABULAR_RULE; got " + executionType);
        }
        this.executionType = executionType;
        this.ruleScript = ruleScript;
        this.agendaGroup = agendaGroup;
        this.executor = new FolNodeExecutor();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> facts = new HashMap<>();

        Object body = exchange.getIn().getBody();
        if (body instanceof Map<?, ?> bodyMap) {
            for (Map.Entry<?, ?> e : bodyMap.entrySet()) {
                facts.put(String.valueOf(e.getKey()), e.getValue());
            }
        } else if (body != null) {
            facts.put("body", body);
        }
        facts.putAll(exchange.getIn().getHeaders());

        Map<String, Object> params = new HashMap<>();
        if (agendaGroup != null && !agendaGroup.isBlank()) {
            params.put("agendaGroup", agendaGroup);
        }

        ComputeNode node = ComputeNode.builder()
                .id("camel-fol-" + exchange.getExchangeId())
                .name("Camel FOL Bridge")
                .executionType(executionType)
                .script(ruleScript)
                .parameters(params)
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("camel-graph")
                .name("Camel FOL Graph")
                .nodes(List.of(node))
                .edges(List.of())
                .build();

        ExecutionContext context = new ExecutionContext(
                UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

        ExecutionResult result = executor.execute(node, facts, context);

        if (result.getStatus() == ExecutionStatus.COMPLETED) {
            Map<String, Object> outputs = result.getOutputs();
            exchange.getIn().setBody(outputs);
            exchange.getIn().setHeader("kompile_fol_status", "COMPLETED");
            for (Map.Entry<String, Object> e : outputs.entrySet()) {
                exchange.getIn().setHeader("kompile_fol_" + e.getKey(), e.getValue());
            }
            log.debug("FolCamelProcessor [{}]: {} rules fired, converged={}",
                    executionType, outputs.get("_rulesFired"), outputs.get("_converged"));
        } else {
            log.warn("FolCamelProcessor [{}]: execution failed — {}", executionType, result.getError());
            exchange.getIn().setHeader("kompile_fol_status", "FAILED");
            exchange.getIn().setHeader("kompile_fol_error", result.getError());
        }
    }
}
