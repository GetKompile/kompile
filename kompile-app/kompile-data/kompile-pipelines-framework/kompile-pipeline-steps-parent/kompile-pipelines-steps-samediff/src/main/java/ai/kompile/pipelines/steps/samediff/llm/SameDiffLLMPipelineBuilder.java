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

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.*;

import java.util.*;

/**
 * Fluent builder for constructing a complete SameDiff LLM inference pipeline as a {@link GraphPipeline}.
 *
 * Creates the full DAG for language model inference with optional tool calling support:
 * <pre>
 *   pipeline_input → tokenizer → embed_tokens → decoder_loop → token_decode → output
 *                         ↓
 *                    tool_integration (optional)
 * </pre>
 *
 * Usage:
 * <pre>
 * GraphPipeline pipeline = SameDiffLLMPipelineBuilder.create()
 *     .modelId("smollm-135m-instruct")
 *     .embedTokens("path/to/embed_tokens.fb")
 *     .decoder("path/to/decoder.fb")
 *     .tokenizer("path/to/tokenizer.json")
 *     .eosTokenId(2)
 *     .maxNewTokens(512)
 *     .enableToolCalling(true)
 *     .build();
 * </pre>
 */
public class SameDiffLLMPipelineBuilder {

    private String modelId;
    private String embedTokensModelUri;
    private String decoderModelUri;
    private String tokenizerPath;
    private String tokenizerType = "samediff_wordpiece";
    private int eosTokenId = 2;
    private int padTokenId = 0;
    private int unkTokenId = 1;
    private int numHeads = 12;
    private int headDim = 64;
    private int numKvLayers = 0; // Auto-detect from model
    private int maxNewTokens = 512;
    private float temperature = 0.7f;
    private int topK = 40;
    private List<String> embedTokensOutputNames = Collections.singletonList("hidden_states");
    private List<String> decoderOutputNames; // Auto-detect if null
    private String pipelineId;
    
    // Tool calling configuration
    private boolean enableToolCalling = false;
    private String toolCallFormat = "json_markers"; // json_markers, openai_json
    private String toolChoice = "auto"; // auto, none, specific
    private String specificToolName = null;

    private SameDiffLLMPipelineBuilder() {}

    public static SameDiffLLMPipelineBuilder create() {
        return new SameDiffLLMPipelineBuilder();
    }

    public SameDiffLLMPipelineBuilder modelId(String id) {
        this.modelId = id;
        return this;
    }

    public SameDiffLLMPipelineBuilder embedTokens(String modelUri) {
        this.embedTokensModelUri = modelUri;
        return this;
    }

    public SameDiffLLMPipelineBuilder embedTokensOutputNames(List<String> names) {
        this.embedTokensOutputNames = names;
        return this;
    }

    public SameDiffLLMPipelineBuilder decoder(String modelUri) {
        this.decoderModelUri = modelUri;
        return this;
    }

    public SameDiffLLMPipelineBuilder decoderOutputNames(List<String> names) {
        this.decoderOutputNames = names;
        return this;
    }

    public SameDiffLLMPipelineBuilder tokenizer(String path) {
        this.tokenizerPath = path;
        return this;
    }

    public SameDiffLLMPipelineBuilder tokenizerType(String type) {
        this.tokenizerType = type;
        return this;
    }

    public SameDiffLLMPipelineBuilder eosTokenId(int id) {
        this.eosTokenId = id;
        return this;
    }

    public SameDiffLLMPipelineBuilder padTokenId(int id) {
        this.padTokenId = id;
        return this;
    }

    public SameDiffLLMPipelineBuilder unkTokenId(int id) {
        this.unkTokenId = id;
        return this;
    }

    public SameDiffLLMPipelineBuilder numHeads(int n) {
        this.numHeads = n;
        return this;
    }

    public SameDiffLLMPipelineBuilder headDim(int d) {
        this.headDim = d;
        return this;
    }

    public SameDiffLLMPipelineBuilder numKvLayers(int n) {
        this.numKvLayers = n;
        return this;
    }

    public SameDiffLLMPipelineBuilder maxNewTokens(int n) {
        this.maxNewTokens = n;
        return this;
    }

    public SameDiffLLMPipelineBuilder temperature(float t) {
        this.temperature = t;
        return this;
    }

    public SameDiffLLMPipelineBuilder topK(int k) {
        this.topK = k;
        return this;
    }

    public SameDiffLLMPipelineBuilder pipelineId(String id) {
        this.pipelineId = id;
        return this;
    }

    // Tool calling configuration
    public SameDiffLLMPipelineBuilder enableToolCalling(boolean enable) {
        this.enableToolCalling = enable;
        return this;
    }

    public SameDiffLLMPipelineBuilder toolCallFormat(String format) {
        this.toolCallFormat = format;
        return this;
    }

    public SameDiffLLMPipelineBuilder toolChoice(String choice) {
        this.toolChoice = choice;
        return this;
    }

    public SameDiffLLMPipelineBuilder specificToolName(String name) {
        this.specificToolName = name;
        return this;
    }

