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

package ai.kompile.embedding.samediff.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component; // Or @Configuration if preferred

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@Component // Ensures it's picked up for @EnableConfigurationProperties
@ConfigurationProperties(prefix = "kompile.embedding.samediff")
public class SameDiffEmbeddingProperties {

    /**
     * Whether this SameDiff embedding model is enabled.
     */
    private boolean enabled = true;

    /**
     * URI of the SameDiff model file (e.g., file:/path/to/model.sd or classpath:/models/embedding.sd).
     */
    private String modelUri;

    /**
     * Name of the input placeholder/variable in the SameDiff graph for the primary text input.
     * This typically expects an NDArray of token IDs or preprocessed features.
     */
    private String inputTensorName = "input"; // Default, but should match the model

    /**
     * Optional: Names of other input placeholders/variables if the model requires multiple inputs.
     * Key: placeholder name, Value: (not used here, but could be for type/shape hints later)
     */
    private Map<String, String> additionalInputTensorNames = Collections.emptyMap();


    /**
     * Name of the output variable in the SameDiff graph that provides the embedding vector.
     */
    private String outputTensorName = "embedding"; // Default, but should match the model

    /**
     * Optional: Names of other output variables if the model produces multiple outputs.
     * For embedding, usually one primary output is used.
     */
    private List<String> additionalOutputTensorNames = Collections.emptyList();


    /**
     * Configuration for the associated pipeline step.
     */
    private PipelineStepProperties pipelineStep = new PipelineStepProperties();

    @Data
    public static class PipelineStepProperties {
        /**
         * Whether the SameDiffEmbeddingStepRunner is enabled.
         */
        private boolean enabled = true;
    }
}