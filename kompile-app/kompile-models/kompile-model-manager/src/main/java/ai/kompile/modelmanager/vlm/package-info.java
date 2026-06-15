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

/**
 * VLM (Vision-Language Model) management for Kompile.
 *
 * <h2>Quick Start: Integration with Document Chunking</h2>
 *
 * <pre>{@code
 * // 1. Ensure models are downloaded before processing
 * VlmExtractionConfig config = VlmModels.configForScannedDocuments();
 * List<String> failures = VlmModels.ensureModelsForConfig(config);
 *
 * // 2. Resolve model paths for the processing pipeline
 * Map<VlmExtractionType, VlmModelResolver.ResolvedModel> models =
 *     VlmModels.resolveForConfig(config);
 *
 * // 3. Convert to PdfProcessingConfig for document loading
 * Map<String, Object> pdfConfigMap = VlmProcessingConfigBridge.toPdfProcessingConfigMap(config);
 * PdfProcessingConfig pdfConfig = PdfProcessingConfig.fromMap(pdfConfigMap);
 *
 * // 4. Load and chunk documents with VLM-enhanced extraction
 * List<Document> docs = documentLoader.load(source, pdfConfig);
 * List<Document> chunks = chunkingService.chunk(docs, "recursive-character");
 * }</pre>
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Model Sets</h3>
 * <p>VLMs consist of multiple components that work together. A {@link ai.kompile.modelmanager.vlm.VlmModelSet}
 * groups all components needed for a specific architecture, enabling batch download.</p>
 *
 * <h3>Extraction Types</h3>
 * <p>Each {@link ai.kompile.modelmanager.vlm.VlmExtractionType} represents a specific content extraction
 * task (document understanding, table extraction, figure understanding) with its default model.</p>
 *
 * <h3>Pipeline Stages</h3>
 * <p>Each {@link ai.kompile.modelmanager.vlm.VlmPipelineStage} documents a transformation step with
 * clear input/output contracts for transparency.</p>
 *
 * <h3>Components</h3>
 * <p>A {@link ai.kompile.modelmanager.vlm.VlmModelComponent} represents a single model file
 * (ONNX, tokenizer JSON, etc.) with its pipeline role documented.</p>
 *
 * <h2>SmolDocling Pipeline Example</h2>
 *
 * The SmolDocling-256M model converts document images to structured text (DocTags, Markdown, JSON).
 *
 * <pre>
 * ┌───────────────────────────────────────────────────────────────────────────┐
 * │                     SMOLDOCLING PIPELINE FLOW                             │
 * ├───────────────────────────────────────────────────────────────────────────┤
 * │                                                                           │
 * │  PDF Page (BufferedImage)                                                 │
 * │       │                                                                   │
 * │       ▼                                                                   │
 * │  ┌─────────────────┐                                                     │
 * │  │ IMAGE_TILING    │  Split into 512x512 tiles + global thumbnail        │
 * │  │ (algorithmic)   │  → List&lt;BufferedImage&gt; [N+1 frames]                 │
 * │  └────────┬────────┘                                                     │
 * │           │                                                               │
 * │           ▼                                                               │
 * │  ┌─────────────────┐                                                     │
 * │  │ NORMALIZATION   │  Rescale 0-255→0-1, normalize with mean/std         │
 * │  │ (algorithmic)   │  → INDArray [1, N+1, 3, 512, 512]                   │
 * │  └────────┬────────┘                                                     │
 * │           │                                                               │
 * │           ▼                                                               │
 * │  ┌─────────────────┐                                                     │
 * │  │ VISION_ENCODER  │  Model: vision_encoder.onnx                         │
 * │  │ (ONNX model)    │  Input:  pixel_values [1,1,3,512,512]               │
 * │  │                 │  Output: image_features [1, 64, 576] per frame      │
 * │  └────────┬────────┘                                                     │
 * │           │                                                               │
 * │           │              ┌─────────────────────────────────────────────┐ │
 * │           │              │ "Convert this page to docling."             │ │
 * │           │              │       + &lt;image&gt; tokens                      │ │
 * │           │              └──────────────────┬──────────────────────────┘ │
 * │           │                                 │                            │
 * │           │                                 ▼                            │
 * │           │              ┌─────────────────┐                             │
 * │           │              │ TOKENIZER       │  Model: tokenizer.json      │
 * │           │              │ (JSON config)   │  → int[] token_ids          │
 * │           │              └────────┬────────┘                             │
 * │           │                       │                                      │
 * │           │                       ▼                                      │
 * │           │              ┌─────────────────┐                             │
 * │           │              │ EMBED_TOKENS    │  Model: embed_tokens.onnx   │
 * │           │              │ (ONNX model)    │  Input:  [1, seq] int64     │
 * │           │              │                 │  Output: [1, seq, 576]      │
 * │           │              └────────┬────────┘                             │
 * │           │                       │                                      │
 * │           ▼                       ▼                                      │
 * │  ┌───────────────────────────────────────┐                               │
 * │  │         EMBEDDING FUSION              │                               │
 * │  │         (algorithmic)                 │                               │
 * │  │                                       │                               │
 * │  │  Replace &lt;image&gt; positions in text    │                               │
 * │  │  embeddings with vision embeddings    │                               │
 * │  │                                       │                               │
 * │  │  → inputs_embeds [1, total_seq, 576]  │                               │
 * │  └───────────────────┬───────────────────┘                               │
 * │                      │                                                   │
 * │                      ▼                                                   │
 * │  ┌─────────────────────────────────────────────────────────────────────┐│
 * │  │                      AUTOREGRESSIVE LOOP                            ││
 * │  │                                                                     ││
 * │  │  ┌─────────────────┐                                                ││
 * │  │  │ DECODER         │  Model: decoder_model_merged.onnx              ││
 * │  │  │ (ONNX model)    │  Input:  inputs_embeds + mask + pos_ids + KV   ││
 * │  │  │                 │  Output: logits [1, 1, 151936] + KV cache      ││
 * │  │  └────────┬────────┘                                                ││
 * │  │           │                                                         ││
 * │  │           ▼                                                         ││
 * │  │  ┌─────────────────┐                                                ││
 * │  │  │ SAMPLER         │  Strategy: greedy/top-k/top-p/temperature      ││
 * │  │  │ (algorithmic)   │  → next_token_id (int)                         ││
 * │  │  └────────┬────────┘                                                ││
 * │  │           │                                                         ││
 * │  │           ├──────────────────────────────────────────────────────┐  ││
 * │  │           │ if token != EOS, embed new token and loop            │  ││
 * │  │           └──────────────────────────────────────────────────────┘  ││
 * │  │                                                                     ││
 * │  └─────────────────────────────────────────────────────────────────────┘│
 * │                      │                                                   │
 * │                      ▼                                                   │
 * │  ┌─────────────────┐                                                    │
 * │  │ TOKENIZER       │  Decode token IDs to text                          │
 * │  │ (decode)        │  → DocTags / Markdown / JSON string                │
 * │  └─────────────────┘                                                    │
 * │                                                                          │
 * └───────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Model Set Download</h2>
 *
 * Download all components at once:
 * <pre>{@code
 * VlmModelSetDownloader downloader = new VlmModelSetDownloader();
 *
 * // Download SmolDocling (5 components)
 * DownloadSetResult result = downloader.downloadModelSet(VlmModelSet.SMOLDOCLING_256M);
 *
 * // Access individual model paths
 * Path visionEncoder = result.getVisionEncoderPath().get();
 * Path decoder = result.getDecoderPath().get();
 * Path embedTokens = result.getEmbedTokensPath().get();
 * Path tokenizer = result.getTokenizerPath().get();
 * }</pre>
 *
 * <h2>Available Model Sets</h2>
 * <ul>
 *   <li>{@link ai.kompile.modelmanager.vlm.VlmModelSet#SMOLDOCLING_256M} - Document understanding (DocTags)</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.VlmModelSet#DONUT_BASE} - Document understanding (Swin+BART)</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.VlmModelSet#SIGLIP_VISION} - Vision-only encoder</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.VlmModelSet#CLIP_VIT_BASE} - Vision-text similarity</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.VlmModelSet#DOCLING_TABLEFORMER} - Table structure</li>
 * </ul>
 *
 * <h2>Pipeline Stage Documentation</h2>
 *
 * Each stage documents:
 * <ul>
 *   <li><b>Input format</b>: Shape and type of input tensors</li>
 *   <li><b>Output format</b>: Shape and type of output tensors</li>
 *   <li><b>Model requirement</b>: Which model file (if any) is needed</li>
 * </ul>
 *
 * This transparency helps understand what happens at each handoff point:
 * <pre>{@code
 * for (VlmPipelineStage stage : VlmPipelineStage.values()) {
 *     System.out.println(stage.getDisplayName());
 *     System.out.println("  Input:  " + stage.getInputDescription());
 *     System.out.println("  Output: " + stage.getOutputDescription());
 *     System.out.println("  Model:  " + (stage.getModelComponentKey() != null ?
 *         stage.getModelComponentKey() : "algorithmic"));
 * }
 * }</pre>
 *
 * @author Kompile Inc.
 * @see ai.kompile.modelmanager.vlm.VlmModelSet
 * @see ai.kompile.modelmanager.vlm.VlmPipelineStage
 * @see ai.kompile.modelmanager.vlm.VlmModelSetDownloader
 */
package ai.kompile.modelmanager.vlm;
