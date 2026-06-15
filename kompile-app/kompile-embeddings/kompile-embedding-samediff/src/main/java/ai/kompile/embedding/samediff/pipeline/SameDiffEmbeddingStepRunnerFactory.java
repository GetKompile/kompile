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

package ai.kompile.embedding.samediff.pipeline;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
// StepConfig is not directly used by this specific factory's create() method signature,
// but the schema describes the config the runner expects.
// import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
// StepSchemaProvider is not implemented by this class, but getSchema() returns a StepSchema.
// import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.jetbrains.annotations.NotNull; // Assuming this is your preferred annotation
import org.springframework.stereotype.Component; // For Spring discovery if used

/**
 * Factory for creating {@link SameDiffEmbeddingStepRunner} instances and providing their configuration schema.
 */
@Component // Makes this factory discoverable by Spring if component scanning is active for this package.
// For ServiceLoader, ensure META-INF/services entry points to this class.
public class SameDiffEmbeddingStepRunnerFactory implements PipelineStepRunnerFactory {

    // The schema is static for this type of runner, so it can be pre-built.
    private static final StepSchema SAMEDIFF_EMBEDDING_SCHEMA = StepSchema.builder()
            .name(SameDiffEmbeddingStepConfig.STEP_TYPE_NAME) // Symbolic name of the step type
            .runnerClassName(SameDiffEmbeddingStepRunner.class.getName()) // Actual runner class
            .description("Generates text embeddings using a SameDiff model.")
            .configClass(SameDiffEmbeddingStepConfig.class.getName()) // The specific StepConfig class for this runner
            .parameter(ParameterSchema.builder()
                    .name("name") // Optional: A common parameter to name step instances
                    .type(ValueType.STRING)
                    .description("User-defined name for this step instance in the pipeline definition.")
                    .required(false)
                    .defaultValue("UnnamedSameDiffEmbeddingStep")
                    .build())
            .parameter(ParameterSchema.builder()
                    .name("modelUri")
                    .type(ValueType.STRING)
                    .description("URI of the SameDiff model file (.sd). Supports file:/, classpath:/, or plain paths.")
                    .required(true)
                    .build())
            .parameter(ParameterSchema.builder()
                    .name("inputTensorName")
                    .type(ValueType.STRING)
                    .description("Name of the input tensor/placeholder in the SameDiff graph for text(s).")
                    .defaultValue("input") // Default if not provided
                    .required(false) // False because there's a default
                    .build())
            .parameter(ParameterSchema.builder()
                    .name("outputTensorName")
                    .type(ValueType.STRING)
                    .description("Name of the output tensor/variable in the SameDiff graph for embeddings.")
                    .defaultValue("embedding") // Default if not provided
                    .required(false) // False because there's a default
                    .build())
            .parameter(ParameterSchema.builder()
                    .name("inputTextKey")
                    .type(ValueType.STRING)
                    .description("Key in the pipeline Data object for input text (String or List<String>).")
                    .defaultValue("inputText") // Default if not provided
                    .required(true) // True because the runner logic expects this key
                    .build())
            .parameter(ParameterSchema.builder()
                    .name("outputEmbeddingsKey")
                    .type(ValueType.STRING)
                    .description("Key in the pipeline Data object for output embeddings (List<Float> or List<List<Float>>).")
                    .defaultValue("samediffEmbeddings") // Default if not provided
                    .required(true) // True because the runner logic outputs to this key
                    .build())
            // Define expected inputs for the exec(Data, Context) method
            .input(ParameterSchema.builder()
                    .name("inputTextKey") // This name should match a key expected in the input Data object.
                    // It's also one of the config parameters above, which defines the *actual* key name to use.
                    .type(ValueType.STRING)  // Can be STRING or LIST of STRING
                    .description("Input text (String) or list of texts (List<String>) to embed. The actual key name is specified by the 'inputTextKey' configuration parameter.")
                    .required(true) // The data itself is required for the step to function.
                    .build())
            // Define potential outputs from the exec(Data, Context) method
            .output(ParameterSchema.builder()
                    .name("outputEmbeddingsKey") // Similar to input, references a config parameter for the actual key.
                    .type(ValueType.LIST)       // Output will be List<Float> or List<List<Float>>
                    // For List<List<Float>>, listElementType would be LIST.
                    // For List<Float>, listElementType would be DOUBLE (as Float stored as Double).
                    .listElementType(ValueType.STRING) // Placeholder, actual element type (FLOAT or LIST of FLOAT) depends on single vs batch
                    .description("Generated embedding(s). Stored as List<Float> for single input or List<List<Float>> for batch input. The actual key name is specified by the 'outputEmbeddingsKey' configuration parameter.")
                    .required(true) // The step is expected to produce this output.
                    .build())
            .build();

    /**
     * Returns the symbolic type name for the SameDiff embedding step.
     * This matches the {@code name} field in the {@link StepSchema} and is used in pipeline definitions.
     *
     * @return The unique string "SAMEDIFF_EMBEDDING".
     */
    @Override
    public @NotNull String stepTypeName() {
        return SameDiffEmbeddingStepConfig.STEP_TYPE_NAME;
    }

    /**
     * Returns the fully qualified class name of the {@link SameDiffEmbeddingStepRunner}.
     *
     * @return The class name of the runner.
     */
    @Override
    public @NotNull String getRunnerType() {
        return SameDiffEmbeddingStepRunner.class.getName();
    }

    /**
     * Creates a new, uninitialized instance of {@link SameDiffEmbeddingStepRunner}.
     * The runner will be subsequently initialized via its {@code init} method by the pipeline executor.
     *
     * @return A new instance of {@link SameDiffEmbeddingStepRunner}.
     */
    @Override
    public @NotNull PipelineStepRunner create() {
        return new SameDiffEmbeddingStepRunner();
        // The SameDiffEmbeddingStepRunner's constructor is now a no-arg constructor.
        // All configuration and initialization logic is handled in its init() method,
        // which receives the StepConfig.
    }

    /**
     * Returns the static {@link StepSchema} that describes the configuration for the
     * {@link SameDiffEmbeddingStepRunner}.
     *
     * @return The {@link StepSchema} for this step type.
     */
    @Override
    public @NotNull StepSchema getSchema() {
        return SAMEDIFF_EMBEDDING_SCHEMA;
    }
}