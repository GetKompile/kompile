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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Autowired
    public KbWeightsController(@Nullable WeightStore weightStore) {
        this.weightStore = weightStore;
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
}
