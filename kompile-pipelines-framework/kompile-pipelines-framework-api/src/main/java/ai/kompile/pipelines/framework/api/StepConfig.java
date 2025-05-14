package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.data.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Represents the configuration for a single step in a pipeline.
 * This interface allows for generic, attribute-based configuration
 * where parameters are stored as key-value pairs within an internal {@link Data} object.
 *
 * It includes a method to identify the {@link PipelineStepRunner} responsible for executing this step.
 * Implementations of this interface are serializable to/from JSON/YAML.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface StepConfig extends Configuration { // Extends Configuration for Serializable marker & type info

    /**
     * Returns the fully qualified class name of the {@link PipelineStepRunner}
     * that will execute the logic defined by this configuration.
     * This is crucial for the pipeline runtime to instantiate the correct runner.
     *
     * @return The runner's class name.
     */
    @JsonProperty("runnerClassName") // Ensures this is part of the serialized form
    String runnerClassName();

    /**
     * Retrieves a configuration parameter by its key.
     * This is a convenience method to access parameters stored within the internal {@link Data} object.
     * The actual retrieval and type casting is delegated to the underlying {@link Data#get(String)} method.
     *
     * @param key The key of the parameter.
     * @param <T> The expected type of the parameter.
     * @return The parameter's value, or null if the key is not found or the value is null.
     * @throws ClassCastException if the value exists but cannot be cast to T.
     * @see Data#get(String)
     */
    @JsonIgnore // Individual get/put are convenience for programmatic construction;
    // parameters are primarily accessed and serialized via getParameters()
    <T> T get(String key);

    /**
     * Retrieves a configuration parameter by its key, returning a default value if not found
     * or if the value is null.
     * This is a convenience method that delegates to the underlying {@link Data#get(String, Object)} method.
     *
     * @param key The key of the parameter.
     * @param defaultValue The default value to return if the key is not found or the value is null.
     * @param <T> The expected type of the parameter.
     * @return The parameter's value, or the defaultValue.
     * @throws ClassCastException if the value exists but cannot be cast to T (unless defaultValue is returned).
     * @see Data#get(String, Object)
     */
    @JsonIgnore
    <T> T get(String key, T defaultValue);

    /**
     * Sets a configuration parameter.
     * This stores the key-value pair in the internal {@link Data} object.
     * This method is primarily for programmatic construction of StepConfig instances.
     * Implementations (like GenericStepConfig) will manage this by delegating to their internal Data object.
     *
     * @param key The key of the parameter.
     * @param value The value of the parameter.
     * @return This StepConfig instance, allowing for fluent configuration.
     */
    StepConfig put(String key, Object value);

    /**
     * Returns all configuration parameters for this step as a {@link Data} object.
     * This is the primary way {@link PipelineStepRunner}s will access their complete
     * configuration and is also the part that gets serialized under the "parameters" field in JSON/YAML.
     *
     * @return A non-null {@link Data} object containing all parameters for this step.
     * Implementations should ensure this never returns null (e.g., return an empty Data object).
     */
    @JsonProperty("parameters") // Ensures the Data object is serialized under this key
    Data getParameters();
}