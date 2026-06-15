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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffLLMTokenizer;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffWordPieceTokenizer;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Map;

/**
 * Step runner for tokenizing text.
 */
@Slf4j
class SameDiffLLMTokenizerStepRunner implements PipelineStepRunner {

    private SameDiffLLMTokenizer tokenizer;
    private long eosTokenId;
    private long padTokenId;
    private long unkTokenId;
    private volatile boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        if (!(stepConfig instanceof GenericStepConfig)) {
            throw new IllegalArgumentException("Configuration must be an instance of GenericStepConfig");
        }
        GenericStepConfig config = (GenericStepConfig) stepConfig;
        Data params = config.getParameters();

        String tokenizerPath = params.getString(SameDiffLLMConstants.PARAM_TOKENIZER_PATH);
        if (tokenizerPath == null || tokenizerPath.isEmpty()) {
            throw new IllegalArgumentException("Tokenizer path must be specified");
        }

        String tokenizerType = params.getString(SameDiffLLMConstants.PARAM_TOKENIZER_TYPE, "samediff_wordpiece");

        if ("samediff_wordpiece".equals(tokenizerType) || "wordpiece".equals(tokenizerType)) {
            this.tokenizer = new SameDiffWordPieceTokenizer();
        } else {
            throw new IllegalArgumentException("Unsupported tokenizer type: " + tokenizerType);
        }

        this.tokenizer.initialize(tokenizerPath, null);
        log.info("Initialized {} tokenizer from: {}", tokenizerType, tokenizerPath);

        this.eosTokenId = params.getInt64(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, 2L);
        this.padTokenId = params.getInt64(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID, 0L);
        this.unkTokenId = params.getInt64(SameDiffLLMConstants.PARAM_UNK_TOKEN_ID, 1L);

        this.initialized = true;
        log.info("SameDiffLLMTokenizerStepRunner initialized successfully");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("SameDiffLLMTokenizerStepRunner is not initialized");
        }

        String prompt = input.getString(SameDiffLLMConstants.KEY_PROMPT_INPUT);
        if (prompt == null) {
            prompt = "";
        }

        Map<String, INDArray> encoded = tokenizer.batchEncode(java.util.Collections.singletonList(prompt), true);

        Data result = Data.empty();
        result.put(SameDiffLLMConstants.KEY_INPUT_IDS, encoded.get("input_ids"));

        INDArray attentionMask = encoded.get("attention_mask");
        if (attentionMask != null) {
            result.put(SameDiffLLMConstants.KEY_ATTENTION_MASK, attentionMask);
        }

        log.debug("Tokenized prompt of {} characters to {} tokens",
                prompt.length(), encoded.get("input_ids").length());
        return result;
    }

    @Override
    public void close() throws Exception {
        // Tokenizer cleanup if needed
    }
}
