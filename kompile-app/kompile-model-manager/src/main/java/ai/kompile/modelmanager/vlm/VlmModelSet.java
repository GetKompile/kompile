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

import java.util.*;

/**
 * Defines a complete set of models needed for a VLM pipeline.
 *
 * A model set groups all the components required to run a specific VLM architecture,
 * enabling batch download and validation of the complete pipeline.
 *
 * <h2>Example: SmolDocling Model Set</h2>
 * <pre>
 * VlmModelSet smolDocling = VlmModelSet.SMOLDOCLING_256M;
 *
 * // Download all components at once
 * VlmModelSetDownloader.downloadAll(smolDocling);
 *
 * // Check which stages have models available
 * for (VlmPipelineStage stage : smolDocling.getStagesRequiringModels()) {
 *     System.out.println(stage.getDisplayName() + " -> " +
 *         smolDocling.getComponentForStage(stage).getFileName());
 * }
 * </pre>
 *
 * @author Kompile Inc.
 */
public class VlmModelSet {

    private final String setId;
    private final String displayName;
    private final String description;
    private final String huggingFaceRepo;
    private final List<VlmModelComponent> components;
    private final Map<String, Object> pipelineConfig;

    private VlmModelSet(Builder builder) {
        this.setId = builder.setId;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.huggingFaceRepo = builder.huggingFaceRepo;
        this.components = Collections.unmodifiableList(new ArrayList<>(builder.components));
        this.pipelineConfig = Collections.unmodifiableMap(new HashMap<>(builder.pipelineConfig));
    }

    // =====================================================================
    // PREDEFINED MODEL SETS
    // =====================================================================

