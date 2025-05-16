// New File: upload/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/data/PipelineToolCallRequest.java
package ai.kompile.pipelines.framework.api.data;

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Objects;

/**
 * Represents a request made by an executing pipeline to call an external or internal tool.
 * This object would typically be part of a list in a {@link Data} object when a pipeline
 * signals its intent to make one or more tool calls.
 */
@Builder
public class PipelineToolCallRequest implements Configuration {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String arguments; // JSON string representing the arguments

    /**
     * Constructs a new PipelineToolCallRequest.
     *
     * @param id A unique identifier for this specific tool call request instance.
     * This ID will be used to correlate this request with its corresponding response. Must not be null.
     * @param name The name of the tool to be invoked. Must not be null.
     * @param arguments A JSON string representing the arguments for the tool. Must conform to the tool's input schema. Must not be null.
     */
    @JsonCreator
    public PipelineToolCallRequest(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "arguments", required = true) String arguments) {
        this.id = Objects.requireNonNull(id, "Tool call request ID cannot be null.");
        this.name = Objects.requireNonNull(name, "Tool call request name cannot be null.");
        this.arguments = Objects.requireNonNull(arguments, "Tool call request arguments cannot be null.");
    }

    /**
     * @return The unique identifier for this tool call request.
     */
    public String getId() {
        return id;
    }

    /**
     * @return The name of the tool to be invoked.
     */
    public String getName() {
        return name;
    }

    /**
     * @return A JSON string representing the arguments for the tool.
     */
    public String getArguments() {
        return arguments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineToolCallRequest that = (PipelineToolCallRequest) o;
        return id.equals(that.id) &&
                name.equals(that.name) &&
                arguments.equals(that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, arguments);
    }

    @Override
    public String toString() {
        return "PipelineToolCallRequest{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments='" + arguments + '\'' +
                '}';
    }
}