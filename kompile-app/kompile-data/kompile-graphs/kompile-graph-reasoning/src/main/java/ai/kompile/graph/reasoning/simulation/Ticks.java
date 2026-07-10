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
package ai.kompile.graph.reasoning.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared tick partitioning for generators: nodes are spread evenly across {@code nTicks} in list
 * order, and every edge lands on the first tick at which both endpoints exist (preserving the
 * {@link TickBatch} no-dangling-reference invariant).
 */
final class Ticks {

    private Ticks() {
    }

    static List<TickBatch> partition(List<ScenarioNode> nodes, List<ScenarioEdge> edges, int nTicks) {
        int ticks = Math.max(1, nTicks);
        Map<String, Integer> tickOfNode = new HashMap<>(nodes.size() * 2);
        List<List<ScenarioNode>> nodesPerTick = new ArrayList<>(ticks);
        List<List<ScenarioEdge>> edgesPerTick = new ArrayList<>(ticks);
        for (int t = 0; t < ticks; t++) {
            nodesPerTick.add(new ArrayList<>());
            edgesPerTick.add(new ArrayList<>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            ScenarioNode node = nodes.get(i);
            int tick = nodes.size() <= 1 ? 0 : Math.min(ticks - 1, (int) ((long) i * ticks / nodes.size()));
            Integer prior = tickOfNode.putIfAbsent(node.key(), tick);
            if (prior != null) {
                throw new IllegalArgumentException("Duplicate scenario node key: " + node.key());
            }
            nodesPerTick.get(tick).add(node);
        }
        for (ScenarioEdge edge : edges) {
            Integer srcTick = tickOfNode.get(edge.sourceKey());
            Integer dstTick = tickOfNode.get(edge.targetKey());
            if (srcTick == null || dstTick == null) {
                throw new IllegalArgumentException("Edge references unknown node: "
                        + edge.sourceKey() + " -> " + edge.targetKey());
            }
            edgesPerTick.get(Math.max(srcTick, dstTick)).add(edge);
        }
        List<TickBatch> batches = new ArrayList<>(ticks);
        for (int t = 0; t < ticks; t++) {
            batches.add(new TickBatch(t, nodesPerTick.get(t), edgesPerTick.get(t)));
        }
        return batches;
    }
}
