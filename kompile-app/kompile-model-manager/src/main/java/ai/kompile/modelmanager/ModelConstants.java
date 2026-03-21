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

package ai.kompile.modelmanager;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Constants and descriptors for commonly used models.
 */
public class ModelConstants {

    // OpenNLP Constants (existing code remains unchanged)
    public static final String OPENNLP_MODEL_BASE_URL = "https://dlcdn.apache.org/opennlp/models/ud-models-1.2/";
    public static final String DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR = ".kompile/models";
    public static final String ENV_KOMPILE_MODEL_CACHE_DIR = "KOMPILE_MODEL_CACHE_DIR";
    private static final Map<String, String> LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME_MAP;
    private static final Map<String, String> LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME_MAP;

    static {
        Map<String, String> remoteFilenames = new LinkedHashMap<>();
        remoteFilenames.put("bg", "opennlp-bg-ud-btb-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("ca", "opennlp-ca-ud-ancora-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("cs", "opennlp-cs-ud-pdt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("da", "opennlp-da-ud-ddt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("de", "opennlp-de-ud-gsd-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("el", "opennlp-el-ud-gdt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("en", "opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("es", "opennlp-es-ud-gsd-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("et", "opennlp-et-ud-edt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("eu", "opennlp-eu-ud-bdt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("fi", "opennlp-fi-ud-tdt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("fr", "opennlp-fr-ud-gsd-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("hr", "opennlp-hr-ud-set-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("hy", "opennlp-hy-ud-bsut-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("is", "opennlp-is-ud-icepahc-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("it", "opennlp-it-ud-vit-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("ka", "opennlp-ka-ud-glc-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("kk", "opennlp-kk-ud-ktb-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("ko", "opennlp-ko-ud-kaist-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("lv", "opennlp-lv-ud-lvtb-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("nl", "opennlp-nl-ud-alpino-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("no", "opennlp-no-ud-bokmaal-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("pl", "opennlp-pl-ud-pdb-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("pt", "opennlp-pt-ud-gsd-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("ro", "opennlp-ro-ud-rrt-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("ru", "opennlp-ru-ud-gsd-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("sk", "opennlp-sk-ud-snk-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("sl", "opennlp-sl-ud-ssj-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("sr", "opennlp-sr-ud-set-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("sv", "opennlp-sv-ud-talbanken-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("tr", "opennlp-tr-ud-boun-sentence-1.2-2.5.0.bin");
        remoteFilenames.put("uk", "opennlp-uk-ud-iu-sentence-1.2-2.5.0.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME_MAP = Collections.unmodifiableMap(remoteFilenames);

        Map<String, String> localFilenames = new LinkedHashMap<>();
        localFilenames.put("bg", "bg-sent.bin");
        localFilenames.put("ca", "ca-sent.bin");
        localFilenames.put("cs", "cs-sent.bin");
        localFilenames.put("da", "da-sent.bin");
        localFilenames.put("de", "de-sent.bin");
        localFilenames.put("el", "el-sent.bin");
        localFilenames.put("en", "en-sent.bin");
        localFilenames.put("es", "es-sent.bin");
        localFilenames.put("et", "et-sent.bin");
        localFilenames.put("eu", "eu-sent.bin");
        localFilenames.put("fi", "fi-sent.bin");
        localFilenames.put("fr", "fr-sent.bin");
        localFilenames.put("hr", "hr-sent.bin");
        localFilenames.put("hy", "hy-sent.bin");
        localFilenames.put("is", "is-sent.bin");
        localFilenames.put("it", "it-sent.bin");
        localFilenames.put("ka", "ka-sent.bin");
        localFilenames.put("kk", "kk-sent.bin");
        localFilenames.put("ko", "ko-sent.bin");
        localFilenames.put("lv", "lv-sent.bin");
        localFilenames.put("nl", "nl-sent.bin");
        localFilenames.put("no", "no-sent.bin");
        localFilenames.put("pl", "pl-sent.bin");
        localFilenames.put("pt", "pt-sent.bin");
        localFilenames.put("ro", "ro-sent.bin");
        localFilenames.put("ru", "ru-sent.bin");
        localFilenames.put("sk", "sk-sent.bin");
        localFilenames.put("sl", "sl-sent.bin");
        localFilenames.put("sr", "sr-sent.bin");
        localFilenames.put("sv", "sv-sent.bin");
        localFilenames.put("tr", "tr-sent.bin");
        localFilenames.put("uk", "uk-sent.bin");
        LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME_MAP = Collections.unmodifiableMap(localFilenames);
    }

    public static String getOpenNLPModelRemoteFilename(String langCode) {
        return LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_REMOTE_FILENAME_MAP.get(langCode);
    }

    public static String getOpenNLPModelLocalFilename(String langCode) {
        return LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME_MAP.get(langCode);
    }

    public static boolean isOpenNLPLanguageSupported(String langCode) {
        return LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME_MAP.containsKey(langCode);
    }

    /**
     * Get all supported OpenNLP sentence detection language codes.
     * @return Set of supported language codes
     */
    public static Set<String> getSupportedOpenNLPLanguages() {
        return Collections.unmodifiableSet(LANGUAGE_TO_OPENNLP_SENTENCE_MODEL_LOCAL_FILENAME_MAP.keySet());
    }

    /**
     * Create a ModelDescriptor for an OpenNLP sentence detection model.
     * @param languageCode Language code (e.g., "en", "de", "fr")
     * @return ModelDescriptor for the OpenNLP sentence model
     * @throws IllegalArgumentException if the language is not supported
     */
    public static ModelDescriptor createOpenNLPSentenceModelDescriptor(String languageCode) {
        if (!isOpenNLPLanguageSupported(languageCode)) {
            throw new IllegalArgumentException("Unsupported language code for OpenNLP sentence detection: " + languageCode);
        }

        String remoteFilename = getOpenNLPModelRemoteFilename(languageCode);
        String localFilename = getOpenNLPModelLocalFilename(languageCode);
        String downloadUrl = OPENNLP_MODEL_BASE_URL + remoteFilename;
        String cacheSubpath = "opennlp/sentence/" + localFilename;

        return new ModelDescriptor(
                "opennlp-sentence-" + languageCode,
                ModelType.OPENNLP_SENTENCE,
                downloadUrl,
                cacheSubpath,
                "1.2-2.5.0", // Version from the URL pattern
                null, // No checksum provided in constants
                Map.of(
                        "language", languageCode,
                        "description", "OpenNLP sentence detection model for " + languageCode,
                        "framework", "opennlp"
                )
        );
    }

    // Anserini Prebuilt Lucene Indexes (existing code remains unchanged)
    private static final Map<String, ModelDescriptor> ANSERINI_INDEX_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> anseriniIndexes = new HashMap<>();
        anseriniIndexes.put("msmarco-passage-v1", new ModelDescriptor(
                "anserini_index_msmarco-passage-v1",
                ModelType.ANSERINI_INDEX,
                "https://rgw.cs.uwaterloo.ca/pyserini/data/index-msmarco-passage-20201117-f87c94.tar.gz",
                "anserini/indexes/msmarco-passage-v1",
                "20201117-f87c94", null,
                Map.of("description", "Anserini prebuilt index for MS MARCO V1 passages (BM25, default Lucene)")
        ));
        anseriniIndexes.put("msmarco-doc-v1", new ModelDescriptor(
                "anserini_index_msmarco-doc-v1",
                ModelType.ANSERINI_INDEX,
                "https://rgw.cs.uwaterloo.ca/pyserini/data/index-msmarco-doc-20201117-f87c94.tar.gz",
                "anserini/indexes/msmarco-doc-v1",
                "20201117-f87c94", null,
                Map.of("description", "Anserini prebuilt index for MS MARCO V1 documents (BM25, default Lucene)")
        ));
        ANSERINI_INDEX_DESCRIPTORS = Collections.unmodifiableMap(anseriniIndexes);
    }

    public static ModelDescriptor getAnseriniIndexDescriptor(String indexId) {
        return ANSERINI_INDEX_DESCRIPTORS.get(indexId);
    }

    // Anserini Encoder Models - UPDATED WITH NEW MODELS AND TOKENIZER METADATA
    private static final String KOMPILE_MODEL_BASE_URL = "https://github.com/GetKompile/kompile/releases/download/opennlp/";
    private static final Map<String, ModelDescriptor> ANSERINI_ENCODER_MODEL_DESCRIPTORS;
    private static final Map<String, ModelDescriptor> ANSERINI_ENCODER_VOCAB_DESCRIPTORS;

    static {
        Map<String, ModelDescriptor> encoderModels = new HashMap<>();
        Map<String, ModelDescriptor> vocabModels = new HashMap<>();

        // BGE Base English v1.5 - Based on BERT-style tokenization with 512 max length
        encoderModels.put("bge-base-en-v1.5", new ModelDescriptor(
                "bge-base-en-v1.5",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "bge-base-en-v1.5.sdz",
                "anserini/encoders/bge-base-en-v1.5/bge-base-en-v1.5.sdz",
                "v1.5", null,
                Map.of(
                        "description", "BGE Base English v1.5 SameDiff model",
                        "framework", "samediff",
                        "model_type", "dense",
                        "embedding_dim", 768,
                        "tokenizer_do_lower_case", true,
                        "tokenizer_add_special_tokens", true,
                        "tokenizer_max_sequence_length", 512,
                        "tokenizer_strip_accents", true
                )
        ));

        vocabModels.put("bge-base-en-v1.5", new ModelDescriptor(
                "bge-base-en-v1.5-vocab",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "bge-base-en-v1.5-vocab.txt",
                "anserini/encoders/bge-base-en-v1.5/vocab.txt",
                "v1.5", null,
                Map.of("description", "BGE Base English v1.5 vocabulary file")
        ));

        // Arctic Embed Large - Based on XLM-RoBERTa with 8192 max length support
        encoderModels.put("arctic-embed-l", new ModelDescriptor(
                "arctic-embed-l",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "arctic-embed-l.sdz",
                "anserini/encoders/arctic-embed-l/arctic-embed-l.sdz",
                "latest", null,
                Map.of(
                        "description", "Arctic Embed Large SameDiff model",
                        "framework", "samediff",
                        "model_type", "dense",
                        "embedding_dim", 1024,
                        "tokenizer_do_lower_case", false, // XLM-RoBERTa typically doesn't lowercase
                        "tokenizer_add_special_tokens", true,
                        "tokenizer_max_sequence_length", 8192, // Arctic supports 8k context
                        "tokenizer_strip_accents", false // RoBERTa-style models typically don't strip accents
                )
        ));

        vocabModels.put("arctic-embed-l", new ModelDescriptor(
                "arctic-embed-l-vocab",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "arctic-embed-l-vocab.txt",
                "anserini/encoders/arctic-embed-l/vocab.txt",
                "latest", null,
                Map.of("description", "Arctic Embed Large vocabulary file")
        ));

        // CosDPR Distilled - Based on DPR architecture with BERT-style tokenization
        encoderModels.put("cosdpr-distil", new ModelDescriptor(
                "cosdpr-distil",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "cosdpr-distil.sdz",
                "anserini/encoders/cosdpr-distil/cosdpr-distil.sdz",
                "latest", null,
                Map.of(
                        "description", "CosDPR Distilled SameDiff model",
                        "framework", "samediff",
                        "model_type", "dense",
                        "embedding_dim", 768,
                        "tokenizer_do_lower_case", true, // DPR typically uses BERT-style tokenization
                        "tokenizer_add_special_tokens", true,
                        "tokenizer_max_sequence_length", 512,
                        "tokenizer_strip_accents", true
                )
        ));

        vocabModels.put("cosdpr-distil", new ModelDescriptor(
                "cosdpr-distil-vocab",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "cosdpr-distil-vocab.txt",
                "anserini/encoders/cosdpr-distil/vocab.txt",
                "latest", null,
                Map.of("description", "CosDPR Distilled vocabulary file")
        ));

        // SPLADE++ EfficientDistil - Based on BERT with MLM head, uses BERT vocabulary (30522 tokens)
        encoderModels.put("splade-pp-ed", new ModelDescriptor(
                "splade-pp-ed",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "splade-pp-ed.sdz",
                "anserini/encoders/splade-pp-ed/splade-pp-ed.sdz",
                "latest", null,
                Map.of(
                        "description", "SPLADE++ EfficientDistil SameDiff model",
                        "framework", "samediff",
                        "model_type", "sparse",
                        "embedding_dim", 30522, // BERT vocabulary size
                        "tokenizer_do_lower_case", true, // SPLADE typically uses BERT-style tokenization
                        "tokenizer_add_special_tokens", true,
                        "tokenizer_max_sequence_length", 512, // Standard BERT max length
                        "tokenizer_strip_accents", true
                )
        ));

        vocabModels.put("splade-pp-ed", new ModelDescriptor(
                "splade-pp-ed-vocab",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "splade-pp-ed-vocab.txt",
                "anserini/encoders/splade-pp-ed/vocab.txt",
                "latest", null,
                Map.of("description", "SPLADE++ EfficientDistil vocabulary file")
        ));

        // SPLADE++ SelfDistil - Same as EfficientDistil but self-distilled variant
        encoderModels.put("splade-pp-sd", new ModelDescriptor(
                "splade-pp-sd",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "splade-pp-sd.sdz",
                "anserini/encoders/splade-pp-sd/splade-pp-sd.sdz",
                "latest", null,
                Map.of(
                        "description", "SPLADE++ SelfDistil SameDiff model",
                        "framework", "samediff",
                        "model_type", "sparse",
                        "embedding_dim", 30522, // BERT vocabulary size
                        "tokenizer_do_lower_case", true, // SPLADE typically uses BERT-style tokenization
                        "tokenizer_add_special_tokens", true,
                        "tokenizer_max_sequence_length", 512, // Standard BERT max length
                        "tokenizer_strip_accents", true
                )
        ));

        vocabModels.put("splade-pp-sd", new ModelDescriptor(
                "splade-pp-sd-vocab",
                ModelType.ANSERINI_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "splade-pp-sd-vocab.txt",
                "anserini/encoders/splade-pp-sd/vocab.txt",
                "latest", null,
                Map.of("description", "SPLADE++ SelfDistil vocabulary file")
        ));

        ANSERINI_ENCODER_MODEL_DESCRIPTORS = Collections.unmodifiableMap(encoderModels);
        ANSERINI_ENCODER_VOCAB_DESCRIPTORS = Collections.unmodifiableMap(vocabModels);
    }

    // Cross-Encoder Reranking Models - SameDiff format (.sdz)
    // These models are converted from HuggingFace PyTorch models to SameDiff format.
    //
    // Source URLs for model conversion:
    // - ms-marco-MiniLM-L-6-v2:      https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2
    // - ms-marco-MiniLM-L-12-v2:     https://huggingface.co/cross-encoder/ms-marco-MiniLM-L12-v2
    // - stsb-TinyBERT-L-4:           https://huggingface.co/cross-encoder/stsb-TinyBERT-L-4
    // - mmarco-mMiniLMv2-L12-H384-v1: https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1
    // - qnli-distilroberta-base:     https://huggingface.co/cross-encoder/qnli-distilroberta-base
    //
    // ONNX versions (for reference, Xenova conversions):
    // - https://huggingface.co/Xenova/ms-marco-MiniLM-L-6-v2
    // - https://huggingface.co/Xenova/ms-marco-MiniLM-L-12-v2
    //
    private static final Map<String, ModelDescriptor> CROSS_ENCODER_MODEL_DESCRIPTORS;
    private static final Map<String, ModelDescriptor> CROSS_ENCODER_VOCAB_DESCRIPTORS;

    static {
        Map<String, ModelDescriptor> crossEncoderModels = new HashMap<>();
        Map<String, ModelDescriptor> crossEncoderVocabs = new HashMap<>();

        // MS MARCO MiniLM L-6 - Lightweight, fast cross-encoder for passage reranking
        // Source: https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2
        crossEncoderModels.put("ms-marco-MiniLM-L-6-v2", new ModelDescriptor(
                "ms-marco-MiniLM-L-6-v2",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "ms-marco-MiniLM-L-6-v2.sdz",
                "anserini/cross-encoders/ms-marco-MiniLM-L-6-v2/ms-marco-MiniLM-L-6-v2.sdz",
                "v2", null,
                Map.ofEntries(
                        Map.entry("description", "MS MARCO MiniLM L-6 v2 Cross-Encoder for passage reranking"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_type", "cross_encoder"),
                        Map.entry("hidden_size", 384),
                        Map.entry("num_layers", 6),
                        Map.entry("max_sequence_length", 512),
                        Map.entry("embedding_dim", 384),
                        Map.entry("tokenizer_do_lower_case", true),
                        Map.entry("tokenizer_add_special_tokens", true),
                        Map.entry("tokenizer_max_sequence_length", 512),
                        Map.entry("tokenizer_strip_accents", true),
                        Map.entry("input_format", "query [SEP] document"),
                        Map.entry("output_type", "relevance_score"),
                        Map.entry("training_data", "ms-marco-passage"),
                        Map.entry("huggingface_source", "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2")
                )
        ));
        crossEncoderVocabs.put("ms-marco-MiniLM-L-6-v2", new ModelDescriptor(
                "ms-marco-MiniLM-L-6-v2-vocab",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "ms-marco-MiniLM-L-6-v2-vocab.txt",
                "anserini/cross-encoders/ms-marco-MiniLM-L-6-v2/vocab.txt",
                "v2", null,
                Map.of("description", "MS MARCO MiniLM L-6 v2 vocabulary file")
        ));

        // MS MARCO MiniLM L-12 - Higher quality, slightly slower
        // Source: https://huggingface.co/cross-encoder/ms-marco-MiniLM-L12-v2
        crossEncoderModels.put("ms-marco-MiniLM-L-12-v2", new ModelDescriptor(
                "ms-marco-MiniLM-L-12-v2",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "ms-marco-MiniLM-L-12-v2.sdz",
                "anserini/cross-encoders/ms-marco-MiniLM-L-12-v2/ms-marco-MiniLM-L-12-v2.sdz",
                "v2", null,
                Map.ofEntries(
                        Map.entry("description", "MS MARCO MiniLM L-12 v2 Cross-Encoder for passage reranking"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_type", "cross_encoder"),
                        Map.entry("hidden_size", 384),
                        Map.entry("num_layers", 12),
                        Map.entry("max_sequence_length", 512),
                        Map.entry("embedding_dim", 384),
                        Map.entry("tokenizer_do_lower_case", true),
                        Map.entry("tokenizer_add_special_tokens", true),
                        Map.entry("tokenizer_max_sequence_length", 512),
                        Map.entry("tokenizer_strip_accents", true),
                        Map.entry("input_format", "query [SEP] document"),
                        Map.entry("output_type", "relevance_score"),
                        Map.entry("training_data", "ms-marco-passage"),
                        Map.entry("huggingface_source", "https://huggingface.co/cross-encoder/ms-marco-MiniLM-L12-v2")
                )
        ));
        crossEncoderVocabs.put("ms-marco-MiniLM-L-12-v2", new ModelDescriptor(
                "ms-marco-MiniLM-L-12-v2-vocab",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "ms-marco-MiniLM-L-12-v2-vocab.txt",
                "anserini/cross-encoders/ms-marco-MiniLM-L-12-v2/vocab.txt",
                "v2", null,
                Map.of("description", "MS MARCO MiniLM L-12 v2 vocabulary file")
        ));

        // TinyBERT for STS-B - Very lightweight for semantic similarity
        // Source: https://huggingface.co/cross-encoder/stsb-TinyBERT-L-4
        crossEncoderModels.put("stsb-TinyBERT-L-4", new ModelDescriptor(
                "stsb-TinyBERT-L-4",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "stsb-TinyBERT-L-4.sdz",
                "anserini/cross-encoders/stsb-TinyBERT-L-4/stsb-TinyBERT-L-4.sdz",
                "latest", null,
                Map.ofEntries(
                        Map.entry("description", "TinyBERT L-4 Cross-Encoder for semantic text similarity"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_type", "cross_encoder"),
                        Map.entry("hidden_size", 312),
                        Map.entry("num_layers", 4),
                        Map.entry("max_sequence_length", 512),
                        Map.entry("embedding_dim", 312),
                        Map.entry("tokenizer_do_lower_case", true),
                        Map.entry("tokenizer_add_special_tokens", true),
                        Map.entry("tokenizer_max_sequence_length", 512),
                        Map.entry("tokenizer_strip_accents", true),
                        Map.entry("input_format", "sentence1 [SEP] sentence2"),
                        Map.entry("output_type", "similarity_score"),
                        Map.entry("training_data", "stsb"),
                        Map.entry("huggingface_source", "https://huggingface.co/cross-encoder/stsb-TinyBERT-L-4")
                )
        ));
        crossEncoderVocabs.put("stsb-TinyBERT-L-4", new ModelDescriptor(
                "stsb-TinyBERT-L-4-vocab",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "stsb-TinyBERT-L-4-vocab.txt",
                "anserini/cross-encoders/stsb-TinyBERT-L-4/vocab.txt",
                "latest", null,
                Map.of("description", "TinyBERT L-4 vocabulary file")
        ));

        // Multilingual cross-encoder for mMARCO
        // Source: https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1
        crossEncoderModels.put("mmarco-mMiniLMv2-L12-H384-v1", new ModelDescriptor(
                "mmarco-mMiniLMv2-L12-H384-v1",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "mmarco-mMiniLMv2-L12-H384-v1.sdz",
                "anserini/cross-encoders/mmarco-mMiniLMv2-L12-H384-v1/mmarco-mMiniLMv2-L12-H384-v1.sdz",
                "v1", null,
                Map.ofEntries(
                        Map.entry("description", "Multilingual MiniLM Cross-Encoder for mMARCO passage reranking"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_type", "cross_encoder"),
                        Map.entry("hidden_size", 384),
                        Map.entry("num_layers", 12),
                        Map.entry("max_sequence_length", 512),
                        Map.entry("embedding_dim", 384),
                        Map.entry("tokenizer_do_lower_case", false),
                        Map.entry("tokenizer_add_special_tokens", true),
                        Map.entry("tokenizer_max_sequence_length", 512),
                        Map.entry("tokenizer_strip_accents", false),
                        Map.entry("input_format", "query [SEP] document"),
                        Map.entry("output_type", "relevance_score"),
                        Map.entry("training_data", "mmarco"),
                        Map.entry("languages", "multilingual"),
                        Map.entry("huggingface_source", "https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1")
                )
        ));
        crossEncoderVocabs.put("mmarco-mMiniLMv2-L12-H384-v1", new ModelDescriptor(
                "mmarco-mMiniLMv2-L12-H384-v1-vocab",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "mmarco-mMiniLMv2-L12-H384-v1-vocab.txt",
                "anserini/cross-encoders/mmarco-mMiniLMv2-L12-H384-v1/vocab.txt",
                "v1", null,
                Map.of("description", "Multilingual MiniLM vocabulary file")
        ));

        // QNLI DistilRoBERTa - For question-answer relevance
        // Source: https://huggingface.co/cross-encoder/qnli-distilroberta-base
        // Note: RoBERTa uses BPE tokenization with merges.txt, not WordPiece with vocab.txt
        crossEncoderModels.put("qnli-distilroberta-base", new ModelDescriptor(
                "qnli-distilroberta-base",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "qnli-distilroberta-base.sdz",
                "anserini/cross-encoders/qnli-distilroberta-base/qnli-distilroberta-base.sdz",
                "latest", null,
                Map.ofEntries(
                        Map.entry("description", "DistilRoBERTa Cross-Encoder for Question-NLI"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_type", "cross_encoder"),
                        Map.entry("hidden_size", 768),
                        Map.entry("num_layers", 6),
                        Map.entry("max_sequence_length", 512),
                        Map.entry("embedding_dim", 768),
                        Map.entry("tokenizer_do_lower_case", false),
                        Map.entry("tokenizer_add_special_tokens", true),
                        Map.entry("tokenizer_max_sequence_length", 512),
                        Map.entry("tokenizer_strip_accents", false),
                        Map.entry("tokenizer_type", "roberta"), // Uses BPE, not WordPiece
                        Map.entry("input_format", "question [SEP] answer"),
                        Map.entry("output_type", "entailment_score"),
                        Map.entry("training_data", "qnli"),
                        Map.entry("huggingface_source", "https://huggingface.co/cross-encoder/qnli-distilroberta-base")
                )
        ));
        crossEncoderVocabs.put("qnli-distilroberta-base", new ModelDescriptor(
                "qnli-distilroberta-base-vocab",
                ModelType.CROSS_ENCODER_MODEL,
                KOMPILE_MODEL_BASE_URL + "qnli-distilroberta-base-vocab.json",
                "anserini/cross-encoders/qnli-distilroberta-base/vocab.json",
                "latest", null,
                Map.of("description", "DistilRoBERTa vocabulary file (BPE)")
        ));

        CROSS_ENCODER_MODEL_DESCRIPTORS = Collections.unmodifiableMap(crossEncoderModels);
        CROSS_ENCODER_VOCAB_DESCRIPTORS = Collections.unmodifiableMap(crossEncoderVocabs);
    }

    public static ModelDescriptor getAnseriniEncoderModelDescriptor(String modelId) {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.get(modelId);
    }

    public static ModelDescriptor getAnseriniEncoderVocabDescriptor(String modelId) {
        return ANSERINI_ENCODER_VOCAB_DESCRIPTORS.get(modelId);
    }

    public static Set<String> getAvailableEncoderModelIds() {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.keySet();
    }

    public static boolean isEncoderModelAvailable(String modelId) {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.containsKey(modelId);
    }

    public static String getModelType(String modelId) {
        ModelDescriptor descriptor = getAnseriniEncoderModelDescriptor(modelId);
        return descriptor != null ? descriptor.getMetadataString("model_type") : "unknown";
    }

    public static Integer getEmbeddingDimension(String modelId) {
        ModelDescriptor descriptor = getAnseriniEncoderModelDescriptor(modelId);
        if (descriptor != null) {
            Object dim = descriptor.getMetadata().get("embedding_dim");
            return dim instanceof Integer ? (Integer) dim : null;
        }
        return null;
    }

    /**
     * Get tokenizer configuration for a specific model.
     * @param modelId The model identifier
     * @return TokenizerConfig for the model, or default config if model not found
     */
    public static TokenizerConfig getTokenizerConfig(String modelId) {
        ModelDescriptor descriptor = getAnseriniEncoderModelDescriptor(modelId);
        if (descriptor != null) {
            return TokenizerConfig.fromMetadata(descriptor.getMetadata());
        }
        return TokenizerConfig.defaultConfig();
    }

    // Cross-Encoder Model Accessors

    /**
     * Get a cross-encoder model descriptor by ID.
     * @param modelId The model identifier (e.g., "ms-marco-MiniLM-L-6-v2")
     * @return ModelDescriptor for the cross-encoder, or null if not found
     */
    public static ModelDescriptor getCrossEncoderModelDescriptor(String modelId) {
        return CROSS_ENCODER_MODEL_DESCRIPTORS.get(modelId);
    }

    /**
     * Get a cross-encoder vocabulary descriptor by model ID.
     * @param modelId The model identifier (e.g., "ms-marco-MiniLM-L-6-v2")
     * @return ModelDescriptor for the vocabulary, or null if not found
     */
    public static ModelDescriptor getCrossEncoderVocabDescriptor(String modelId) {
        return CROSS_ENCODER_VOCAB_DESCRIPTORS.get(modelId);
    }

    /**
     * Get all available cross-encoder model IDs.
     * @return Set of cross-encoder model IDs
     */
    public static Set<String> getAvailableCrossEncoderModelIds() {
        return CROSS_ENCODER_MODEL_DESCRIPTORS.keySet();
    }

    /**
     * Check if a cross-encoder model is available.
     * @param modelId The model identifier
     * @return true if the model is available
     */
    public static boolean isCrossEncoderModelAvailable(String modelId) {
        return CROSS_ENCODER_MODEL_DESCRIPTORS.containsKey(modelId);
    }

    /**
     * Get the default cross-encoder model ID.
     * @return The recommended default cross-encoder model
     */
    public static String getDefaultCrossEncoderModelId() {
        return "ms-marco-MiniLM-L-6-v2";
    }

    /**
     * Get tokenizer configuration for a cross-encoder model.
     * @param modelId The cross-encoder model identifier
     * @return TokenizerConfig for the model, or default config if model not found
     */
    public static TokenizerConfig getCrossEncoderTokenizerConfig(String modelId) {
        ModelDescriptor descriptor = getCrossEncoderModelDescriptor(modelId);
        if (descriptor != null) {
            return TokenizerConfig.fromMetadata(descriptor.getMetadata());
        }
        return TokenizerConfig.defaultConfig();
    }

    // ==================== OCR Model Descriptors ====================
    // OCR models are primarily managed via the model staging registry,
    // but these descriptors serve as fallback for built-in models.

    private static final Map<String, ModelDescriptor> OCR_MODEL_DESCRIPTORS = new HashMap<>();
    private static final Map<String, ModelDescriptor> OCR_VOCAB_DESCRIPTORS = new HashMap<>();

    // OCR models can be added here as fallback when not using staging registry
    // Example:
    // static {
    //     Map<String, Object> dbnetMeta = new HashMap<>();
    //     dbnetMeta.put("model_type", "ocr_detection");
    //     dbnetMeta.put("input_height", 960);
    //     dbnetMeta.put("average_accuracy", 0.92);
    //     OCR_MODEL_DESCRIPTORS.put("dbnet-v2", ModelDescriptor.builder()
    //             .modelId("dbnet-v2")
    //             .downloadUrl("https://github.com/GetKompile/kompile/releases/download/ocr-models-v1.0.0/dbnet-v2.sdz")
    //             .expectedCacheSubpath("ocr-detection/dbnet-v2/model.sdz")
    //             .modelType(ModelType.OCR_DETECTION)
    //             .metadata(dbnetMeta)
    //             .build());
    // }

    /**
     * Get an OCR model descriptor by model ID.
     * @param modelId The model identifier (e.g., "dbnet-v2")
     * @return ModelDescriptor for the OCR model, or null if not found
     */
    public static ModelDescriptor getOcrModelDescriptor(String modelId) {
        return OCR_MODEL_DESCRIPTORS.get(modelId);
    }

    /**
     * Get an OCR vocabulary descriptor by model ID.
     * @param modelId The model identifier
     * @return ModelDescriptor for the OCR vocabulary, or null if not found
     */
    public static ModelDescriptor getOcrVocabDescriptor(String modelId) {
        return OCR_VOCAB_DESCRIPTORS.get(modelId);
    }

    /**
     * Get all OCR model IDs.
     * @return Set of OCR model identifiers
     */
    public static Set<String> getOcrModelIds() {
        return OCR_MODEL_DESCRIPTORS.keySet();
    }

    /**
     * Get all model descriptors including encoders, cross-encoders, indexes, and OCR.
     * Useful for model management UI that needs to display all available models.
     * @return Map of model ID to ModelDescriptor for all model types
     */
    public static Map<String, ModelDescriptor> getAllModelDescriptors() {
        Map<String, ModelDescriptor> allModels = new HashMap<>();
        allModels.putAll(ANSERINI_ENCODER_MODEL_DESCRIPTORS);
        allModels.putAll(CROSS_ENCODER_MODEL_DESCRIPTORS);
        allModels.putAll(ANSERINI_INDEX_DESCRIPTORS);
        allModels.putAll(OCR_MODEL_DESCRIPTORS);
        return Collections.unmodifiableMap(allModels);
    }

    /**
     * Get model descriptors filtered by type.
     * @param type The model type to filter by
     * @return Map of model ID to ModelDescriptor for the specified type
     */
    public static Map<String, ModelDescriptor> getModelDescriptorsByType(ModelType type) {
        Map<String, ModelDescriptor> filtered = new HashMap<>();
        for (Map.Entry<String, ModelDescriptor> entry : getAllModelDescriptors().entrySet()) {
            if (entry.getValue().getModelType() == type) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(filtered);
    }

    // ==================== VLM Model Descriptors ====================
    // Vision-Language Models for end-to-end document understanding.
    // VLM models must be imported/converted to SameDiff (.sdz) format before use.
    // Use the import tools to convert from HuggingFace ONNX exports.

    private static final Map<String, VlmModelDescriptor> VLM_MODEL_DESCRIPTORS;

    static {
        Map<String, VlmModelDescriptor> vlmModels = new HashMap<>();

        // SmolDocling-256M - Compact document understanding model
        // Source: https://huggingface.co/ds4sd/SmolDocling-256M-preview
        // Must be imported to SDZ format using samediff-import-onnx
        vlmModels.put("smoldocling-256m", new VlmModelDescriptor(
                "smoldocling-256m",
                "SmolDocling 256M Preview",
                "https://huggingface.co/ds4sd/SmolDocling-256M-preview",
                "vlm/smoldocling-256m",
                Map.ofEntries(
                        Map.entry("description", "SmolDocling 256M - Compact document understanding VLM (SDZ format)"),
                        Map.entry("huggingface_repo", "ds4sd/SmolDocling-256M-preview"),
                        Map.entry("architecture", "encoder-decoder"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_file", "smoldocling-256m.sdz"),
                        Map.entry("vocab_file", "vocab.txt"),
                        Map.entry("tokenizer_config", "tokenizer_config.json"),
                        Map.entry("output_formats", "DOCTAGS,MARKDOWN,JSON,TEXT"),
                        Map.entry("max_image_size", 1024),
                        Map.entry("hidden_size", 256),
                        Map.entry("max_new_tokens", 4096)
                )
        ));

        // Donut - Document Understanding Transformer
        // Source: https://huggingface.co/naver-clova-ix/donut-base
        vlmModels.put("donut-base", new VlmModelDescriptor(
                "donut-base",
                "Donut Base",
                "https://huggingface.co/naver-clova-ix/donut-base",
                "vlm/donut-base",
                Map.ofEntries(
                        Map.entry("description", "Donut - Document Understanding Transformer (SDZ format)"),
                        Map.entry("huggingface_repo", "naver-clova-ix/donut-base"),
                        Map.entry("architecture", "encoder-decoder"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_file", "donut-base.sdz"),
                        Map.entry("vocab_file", "vocab.txt"),
                        Map.entry("tokenizer_config", "tokenizer_config.json"),
                        Map.entry("output_formats", "JSON,TEXT"),
                        Map.entry("max_image_size", 2560),
                        Map.entry("hidden_size", 1024),
                        Map.entry("max_new_tokens", 2048)
                )
        ));

        // Nougat - Academic Document Parser
        // Source: https://huggingface.co/facebook/nougat-base
        vlmModels.put("nougat-base", new VlmModelDescriptor(
                "nougat-base",
                "Nougat Base",
                "https://huggingface.co/facebook/nougat-base",
                "vlm/nougat-base",
                Map.ofEntries(
                        Map.entry("description", "Nougat - Academic PDF to Markdown VLM (SDZ format)"),
                        Map.entry("huggingface_repo", "facebook/nougat-base"),
                        Map.entry("architecture", "encoder-decoder"),
                        Map.entry("framework", "samediff"),
                        Map.entry("model_file", "nougat-base.sdz"),
                        Map.entry("vocab_file", "vocab.txt"),
                        Map.entry("tokenizer_config", "tokenizer_config.json"),
                        Map.entry("output_formats", "MARKDOWN,TEXT"),
                        Map.entry("max_image_size", 896),
                        Map.entry("hidden_size", 1024),
                        Map.entry("max_new_tokens", 4096)
                )
        ));

        VLM_MODEL_DESCRIPTORS = Collections.unmodifiableMap(vlmModels);
    }

    /**
     * Get a VLM model descriptor by ID.
     * @param modelId The VLM model identifier (e.g., "smoldocling-256m")
     * @return VlmModelDescriptor for the VLM, or null if not found
     */
    public static VlmModelDescriptor getVlmModelDescriptor(String modelId) {
        // Normalize model ID
        String normalizedId = modelId.toLowerCase().replace("_", "-");
        return VLM_MODEL_DESCRIPTORS.get(normalizedId);
    }

    /**
     * Get all available VLM model IDs.
     * @return Set of VLM model IDs
     */
    public static Set<String> getAvailableVlmModelIds() {
        return VLM_MODEL_DESCRIPTORS.keySet();
    }

    /**
     * Check if a VLM model is available.
     * @param modelId The VLM model identifier
     * @return true if the VLM model is available
     */
    public static boolean isVlmModelAvailable(String modelId) {
        String normalizedId = modelId.toLowerCase().replace("_", "-");
        return VLM_MODEL_DESCRIPTORS.containsKey(normalizedId);
    }

    /**
     * Get the default VLM model ID.
     * @return The recommended default VLM model
     */
    public static String getDefaultVlmModelId() {
        return "smoldocling-256m";
    }

    /**
     * Descriptor for Vision-Language Models.
     * VLMs are imported/converted to SameDiff (.sdz) format.
     * Use samediff-import-onnx to convert HuggingFace ONNX models to SDZ.
     */
    public static class VlmModelDescriptor {
        private final String modelId;
        private final String displayName;
        private final String huggingFaceUrl;
        private final String cacheSubpath;
        private final Map<String, Object> metadata;

        public VlmModelDescriptor(String modelId, String displayName, String huggingFaceUrl,
                                  String cacheSubpath, Map<String, Object> metadata) {
            this.modelId = modelId;
            this.displayName = displayName;
            this.huggingFaceUrl = huggingFaceUrl;
            this.cacheSubpath = cacheSubpath;
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
        }

        public String getModelId() { return modelId; }
        public String getDisplayName() { return displayName; }
        public String getHuggingFaceUrl() { return huggingFaceUrl; }
        public String getCacheSubpath() { return cacheSubpath; }
        public Map<String, Object> getMetadata() { return metadata; }

        public String getDescription() {
            return (String) metadata.get("description");
        }

        public String getHuggingFaceRepo() {
            return (String) metadata.get("huggingface_repo");
        }

        /**
         * Gets the SDZ model file name.
         */
        public String getModelFile() {
            return (String) metadata.getOrDefault("model_file", modelId + ".sdz");
        }

        /**
         * Gets the vocabulary file name.
         */
        public String getVocabFile() {
            return (String) metadata.getOrDefault("vocab_file", "vocab.txt");
        }

        /**
         * Gets the tokenizer config file name.
         */
        public String getTokenizerConfigFile() {
            return (String) metadata.getOrDefault("tokenizer_config", "tokenizer_config.json");
        }

        /**
         * Gets the framework (should be "samediff" for SDZ models).
         */
        public String getFramework() {
            return (String) metadata.getOrDefault("framework", "samediff");
        }

        public Integer getMaxNewTokens() {
            Object val = metadata.get("max_new_tokens");
            return val instanceof Integer ? (Integer) val : 4096;
        }

        public Integer getMaxImageSize() {
            Object val = metadata.get("max_image_size");
            return val instanceof Integer ? (Integer) val : 1024;
        }

        public Integer getHiddenSize() {
            Object val = metadata.get("hidden_size");
            return val instanceof Integer ? (Integer) val : null;
        }

        public String[] getOutputFormats() {
            String formats = (String) metadata.get("output_formats");
            return formats != null ? formats.split(",") : new String[]{"TEXT"};
        }

        @Override
        public String toString() {
            return "VlmModelDescriptor{" +
                    "modelId='" + modelId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", framework='" + getFramework() + '\'' +
                    ", modelFile='" + getModelFile() + '\'' +
                    '}';
        }
    }
}