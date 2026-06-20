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
     * @param noise 0 = classic Inductive Miner; 0&lt;t≤1 = IMf infrequent-behaviour filter
     */
    @GetMapping("/discover")
    public ResponseEntity<ProcessSuggestion> discover(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise) {
        ProcessSuggestion suggestion = miningService.discoverForFactSheet(factSheetId, noise);
        return suggestion == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(suggestion);
    }

    /** Inspect the intermediate artifacts (event log stats, directly-follows arcs, process tree). */
    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0.0") double noise) {
        return miningService.preview(factSheetId, noise);
    }

    /** The causal coupling: χ²-tested directly-follows dependencies (typed) plus generated PSL rules. */
    @GetMapping("/causal")
    public ProcessCausalAnalyzer.ProcessCausalModel causal(@RequestParam Long factSheetId) {
        return miningService.causalAnalysis(factSheetId);
    }

    /**
     * Run the project's HL-MRF engine over the discovered process: {@code Link} atoms carry the
     * directly-follows dependency strengths, optional {@code evidence} activities are clamped active.
     */
    @GetMapping("/psl")
    public ProcessPslInference.Result psl(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) List<String> evidence) {
        return miningService.pslInference(factSheetId, evidence);
    }

    /** Run exact Bayesian inference (noisy-OR + variable elimination) over the discovered process. */
    @GetMapping("/bayesian")
    public ProcessBayesianInference.Result bayesian(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) List<String> evidence) {
        return miningService.bayesianInference(factSheetId, evidence);
    }
}
