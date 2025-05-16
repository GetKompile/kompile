/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.data.Data; // Not directly used but contextually relevant
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
 * Configuration for a {@link GraphStepType#ANY} node within a {@link GraphPipeline}.
 * This node takes multiple inputs and forwards the {@link Data} object from the *first*
 * input that provides data. Subsequent inputs that might arrive later for the same
 * pipeline execution are typically ignored for this node's output.
 * <p>
 * This is often used in scenarios with conditional branches where only one branch is expected
 * to produce a result, and the ANY node serves to consolidate that result into a common path.
 */
@Getter
@JsonTypeName("ANY") // Used by Jackson for polymorphic deserialization based on @graphNodeType
public class AnyNodeConfig implements GraphNodeConfig {
    private static final long serialVersionUID = 1L;

    private final String name; // Name of this graph node
    @Singular // For Lombok builder: .input("nodeA").input("nodeB")
    private final List<String> inputs; // Names of predecessor nodes providing input

    /**
     * Creates an instance of AnyNodeConfig.
     *
     * @param name The unique name of this ANY node within the graph. Cannot be null.
     * @param inputs A list of names of predecessor nodes. Must not be null and should typically contain at least two inputs
     * for the "ANY" logic to be meaningful, though one input is technically possible (acting as a pass-through).
     */
    @JsonCreator
    @Builder // Allows for builder pattern instantiation
    public AnyNodeConfig(
            @NonNull @JsonProperty(value = "name", required = true) String name,
            @NonNull @JsonProperty(value = "inputs", required = true) List<String> inputs) {
        this.name = name;
        this.inputs = Objects.requireNonNull(inputs, "Inputs list cannot be null for AnyNodeConfig.");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("AnyNodeConfig requires at least one input.");
        }
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.ANY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnyNodeConfig that = (AnyNodeConfig) o;
        return name.equals(that.name) &&
                inputs.equals(that.inputs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, inputs);
    }

    @Override
    public String toString() {
        return "AnyNodeConfig{" +
                "name='" + name + '\'' +
                ", inputs=" + inputs +
                '}';
    }
}