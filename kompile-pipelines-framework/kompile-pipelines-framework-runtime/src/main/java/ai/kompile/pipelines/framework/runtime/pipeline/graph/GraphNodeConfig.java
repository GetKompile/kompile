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

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

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