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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy.FailureMode;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExtractionPreviewServicePolicyTest {

    private final GraphExtractionPreviewService service = new GraphExtractionPreviewService();

    @Test
    void previewPromptUsesProjectValidationRulesWithoutExampleFacts() {
        GraphExtractionValidationPolicy policy = GraphExtractionValidationPolicy.builder()
                .failureMode(FailureMode.RETRY)
                .enabledValidators(List.of(
                        GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS,
                        GraphExtractionValidationPolicy.RELATION_SCHEMA_PATTERN))
                .relationPatterns(List.of("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"))
                .build();
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .validationPolicy(policy)
                .build();

        String prompt = service.buildExtractionPrompt(config);

        assertTrue(prompt.contains("Every entity and relation MUST have a concise description"));
        assertTrue(prompt.contains("(PERSON)-[:APPROVED_BY]->(CLOSE_STEP)"));
        assertTrue(prompt.contains("ONLY from the SOURCE TEXT"));
        assertFalse(prompt.contains("John"));
        assertFalse(prompt.contains("Acme"));
    }

    @Test
    void previewValidationHonorsRetryAndWarnFailureModes() {
        ExtractionResult missingDescription = ExtractionResult.of(
                List.of(new ExtractedEntity(
                        "e1", "Revenue", "METRIC", List.of(), null, 0.95, Map.of())),
                List.of(),
                null);
        GraphExtractionValidationPolicy retry = GraphExtractionValidationPolicy.builder()
                .failureMode(FailureMode.RETRY)
                .enabledValidators(List.of(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS))
                .build();
        GraphExtractionConfig retryConfig = GraphExtractionConfig.builder()
                .validationPolicy(retry)
                .build();

        var retryResult = service.validateExtraction(missingDescription, retryConfig);

        assertFalse(retryResult.valid());
        assertTrue(retryResult.errors().stream().anyMatch(error -> error.contains("REQUIRED_DESCRIPTIONS")));

        GraphExtractionValidationPolicy warn = retry.copy();
        warn.setFailureMode(FailureMode.WARN);
        GraphExtractionConfig warnConfig = GraphExtractionConfig.builder()
                .validationPolicy(warn)
                .build();

        var warnResult = service.validateExtraction(missingDescription, warnConfig);

        assertTrue(warnResult.valid());
        assertTrue(warnResult.warnings().stream().anyMatch(error -> error.contains("REQUIRED_DESCRIPTIONS")));
    }
}