    /**
     * SmolDocling 256M - Compact document understanding model.
     *
     * <p>Components:</p>
     * <ul>
     *   <li>vision_encoder.onnx - SigLIP-based vision transformer (512x512 input)</li>
     *   <li>decoder_model_merged.onnx - Qwen2-based autoregressive decoder</li>
     *   <li>embed_tokens.onnx - Text token embedding layer</li>
     *   <li>tokenizer.json - HuggingFace tokenizer</li>
     *   <li>tokenizer_config.json - Tokenizer configuration</li>
     * </ul>
     *
     * <p>Pipeline: Image tiles → Vision Encoder → Fuse with text → Decoder → DocTags</p>
     */
    public static final VlmModelSet SMOLDOCLING_256M = builder()
        .setId("smoldocling-256m")
        .displayName("SmolDocling 256M Preview")
        .description("Compact document understanding VLM for converting documents to structured formats")
        .huggingFaceRepo("ds4sd/SmolDocling-256M-preview")
        .addComponent(VlmModelComponent.builder()
            .componentKey("vision_encoder")
            .fileName("vision_encoder.onnx")
            .downloadUrl("https://huggingface.co/ds4sd/SmolDocling-256M-preview/resolve/main/onnx/vision_encoder.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("SigLIP-based vision encoder, processes 512x512 image tiles")
            .inputShape("[1, 1, 3, 512, 512] pixel_values + [1, 1, 512, 512] attention_mask")
            .outputShape("[1, 64, 576] image_features per tile")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("decoder")
            .fileName("decoder_model_merged.onnx")
            .downloadUrl("https://huggingface.co/ds4sd/SmolDocling-256M-preview/resolve/main/onnx/decoder_model_merged.onnx")
            .pipelineStage(VlmPipelineStage.AUTOREGRESSIVE_DECODING)
            .description("Qwen2-based decoder with merged KV cache, generates DocTags tokens")
            .inputShape("[1, seq, 576] inputs_embeds + attention_mask + position_ids + past_key_values")
            .outputShape("[1, seq, vocab_size] logits + present key/values")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("embed_tokens")
            .fileName("embed_tokens.onnx")
            .downloadUrl("https://huggingface.co/ds4sd/SmolDocling-256M-preview/resolve/main/onnx/embed_tokens.onnx")
            .pipelineStage(VlmPipelineStage.TEXT_EMBEDDING)
            .description("Converts token IDs to dense embeddings for the decoder")
            .inputShape("[1, seq_len] token_ids (int64)")
            .outputShape("[1, seq_len, 576] text_embeddings")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("tokenizer")
            .fileName("tokenizer.json")
            .downloadUrl("https://huggingface.co/ds4sd/SmolDocling-256M-preview/resolve/main/tokenizer.json")
            .pipelineStage(VlmPipelineStage.TEXT_TOKENIZATION)
            .description("HuggingFace tokenizer for encoding prompts and decoding generated tokens")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("tokenizer_config")
            .fileName("tokenizer_config.json")
            .downloadUrl("https://huggingface.co/ds4sd/SmolDocling-256M-preview/resolve/main/tokenizer_config.json")
            .pipelineStage(VlmPipelineStage.TEXT_TOKENIZATION)
            .description("Tokenizer configuration including special tokens")
            .build())
        // Pipeline configuration
        .pipelineConfig("image_size", 512)
        .pipelineConfig("tiling_enabled", true)
        .pipelineConfig("max_tiles", 9)
        .pipelineConfig("longest_edge_resize", 2048)
        .pipelineConfig("hidden_size", 576)
        .pipelineConfig("vocab_size", 151936)
        .pipelineConfig("max_new_tokens", 4096)
        .pipelineConfig("image_token", "<image>")
        .pipelineConfig("image_token_id", 49190)
        .pipelineConfig("eos_token_id", 151645)
        .pipelineConfig("image_mean", new double[]{0.5, 0.5, 0.5})
        .pipelineConfig("image_std", new double[]{0.5, 0.5, 0.5})
        .pipelineConfig("output_formats", Arrays.asList("DOCTAGS", "MARKDOWN", "JSON", "TEXT"))
        .build();

    /**
     * Donut Base - Document Understanding Transformer.
     *
     * <p>Components:</p>
     * <ul>
     *   <li>encoder - Swin Transformer vision encoder</li>
     *   <li>decoder - BART-based text decoder</li>
     *   <li>tokenizer - Donut-specific tokenizer</li>
     * </ul>
     */
    public static final VlmModelSet DONUT_BASE = builder()
        .setId("donut-base")
        .displayName("Donut Base")
        .description("Document Understanding Transformer for extracting structured data from documents")
        .huggingFaceRepo("naver-clova-ix/donut-base")
        .addComponent(VlmModelComponent.builder()
            .componentKey("encoder")
            .fileName("encoder_model.onnx")
            .downloadUrl("https://huggingface.co/naver-clova-ix/donut-base/resolve/main/onnx/encoder_model.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("Swin Transformer encoder for document images")
            .inputShape("[1, 3, 2560, 1920] pixel_values")
            .outputShape("[1, seq, hidden] encoder_hidden_states")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("decoder")
            .fileName("decoder_model.onnx")
            .downloadUrl("https://huggingface.co/naver-clova-ix/donut-base/resolve/main/onnx/decoder_model.onnx")
            .pipelineStage(VlmPipelineStage.AUTOREGRESSIVE_DECODING)
            .description("BART-based decoder for generating structured output")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("tokenizer")
            .fileName("tokenizer.json")
            .downloadUrl("https://huggingface.co/naver-clova-ix/donut-base/resolve/main/tokenizer.json")
            .pipelineStage(VlmPipelineStage.TEXT_TOKENIZATION)
            .build())
        .pipelineConfig("image_size", new int[]{2560, 1920})
        .pipelineConfig("max_new_tokens", 2048)
        .build();

    /**
     * SigLIP Vision Only - Vision encoder without text decoder.
     * Useful for embedding images for retrieval.
     */
    public static final VlmModelSet SIGLIP_VISION = builder()
        .setId("siglip-vision")
        .displayName("SigLIP Base Vision Encoder")
        .description("Google's SigLIP vision encoder for image embeddings")
        .huggingFaceRepo("Xenova/siglip-base-patch16-224")
        .addComponent(VlmModelComponent.builder()
            .componentKey("vision_encoder")
            .fileName("vision_model.onnx")
            .downloadUrl("https://huggingface.co/Xenova/siglip-base-patch16-224/resolve/main/onnx/vision_model.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("SigLIP vision encoder for 224x224 images")
            .inputShape("[1, 3, 224, 224] pixel_values")
            .outputShape("[1, 197, 768] image_features")
            .build())
        .pipelineConfig("image_size", 224)
        .pipelineConfig("hidden_size", 768)
        .pipelineConfig("patch_size", 16)
        .build();

    /**
     * CLIP Vision + Text - Full CLIP model for vision-language similarity.
     */
    public static final VlmModelSet CLIP_VIT_BASE = builder()
        .setId("clip-vit-base-patch32")
        .displayName("CLIP ViT Base Patch32")
        .description("OpenAI CLIP for vision-text similarity")
        .huggingFaceRepo("Xenova/clip-vit-base-patch32")
        .addComponent(VlmModelComponent.builder()
            .componentKey("model")
            .fileName("model.onnx")
            .downloadUrl("https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/model.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("Full CLIP model (vision + text encoders)")
            .inputShape("[1, 3, 224, 224] pixel_values + [1, seq] input_ids")
            .outputShape("image_embeds [1, 512] + text_embeds [1, 512]")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("tokenizer")
            .fileName("tokenizer.json")
            .downloadUrl("https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/tokenizer.json")
            .pipelineStage(VlmPipelineStage.TEXT_TOKENIZATION)
            .build())
        .pipelineConfig("image_size", 224)
        .pipelineConfig("hidden_size", 512)
        .build();

    /**
     * Docling TableFormer - Table structure recognition.
     */
    public static final VlmModelSet DOCLING_TABLEFORMER = builder()
        .setId("docling-tableformer")
        .displayName("Docling TableFormer")
        .description("Table structure recognition for document understanding")
        .huggingFaceRepo("asmud/ds4sd-docling-models-onnx")
        .addComponent(VlmModelComponent.builder()
            .componentKey("tableformer_accurate")
            .fileName("tableformer_accurate.onnx")
            .downloadUrl("https://huggingface.co/asmud/ds4sd-docling-models-onnx/resolve/main/ds4sd_docling_models_tableformer_accurate_jpqd.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("High accuracy table structure model")
            .inputShape("[1, 3, 448, 448]")
            .build())
        .addComponent(VlmModelComponent.builder()
            .componentKey("tableformer_fast")
            .fileName("tableformer_fast.onnx")
            .downloadUrl("https://huggingface.co/asmud/ds4sd-docling-models-onnx/resolve/main/ds4sd_docling_models_tableformer_fast_jpqd.onnx")
            .pipelineStage(VlmPipelineStage.VISION_ENCODING)
            .description("Fast table structure model")
            .inputShape("[1, 3, 448, 448]")
            .build())
        .pipelineConfig("image_size", 448)
        .build();

    // =====================================================================
    // REGISTRY OF ALL MODEL SETS
    // =====================================================================

    private static final Map<String, VlmModelSet> REGISTRY = new LinkedHashMap<>();

    static {
        register(SMOLDOCLING_256M);
        register(DONUT_BASE);
        register(SIGLIP_VISION);
        register(CLIP_VIT_BASE);
        register(DOCLING_TABLEFORMER);
    }

    private static void register(VlmModelSet set) {
        REGISTRY.put(set.getSetId(), set);
    }

    /**
     * Get all registered model sets.
     */
    public static Collection<VlmModelSet> getAllModelSets() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /**
     * Get a model set by ID.
     */
    public static VlmModelSet getModelSet(String setId) {
        return REGISTRY.get(setId);
    }

    /**
     * Get all available model set IDs.
     */
    public static Set<String> getAvailableModelSetIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    // =====================================================================
    // INSTANCE METHODS
    // =====================================================================

    public String getSetId() {
        return setId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getHuggingFaceRepo() {
        return huggingFaceRepo;
    }

    public List<VlmModelComponent> getComponents() {
        return components;
    }

    public Map<String, Object> getPipelineConfig() {
        return pipelineConfig;
    }

    /**
     * Get a specific component by key.
     */
    public Optional<VlmModelComponent> getComponent(String componentKey) {
        return components.stream()
            .filter(c -> c.getComponentKey().equals(componentKey))
            .findFirst();
    }

    /**
     * Get the component for a specific pipeline stage.
     */
    public Optional<VlmModelComponent> getComponentForStage(VlmPipelineStage stage) {
        return components.stream()
            .filter(c -> c.getPipelineStage() == stage)
            .findFirst();
    }

    /**
     * Get all pipeline stages that require model files in this set.
     */
    public List<VlmPipelineStage> getStagesRequiringModels() {
        return components.stream()
            .map(VlmModelComponent::getPipelineStage)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * Get pipeline configuration value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getPipelineConfigValue(String key, T defaultValue) {
        Object value = pipelineConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Get total download size estimate in bytes.
     */
    public long getEstimatedDownloadSizeBytes() {
        return components.stream()
            .mapToLong(VlmModelComponent::getEstimatedSizeBytes)
            .sum();
    }

    /**
     * Get the model architecture name.
     * Extracted from description or set ID.
     */
    public String getArchitecture() {
        // Try to extract architecture from description or set ID
        if (description != null && description.contains("SigLIP")) {
            return "SigLIP";
        } else if (description != null && description.contains("CLIP")) {
            return "CLIP";
        } else if (description != null && description.contains("Donut")) {
            return "Donut";
        } else if (description != null && description.contains("TableFormer")) {
            return "TableFormer";
        } else if (setId != null && setId.contains("smoldocling")) {
            return "SmolDocling";
        }
        return "VLM";
    }

    /**
     * Get the input image size.
     */
    public int getInputSize() {
        Object size = pipelineConfig.get("image_size");
        if (size instanceof Integer) {
            return (Integer) size;
        } else if (size instanceof int[]) {
            int[] arr = (int[]) size;
            return arr.length > 0 ? arr[0] : 512;
        }
        return 512; // default
    }

    /**
     * Get the hidden dimension size.
     */
    public int getHiddenSize() {
        Object size = pipelineConfig.get("hidden_size");
        if (size instanceof Integer) {
            return (Integer) size;
        }
        return 768; // default
    }

    /**
     * Print a human-readable summary of this model set.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Model Set: ").append(displayName).append(" (").append(setId).append(")\n");
        sb.append("Description: ").append(description).append("\n");
        sb.append("HuggingFace: ").append(huggingFaceRepo).append("\n\n");
        sb.append("Components:\n");

        for (VlmModelComponent comp : components) {
            sb.append("  - ").append(comp.getComponentKey())
              .append(" (").append(comp.getFileName()).append(")\n");
            sb.append("    Stage: ").append(comp.getPipelineStage() != null ?
                comp.getPipelineStage().getDisplayName() : "N/A").append("\n");
            if (comp.getDescription() != null) {
                sb.append("    Desc: ").append(comp.getDescription()).append("\n");
            }
            if (comp.getInputShape() != null) {
                sb.append("    Input: ").append(comp.getInputShape()).append("\n");
            }
            if (comp.getOutputShape() != null) {
                sb.append("    Output: ").append(comp.getOutputShape()).append("\n");
            }
        }

        sb.append("\nPipeline Config:\n");
        for (Map.Entry<String, Object> entry : pipelineConfig.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof double[]) {
                sb.append(Arrays.toString((double[]) entry.getValue()));
            } else if (entry.getValue() instanceof int[]) {
                sb.append(Arrays.toString((int[]) entry.getValue()));
            } else {
                sb.append(entry.getValue());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "VlmModelSet{" + setId + ", components=" + components.size() + "}";
    }

    // =====================================================================
    // BUILDER
    // =====================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String setId;
        private String displayName;
        private String description;
        private String huggingFaceRepo;
        private final List<VlmModelComponent> components = new ArrayList<>();
        private final Map<String, Object> pipelineConfig = new LinkedHashMap<>();

        public Builder setId(String setId) {
            this.setId = setId;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder huggingFaceRepo(String huggingFaceRepo) {
            this.huggingFaceRepo = huggingFaceRepo;
            return this;
        }

        public Builder addComponent(VlmModelComponent component) {
            this.components.add(component);
            return this;
        }

        public Builder pipelineConfig(String key, Object value) {
            this.pipelineConfig.put(key, value);
            return this;
        }

        public VlmModelSet build() {
            Objects.requireNonNull(setId, "setId is required");
            Objects.requireNonNull(displayName, "displayName is required");
            return new VlmModelSet(this);
        }
    }
}
