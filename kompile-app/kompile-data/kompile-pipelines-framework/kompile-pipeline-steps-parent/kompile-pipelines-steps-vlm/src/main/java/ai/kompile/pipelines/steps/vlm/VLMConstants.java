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

/**
 * Constants for VLM pipeline step configuration and data keys.
 */
public final class VLMConstants {

    private VLMConstants() {}

    // Step type names
    public static final String VISION_ENCODER_TYPE = "VLM_VISION_ENCODER";
    public static final String TEXT_EMBEDDING_TYPE = "VLM_TEXT_EMBEDDING";
    public static final String VISION_TEXT_FUSION_TYPE = "VLM_VISION_TEXT_FUSION";
    public static final String VLM_DECODER_TYPE = "VLM_DECODER";
    public static final String TOKEN_SAMPLING_TYPE = "VLM_TOKEN_SAMPLING";
    public static final String IMAGE_PREPROCESSING_TYPE = "VLM_IMAGE_PREPROCESSING";
    public static final String TOKEN_DECODING_TYPE = "VLM_TOKEN_DECODING";

    // Common parameter keys
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_OUTPUT_NAMES = "outputNames";

    // Data keys for vision pipeline
    public static final String KEY_PIXEL_VALUES = "pixel_values";
    public static final String KEY_PIXEL_ATTENTION_MASK = "pixel_attention_mask";
    public static final String KEY_IMAGE_FEATURES = "image_features";

    // Data keys for text pipeline
    public static final String KEY_INPUT_IDS = "input_ids";
    public static final String KEY_TEXT_EMBEDDINGS = "text_embeddings";
    public static final String KEY_INPUTS_EMBEDS = "inputs_embeds";
    public static final String KEY_ATTENTION_MASK = "attention_mask";
    public static final String KEY_POSITION_IDS = "position_ids";

    // Data keys for decoder
    public static final String KEY_KV_CACHE = "kv_cache";
    public static final String KEY_LOGITS = "logits";
    public static final String KEY_USE_CACHE_BRANCH = "use_cache_branch";

    // Data keys for token sampling
    public static final String KEY_NEXT_TOKEN_ID = "next_token_id";
    public static final String KEY_IS_EOS = "is_eos";

    // Config parameter keys
    public static final String PARAM_IMAGE_TOKEN_ID = "imageTokenId";
    public static final String PARAM_EOS_TOKEN_ID = "eosTokenId";
    public static final String PARAM_SAMPLING_STRATEGY = "samplingStrategy";
    public static final String PARAM_MAX_NEW_TOKENS = "maxNewTokens";
    public static final String PARAM_TOKENIZER_PATH = "tokenizerPath";
    public static final String PARAM_NUM_KV_LAYERS = "numKvLayers";
    public static final String PARAM_NUM_HEADS = "numHeads";
    public static final String PARAM_HEAD_DIM = "headDim";

    // Sampling config parameter keys
    public static final String PARAM_TEMPERATURE = "temperature";
    public static final String PARAM_TOP_K = "topK";
    public static final String PARAM_TOP_P = "topP";
    public static final String PARAM_REPETITION_PENALTY = "repetitionPenalty";
    public static final String PARAM_DO_SAMPLE = "doSample";
    public static final String PARAM_MAX_CACHE_SEQ_LEN = "maxCacheSeqLen";

    // Image preprocessing config
    public static final String PARAM_TILE_SIZE = "tileSize";
    public static final String PARAM_MAX_TILES = "maxTiles";
    public static final String PARAM_IMAGE_MEAN = "imageMean";
    public static final String PARAM_IMAGE_STD = "imageStd";
    public static final String PARAM_RESCALE_FACTOR = "rescaleFactor";

    // Sampling strategies
    public static final String SAMPLING_GREEDY = "greedy";
    public static final String SAMPLING_TOP_K = "top_k";
    public static final String SAMPLING_TOP_P = "top_p";

    // Generated output keys
    public static final String KEY_GENERATED_TOKENS = "generated_tokens";
    public static final String KEY_GENERATED_TEXT = "generated_text";
}
