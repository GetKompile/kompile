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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchResultTest {

    @Test
    void of_outputsOnly_hasEmptyDiscoveredIds() {
        Map<String, Object> outputs = Map.of("result", "ok");
        DispatchResult result = DispatchResult.of(outputs);

        assertThat(result.getOutputs()).isEqualTo(outputs);
        assertThat(result.getDiscoveredGraphNodeIds()).isEmpty();
        assertThat(result.hasDiscoveredNodeIds()).isFalse();
    }

    @Test
    void of_withBothOutputsAndIds_preservesBoth() {
        Map<String, Object> outputs = Map.of("score", 42, "label", "approved");
        List<String> nodeIds = List.of("node-1", "node-2", "node-3");

        DispatchResult result = DispatchResult.of(outputs, nodeIds);

        assertThat(result.getOutputs()).containsEntry("score", 42);
        assertThat(result.getOutputs()).containsEntry("label", "approved");
        assertThat(result.getDiscoveredGraphNodeIds()).containsExactly("node-1", "node-2", "node-3");
    }

    @Test
    void hasDiscoveredNodeIds_trueWhenNonEmpty() {
        DispatchResult result = DispatchResult.of(Map.of(), List.of("node-abc"));

        assertThat(result.hasDiscoveredNodeIds()).isTrue();
    }

    @Test
    void hasDiscoveredNodeIds_falseWhenEmpty() {
        DispatchResult result = DispatchResult.of(Map.of(), List.of());

        assertThat(result.hasDiscoveredNodeIds()).isFalse();
    }

    @Test
    void nullSafety_nullOutputs_becomesEmptyMap() {
        DispatchResult result = DispatchResult.of(null);

        assertThat(result.getOutputs()).isNotNull().isEmpty();
    }

    @Test
    void nullSafety_nullIds_becomesEmptyList() {
        DispatchResult result = DispatchResult.of(Map.of("k", "v"), null);

        assertThat(result.getDiscoveredGraphNodeIds()).isNotNull().isEmpty();
    }
}
