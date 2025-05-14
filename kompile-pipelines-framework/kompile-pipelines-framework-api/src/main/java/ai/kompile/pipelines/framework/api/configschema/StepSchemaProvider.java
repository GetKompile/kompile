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

package ai.kompile.pipelines.framework.api.configschema;

import java.util.Optional;
import java.util.ServiceLoader; // For Javadoc reference

/**
 * Defines a contract for components that can provide {@link StepSchema} definitions.
 * <p>
 * The primary purpose of this interface is to abstract the mechanism by which schemas
 * for {@link ai.kompile.pipelines.framework.api.PipelineStepRunner} configurations are retrieved.
 * Implementations, such as a {@code SchemaRegistry} in the framework's core module,
 * would typically load these schemas from classpath resources (e.g., JSON or YAML files
 * co-located with runner implementations) or other sources.
 * <p>
 * This allows the framework and tooling (like a CLI or UI) to dynamically obtain
 * metadata about the expected parameters for any given step runner, facilitating
 * validation, documentation generation, and user assistance.
 * <p>
 * Implementations of this interface can be made discoverable using Java's
 * {@link ServiceLoader} mechanism if desired, allowing for a pluggable schema
 * provision system, though a central registry implementation is also common.
 */
public interface StepSchemaProvider {

    /**
     * Retrieves the schema for a {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
     * identified by its fully qualified class name.
     *
     * @param runnerClassName The fully qualified class name of the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
     * for which the schema is requested. Must not be null or empty.
     * @return An {@link Optional} containing the {@link StepSchema} if found for the given runner,
     * otherwise an empty {@link Optional}.
     * @throws IllegalArgumentException if {@code runnerClassName} is null or empty.
     */
    Optional<StepSchema> getSchema(String runnerClassName);

    /**
     * Convenience method to retrieve the schema for a given {@link ai.kompile.pipelines.framework.api.PipelineStepRunner} class.
     * This typically delegates to {@link #getSchema(String)} using the class's name.
     *
     * @param runnerClass The class of the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}. Must not be null.
     * @return An {@link Optional} containing the {@link StepSchema} if found for the given runner class,
     * otherwise an empty {@link Optional}.
     * @throws IllegalArgumentException if {@code runnerClass} is null.
     */
    default Optional<StepSchema> getSchema(Class<?> runnerClass) {
        if (runnerClass == null) {
            throw new IllegalArgumentException("Runner class cannot be null when requesting a schema.");
        }
        return getSchema(runnerClass.getName());
    }
}