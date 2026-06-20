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

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-free performance (bottleneck) miner: for every directly-follows arc {@code a → b} observed
 * across all traces in an {@link EventLog}, it computes the count, mean, and median of the elapsed
 * seconds between consecutive events.
 *
 * <p>Event pairs where either timestamp is {@code null} are silently skipped for the duration
 * calculation; the arc count still increments so the caller can distinguish "arc exists but no timing
 * data" from "arc never observed."
 *
 * <p>The result is sorted by median descending (slowest bottleneck first).
 */
public final class PerformanceMiner {

    private PerformanceMiner() {}

    /** Typed key for a directly-follows arc — used as map key (record equality + hashCode). */
    private record ArcKey(String from, String to) {}

    /**
     * Analyse {@code log} and return per-arc performance statistics.
     *
     * @param log the event log to analyse; must not be {@code null}
     * @return a {@link PerformanceAnalysis} with arcs sorted by median duration descending
     */
    public static PerformanceAnalysis analyze(EventLog log) {
        // arc key → list of durations in seconds (only timed pairs)
        Map<ArcKey, List<Double>> durations = new LinkedHashMap<>();
        // arc key → total observation count (including un-timed pairs)
        Map<ArcKey, Long> counts = new LinkedHashMap<>();

        for (Trace trace : log.traces()) {
            List<Event> events = trace.ordered();
            for (int i = 0; i < events.size() - 1; i++) {
                Event a = events.get(i);
                Event b = events.get(i + 1);
                ArcKey key = new ArcKey(a.activity(), b.activity());
                counts.merge(key, 1L, Long::sum);
                if (a.timestamp() != null && b.timestamp() != null) {
                    long seconds = ChronoUnit.SECONDS.between(a.timestamp(), b.timestamp());
                    durations.computeIfAbsent(key, k -> new ArrayList<>()).add((double) seconds);
                }
            }
        }

        List<PerformanceAnalysis.ArcPerformance> arcs = new ArrayList<>();
        for (Map.Entry<ArcKey, Long> entry : counts.entrySet()) {
            ArcKey key = entry.getKey();
            long count = entry.getValue();
            List<Double> secs = durations.getOrDefault(key, List.of());
            double mean = secs.isEmpty() ? 0.0
                    : secs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double median = secs.isEmpty() ? 0.0 : computeMedian(secs);
            arcs.add(new PerformanceAnalysis.ArcPerformance(key.from(), key.to(), count, mean, median));
        }

        // sort slowest (by median) first — bottlenecks at the top
        arcs.sort((x, y) -> Double.compare(y.medianSeconds(), x.medianSeconds()));
        return new PerformanceAnalysis(Collections.unmodifiableList(arcs));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static double computeMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
