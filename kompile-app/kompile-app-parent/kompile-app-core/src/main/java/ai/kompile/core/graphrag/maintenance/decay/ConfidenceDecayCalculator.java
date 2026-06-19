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

package ai.kompile.core.graphrag.maintenance.decay;

import ai.kompile.core.graphrag.maintenance.model.ConfidenceDecayPolicy;
import java.time.Duration;
import java.time.Instant;

public final class ConfidenceDecayCalculator {

    private ConfidenceDecayCalculator() {}

    public static double computeEffectiveConfidence(
            double originalConfidence,
            Instant lastVerifiedAt,
            Instant now,
            ConfidenceDecayPolicy policy,
            String entityType,
            boolean isInferred) {

        if (lastVerifiedAt == null || now.isBefore(lastVerifiedAt)) {
            return originalConfidence;
        }

        Duration halfLife = policy.entityTypeHalfLifeOverrides() != null
                ? policy.entityTypeHalfLifeOverrides().getOrDefault(entityType, policy.defaultHalfLife())
                : policy.defaultHalfLife();

        if (isInferred && policy.decayInferredFaster()) {
            halfLife = halfLife.dividedBy(2);
        }

        if (!isInferred && policy.exemptExtracted()) {
            return originalConfidence;
        }

        long elapsedMs = Duration.between(lastVerifiedAt, now).toMillis();
        long halfLifeMs = halfLife.toMillis();
        if (halfLifeMs <= 0) return originalConfidence;

        double decayed = originalConfidence * Math.pow(2.0, -(double) elapsedMs / halfLifeMs);
        return Math.max(policy.floor(), decayed);
    }
}
