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

import ai.kompile.pipelines.framework.api.StepConfig; // For javadoc/context
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull; // Assuming this is your preferred annotation

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the {@link SameDiffEmbeddingStepRunner}.
 * This class holds parameters specific to configuring and running a SameDiff model for embeddings.
 * It extends {@link GenericStepConfig} to inherit common configuration handling.
 */
@Getter
@ToString(callSuper = true) // Include superclass fields in toString
@EqualsAndHashCode(callSuper = true) // Include superclass fields in equals/hashCode
public class SameDiffEmbeddingStepConfig extends GenericStepConfig {
    private static final long serialVersionUID = 1L; // From Configuration interface

    public static final String STEP_TYPE_NAME = "SAMEDIFF_EMBEDDING"; // Symbolic type for this step

    // These fields will store the strongly-typed configuration values after parsing from the 'parameters' Data object.
    @JsonPropertyDescription("URI of the SameDiff model file (.sd). Example: file:/path/to/model.sd or classpath:/models/my_embedding_model.sd")
    private final String modelUri;

    @JsonPropertyDescription("Name of the input tensor/placeholder in the SameDiff graph for the text(s) to be embedded.")
    private final String inputTensorName;

    @JsonPropertyDescription("Name of the output tensor/variable in the SameDiff graph that provides the embedding vector(s).")
    private final String outputTensorName;

    @JsonPropertyDescription("Key in the pipeline Data object from which to read the input text or list of texts.")
    private final String inputTextKey;

    @JsonPropertyDescription("Key in the pipeline Data object where the output embedding (List<Float> or List<List<Float>>) will be stored.")
    private final String outputEmbeddingsKey;

    // 'name' is not a direct field here, but accessed via getParameters().getString("name", ...) if needed,
    // or set via put("name", ...) on the underlying Data object.
    // GenericStepConfig itself doesn't have a 'name' field; it's a parameter within 'parameters'.
    // However, we might want a convenience getter if 'name' is a standard parameter.
    private final String name;


    /**
     * Primary constructor used for creating an instance from a map of configuration values,
     * typically derived from a pipeline definition (JSON/YAML).
     *
     * @param configValues A map where keys are parameter names and values are their corresponding values.
     * This map is used to populate the underlying {@link Data} object in {@link GenericStepConfig}.
     * The {@code runnerClassName} must be provided or defaulted correctly.
     */
    @JsonCreator // Hint for Jackson if it needs to create this from a JSON object directly
    public SameDiffEmbeddingStepConfig(@NotNull @JsonProperty("configValues") Map<String, Object> configValues) {
        // The runnerClassName for GenericStepConfig should be the class name of SameDiffEmbeddingStepRunner.
        // It's often not directly in configValues for a specific config type, but set by the factory
        // or inferred. Here, we assume it's either passed in configValues under a key like "runnerClassName"
        // or the factory will handle setting it when creating StepConfig instances.
        // For robust direct construction, it should be present or defaulted.
        super(
                (String) configValues.getOrDefault("runnerClassName", SameDiffEmbeddingStepRunner.class.getName()), // Runner class for this config
                Data.fromMap(configValues) // Create a Data object from the input map for parameters
        );

        // Now, populate the specific fields of this class from the 'parameters' Data object (inherited)
        Data params = getParameters(); // Get the Data object stored by GenericStepConfig
        Objects.requireNonNull(params, "Internal parameters Data object is null after super constructor call.");

        this.name = params.getString("name", "UnnamedSameDiffEmbeddingStep"); // Default name if not specified
        this.modelUri = params.getString("modelUri", null); // No default, should be required
        this.inputTensorName = params.getString("inputTensorName", "input"); // Default value "input"
        this.outputTensorName = params.getString("outputTensorName", "embedding"); // Default value "embedding"
        this.inputTextKey = params.getString("inputTextKey", "inputText"); // Default value "inputText"
        this.outputEmbeddingsKey = params.getString("outputEmbeddingsKey", "samediffEmbeddings"); // Default "samediffEmbeddings"

        // Perform validation for required fields
        if (this.modelUri == null || this.modelUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required configuration parameter 'modelUri' for SAMEDIFF_EMBEDDING step '" + this.name + "'.");
        }
        if (this.inputTextKey == null || this.inputTextKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required configuration parameter 'inputTextKey' for SAMEDIFF_EMBEDDING step '" + this.name + "'.");
        }
        if (this.outputEmbeddingsKey == null || this.outputEmbeddingsKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required configuration parameter 'outputEmbeddingsKey' for SAMEDIFF_EMBEDDING step '" + this.name + "'.");
        }
    }

    /**
     * Returns the symbolic type name for this step configuration.
     * This implementation returns the class name of the associated runner for uniqueness if not overridden.
     * It is preferable for factories to provide a specific symbolic name.
     * The {@link StepConfig#type()} method is often where a short, user-friendly type is defined.
     * This should align with what `SameDiffEmbeddingStepRunnerFactory.getType()` returns.
     */
    @Override
    public String type() {
        // This should match the value returned by SameDiffEmbeddingStepRunnerFactory.getType()
        return STEP_TYPE_NAME;
    }

    // Convenience getter for the step name that was extracted during construction
    // The actual "name" parameter is stored in the inherited 'parameters' Data object.
    public String getName() {
        return this.name;
    }

    // setName is not directly provided as fields are final.
    // Configuration modifications should go through put() on parameters if GenericStepConfig is mutable that way
    // or by creating a new config instance.
    // The put method from GenericStepConfig (inherited from StepConfig) modifies the internal 'parameters' Data object.
    // public SameDiffEmbeddingStepConfig setName(String name) {
    //    this.getParameters().put("name", name);
    //    // Note: this.name field is final, so it won't be updated. This indicates a design choice:
    //    // either make fields non-final, or consider 'name' as just another parameter in the Data map.
    //    // For now, 'name' field is initialized at construction.
    //    return this;
    // }
}