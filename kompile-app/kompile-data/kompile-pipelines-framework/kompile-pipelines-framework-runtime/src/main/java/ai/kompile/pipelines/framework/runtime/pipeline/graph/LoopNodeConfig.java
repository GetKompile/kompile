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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for a {@link GraphStepType#LOOP} node within a {@link GraphPipeline}.
 * A loop node repeatedly executes a body step (identified by {@code bodyStepName}),
 * feeding specified output keys back as input for the next iteration, until
 * a {@link ai.kompile.pipelines.framework.api.loop.LoopCondition} signals termination.
 *
 * This is designed for autoregressive decoding loops in VLM pipelines where:
 * - The decoder step runs iteratively
 * - KV cache, attention mask, position IDs, and generated tokens are fed back
 * - The loop terminates when an EOS token is generated or max iterations reached
 */
@Getter
@JsonTypeName("LOOP")
public class LoopNodeConfig implements GraphNodeConfig {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final List<String> inputs;

    /**
     * The name of the graph step to execute on each iteration of the loop.
     * This step must be a STANDARD node that accepts the feedback keys as input.
     */
    private final String bodyStepName;

    /**
     * Keys from the body step's output that are fed back as input to the next iteration.
     * For VLM decoding, this typically includes: kv_cache, next_token_id, attention_mask, position_ids.
     */
    private final List<String> feedbackKeys;

    /**
     * Fully qualified class name of the {@link ai.kompile.pipelines.framework.api.loop.LoopCondition}
     * implementation that determines when the loop terminates.
     */
    private final String conditionClassName;

    /**
     * Key in the loop output Data where accumulated results (e.g., generated token IDs) are stored.
     * Defaults to "generated_tokens" if not specified.
     */
    private final String accumulatorKey;

    /**
     * Key in each iteration's output Data that holds the value to accumulate.
     * For autoregressive decoding, this is typically the "next_token_id" output from the sampler.
     */
    private final String accumulateFromKey;

    @JsonCreator
    public LoopNodeConfig(
            @NonNull @JsonProperty(value = "name", required = true) String name,
            @NonNull @JsonProperty(value = "inputs", required = true) List<String> inputs,
            @NonNull @JsonProperty(value = "bodyStepName", required = true) String bodyStepName,
            @NonNull @JsonProperty(value = "feedbackKeys", required = true) List<String> feedbackKeys,
            @NonNull @JsonProperty(value = "conditionClassName", required = true) String conditionClassName,
            @JsonProperty("accumulatorKey") String accumulatorKey,
            @JsonProperty("accumulateFromKey") String accumulateFromKey) {
        this.name = Objects.requireNonNull(name);
        this.inputs = Objects.requireNonNull(inputs);
        this.bodyStepName = Objects.requireNonNull(bodyStepName);
        this.feedbackKeys = Objects.requireNonNull(feedbackKeys);
        this.conditionClassName = Objects.requireNonNull(conditionClassName);
        this.accumulatorKey = (accumulatorKey != null) ? accumulatorKey : "generated_tokens";
        this.accumulateFromKey = (accumulateFromKey != null) ? accumulateFromKey : "next_token_id";
    }

    @Override
    public GraphStepType getGraphStepType() {
        return GraphStepType.LOOP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoopNodeConfig that = (LoopNodeConfig) o;
        return name.equals(that.name) &&
                inputs.equals(that.inputs) &&
                bodyStepName.equals(that.bodyStepName) &&
                feedbackKeys.equals(that.feedbackKeys) &&
                conditionClassName.equals(that.conditionClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, inputs, bodyStepName, feedbackKeys, conditionClassName);
    }

    @Override
    public String toString() {
        return "LoopNodeConfig{" +
                "name='" + name + '\'' +
                ", inputs=" + inputs +
                ", bodyStepName='" + bodyStepName + '\'' +
                ", feedbackKeys=" + feedbackKeys +
                ", conditionClassName='" + conditionClassName + '\'' +
                ", accumulatorKey='" + accumulatorKey + '\'' +
                ", accumulateFromKey='" + accumulateFromKey + '\'' +
                '}';
    }
}
