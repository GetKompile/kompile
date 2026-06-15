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

package ai.kompile.modelmanager.vlm;

/**
 * Defines the stages in a VLM (Vision-Language Model) pipeline.
 *
 * Each stage represents a distinct transformation with clear input/output contracts.
 * This enum documents the data flow through the pipeline for transparency.
 *
 * <h2>SmolDocling/Idefics3 Pipeline Example:</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                        VLM PIPELINE DATA FLOW                               │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │                                                                             │
 * │  ┌─────────────┐                                                           │
 * │  │   IMAGE     │  BufferedImage (e.g., PDF page at 150 DPI)                │
 * │  │  (Input)    │  Shape: [width x height] pixels                           │
 * │  └──────┬──────┘                                                           │
 * │         │                                                                   │
 * │         ▼  IMAGE_PREPROCESSING                                             │
 * │  ┌─────────────┐                                                           │
 * │  │   TILER     │  Split into tiles + global thumbnail                      │
 * │  │             │  Output: List&lt;BufferedImage&gt; [N tiles + 1 global]         │
 * │  └──────┬──────┘                                                           │
 * │         │                                                                   │
 * │         ▼  IMAGE_NORMALIZATION                                             │
 * │  ┌─────────────┐                                                           │
 * │  │ PREPROCESSOR│  Resize, rescale (0-255→0-1), normalize (mean/std)        │
 * │  │             │  Output: INDArray [batch, frames, 3, H, W]                │
 * │  └──────┬──────┘                                                           │
 * │         │                                                                   │
 * │         ▼  VISION_ENCODING                                                 │
 * │  ┌─────────────┐                                                           │
 * │  │   VISION    │  Model: vision_encoder.onnx                               │
 * │  │   ENCODER   │  Input:  pixel_values [1, 1, 3, 512, 512]                 │
 * │  │             │          pixel_attention_mask [1, 1, 512, 512]            │
 * │  │             │  Output: image_features [1, 64, 576] per frame            │
 * │  └──────┬──────┘                                                           │
 * │         │                                                                   │
 * │         │         ┌─────────────┐                                          │
 * │         │         │   PROMPT    │  "Convert this page to docling."         │
 * │         │         │   (Input)   │                                          │
 * │         │         └──────┬──────┘                                          │
 * │         │                │                                                  │
 * │         │                ▼  TEXT_TOKENIZATION                              │
 * │         │         ┌─────────────┐                                          │
 * │         │         │  TOKENIZER  │  Model: tokenizer.json                   │
 * │         │         │             │  Input:  prompt string                   │
 * │         │         │             │  Output: token_ids [1, seq_len]          │
 * │         │         │             │  (includes &lt;image&gt; placeholders)         │
 * │         │         └──────┬──────┘                                          │
 * │         │                │                                                  │
 * │         │                ▼  TEXT_EMBEDDING                                 │
 * │         │         ┌─────────────┐                                          │
 * │         │         │ EMBED_TOKENS│  Model: embed_tokens.onnx                │
 * │         │         │             │  Input:  token_ids [1, seq_len]          │
 * │         │         │             │  Output: text_embeds [1, seq_len, 576]   │
 * │         │         └──────┬──────┘                                          │
 * │         │                │                                                  │
 * │         ▼                ▼                                                  │
 * │  ┌───────────────────────────────┐                                         │
 * │  │    EMBEDDING FUSION           │  VISION_TEXT_FUSION                     │
 * │  │                               │  Replace &lt;image&gt; token embeddings       │
 * │  │  vision_embeds + text_embeds  │  with vision_embeds                     │
 * │  │           ↓                   │  Output: inputs_embeds [1, total, 576]  │
 * │  │     inputs_embeds             │                                         │
 * │  └───────────────┬───────────────┘                                         │
 * │                  │                                                          │
 * │                  ▼  AUTOREGRESSIVE_DECODING                                │
 * │  ┌───────────────────────────────┐                                         │
 * │  │         DECODER               │  Model: decoder_model_merged.onnx       │
 * │  │                               │  Input:  inputs_embeds [1, seq, 576]    │
 * │  │  (with KV-cache for speed)    │          attention_mask [1, total_len]  │
 * │  │                               │          position_ids [1, seq]          │
 * │  │                               │          past_key_values.*.key/value    │
 * │  │                               │  Output: logits [1, seq, vocab_size]    │
 * │  │                               │          present.*.key/value (KV cache) │
 * │  └───────────────┬───────────────┘                                         │
 * │                  │                                                          │
 * │                  ▼  TOKEN_SAMPLING                                         │
 * │  ┌───────────────────────────────┐                                         │
 * │  │         SAMPLER               │  Strategy: greedy, top-k, top-p, temp   │
 * │  │                               │  Input:  logits [vocab_size]            │
 * │  │                               │  Output: next_token_id (int)            │
 * │  └───────────────┬───────────────┘                                         │
 * │                  │                                                          │
 * │                  ▼  TOKEN_DECODING                                         │
 * │  ┌───────────────────────────────┐                                         │
 * │  │         TOKENIZER             │  Model: tokenizer.json                  │
 * │  │         (decode)              │  Input:  token_ids []                   │
 * │  │                               │  Output: text string                    │
 * │  └───────────────┬───────────────┘                                         │
 * │                  │                                                          │
 * │                  ▼                                                          │
 * │  ┌─────────────────┐                                                       │
 * │  │  GENERATED TEXT │  DocTags, Markdown, JSON, etc.                        │
 * │  │    (Output)     │                                                       │
 * │  └─────────────────┘                                                       │
 * │                                                                             │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author Kompile Inc.
 */
public enum VlmPipelineStage {

