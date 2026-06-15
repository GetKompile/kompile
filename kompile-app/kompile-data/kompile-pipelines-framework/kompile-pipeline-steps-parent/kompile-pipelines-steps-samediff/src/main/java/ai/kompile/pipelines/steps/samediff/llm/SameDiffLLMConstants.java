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

/**
 * Constants for SameDiff LLM pipeline step configuration and data keys.
 */
public class SameDiffLLMConstants {

    // Model parameters
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_TOKENIZER_PATH = "tokenizerPath";
    public static final String PARAM_TOKENIZER_TYPE = "tokenizerType";
    public static final String PARAM_EOS_TOKEN_ID = "eosTokenId";
    public static final String PARAM_PAD_TOKEN_ID = "padTokenId";
    public static final String PARAM_UNK_TOKEN_ID = "unkTokenId";
    public static final String PARAM_NUM_HEADS = "numHeads";
    public static final String PARAM_HEAD_DIM = "headDim";
    public static final String PARAM_NUM_KV_LAYERS = "numKvLayers";
    public static final String PARAM_MAX_NEW_TOKENS = "maxNewTokens";
    public static final String PARAM_TEMPERATURE = "temperature";
    public static final String PARAM_TOP_K = "topK";
    public static final String PARAM_OUTPUT_NAMES = "outputNames";
    
    // Tool calling parameters
    public static final String PARAM_ENABLE_TOOL_CALLING = "enableToolCalling";
    public static final String PARAM_TOOL_CALL_FORMAT = "toolCallFormat";
    public static final String PARAM_TOOL_CHOICE = "toolChoice";
    public static final String PARAM_SPECIFIC_TOOL_NAME = "specificToolName";
    
    // Data keys
    public static final String KEY_INPUT_IDS = "input_ids";
    public static final String KEY_ATTENTION_MASK = "attention_mask";
    public static final String KEY_POSITION_IDS = "position_ids";
    public static final String KEY_KV_CACHE = "kv_cache";
    public static final String KEY_GENERATED_TOKENS = "generated_tokens";
    public static final String KEY_NEXT_TOKEN_ID = "next_token_id";
    public static final String KEY_HIDDEN_STATES = "hidden_states";
    
    // Tool calling data keys
    public static final String KEY_TOOL_CALL_REQUEST = "tool_call_request";
    public static final String KEY_TOOL_CALL_RESPONSE = "tool_call_response";
    public static final String KEY_AVAILABLE_TOOLS = "available_tools";
    public static final String KEY_IS_TOOL_CALL_REQUEST = "is_tool_call_request";
    
    // Autoregressive loop keys
    public static final String KEY_IS_EOS = "is_eos";
    public static final String KEY_LOGITS = "logits";

    // Pipeline input/output keys
    public static final String KEY_PROMPT_INPUT = "prompt";
    public static final String KEY_RESPONSE_OUTPUT = "response_text";
    public static final String KEY_CONVERSATION_HISTORY = "conversation_history";

    // Sampling strategies
    public static final String SAMPLING_STRATEGY_GREEDY = "greedy";
    public static final String SAMPLING_STRATEGY_TOP_K = "top_k";
    public static final String SAMPLING_STRATEGY_TOP_P = "top_p";
    public static final String PARAM_SAMPLING_STRATEGY = "samplingStrategy";
    public static final String PARAM_TOP_P = "topP";
    public static final String PARAM_REPETITION_PENALTY = "repetitionPenalty";
    
    // Model roles/types
    public static final String MODEL_ROLE_EMBED_TOKENS = "embed_tokens";
    public static final String MODEL_ROLE_DECODER = "decoder";
    public static final String MODEL_ROLE_TOKENIZER = "tokenizer";
    
    // Tokenizer types
    public static final String TOKENIZER_TYPE_SAMEDIFF_WORDPIECE = "samediff_wordpiece";
    public static final String TOKENIZER_TYPE_WORDPIECE = "wordpiece";
    public static final String TOKENIZER_TYPE_BPE = "bpe";
    public static final String TOKENIZER_TYPE_UNIGRAM = "unigram";
    
    // Tool call formats
    public static final String TOOL_CALL_FORMAT_JSON_MARKERS = "json_markers";
    public static final String TOOL_CALL_FORMAT_OPENAI_JSON = "openai_json";
    
    // Tool choice modes
    public static final String TOOL_CHOICE_AUTO = "auto";
    public static final String TOOL_CHOICE_NONE = "none";
    public static final String TOOL_CHOICE_SPECIFIC = "specific";

    private SameDiffLLMConstants() {
        // Prevent instantiation
    }
}
