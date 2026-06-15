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

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.*;

import java.util.*;

/**
 * Fluent builder for constructing a complete VLM inference pipeline as a {@link GraphPipeline}.
 *
 * Creates the full DAG for vision-language model inference:
 * <pre>
 *   image_preprocess ──→ vision_encoder ──→ fusion ──→ decoder_loop ──→ token_decode
 *                         text_embedding ──↗
 * </pre>
 *
 * Usage:
 * <pre>
 * GraphPipeline pipeline = VlmPipelineBuilder.create()
 *     .visionEncoder("path/to/vision_encoder.fb")
 *     .embedTokens("path/to/embed_tokens.fb")
 *     .decoder("path/to/decoder.fb")
 *     .tokenizer("path/to/tokenizer.json")
 *     .imageTokenId(49153)
 *     .eosTokenId(2)
 *     .numHeads(32)
 *     .headDim(96)
 *     .maxNewTokens(4096)
 *     .build();
 * </pre>
 */
public class VlmPipelineBuilder {

    private String visionEncoderModelUri;
    private String embedTokensModelUri;
    private String decoderModelUri;
    private String tokenizerPath;
    private int imageTokenId = 49153;
    private int eosTokenId = 2;
    private int numHeads = 32;
    private int headDim = 96;
    private int numKvLayers = 0; // Auto-detect
    private int maxNewTokens = 4096;
    private int tileSize = 364;
    private int maxTiles = 5;
    private List<String> visionEncoderOutputNames = Collections.singletonList("image_features");
    private List<String> embedTokensOutputNames = Collections.singletonList("text_embeddings");
    private List<String> decoderOutputNames; // Auto-detect if null
    private String pipelineId;

    private VlmPipelineBuilder() {}

    public static VlmPipelineBuilder create() {
        return new VlmPipelineBuilder();
    }

    public VlmPipelineBuilder visionEncoder(String modelUri) {
        this.visionEncoderModelUri = modelUri;
        return this;
    }

    public VlmPipelineBuilder visionEncoderOutputNames(List<String> names) {
        this.visionEncoderOutputNames = names;
        return this;
    }

    public VlmPipelineBuilder embedTokens(String modelUri) {
        this.embedTokensModelUri = modelUri;
        return this;
    }

    public VlmPipelineBuilder embedTokensOutputNames(List<String> names) {
        this.embedTokensOutputNames = names;
        return this;
    }

    public VlmPipelineBuilder decoder(String modelUri) {
        this.decoderModelUri = modelUri;
        return this;
    }

    public VlmPipelineBuilder decoderOutputNames(List<String> names) {
        this.decoderOutputNames = names;
        return this;
    }

    public VlmPipelineBuilder tokenizer(String path) {
        this.tokenizerPath = path;
        return this;
    }

    public VlmPipelineBuilder imageTokenId(int id) {
        this.imageTokenId = id;
        return this;
    }

    public VlmPipelineBuilder eosTokenId(int id) {
        this.eosTokenId = id;
        return this;
    }

    public VlmPipelineBuilder numHeads(int n) {
        this.numHeads = n;
        return this;
    }

    public VlmPipelineBuilder headDim(int d) {
        this.headDim = d;
        return this;
    }

    public VlmPipelineBuilder numKvLayers(int n) {
        this.numKvLayers = n;
        return this;
    }

    public VlmPipelineBuilder maxNewTokens(int n) {
        this.maxNewTokens = n;
        return this;
    }

    public VlmPipelineBuilder tileSize(int s) {
        this.tileSize = s;
        return this;
    }

    public VlmPipelineBuilder maxTiles(int n) {
        this.maxTiles = n;
        return this;
    }

    public VlmPipelineBuilder pipelineId(String id) {
        this.pipelineId = id;
        return this;
    }

