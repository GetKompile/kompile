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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for model request scheduling and dynamic batching.
 * Persisted to ~/.kompile/config/model-scheduler-config.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelSchedulerConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    @JsonProperty("preferred_batch_size")
    @Builder.Default
    private int preferredBatchSize = 32;

    @JsonProperty("max_batch_size")
    @Builder.Default
    private int maxBatchSize = 64;

    @JsonProperty("max_queue_delay_ms")
    @Builder.Default
    private long maxQueueDelayMs = 50;

    @JsonProperty("queue_capacity")
    @Builder.Default
    private int queueCapacity = 1000;

    @JsonProperty("continuous_batching_enabled")
    @Builder.Default
    private boolean continuousBatchingEnabled = true;

    @JsonProperty("max_concurrent_decodes")
    @Builder.Default
    private int maxConcurrentDecodes = 16;

    public static ModelSchedulerConfig defaults() {
        return ModelSchedulerConfig.builder().build();
    }
}
