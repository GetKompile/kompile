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
import java.io.Serializable;
import java.util.Map;

/**
 * Functional interface for implementing the logic of a COMBINE_FN node in a GraphPipeline.
 * Based on ADR-0004-Graph_pipelines.md
 *
 * A CombineFn takes multiple input Data objects (one from each incoming connection,
 * identified by the input connection's name) and combines them into a single output Data object.
 */
@FunctionalInterface
public interface CombineFn extends Serializable { // Serializable if CombineFn instances are part of config

    /**
     * Combines multiple input Data objects into a single output Data object.
     *
     * @param inputs A map where keys are the names of the input connections (as defined in the
     * graph node's configuration) and values are the {@link Data} objects received
     * from those connections. The executor will ensure all specified inputs are
     * available before calling this function (unless it's an ANY-like combine).
     * @param contextParameters Parameters configured for this specific CombineFn instance,
     * allowing for parameterized combination logic.
     * @return A single {@link Data} object resulting from the combination.
     * @throws Exception if an error occurs during the combination logic.
     */
    Data combine(Map<String, Data> inputs, Data contextParameters) throws Exception;
}