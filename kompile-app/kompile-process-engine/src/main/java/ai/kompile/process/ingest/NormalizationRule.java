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

package ai.kompile.process.ingest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A pre-processing transformation applied to submission data before triage begins.
 * Examples: magnitude scaling (JPY thousands to actuals), fiscal calendar mapping,
 * VAT netting, and channel taxonomy canonicalization.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NormalizationRule {

    private String id;
    private NormalizationType type;
    private String description;
    /**
     * Transformation logic expression.
     * Example: "value * 1000" for MAGNITUDE_SCALING from thousands to actuals.
     */
    private String expression;
    /** Sheet, range, or column identifier the rule applies to. */
    private String appliesTo;
    /**
     * Type-specific parameters.
     * Example for MAGNITUDE_SCALING: {@code {"scaleFactor": 1000, "fromUnit": "thousands", "toUnit": "actuals"}}.
     */
    private Map<String, Object> parameters;
    private boolean applied;
    /** "system" for automatically applied rules, or the person who applied it manually. */
    private String appliedBy;
    private Instant appliedAt;
}
