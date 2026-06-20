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

package ai.kompile.process.discovery.mining.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A collection of {@link Trace}s — the input to every process-mining algorithm in this package.
 *
 * <p>Pure data with no knowledge-graph dependency: the {@code EventLogExtractor} produces it from the
 * KG, but the miners only ever see this, which keeps them deterministic and unit-testable.
 */
public final class EventLog {

    private final List<Trace> traces;

    public EventLog(List<Trace> traces) {
        this.traces = new ArrayList<>(traces == null ? List.of() : traces);
    }

    public List<Trace> traces() {
        return Collections.unmodifiableList(traces);
    }

    public int size() {
        return traces.size();
    }

    public boolean isEmpty() {
        return traces.isEmpty();
    }

    /** The activity alphabet across all traces, in first-seen order. */
    public Set<String> activityNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Trace t : traces) {
            names.addAll(t.activitySequence());
        }
        return names;
    }

    /**
     * Distinct trace variants (activity sequences) mapped to how many traces exhibit each. Variants
     * are the interpretable summary of a log: typically a handful of variants covers most cases, and
     * the rare ones are exactly the noise the IMf miner is designed to filter.
     */
    public Map<List<String>, Long> variants() {
        Map<List<String>, Long> counts = new LinkedHashMap<>();
        for (Trace t : traces) {
            counts.merge(t.activitySequence(), 1L, Long::sum);
        }
        return counts;
    }
}
