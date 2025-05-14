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

import ai.kompile.pipelines.framework.api.data.Data;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for a {@link GraphStepType#COMBINE_FN} node within a {@link GraphPipeline}.
 * This node takes multiple inputs and uses a specified {@link CombineFn} implementation
 * to produce a single output {@link Data} object.
 * <p>
 * The {@code combineFunctionClassName} specifies the custom logic for combining inputs,
 * and {@code combineFunctionParams} can provide additional configuration to this logic.
 */
@Getter
@JsonTypeName("COMBINE_FN") // Used by Jackson for polymorphic deserialization based on @graphNodeType
public class CombineNodeConfig implements GraphNodeConfig {
    private static final long serialVersionUID = 1L;

    private final String name; // Name of this graph node
    @Singular // For Lombok builder: .input("nodeA").input("nodeB")
    private final List<String> inputs; // Names of predecessor nodes providing input
    private final String combineFunctionClassName; // Fully qualified class name of the CombineFn implementation
    private final Data combineFunctionParams; // Parameters specific to the CombineFn

    /**
     * Creates an instance of CombineNodeConfig.
     *
     * @param name The unique name of this combine node within the graph. Cannot be null.
     * @param inputs A list of names of predecessor nodes. Must not be null and should typically contain at least two inputs.
     * @param combineFunctionClassName The fully qualified class name of the {@link CombineFn} implementation. Cannot be null.
     * @param combineFunctionParams Optional {@link Data} object containing parameters for the combine function. If null, an empty Data object is used.
     */
    @JsonCreator
    @Builder // Allows for builder pattern instantiation
    public CombineNodeConfig(
            @NonNull @JsonProperty(value = "name", required = true) String name,
            @NonNull @JsonProperty(value = "inputs", required = true) List<String> inputs,
            @NonNull @JsonProperty(value = "combineFunctionClassName", required = true) String combineFunctionClassName,
            @JsonProperty("combineFunctionParams") Data combineFunctionParams) {
        this.name = name;
        this.inputs = Objects.requireNonNull(inputs, "Inputs list cannot be null for CombineNodeConfig.");
        if (inputs.size() < 1) { // A combine function might technically operate on a single input (e.g., wrapping it)
            // but typically implies multiple. ADR-0004 implies N-to-1 where N >= 1.
            // For stricter N >= 2: throw new IllegalArgumentException("CombineNodeConfig requires at least two inputs.");
            // For now, allowing one input as it might be a valid use case for some CombineFn.
        }
        this.combineFunctionClassName = Objects.requireNonNull(combineFunctionClassName, "combineFunctionClassName cannot be null for CombineNodeConfig.");
        this.combineFunctionParams = (combineFunctionParams != null) ? combineFunctionParams : Data.Factory.get().empty();
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.COMBINE_FN;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CombineNodeConfig that = (CombineNodeConfig) o;
        return name.equals(that.name) &&
                inputs.equals(that.inputs) &&
                combineFunctionClassName.equals(that.combineFunctionClassName) &&
                Objects.equals(combineFunctionParams, that.combineFunctionParams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, inputs, combineFunctionClassName, combineFunctionParams);
    }

    @Override
    public String toString() {
        return "CombineNodeConfig{" +
                "name='" + name + '\'' +
                ", inputs=" + inputs +
                ", combineFunctionClassName='" + combineFunctionClassName + '\'' +
                ", combineFunctionParams=" + combineFunctionParams +
                '}';
    }
}