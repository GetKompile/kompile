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

package ai.kompile.process.discovery;

import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.discovery.mining.ProcessMiningConfig;
import ai.kompile.process.discovery.mining.ProcessMiningConfigManager;
import ai.kompile.process.discovery.mining.causal.ProcessBayesianInference;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.causal.ProcessPslInference;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for the principled, LLM-free process-mining engine.
 *
 * <p>Exposes the REST surface of {@link MiningDiscoveryController} as Spring AI
 * {@link Tool}-annotated methods so LLM agents can run discovery, inspect entailment,
 * check conformance, export BPMN, and read/update the mining config — all in-process
 * without an HTTP round-trip.
 *
 * <p><b>Config placement:</b> {@link ProcessMiningConfigManager} is a bean defined inside
 * {@code kompile-process-discovery}, the same module as this class, so we wire it directly
 * here instead of adding a separate class in {@code kompile-app-main}. This is the
 * least-friction path.
 */
@Component
@ConditionalOnBean(MiningProcessDiscoveryService.class)
public class ProcessMiningTool {

    private static final Logger log = LoggerFactory.getLogger(ProcessMiningTool.class);
    private static final int MAX_ENTAIL_PAIRS = 20;
    private static final int MAX_DECLARE = 20;
    private static final int MAX_RULES = 15;

    private final MiningProcessDiscoveryService miningService;

    @Autowired(required = false)
    private ProcessMiningConfigManager configManager;

    public ProcessMiningTool(MiningProcessDiscoveryService miningService) {
        this.miningService = miningService;
    }

    // ── Input records ────────────────────────────────────────────────────────────

    public record MineInput(
            Long factSheetId,
            Double noise,
            String anchorType) {}

    public record MineAllInput(
            Double noise,
            String anchorType) {}

    public record EntailmentInput(
            Long factSheetId,
            Double minSupport,
            Double minConfidence,
            String anchorType) {}

    public record ConformanceInput(
            Long factSheetId,
            Double noise,
            String anchorType) {}

    public record DeclareInput(
            Long factSheetId,
            Double minSupport,
            Double minConfidence,
            String anchorType) {}

    public record BpmnInput(
            Long factSheetId,
            Double noise,
            String anchorType) {}

    public record ConfigUpdateInput(Map<String, Object> config) {}

    // ── Tools ────────────────────────────────────────────────────────────────────

