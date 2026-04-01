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

package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decodes accumulated token IDs into text using a tokenizer.
 *
 * Input Data keys:
 *   - generated_tokens: List of INT64 token IDs
 *
 * Output Data keys:
 *   - generated_text: STRING with decoded text
 *
 * Config parameters:
 *   - tokenizerPath: Path to tokenizer.json (for HuggingFace tokenizers)
 *
 * Note: This is a basic implementation that converts token IDs to a string
 * representation. Full tokenizer integration (via tokenizers-rust bindings)
 * should be added when the tokenizer module is available on the classpath.
 */
public class TokenDecodingStepRunner implements PipelineStepRunner {

    private static final Logger log = LoggerFactory.getLogger(TokenDecodingStepRunner.class);

    private String tokenizerPath;
    private Object tokenizer; // Will be a tokenizer instance when tokenizers-rust is available
    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        Data params = stepConfig.getParameters();
        this.tokenizerPath = params.getString(VLMConstants.PARAM_TOKENIZER_PATH);

        // Attempt to load tokenizer via reflection to avoid hard dependency
        if (tokenizerPath != null) {
            try {
                Class<?> tokenizerClass = Class.forName("ai.kompile.tokenizers.HuggingFaceTokenizer");
                java.lang.reflect.Method fromFile = tokenizerClass.getMethod("fromFile", String.class);
                this.tokenizer = fromFile.invoke(null, tokenizerPath);
                log.info("Loaded HuggingFace tokenizer from: {}", tokenizerPath);
            } catch (ClassNotFoundException e) {
                log.warn("HuggingFace tokenizer not available on classpath. " +
                        "Token decoding will output raw token IDs.");
            } catch (Exception e) {
                log.warn("Failed to load tokenizer from {}: {}. " +
                        "Token decoding will output raw token IDs.", tokenizerPath, e.getMessage());
            }
        }

        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("TokenDecodingStepRunner not initialized");
        }

        List<Long> tokenIds = input.getList(VLMConstants.KEY_GENERATED_TOKENS, ValueType.INT64);
        if (tokenIds == null || tokenIds.isEmpty()) {
            Data result = Data.empty();
            result.put(VLMConstants.KEY_GENERATED_TEXT, "");
            return result;
        }

        String decodedText;
        if (tokenizer != null) {
            decodedText = decodeWithTokenizer(tokenIds);
        } else {
            // Fallback: represent as comma-separated token IDs
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokenIds.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tokenIds.get(i));
            }
            decodedText = sb.toString();
        }

        Data result = Data.empty();
        result.put(VLMConstants.KEY_GENERATED_TEXT, decodedText);
        return result;
    }

    private String decodeWithTokenizer(List<Long> tokenIds) {
        try {
            // Convert List<Long> to int[] for tokenizer
            int[] ids = new int[tokenIds.size()];
            for (int i = 0; i < tokenIds.size(); i++) {
                ids[i] = tokenIds.get(i).intValue();
            }

            // Use reflection to call tokenizer.decode(int[])
            java.lang.reflect.Method decode = tokenizer.getClass().getMethod("decode", int[].class);
            return (String) decode.invoke(tokenizer, ids);
        } catch (Exception e) {
            log.warn("Failed to decode tokens with tokenizer: {}", e.getMessage());
            // Fallback
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokenIds.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tokenIds.get(i));
            }
            return sb.toString();
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        if (tokenizer != null) {
            try {
                if (tokenizer instanceof AutoCloseable) {
                    ((AutoCloseable) tokenizer).close();
                }
            } catch (Exception ignored) {}
            tokenizer = null;
        }
        initialized = false;
    }
}