    /**
     * Image preprocessing: tiling large images into manageable chunks.
     * <ul>
     *   <li><b>Input:</b> BufferedImage (any size)</li>
     *   <li><b>Output:</b> List&lt;BufferedImage&gt; tiles + global thumbnail</li>
     *   <li><b>Model:</b> None (algorithmic)</li>
     * </ul>
     */
    IMAGE_PREPROCESSING("Image Preprocessing",
        "BufferedImage",
        "List<BufferedImage> tiles",
        null),

    /**
     * Image normalization: rescale, normalize with mean/std.
     * <ul>
     *   <li><b>Input:</b> List&lt;BufferedImage&gt; tiles</li>
     *   <li><b>Output:</b> INDArray [batch, frames, channels, height, width]</li>
     *   <li><b>Model:</b> None (uses PreprocessorConfig)</li>
     * </ul>
     */
    IMAGE_NORMALIZATION("Image Normalization",
        "List<BufferedImage>",
        "INDArray [batch, frames, 3, H, W]",
        null),

    /**
     * Vision encoding: extract visual features using vision transformer.
     * <ul>
     *   <li><b>Input:</b> pixel_values [1, 1, 3, H, W], pixel_attention_mask [1, 1, H, W]</li>
     *   <li><b>Output:</b> image_features [1, seq_len, hidden_size]</li>
     *   <li><b>Model:</b> vision_encoder.onnx (or .sdz)</li>
     * </ul>
     */
    VISION_ENCODING("Vision Encoding",
        "pixel_values [1, 1, 3, H, W]",
        "image_features [1, seq_len, hidden_size]",
        "vision_encoder"),

    /**
     * Text tokenization: convert prompt string to token IDs.
     * <ul>
     *   <li><b>Input:</b> String prompt (with &lt;image&gt; placeholders)</li>
     *   <li><b>Output:</b> int[] token_ids</li>
     *   <li><b>Model:</b> tokenizer.json</li>
     * </ul>
     */
    TEXT_TOKENIZATION("Text Tokenization",
        "String prompt",
        "int[] token_ids",
        "tokenizer"),

    /**
     * Text embedding: convert token IDs to dense embeddings.
     * <ul>
     *   <li><b>Input:</b> token_ids [1, seq_len]</li>
     *   <li><b>Output:</b> text_embeddings [1, seq_len, hidden_size]</li>
     *   <li><b>Model:</b> embed_tokens.onnx (or .sdz)</li>
     * </ul>
     */
    TEXT_EMBEDDING("Text Embedding",
        "token_ids [1, seq_len]",
        "text_embeddings [1, seq_len, hidden_size]",
        "embed_tokens"),

    /**
     * Vision-text fusion: merge vision embeddings into text embedding sequence.
     * Replaces &lt;image&gt; token positions with vision embeddings.
     * <ul>
     *   <li><b>Input:</b> vision_embeds [1, vision_seq, hidden], text_embeds [1, text_seq, hidden]</li>
     *   <li><b>Output:</b> inputs_embeds [1, total_seq, hidden_size]</li>
     *   <li><b>Model:</b> None (algorithmic replacement at &lt;image&gt; positions)</li>
     * </ul>
     */
    VISION_TEXT_FUSION("Vision-Text Fusion",
        "vision_embeds + text_embeds",
        "inputs_embeds [1, total_seq, hidden_size]",
        null),

    /**
     * Autoregressive decoding: generate next token logits.
     * <ul>
     *   <li><b>Input:</b> inputs_embeds, attention_mask, position_ids, past_key_values</li>
     *   <li><b>Output:</b> logits [1, seq, vocab_size], present key/values</li>
     *   <li><b>Model:</b> decoder_model_merged.onnx (or .sdz)</li>
     * </ul>
     */
    AUTOREGRESSIVE_DECODING("Autoregressive Decoding",
        "inputs_embeds [1, seq, hidden], attention_mask, position_ids, KV cache",
        "logits [1, seq, vocab_size], updated KV cache",
        "decoder"),

    /**
     * Token sampling: select next token from logits distribution.
     * <ul>
     *   <li><b>Input:</b> logits [vocab_size]</li>
     *   <li><b>Output:</b> next_token_id (int)</li>
     *   <li><b>Model:</b> None (algorithmic: greedy, top-k, top-p, temperature)</li>
     * </ul>
     */
    TOKEN_SAMPLING("Token Sampling",
        "logits [vocab_size]",
        "next_token_id (int)",
        null),

    /**
     * Token decoding: convert token IDs back to text.
     * <ul>
     *   <li><b>Input:</b> int[] token_ids</li>
     *   <li><b>Output:</b> String decoded_text</li>
     *   <li><b>Model:</b> tokenizer.json</li>
     * </ul>
     */
    TOKEN_DECODING("Token Decoding",
        "int[] token_ids",
        "String decoded_text",
        "tokenizer");

    private final String displayName;
    private final String inputDescription;
    private final String outputDescription;
    private final String modelComponentKey;

    VlmPipelineStage(String displayName, String inputDescription,
                     String outputDescription, String modelComponentKey) {
        this.displayName = displayName;
        this.inputDescription = inputDescription;
        this.outputDescription = outputDescription;
        this.modelComponentKey = modelComponentKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getInputDescription() {
        return inputDescription;
    }

    public String getOutputDescription() {
        return outputDescription;
    }

    /**
     * Get the model component key required for this stage.
     * @return model component key (e.g., "vision_encoder", "decoder", "tokenizer"),
     *         or null if this stage is algorithmic (no model required)
     */
    public String getModelComponentKey() {
        return modelComponentKey;
    }

    /**
     * Check if this stage requires a neural network model.
     * @return true if a model file is needed, false if purely algorithmic
     */
    public boolean requiresModel() {
        return modelComponentKey != null;
    }
}
