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

package ai.kompile.modelmanager.llm.dynamic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage instance within an LLM pipeline, analogous to {@code VlmPipelineStageConfig}.
 *
 * <p>Configures a specific stage within a pipeline definition, including
 * ordering, enabled state, model overrides, and stage-specific parameters.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class LlmPipelineStageConfig {

    private String stageId;
    private int order;
    private boolean enabled;
    private String modelOverrideId;
    private Map<String, Object> parameters;

    public LlmPipelineStageConfig() {
        this.enabled = true;
        this.parameters = new HashMap<>();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final LlmPipelineStageConfig config = new LlmPipelineStageConfig();

        public Builder stageId(String stageId) { config.stageId = stageId; return this; }
        public Builder order(int order) { config.order = order; return this; }
        public Builder enabled(boolean enabled) { config.enabled = enabled; return this; }
        public Builder modelOverrideId(String modelOverrideId) { config.modelOverrideId = modelOverrideId; return this; }
        public Builder parameters(Map<String, Object> parameters) { config.parameters = new HashMap<>(parameters); return this; }
        public Builder addParameter(String key, Object value) { config.parameters.put(key, value); return this; }

        public LlmPipelineStageConfig build() {
            if (config.stageId == null || config.stageId.isEmpty()) {
                throw new IllegalArgumentException("stageId is required");
            }
            return config;
        }
    }

}