    @Tool(name = "process_mine",
          description = "Run the Inductive Miner on a fact sheet's knowledge graph and persist the result as a process suggestion. " +
                  "factSheetId: required. noise: IMf filter threshold 0-1 (0 = classic). anchorType: optional object-centric case notion.")
    public Map<String, Object> mine(MineInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (input.factSheetId() == null) {
                result.put("status", "error");
                result.put("error", "factSheetId is required");
                return result;
            }
            double noise = input.noise() != null ? input.noise() : 0.0;
            ProcessSuggestion suggestion = miningService.discoverForFactSheet(
                    input.factSheetId(), noise, input.anchorType());
            if (suggestion == null) {
                result.put("status", "empty");
                result.put("message", "No process mined — fact sheet may have insufficient event data");
            } else {
                result.put("status", "success");
                result.put("suggestionId", suggestion.getId());
                result.put("name", suggestion.getName());
                result.put("confidence", suggestion.getConfidence());
                result.put("phaseCount", suggestion.getPhases() != null ? suggestion.getPhases().size() : 0);
                result.put("processKey", suggestion.getProcessKey());
                result.put("discoveredAt", suggestion.getDiscoveredAt() != null ? suggestion.getDiscoveredAt().toString() : null);
                if (suggestion.getNarrative() != null) {
                    result.put("narrative", suggestion.getNarrative());
                }
            }
        } catch (Exception e) {
            log.error("process_mine failed factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mine_all",
          description = "Mine every fact sheet that has a graph. Returns a map of factSheetId -> suggestionId. " +
                  "noise: IMf filter threshold 0-1. anchorType: optional object-centric case notion.")
    public Map<String, Object> mineAll(MineAllInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            double noise = input.noise() != null ? input.noise() : 0.0;
            Map<Long, String> discovered = miningService.discoverAll(noise, input.anchorType());
            result.put("status", "success");
            result.put("count", discovered.size());
            result.put("suggestionsByFactSheet", discovered);
        } catch (Exception e) {
            log.error("process_mine_all failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_entailment",
          description = "Run the PSL entailment pass over a fact sheet's process. Returns accepted/derived " +
                  "precedence pairs with posteriors, rule weights, temporal verdicts, and a fused opinion. " +
                  "factSheetId: required. minSupport/minConfidence: Declare mining thresholds.")
    public Map<String, Object> entailment(EntailmentInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (input.factSheetId() == null) {
                result.put("status", "error");
                result.put("error", "factSheetId is required");
                return result;
            }
            double minSupport = input.minSupport() != null ? input.minSupport() : 0.2;
            double minConfidence = input.minConfidence() != null ? input.minConfidence() : 0.66;

            ProcessEntailmentResult r = miningService.entailment(
                    input.factSheetId(), input.anchorType(), minSupport, minConfidence);

            result.put("status", "success");
            result.put("empty", r.isEmpty());
            result.put("transitivityApplied", r.transitivityApplied());
            result.put("runId", r.runId());
            result.put("totalPairs", r.precedences().size());
            result.put("acceptedPairs", r.accepted().size());
            result.put("entailedOnlyPairs", r.entailedOnly().size());

            // Fused opinion summary
            var opinion = r.fusedOpinion();
            Map<String, Object> opinionMap = new LinkedHashMap<>();
            opinionMap.put("belief", opinion.belief());
            opinionMap.put("disbelief", opinion.disbelief());
            opinionMap.put("uncertainty", opinion.uncertainty());
            opinionMap.put("expectation", opinion.expectation());
            result.put("fusedOpinion", opinionMap);

            // Top accepted precedences (capped)
            List<Map<String, Object>> pairs = new ArrayList<>();
            List<ProcessEntailmentResult.EntailedPrecedence> accepted = r.accepted();
            int shown = Math.min(accepted.size(), MAX_ENTAIL_PAIRS);
            for (int i = 0; i < shown; i++) {
                var p = accepted.get(i);
                Map<String, Object> pMap = new LinkedHashMap<>();
                pMap.put("from", p.from());
                pMap.put("to", p.to());
                pMap.put("posterior", p.posterior());
                pMap.put("observed", p.observed());
                pMap.put("temporallyRefuted", p.temporallyRefuted());
                pMap.put("concurrent", p.concurrent());
                pMap.put("orderedEvidence", p.orderedEvidence());
                pMap.put("reversedEvidence", p.reversedEvidence());
                if (!p.activatedRules().isEmpty()) {
                    pMap.put("activatedRules", p.activatedRules().size() <= 3
                            ? p.activatedRules()
                            : p.activatedRules().subList(0, 3));
                }
                pairs.add(pMap);
            }
            result.put("acceptedPrecedences", pairs);
            if (accepted.size() > MAX_ENTAIL_PAIRS) {
                result.put("acceptedPrecedencesMore", accepted.size() - MAX_ENTAIL_PAIRS);
            }

            // Rule texts (capped)
            List<String> rules = r.ruleTexts();
            if (!rules.isEmpty()) {
                result.put("ruleCount", rules.size());
                result.put("rules", rules.size() <= MAX_RULES ? rules : rules.subList(0, MAX_RULES));
                if (rules.size() > MAX_RULES) {
                    result.put("rulesMore", rules.size() - MAX_RULES);
                }
            }
        } catch (Exception e) {
            log.error("process_mining_entailment failed factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_conformance",
          description = "Compute fitness/precision/simplicity of the discovered model against the event log. " +
                  "factSheetId: required. noise: IMf filter 0-1. anchorType: optional.")
    public Map<String, Object> conformance(ConformanceInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (input.factSheetId() == null) {
                result.put("status", "error");
                result.put("error", "factSheetId is required");
                return result;
            }
            double noise = input.noise() != null ? input.noise() : 0.0;
            ConformanceResult r = miningService.conformance(input.factSheetId(), noise, input.anchorType());
            result.put("status", "success");
            result.put("fitness", r.fitness());
            result.put("precision", r.precision());
            result.put("simplicity", r.simplicity());
            result.put("fscore", r.fScore());
        } catch (Exception e) {
            log.error("process_mining_conformance failed factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_declare",
          description = "Get declarative constraints (Response/Precedence/ChainResponse/NotCoExistence/Init/End) " +
                  "from the process. factSheetId: required. minSupport/minConfidence optional.")
    public Map<String, Object> declare(DeclareInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (input.factSheetId() == null) {
                result.put("status", "error");
                result.put("error", "factSheetId is required");
                return result;
            }
            double minSupport = input.minSupport() != null ? input.minSupport() : 0.1;
            double minConfidence = input.minConfidence() != null ? input.minConfidence() : 0.9;
            List<DeclareConstraint> constraints = miningService.declareConstraints(
                    input.factSheetId(), minSupport, minConfidence, input.anchorType());

            result.put("status", "success");
            result.put("totalCount", constraints.size());

            List<Map<String, Object>> items = new ArrayList<>();
            int shown = Math.min(constraints.size(), MAX_DECLARE);
            for (int i = 0; i < shown; i++) {
                DeclareConstraint c = constraints.get(i);
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("template", c.template() != null ? c.template().name() : null);
                cm.put("activityA", c.activityA());
                cm.put("activityB", c.activityB());
                cm.put("support", c.support());
                cm.put("confidence", c.confidence());
                items.add(cm);
            }
            result.put("constraints", items);
            if (constraints.size() > MAX_DECLARE) {
                result.put("more", constraints.size() - MAX_DECLARE);
            }
        } catch (Exception e) {
            log.error("process_mining_declare failed factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_bpmn",
          description = "Export the discovered process as BPMN 2.0 XML with swim lanes keyed by role binding. " +
                  "factSheetId: required. noise: IMf filter 0-1. anchorType: optional.")
    public Map<String, Object> bpmn(BpmnInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (input.factSheetId() == null) {
                result.put("status", "error");
                result.put("error", "factSheetId is required");
                return result;
            }
            double noise = input.noise() != null ? input.noise() : 0.0;
            String xml = miningService.bpmnExport(input.factSheetId(), noise, input.anchorType());
            if (xml == null) {
                result.put("status", "empty");
                result.put("message", "No BPMN produced — ensure process discovery has been run first");
            } else {
                result.put("status", "success");
                result.put("bpmnXml", xml);
                result.put("length", xml.length());
            }
        } catch (Exception e) {
            log.error("process_mining_bpmn failed factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_config_get",
          description = "Get the current process-mining configuration (18 tunables: thresholds, clustering, " +
                  "entailment, semantic blend, alias unification, identity, change-point, conflict, taxonomy).")
    public Map<String, Object> configGet() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessMiningConfig cfg = configManager != null
                    ? configManager.current()
                    : ProcessMiningConfig.defaults();
            result.put("status", "success");
            result.put("config", cfg.toMap());
        } catch (Exception e) {
            log.error("process_mining_config_get failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_mining_config_update",
          description = "Update process-mining configuration. Pass a config object with any subset of the 18 " +
                  "mining* keys (e.g. miningDeclareMinConfidence, miningEntailAssertThreshold). " +
                  "Unknown keys are ignored; values are clamped to valid ranges.")
    public Map<String, Object> configUpdate(ConfigUpdateInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (configManager == null) {
                result.put("status", "error");
                result.put("error", "ProcessMiningConfigManager not available — app-main context required");
                return result;
            }
            if (input.config() == null || input.config().isEmpty()) {
                result.put("status", "error");
                result.put("error", "config map is required and must not be empty");
                return result;
            }
            configManager.update(new LinkedHashMap<>(input.config()));
            ProcessMiningConfig updated = configManager.current();
            result.put("status", "success");
            result.put("config", updated.toMap());
        } catch (Exception e) {
            log.error("process_mining_config_update failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }
}