    /**
     * Builds the complete VLM pipeline as a GraphPipeline.
     */
    public GraphPipeline build() {
        Objects.requireNonNull(visionEncoderModelUri, "Vision encoder model URI is required");
        Objects.requireNonNull(embedTokensModelUri, "Embed tokens model URI is required");
        Objects.requireNonNull(decoderModelUri, "Decoder model URI is required");

        List<GraphNodeConfig> nodes = new ArrayList<>();

        // 1. Image preprocessing step
        Data imagePreprocessParams = Data.empty();
        imagePreprocessParams.put(VLMConstants.PARAM_TILE_SIZE, (long) tileSize);
        imagePreprocessParams.put(VLMConstants.PARAM_MAX_TILES, (long) maxTiles);

        nodes.add(new StandardGraphNodeConfig(
                "image_preprocess",
                Collections.singletonList("pipeline_input"),
                createStepConfig(ImagePreprocessingStepRunnerFactory.RUNNER_FQCN, imagePreprocessParams)
        ));

        // 2. Vision encoder step
        Data visionEncoderParams = Data.empty();
        visionEncoderParams.put(VLMConstants.PARAM_MODEL_URI, visionEncoderModelUri);
        visionEncoderParams.putList(VLMConstants.PARAM_OUTPUT_NAMES, visionEncoderOutputNames, ValueType.STRING);

        nodes.add(new StandardGraphNodeConfig(
                "vision_encoder",
                Collections.singletonList("image_preprocess"),
                createStepConfig(VisionEncoderStepRunnerFactory.RUNNER_FQCN, visionEncoderParams)
        ));

        // 3. Text embedding step
        Data textEmbeddingParams = Data.empty();
        textEmbeddingParams.put(VLMConstants.PARAM_MODEL_URI, embedTokensModelUri);
        textEmbeddingParams.putList(VLMConstants.PARAM_OUTPUT_NAMES, embedTokensOutputNames, ValueType.STRING);

        nodes.add(new StandardGraphNodeConfig(
                "text_embedding",
                Collections.singletonList("pipeline_input"),
                createStepConfig(TextEmbeddingStepRunnerFactory.RUNNER_FQCN, textEmbeddingParams)
        ));

        // 4. Vision-text fusion (COMBINE_FN that merges vision encoder + text embedding outputs)
        Data fusionParams = Data.empty();
        fusionParams.put(VLMConstants.PARAM_IMAGE_TOKEN_ID, (long) imageTokenId);

        nodes.add(new StandardGraphNodeConfig(
                "fusion",
                Arrays.asList("vision_encoder", "text_embedding", "pipeline_input"),
                createStepConfig(VisionTextFusionStepRunnerFactory.RUNNER_FQCN, fusionParams)
        ));

        // 5. Decoder step (body of the loop)
        Data decoderParams = Data.empty();
        decoderParams.put(VLMConstants.PARAM_MODEL_URI, decoderModelUri);
        decoderParams.put(VLMConstants.PARAM_NUM_KV_LAYERS, (long) numKvLayers);
        decoderParams.put(VLMConstants.PARAM_NUM_HEADS, (long) numHeads);
        decoderParams.put(VLMConstants.PARAM_HEAD_DIM, (long) headDim);
        decoderParams.put(VLMConstants.PARAM_EOS_TOKEN_ID, (long) eosTokenId);
        if (decoderOutputNames != null) {
            decoderParams.putList(VLMConstants.PARAM_OUTPUT_NAMES, decoderOutputNames, ValueType.STRING);
        }

        nodes.add(new StandardGraphNodeConfig(
                "decoder_body",
                Collections.singletonList("fusion"),
                createStepConfig(VLMDecoderStepRunnerFactory.RUNNER_FQCN, decoderParams)
        ));

        // 6. Loop node wrapping the decoder
        nodes.add(new LoopNodeConfig(
                "decoder_loop",
                Collections.singletonList("fusion"),
                "decoder_body",
                Arrays.asList(
                        VLMConstants.KEY_KV_CACHE,
                        VLMConstants.KEY_INPUT_IDS,
                        VLMConstants.KEY_ATTENTION_MASK,
                        VLMConstants.KEY_POSITION_IDS
                ),
                AutoregressiveLoopCondition.class.getName(),
                VLMConstants.KEY_GENERATED_TOKENS,
                VLMConstants.KEY_NEXT_TOKEN_ID
        ));

        // 7. Token decoding step
        Data tokenDecodingParams = Data.empty();
        if (tokenizerPath != null) {
            tokenDecodingParams.put(VLMConstants.PARAM_TOKENIZER_PATH, tokenizerPath);
        }

        nodes.add(new StandardGraphNodeConfig(
                "token_decode",
                Collections.singletonList("decoder_loop"),
                createStepConfig(TokenDecodingStepRunnerFactory.RUNNER_FQCN, tokenDecodingParams)
        ));

        String id = (pipelineId != null) ? pipelineId : "vlm-pipeline-" + UUID.randomUUID();
        return new GraphPipeline(id, nodes, "pipeline_input", "token_decode");
    }

    private GenericStepConfig createStepConfig(String runnerClassName, Data parameters) {
        return new GenericStepConfig(runnerClassName, parameters);
    }
}
