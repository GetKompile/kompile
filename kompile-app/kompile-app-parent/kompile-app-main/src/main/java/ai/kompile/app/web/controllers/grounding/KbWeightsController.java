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

package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller exposing the learned PSL rule weights persisted in the {@link WeightStore}.
 *
 * <p>The WeightStore may not be registered as a Spring bean in all deployment configurations.
 * When absent the controller degrades gracefully: GET /api/kb/weights returns 503 with a
 * descriptive message rather than a 500 error.</p>
 *
 * <p>Package is under {@code ai.kompile.app.web.controllers.grounding} which is covered by
 * the global {@link ai.kompile.app.web.GlobalExceptionHandler}.</p>
 */
@RestController
@RequestMapping("/api/kb/weights")
public class KbWeightsController {

    @Nullable
    private final WeightStore weightStore;

    @Nullable
    private final MebnWeightPersistenceAdapter mebnWeightPersistenceAdapter;

    @Autowired
    public KbWeightsController(@Nullable WeightStore weightStore,
                               @Nullable MebnWeightPersistenceAdapter mebnWeightPersistenceAdapter) {
        this.weightStore = weightStore;
        this.mebnWeightPersistenceAdapter = mebnWeightPersistenceAdapter;
    }

    /**
     * Retrieve the latest weight-set for the given programId.
     *
     * @param programId the PSL program/ruleset identifier; defaults to {@code "default"}
     * @return 200 with {@link WeightsResponse}; 503 if the WeightStore is not available
     */
    @GetMapping
    public ResponseEntity<WeightsResponse> getWeights(
            @RequestParam(defaultValue = "default") String programId) {

        if (weightStore == null) {
            return ResponseEntity.status(503).body(
                    new WeightsResponse(programId, 0, Map.of(), List.of(),
                            "WeightStore is not available in this configuration"));
        }

        Optional<Map<String, Double>> latest = weightStore.latest(programId);
        List<String> available = weightStore.programIds().stream().sorted().toList();

        if (latest.isEmpty()) {
            return ResponseEntity.ok(
                    new WeightsResponse(programId, 0, Map.of(), available, null));
        }

        return ResponseEntity.ok(
                new WeightsResponse(programId,
                        weightStore.latestVersion(programId),
                        latest.get(),
                        available,
                        null));
    }

    /**
     * List all programIds that have stored weights.
     *
     * @return sorted list of programIds
     */
    @GetMapping("/programs")
    public ResponseEntity<List<String>> getPrograms() {
        if (weightStore == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(weightStore.programIds().stream().sorted().toList());
    }

    /**
     * Retrieve the MEBN MFrag/theory edge-strength weights for the given fact sheet.
     *
     * <p>Data source: {@code <dataDir>/data/graph/reasoning/<factSheetId>/mebn-weights.json}
     * written by {@link ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter}.
     * Each entry in that file has the composite key {@code "<mfragName>|<parent>-><child>"}
     * mapping to a learned noisy-OR edge strength in [0,1].</p>
     *
     * <p>The returned {@link MebnWeightRow} list splits each composite key into
     * {@code mFragName} and {@code conditionDescription} ({@code "<parent>-><child>"})
     * and includes the {@code learnedStrength}.</p>
     *
     * @param factSheetId the fact-sheet scoping the MEBN theory
     * @return 200 with sorted list of {@link MebnWeightRow}; 503 if the adapter is unavailable;
     *         404 with empty list if no weights have been persisted for this fact sheet yet
     */
    @GetMapping("/mebn/{factSheetId}")
    public ResponseEntity<List<MebnWeightRow>> getMebnWeights(@PathVariable long factSheetId) {
        if (mebnWeightPersistenceAdapter == null) {
            return ResponseEntity.status(503).build();
        }
        try {
            Map<String, Double> raw = mebnWeightPersistenceAdapter.readRawStrengths(factSheetId);
            if (raw.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
            List<MebnWeightRow> rows = new ArrayList<>(raw.size());
            for (Map.Entry<String, Double> entry : raw.entrySet()) {
                String compositeKey = entry.getKey();
                int pipe = compositeKey.lastIndexOf('|');
                String mFragName = pipe >= 0 ? compositeKey.substring(0, pipe) : compositeKey;
                String conditionDescription = pipe >= 0 ? compositeKey.substring(pipe + 1) : "";
                rows.add(new MebnWeightRow(mFragName, conditionDescription, entry.getValue()));
            }
            rows.sort((a, b) -> {
                int cmp = a.mFragName().compareTo(b.mFragName());
                return cmp != 0 ? cmp : a.conditionDescription().compareTo(b.conditionDescription());
            });
            return ResponseEntity.ok(rows);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Response DTO for the weights endpoint.
     *
     * @param programId         the program identifier
     * @param version           the monotonic version of the returned weights (0 = none stored)
     * @param weights           rule-display-text → weight value map
     * @param availablePrograms all programIds that have stored weights
     * @param message           optional informational or error message
     */
    public record WeightsResponse(
            String programId,
            int version,
            Map<String, Double> weights,
            List<String> availablePrograms,
            String message
    ) {}

    /**
     * One MEBN edge-strength row returned by {@link #getMebnWeights(long)}.
     *
     * @param mFragName            the name of the MFrag that owns this edge
     * @param conditionDescription the edge description in {@code "<parent>-><child>"} format
     * @param learnedStrength      the learned noisy-OR edge strength in [0, 1]
     */
    public record MebnWeightRow(
            String mFragName,
            String conditionDescription,
            double learnedStrength
    ) {}
}
