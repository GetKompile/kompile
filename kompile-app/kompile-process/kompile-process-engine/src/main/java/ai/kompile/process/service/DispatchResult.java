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

package ai.kompile.process.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrier class for step execution results that includes both outputs
 * and any graph node IDs discovered during execution.
 *
 * <p>This allows the process engine to merge runtime-discovered graph node IDs
 * (e.g., from Excel graph resolution or tool execution) into the StepExecution
 * record, enriching the graph attribution beyond what was statically defined
 * in the ProcessStep definition.</p>
 */
public class DispatchResult {

    private final Map<String, Object> outputs;
    private final List<String> discoveredGraphNodeIds;

    public DispatchResult(Map<String, Object> outputs) {
        this.outputs = outputs != null ? outputs : new LinkedHashMap<>();
        this.discoveredGraphNodeIds = new ArrayList<>();
    }

    public DispatchResult(Map<String, Object> outputs, List<String> discoveredGraphNodeIds) {
        this.outputs = outputs != null ? outputs : new LinkedHashMap<>();
        this.discoveredGraphNodeIds = discoveredGraphNodeIds != null ? discoveredGraphNodeIds : new ArrayList<>();
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public List<String> getDiscoveredGraphNodeIds() {
        return discoveredGraphNodeIds;
    }

    public boolean hasDiscoveredNodeIds() {
        return !discoveredGraphNodeIds.isEmpty();
    }

    /**
     * Creates a DispatchResult from plain outputs (no discovered node IDs).
     */
    public static DispatchResult of(Map<String, Object> outputs) {
        return new DispatchResult(outputs);
    }

    /**
     * Creates a DispatchResult with both outputs and discovered graph node IDs.
     */
    public static DispatchResult of(Map<String, Object> outputs, List<String> graphNodeIds) {
        return new DispatchResult(outputs, graphNodeIds);
    }
}
