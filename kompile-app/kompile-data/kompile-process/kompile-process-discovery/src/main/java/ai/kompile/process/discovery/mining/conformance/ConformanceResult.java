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

package ai.kompile.process.discovery.mining.conformance;

/**
 * How well a discovered model explains the log it was mined from — the numbers that let a user
 * <em>trust</em> a mined process.
 *
 * @param fitness    fraction of observed (frequency-weighted) behaviour the model permits — 1.0 means
 *                   the model can replay every directly-follows step in the log
 * @param precision  fraction of the behaviour the model permits that was actually observed — low
 *                   precision flags an over-general ("flower") model
 * @param simplicity activity-to-node ratio of the tree — higher is simpler (less structural overhead)
 * @param modelArcs  number of directly-follows pairs the model allows
 * @param logArcs    number of distinct directly-follows pairs seen in the log
 * @param perfectFit true when fitness is (numerically) 1.0 — guaranteed for the classic Inductive Miner
 */
public record ConformanceResult(
        double fitness,
        double precision,
        double simplicity,
        int modelArcs,
        int logArcs,
        boolean perfectFit) {

    /** Harmonic mean of fitness and precision — a single balanced quality score in [0,1]. */
    public double fScore() {
        double denom = fitness + precision;
        return denom <= 0 ? 0.0 : (2.0 * fitness * precision) / denom;
    }
}
