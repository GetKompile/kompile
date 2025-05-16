/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.framework.runtime.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.StepConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A concrete implementation of {@link Pipeline} that represents a linear sequence of steps.
 * Data flows from one step to the next in the order they are defined.
 */

@Getter
@ToString
public class SequencePipeline implements Pipeline {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final List<StepConfig> steps;

    /**
     * Creates a new SequencePipeline.
     *
     * @param id    A unique identifier for this pipeline. If null, a random UUID will be generated.
     * @param steps A list of {@link StepConfig} defining the steps in the sequence. Must not be null.
     *              The list will be defensively copied.
     */
    @JsonCreator
    public SequencePipeline(
            @JsonProperty("id") String id,
            @NonNull @JsonProperty(value = "steps", required = true) List<StepConfig> steps) {
        this.id = (id != null && !id.trim().isEmpty()) ? id : UUID.randomUUID().toString();
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps)); // Defensive copy and unmodifiable
    }

    /**
     * Convenience constructor with an auto-generated ID.
     *
     * @param steps A list of {@link StepConfig} defining the steps in the sequence.
     */
    public SequencePipeline(@NonNull List<StepConfig> steps) {
        this(null, steps);
    }


    @Override
    public List<StepConfig> getSteps() {
        return this.steps; // Already unmodifiable
    }

    @Override
    public PipelineExecutor createExecutor() throws Exception {
        // Pass true to initialize runners immediately upon executor creation.
        return new SequencePipelineExecutor(this, true);
    }

    @Override
    public void validate() throws IllegalStateException {
        if (steps == null || steps.isEmpty()) {
            // Depending on requirements, an empty pipeline might be valid or invalid.
            // For now, let's consider it valid but log a warning.
            // throw new IllegalStateException("Pipeline '" + this.id + "' must have at least one step.");
        }
        for (int i = 0; i < steps.size(); i++) {
            StepConfig stepConfig = steps.get(i);
            if (stepConfig == null) {
                throw new IllegalStateException("Pipeline '" + this.id + "' contains a null StepConfig at index " + i);
            }
            if (stepConfig.runnerClassName() == null || stepConfig.runnerClassName().trim().isEmpty()) {
                throw new IllegalStateException("Pipeline '" + this.id + "': StepConfig at index " + i +
                        " has a null or empty runnerClassName.");
            }
            // Further validation could involve trying to load the runner class or its schema
            // but that might be better suited for the executor's initialization phase.
        }
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SequencePipeline that = (SequencePipeline) o;
        return id.equals(that.id) &&
                steps.equals(that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, steps);
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        protected List<StepConfig> steps = new ArrayList<>();
        private String id;

        public Builder add(StepConfig step) {
            this.steps.add(step);
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public SequencePipeline build() {
            return new SequencePipeline(steps);
        }

        public Builder steps(List<StepConfig> steps) {
            this.steps = steps;
            return this;
        }
    }
}