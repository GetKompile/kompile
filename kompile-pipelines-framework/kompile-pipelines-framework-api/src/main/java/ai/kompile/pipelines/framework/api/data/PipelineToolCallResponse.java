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

// New File: upload/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/data/PipelineToolCallResponse.java
package ai.kompile.pipelines.framework.api.data;

import ai.kompile.pipelines.framework.api.Configuration;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Represents the response received from the execution of a tool,
 * intended to be fed back into a pipeline.
 */
public class PipelineToolCallResponse implements Configuration {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String content; // Content of the tool's response, typically a String or JSON string
    private final boolean isError;

    /**
     * Constructs a new PipelineToolCallResponse.
     *
     * @param id The unique identifier from the corresponding {@link PipelineToolCallRequest}. Must not be null.
     * @param name The name of the tool that was executed. Must not be null.
     * @param content The content of the response from the tool. Must not be null.
     * @param isError True if the tool execution resulted in an error, false otherwise.
     */
    @JsonCreator
    public PipelineToolCallResponse(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "content", required = true) String content,
            @JsonProperty(value = "isError") boolean isError) {
        this.id = Objects.requireNonNull(id, "Tool call response ID cannot be null.");
        this.name = Objects.requireNonNull(name, "Tool call response name cannot be null.");
        this.content = Objects.requireNonNull(content, "Tool call response content cannot be null.");
        this.isError = isError;
    }

    /**
     * @return The unique identifier from the corresponding tool call request.
     */
    public String getId() {
        return id;
    }

    /**
     * @return The name of the tool that was executed.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The content of the response from the tool.
     */
    public String getContent() {
        return content;
    }

    /**
     * @return True if the tool execution resulted in an error, false otherwise.
     */
    public boolean isError() {
        return isError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineToolCallResponse that = (PipelineToolCallResponse) o;
        return isError == that.isError &&
                id.equals(that.id) &&
                name.equals(that.name) &&
                content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, content, isError);
    }

    @Override
    public String toString() {
        return "PipelineToolCallResponse{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", content='" + content + '\'' +
                ", isError=" + isError +
                '}';
    }
}