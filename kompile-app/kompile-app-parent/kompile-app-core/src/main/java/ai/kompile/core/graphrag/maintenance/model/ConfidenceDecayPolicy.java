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

package ai.kompile.core.graphrag.maintenance.model;

import java.time.Duration;
import java.util.Map;

public record ConfidenceDecayPolicy(
    Duration defaultHalfLife,
    double floor,
    boolean decayInferredFaster,
    boolean exemptExtracted,
    Map<String, Duration> entityTypeHalfLifeOverrides
) {
    public static ConfidenceDecayPolicy defaults() {
        return new ConfidenceDecayPolicy(
            Duration.ofDays(180), 0.1, true, false, Map.of()
        );
    }
}
