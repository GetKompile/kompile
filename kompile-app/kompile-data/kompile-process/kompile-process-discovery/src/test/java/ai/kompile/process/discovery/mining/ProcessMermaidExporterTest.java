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

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.export.ProcessMermaidExporter;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the Mermaid exporter emits well-formed, render-ready diagram source for both the
 * directly-follows process map and the process-tree block structure.
 */
class ProcessMermaidExporterTest {

    private static EventLog log(String... traces) {
        List<Trace> ts = new ArrayList<>();
        int caseId = 0;
        for (String tr : traces) {
            String cid = "c" + (caseId++);
            List<Event> events = new ArrayList<>();
            for (String a : tr.trim().split("\\s+")) {
                events.add(Event.of(cid, a, null, null));
            }
            ts.add(new Trace(cid, events));
        }
        return new EventLog(ts);
    }

    @Test
    void rendersDirectlyFollowsProcessMap() {
        DirectlyFollowsGraph dfg = DfgBuilder.build(log("a b c", "a b c"));

        String mermaid = ProcessMermaidExporter.dfgToMermaid(dfg);

        assertTrue(mermaid.startsWith("flowchart"), mermaid);
        assertTrue(mermaid.contains("start"), mermaid);
        assertTrue(mermaid.contains("stop"), mermaid);
        assertTrue(mermaid.contains("\"a\""), mermaid);
        assertTrue(mermaid.contains("-->|2|"), () -> "expected a frequency-labelled edge:\n" + mermaid);
    }

    @Test
    void rendersProcessTreeBlocks() {
        ProcessTree tree = new InductiveMiner().mine(log("a b c", "a b c"));

        String mermaid = ProcessMermaidExporter.treeToMermaid(tree);

        assertTrue(mermaid.startsWith("flowchart"), mermaid);
        assertTrue(mermaid.contains("\"a\""), mermaid);
        assertTrue(mermaid.contains("-->"), mermaid);
    }
}
