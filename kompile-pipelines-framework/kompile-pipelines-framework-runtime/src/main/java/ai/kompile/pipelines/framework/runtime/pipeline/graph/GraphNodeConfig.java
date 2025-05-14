/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.Configuration;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.data.Data; // For switch/combine function parameters
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single node within a {@link GraphPipeline}.
 * It specifies the type of the graph node, its inputs, and its specific configuration
 * (which could be a standard {@link StepConfig} or specific parameters for graph operations
 * like MERGE, SWITCH, COMBINE_FN).
 *
 * ADR-0004 suggests using "@type" for different graph step configurations.
 * For STANDARD nodes, this would embed a regular StepConfig (which itself has a runnerClassName and parameters).
 * For graph-specific nodes like MERGE, it would have its own specific parameters.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@graphNodeType", defaultImpl = StandardGraphNodeConfig.class)
public interface GraphNodeConfig extends Configuration {
    String getName(); // Name of this graph node, used as an ID
    List<String> getInputs(); // Names of other graph nodes that feed into this one
    GraphStepType getGraphStepType(); // The type of this graph node
}

// Example concrete implementations (these would also be in this file or separate files in the package)

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

// Similar configurations for ANY and COMBINE_FN would be created:
// AnyNodeConfig: name, inputs
// CombineNodeConfig: name, inputs, combineFunctionClassName, combineFunctionParams

// And potentially an InputNodeConfig and OutputNodeConfig if explicit graph entry/exit nodes are used.
// ADR-0004 uses "@input": "input" for the first step, implicitly defining a pipeline input.
// And the build(finalOutputStep) for defining the output.