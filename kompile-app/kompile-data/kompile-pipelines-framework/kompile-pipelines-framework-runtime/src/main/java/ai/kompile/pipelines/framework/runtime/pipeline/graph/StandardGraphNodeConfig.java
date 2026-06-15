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

import ai.kompile.pipelines.framework.api.StepConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;

@Getter
@JsonTypeName("STANDARD")
public class StandardGraphNodeConfig implements GraphNodeConfig {
    private final String name;
    private final List<String> inputs; // For a standard step, usually one input from graph or another step
    private final StepConfig stepConfig; // The actual pipeline step configuration

    @JsonCreator
    public StandardGraphNodeConfig(
            @NonNull @JsonProperty("name") String name,
            @NonNull @JsonProperty("inputs") List<String> inputs,
            @NonNull @JsonProperty("stepConfig") StepConfig stepConfig) {
        this.name = name;
        this.inputs = inputs;
        this.stepConfig = stepConfig;
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.STANDARD;
    }
}
