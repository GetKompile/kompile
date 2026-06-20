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

package ai.kompile.process.discovery.mining.dfg;

import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link DirectlyFollowsGraph} from an {@link EventLog} in a single O(total events) pass.
 * This is the only aggregation step in the pipeline; everything downstream operates on the DFG.
 */
public final class DfgBuilder {

    private DfgBuilder() {
    }

    public static DirectlyFollowsGraph build(EventLog log) {
        Set<String> activities = new LinkedHashSet<>();
        Map<String, Map<String, Long>> follows = new LinkedHashMap<>();
        Map<String, Long> starts = new LinkedHashMap<>();
        Map<String, Long> ends = new LinkedHashMap<>();

        for (Trace trace : log.traces()) {
            List<String> seq = trace.activitySequence();
            if (seq.isEmpty()) {
                continue;
            }
            activities.addAll(seq);
            starts.merge(seq.get(0), 1L, Long::sum);
            ends.merge(seq.get(seq.size() - 1), 1L, Long::sum);
            for (int i = 0; i + 1 < seq.size(); i++) {
                follows.computeIfAbsent(seq.get(i), k -> new LinkedHashMap<>())
                        .merge(seq.get(i + 1), 1L, Long::sum);
            }
        }
        return new DirectlyFollowsGraph(activities, follows, starts, ends);
    }
}