    /**
     * Builds the complete SameDiff LLM pipeline as a GraphPipeline.
     */
    public GraphPipeline build() {
        Objects.requireNonNull(embedTokensModelUri, "Embed tokens model URI is required");
        Objects.requireNonNull(decoderModelUri, "Decoder model URI is required");
        Objects.requireNonNull(tokenizerPath, "Tokenizer path is required");

        List<GraphNodeConfig> nodes = new ArrayList<>();

        // 1. Tokenizer step (optional - can be done inline in embed_tokens)
        Data tokenizerParams = Data.empty();
        tokenizerParams.put(SameDiffLLMConstants.PARAM_TOKENIZER_PATH, tokenizerPath);
        tokenizerParams.put(SameDiffLLMConstants.PARAM_TOKENIZER_TYPE, tokenizerType);
        tokenizerParams.put(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, (long) eosTokenId);
        tokenizerParams.put(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID, (long) padTokenId);
        tokenizerParams.put(SameDiffLLMConstants.PARAM_UNK_TOKEN_ID, (long) unkTokenId);

        nodes.add(new StandardGraphNodeConfig(
                "tokenizer",
                Collections.singletonList("pipeline_input"),
                createStepConfig(SameDiffLLMTokenizerStepRunnerFactory.RUNNER_FQCN, tokenizerParams)
        ));

        // 2. Token embedding step
        Data embedTokensParams = Data.empty();
        embedTokensParams.put(SameDiffLLMConstants.PARAM_MODEL_URI, embedTokensModelUri);
        embedTokensParams.put(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, (long) eosTokenId);
        embedTokensParams.put(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID, (long) padTokenId);
        if (embedTokensOutputNames != null) {
            embedTokensParams.putList(SameDiffLLMConstants.PARAM_OUTPUT_NAMES, embedTokensOutputNames, ValueType.STRING);
        }

        nodes.add(new StandardGraphNodeConfig(
                "embed_tokens",
                Collections.singletonList("tokenizer"),
                createStepConfig(SameDiffEmbedTokensStepRunnerFactory.RUNNER_FQCN, embedTokensParams)
        ));

        // 3. Decoder step (body of the loop)
        Data decoderParams = Data.empty();
        decoderParams.put(SameDiffLLMConstants.PARAM_MODEL_URI, decoderModelUri);
        decoderParams.put(SameDiffLLMConstants.PARAM_NUM_KV_LAYERS, (long) numKvLayers);
        decoderParams.put(SameDiffLLMConstants.PARAM_NUM_HEADS, (long) numHeads);
        decoderParams.put(SameDiffLLMConstants.PARAM_HEAD_DIM, (long) headDim);
        decoderParams.put(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, (long) eosTokenId);
        decoderParams.put(SameDiffLLMConstants.PARAM_TEMPERATURE, (double) temperature);
        decoderParams.put(SameDiffLLMConstants.PARAM_TOP_K, (long) topK);
        decoderParams.put(SameDiffLLMConstants.PARAM_MAX_NEW_TOKENS, (long) maxNewTokens);
        
        // Tool calling parameters
        decoderParams.put(SameDiffLLMConstants.PARAM_ENABLE_TOOL_CALLING, enableToolCalling);
        decoderParams.put(SameDiffLLMConstants.PARAM_TOOL_CALL_FORMAT, toolCallFormat);
        decoderParams.put(SameDiffLLMConstants.PARAM_TOOL_CHOICE, toolChoice);
        if (specificToolName != null) {
            decoderParams.put(SameDiffLLMConstants.PARAM_SPECIFIC_TOOL_NAME, specificToolName);
        }
        
        if (decoderOutputNames != null) {
            decoderParams.putList(SameDiffLLMConstants.PARAM_OUTPUT_NAMES, decoderOutputNames, ValueType.STRING);
        }

        nodes.add(new StandardGraphNodeConfig(
                "decoder_body",
                Collections.singletonList("embed_tokens"),
                createStepConfig(SameDiffLanguageModelStepRunnerFactory.STEP_TYPE_NAME, decoderParams)
        ));

        // 4. Loop node wrapping the decoder for autoregressive generation
        nodes.add(new LoopNodeConfig(
                "decoder_loop",
                Collections.singletonList("embed_tokens"),
                "decoder_body",
                Arrays.asList(
                        SameDiffLLMConstants.KEY_KV_CACHE,
                        SameDiffLLMConstants.KEY_INPUT_IDS,
                        SameDiffLLMConstants.KEY_ATTENTION_MASK,
                        SameDiffLLMConstants.KEY_POSITION_IDS
                ),
                AutoregressiveLLMLoopCondition.class.getName(),
                SameDiffLLMConstants.KEY_GENERATED_TOKENS,
                SameDiffLLMConstants.KEY_NEXT_TOKEN_ID
        ));

        // 5. Token decoding step
        Data tokenDecodingParams = Data.empty();
        tokenDecodingParams.put(SameDiffLLMConstants.PARAM_TOKENIZER_PATH, tokenizerPath);
        tokenDecodingParams.put(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, (long) eosTokenId);

        nodes.add(new StandardGraphNodeConfig(
                "token_decode",
                Collections.singletonList("decoder_loop"),
                createStepConfig(SameDiffTokenDecodeStepRunnerFactory.RUNNER_FQCN, tokenDecodingParams)
        ));

        String id = (pipelineId != null) ? pipelineId : "samediff-llm-pipeline-" + UUID.randomUUID();
        return new GraphPipeline(id, nodes, "pipeline_input", "token_decode");
    }

    private GenericStepConfig createStepConfig(String runnerClassName, Data parameters) {
        return new GenericStepConfig(runnerClassName, parameters);
    }
}
