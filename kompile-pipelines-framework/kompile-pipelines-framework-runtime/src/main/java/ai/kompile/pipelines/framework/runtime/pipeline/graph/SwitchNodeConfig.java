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

import ai.kompile.pipelines.framework.api.data.Data;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;

@Getter
@JsonTypeName("SWITCH")
class SwitchNodeConfig implements GraphNodeConfig {
    private final String name;
    private final List<String> inputs; // Typically one input for a switch
    private final String switchFunctionClassName; // FQCN of the SwitchFn implementation
    private final Data switchFunctionParams; // Parameters for the SwitchFn

    @JsonCreator
    public SwitchNodeConfig(
            @NonNull @JsonProperty("name") String name,
            @NonNull @JsonProperty("inputs") List<String> inputs,
            @NonNull @JsonProperty("switchFunctionClassName") String switchFunctionClassName,
            @JsonProperty("switchFunctionParams") Data switchFunctionParams) {
        this.name = name;
        this.inputs = inputs;
        if (inputs == null || inputs.size() != 1) {
            throw new IllegalArgumentException("SwitchNodeConfig typically requires exactly one input.");
        }
        this.switchFunctionClassName = switchFunctionClassName;
        this.switchFunctionParams = (switchFunctionParams != null) ? switchFunctionParams : Data.Factory.get().empty();
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.SWITCH;
    }
}
