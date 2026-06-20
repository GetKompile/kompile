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

package ai.kompile.process.discovery.mining.perf;

import java.util.List;

/**
 * Performance (bottleneck) analysis result: per directly-follows arc, the observed transition count,
 * mean duration (seconds) and median duration (seconds) between consecutive events.
 *
 * <p>Arcs are sorted by {@link ArcPerformance#medianSeconds()} descending so the slowest transitions
 * (bottlenecks) appear first.
 *
 * <p>Event pairs where either timestamp is {@code null} are excluded from the duration calculation;
 * if an arc has no timed observations at all it is still listed with count=0 and durations=0.
 *
 * @param arcs the per-arc statistics, sorted by median descending
 */
public record PerformanceAnalysis(List<ArcPerformance> arcs) {

    /**
     * Statistics for a single directly-follows arc {@code from → to}.
     *
     * @param from          source activity label
     * @param to            target activity label
     * @param count         total number of times this arc was observed (with or without timestamps)
     * @param meanSeconds   arithmetic mean of the timed durations in seconds (0 if none timed)
     * @param medianSeconds median of the timed durations in seconds (0 if none timed)
     */
    public record ArcPerformance(
            String from,
            String to,
            long count,
            double meanSeconds,
            double medianSeconds) {}
}
