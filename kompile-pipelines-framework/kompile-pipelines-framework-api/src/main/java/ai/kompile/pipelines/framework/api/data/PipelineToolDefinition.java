// New File: upload/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/data/PipelineToolDefinition.java
package ai.kompile.pipelines.framework.api.data;

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents the definition of a tool that a pipeline can call.
 * This information can be passed to a pipeline to inform it of available capabilities.
 */
public class PipelineToolDefinition implements Configuration {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String inputSchema; // JSON schema string defining the tool's input parameters

    /**
     * Constructs a new PipelineToolDefinition.
     *
     * @param name The unique name of the tool. Must not be null.
     * @param description A human-readable description of what the tool does. Can be null.
     * @param inputSchema A JSON Schema string that defines the structure and types of the input arguments the tool expects. Must not be null.
     */
    @JsonCreator
    public PipelineToolDefinition(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("description") String description,
            @JsonProperty(value = "inputSchema", required = true) String inputSchema) {
        this.name = Objects.requireNonNull(name, "Tool definition name cannot be null.");
        this.description = description;
        this.inputSchema = Objects.requireNonNull(inputSchema, "Tool definition inputSchema cannot be null.");
    }

    /**
     * @return The unique name of the tool.
     */
    public String getName() {
        return name;
    }

    /**
     * @return A human-readable description of what the tool does.
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return A JSON Schema string that defines the input parameters for the tool.
     */
    public String getInputSchema() {
        return inputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineToolDefinition that = (PipelineToolDefinition) o;
        return name.equals(that.name) &&
                Objects.equals(description, that.description) &&
                inputSchema.equals(that.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, inputSchema);
    }

    @Override
    public String toString() {
        return "PipelineToolDefinition{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", inputSchema='" + inputSchema + '\'' +
                '}';
    }
}