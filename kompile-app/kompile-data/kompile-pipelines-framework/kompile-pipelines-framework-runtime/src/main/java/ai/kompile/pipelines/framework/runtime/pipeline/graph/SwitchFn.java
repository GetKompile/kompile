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
import java.io.Serializable;
import java.util.List;

/**
 * Functional interface for implementing the logic of a SWITCH node in a GraphPipeline.
 * Based on ADR-0004-Graph_pipelines.md
 *
 * A SwitchFn takes an input Data object and determines which output branch(es)
 * the data should be routed to.
 */
@FunctionalInterface
public interface SwitchFn extends Serializable { // Serializable if SwitchFn instances are part of config

    /**
     * Determines the output branch(es) for the given input data.
     *
     * @param input The input Data object to the switch node.
     * @param availableOutputNames A list of names for the potential output branches/connections
     * from this switch node. The SwitchFn should return one or more
     * of these names.
     * @param contextParameters Parameters configured for this specific SwitchFn instance,
     * allowing for parameterized switching logic.
     * @return A list of output names (from availableOutputNames) to which the input data should be routed.
     * If multiple names are returned, the data is routed to all of them (fan-out).
     * If an empty list or null is returned, the data is effectively dropped at this switch.
     * @throws Exception if an error occurs during the switching logic.
     */
    List<String> selectOutput(Data input, List<String> availableOutputNames, Data contextParameters) throws Exception;
}