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
import ai.kompile.process.discovery.mining.causal.ProcessBayesianInference;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.causal.ProcessPslInference;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.miner.HeuristicsNet;
import ai.kompile.process.discovery.mining.perf.PerformanceAnalysis;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the LLM-free process-mining engine. Separate from the legacy
 * {@code ProcessDiscoveryController} (under {@code /api/process/discovery}) so the two engines can be
 * exercised side by side.
 */
@RestController
@RequestMapping("/api/process/mining")
@ConditionalOnBean(MiningProcessDiscoveryService.class)
public class MiningDiscoveryController {

    private final MiningProcessDiscoveryService miningService;

    public MiningDiscoveryController(MiningProcessDiscoveryService miningService) {
        this.miningService = miningService;
    }

    /**
     * Mine a sound, block-structured process from a fact sheet's graph and persist it as a suggestion.
     *
     * @param noise      0 = classic Inductive Miner; 0&lt;t≤1 = IMf infrequent-behaviour filter
     * @param anchorType optional object-centric case notion (e.g. "ORDER"); overrides the service-level config
     */
    @GetMapping("/discover")
    public ResponseEntity<ProcessSuggestion> discover(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise,
            @RequestParam(required = false) String anchorType) {
        ProcessSuggestion suggestion = miningService.discoverForFactSheet(factSheetId, noise, anchorType);
        return suggestion == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(suggestion);
    }

    /** Inspect the intermediate artifacts (event log stats, directly-follows arcs, process tree). */
    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise,
            @RequestParam(required = false) String anchorType) {
        return miningService.preview(factSheetId, noise, anchorType);
    }

    /** The causal coupling: χ²-tested directly-follows dependencies (typed) plus generated PSL rules. */
    @GetMapping("/causal")
    public ProcessCausalAnalyzer.ProcessCausalModel causal(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) String anchorType) {
        return miningService.causalAnalysis(factSheetId, anchorType);
    }

    /**
     * Run the project's HL-MRF engine over the discovered process: {@code Link} atoms carry the
     * directly-follows dependency strengths, optional {@code evidence} activities are clamped active.
     */
    @GetMapping("/psl")
    public ProcessPslInference.Result psl(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) List<String> evidence,
            @RequestParam(required = false) String anchorType) {
        return miningService.pslInference(factSheetId, evidence, anchorType);
    }

    /** Run exact Bayesian inference (noisy-OR + variable elimination) over the discovered process. */
    @GetMapping("/bayesian")
    public ProcessBayesianInference.Result bayesian(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) List<String> evidence,
            @RequestParam(required = false) String anchorType) {
        return miningService.bayesianInference(factSheetId, evidence, anchorType);
    }

    /** Declarative constraints (Response/Precedence/ChainResponse/NotCoExistence/Init/End) of the process. */
    @GetMapping("/declare")
    public List<DeclareConstraint> declare(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.1") double minSupport,
            @RequestParam(defaultValue = "0.9") double minConfidence,
            @RequestParam(required = false) String anchorType) {
        return miningService.declareConstraints(factSheetId, minSupport, minConfidence, anchorType);
    }

    /** Mermaid diagram source (directly-follows process map + process-tree blocks) for rendering. */
    @GetMapping("/mermaid")
    public Map<String, String> mermaid(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise,
            @RequestParam(required = false) String anchorType) {
        return miningService.mermaid(factSheetId, noise, anchorType);
    }

    /** Conformance of the discovered model to the log (fitness / precision / simplicity). */
    @GetMapping("/conformance")
    public ConformanceResult conformance(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise,
            @RequestParam(required = false) String anchorType) {
        return miningService.conformance(factSheetId, noise, anchorType);
    }

    /**
     * Mine a Heuristics Net (Weijters &amp; van der Aalst) from the fact sheet's knowledge graph.
     *
     * <p>Each dependency arc {@code a → b} is kept when the Heuristics-Miner measure
     * {@code (|a→b| − |b→a|) / (|a→b| + |b→a| + 1) ≥ threshold}. Lower threshold → denser net;
     * higher threshold → sparser, more selective net. Typical default: 0.5.
     *
     * @param factSheetId the fact sheet whose knowledge graph supplies the event log
     * @param threshold   dependency-measure cut-off; arcs below this value are pruned
     * @param anchorType  optional object-centric case notion
     * @return the mined {@link HeuristicsNet}
     */
    @GetMapping("/heuristics")
    public HeuristicsNet heuristics(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.5") double threshold,
            @RequestParam(required = false) String anchorType) {
        return miningService.heuristicsNet(factSheetId, threshold, anchorType);
    }

    /**
     * Performance (bottleneck) analysis: per directly-follows arc, the count, mean, and median
     * transition duration in seconds. Pairs without timestamps are excluded from duration calculations.
     * Arcs are sorted by median descending so the slowest transitions (bottlenecks) appear first.
     */
    @GetMapping("/performance")
    public PerformanceAnalysis performance(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) String anchorType) {
        return miningService.performance(factSheetId, anchorType);
    }
}
