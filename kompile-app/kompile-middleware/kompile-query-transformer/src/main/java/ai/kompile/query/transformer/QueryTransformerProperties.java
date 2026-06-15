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

package ai.kompile.query.transformer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for query transformers.
 */
@Data
@ConfigurationProperties(prefix = "kompile.query.transformer")
public class QueryTransformerProperties {

    /**
     * The type of query transformer to use.
     * Options: passthrough, compression, expansion, hyde, step-back, multi-query
     */
    private String type = "passthrough";

    /**
     * Whether query transformation is enabled.
     */
    private boolean enabled = true;

    /**
     * Maximum number of queries to generate for expansion.
     */
    private int maxQueries = 3;

    /**
     * Whether to include the original query in expansion results.
     */
    private boolean includeOriginal = true;

    /**
     * Custom system prompt for LLM-based transformers.
     */
    private String systemPrompt;

    /**
     * Temperature for LLM-based transformers.
     */
    private double temperature = 0.7;

    /**
     * Maximum tokens for generated queries.
     */
    private int maxTokens = 256;
}
