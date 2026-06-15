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

package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;

@Getter
@JsonTypeName("MERGE")
class MergeNodeConfig implements GraphNodeConfig {
    private final String name;
    private final List<String> inputs; // Multiple inputs to merge

    @JsonCreator
    public MergeNodeConfig(
            @NonNull @JsonProperty("name") String name,
            @NonNull @JsonProperty("inputs") List<String> inputs) {
        this.name = name;
        this.inputs = inputs;
        if (inputs == null || inputs.size() < 2) {
            throw new IllegalArgumentException("MergeNodeConfig requires at least two inputs.");
        }
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.MERGE;
    }
}
