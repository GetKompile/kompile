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

package ai.kompile.cli.main.models;

import java.io.File; // Added for File.separator
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModelConstants {

    // Environment variable to specify the model cache directory at runtime
    public static final String ENV_KOMPILE_MODEL_CACHE_DIR = "KOMPILE_MODEL_CACHE_DIR";
    public static final String DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR = ".kompile" + File.separator + "models";

    // Base URL for SameDiff encoder models
    public static final String SAMEDIFF_ENCODER_BASE_URL = "https://github.com/GetKompile/kompile/releases/download/opennlp/";

    // OpenNLP Constants
    public static final String OPENNLP_MODEL_BASE_URL = "https://dlcdn.apache.org/opennlp/models/ud-models-1.2/";
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

    // Anserini Prebuilt Lucene Indexes
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
        // Add other common Anserini prebuilt indexes from Pyserini/Anserini documentation
        // e.g., wikipedia-dpr-100w, various BEIR datasets etc.
        ANSERINI_INDEX_DESCRIPTORS = Collections.unmodifiableMap(anseriniIndexes);
    }
    public static ModelDescriptor getAnseriniIndexDescriptor(String indexId) {
        return ANSERINI_INDEX_DESCRIPTORS.get(indexId);
    }

    // SameDiff Encoder Models - Dense Encoders
    private static final Map<String, ModelDescriptor> SAMEDIFF_DENSE_ENCODER_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> denseEncoders = new HashMap<>();
        
        // BGE Base English v1.5
        denseEncoders.put("bge-base-en-v1.5", new ModelDescriptor(
                "samediff_encoder_bge-base-en-v1.5",
                ModelType.SAMEDIFF_DENSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "bge-base-en-v1.5.sd",
                "samediff/encoders/dense/bge-base-en-v1.5/bge-base-en-v1.5.sd",
                "v1.5", null,
                Map.of(
                    "description", "BGE Base English v1.5 - Dense retrieval encoder",
                    "framework", "samediff",
                    "model_type", "dense",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "bge-base-en-v1.5-vocab.txt",
                    "max_sequence_length", 512,
                    "normalize_embeddings", true,
                    "instruction_prefix", "Represent this sentence for searching relevant passages: ",
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "last_hidden_state"
                )
        ));

        // CosDPR Distil
        denseEncoders.put("cosdpr-distil", new ModelDescriptor(
                "samediff_encoder_cosdpr-distil",
                ModelType.SAMEDIFF_DENSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "cosdpr-distil.sd",
                "samediff/encoders/dense/cosdpr-distil/cosdpr-distil.sd",
                "distil", null,
                Map.of(
                    "description", "CosDPR Distil - Dense passage retrieval encoder",
                    "framework", "samediff",
                    "model_type", "dense",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "cosdpr-distil-vocab.txt",
                    "max_sequence_length", 512,
                    "normalize_embeddings", false,
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "pooler_output"
                )
        ));

        // Arctic Embed Large
        denseEncoders.put("arctic-embed-l", new ModelDescriptor(
                "samediff_encoder_arctic-embed-l",
                ModelType.SAMEDIFF_DENSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "arctic-embed-l.sd",
                "samediff/encoders/dense/arctic-embed-l/arctic-embed-l.sd",
                "large", null,
                Map.of(
                    "description", "Snowflake Arctic Embed Large - Dense retrieval encoder",
                    "framework", "samediff",
                    "model_type", "dense",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "arctic-embed-l-vocab.txt",
                    "max_sequence_length", 512,
                    "normalize_embeddings", true,
                    "instruction_prefix", "Represent this sentence for searching relevant passages: ",
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "last_hidden_state"
                )
        ));

        SAMEDIFF_DENSE_ENCODER_DESCRIPTORS = Collections.unmodifiableMap(denseEncoders);
    }

    // SameDiff Encoder Models - Sparse Encoders
    private static final Map<String, ModelDescriptor> SAMEDIFF_SPARSE_ENCODER_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> sparseEncoders = new HashMap<>();

        // SPLADE++ EnsembleDistil
        sparseEncoders.put("splade-pp-ed", new ModelDescriptor(
                "samediff_encoder_splade-pp-ed",
                ModelType.SAMEDIFF_SPARSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "splade-pp-ed.sd",
                "samediff/encoders/sparse/splade-pp-ed/splade-pp-ed.sd",
                "ensemble-distil", null,
                Map.of(
                    "description", "SPLADE++ EnsembleDistil - Sparse retrieval encoder",
                    "framework", "samediff",
                    "model_type", "sparse",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "splade-pp-ed-vocab.txt",
                    "max_sequence_length", 256,
                    "weight_range", 10,
                    "quant_range", 256,
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "logits"
                )
        ));

        // SPLADE++ SelfDistil
        sparseEncoders.put("splade-pp-sd", new ModelDescriptor(
                "samediff_encoder_splade-pp-sd",
                ModelType.SAMEDIFF_SPARSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "splade-pp-sd.sd",
                "samediff/encoders/sparse/splade-pp-sd/splade-pp-sd.sd",
                "self-distil", null,
                Map.of(
                    "description", "SPLADE++ SelfDistil - Sparse retrieval encoder",
                    "framework", "samediff",
                    "model_type", "sparse",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "splade-pp-sd-vocab.txt",
                    "max_sequence_length", 256,
                    "weight_range", 10,
                    "quant_range", 256,
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "logits"
                )
        ));

        // UniCOIL
        sparseEncoders.put("unicoil", new ModelDescriptor(
                "samediff_encoder_unicoil",
                ModelType.SAMEDIFF_SPARSE_ENCODER,
                SAMEDIFF_ENCODER_BASE_URL + "unicoil.sd",
                "samediff/encoders/sparse/unicoil/unicoil.sd",
                "msmarco", null,
                Map.of(
                    "description", "UniCOIL - Universal Contextualized Inverted List sparse encoder",
                    "framework", "samediff",
                    "model_type", "sparse",
                    "vocab_url", SAMEDIFF_ENCODER_BASE_URL + "unicoil-vocab.txt",
                    "max_sequence_length", 512,
                    "weight_range", 5,
                    "quant_range", 255,
                    "input_tensor_names", "input_ids,attention_mask,token_type_ids",
                    "output_tensor_names", "logits"
                )
        ));

        SAMEDIFF_SPARSE_ENCODER_DESCRIPTORS = Collections.unmodifiableMap(sparseEncoders);
    }

    // Accessor methods for SameDiff encoders
    public static ModelDescriptor getSameDiffDenseEncoderDescriptor(String modelId) {
        return SAMEDIFF_DENSE_ENCODER_DESCRIPTORS.get(modelId);
    }

    public static ModelDescriptor getSameDiffSparseEncoderDescriptor(String modelId) {
        return SAMEDIFF_SPARSE_ENCODER_DESCRIPTORS.get(modelId);
    }

    public static Map<String, ModelDescriptor> getAllSameDiffDenseEncoders() {
        return SAMEDIFF_DENSE_ENCODER_DESCRIPTORS;
    }

    public static Map<String, ModelDescriptor> getAllSameDiffSparseEncoders() {
        return SAMEDIFF_SPARSE_ENCODER_DESCRIPTORS;
    }

    // Combined accessor for any SameDiff encoder
    public static ModelDescriptor getSameDiffEncoderDescriptor(String modelId) {
        ModelDescriptor descriptor = SAMEDIFF_DENSE_ENCODER_DESCRIPTORS.get(modelId);
        if (descriptor == null) {
            descriptor = SAMEDIFF_SPARSE_ENCODER_DESCRIPTORS.get(modelId);
        }
        return descriptor;
    }

    // Legacy ONNX/Anserini Encoder Models (kept for backward compatibility)
    private static final Map<String, ModelDescriptor> ANSERINI_ENCODER_MODEL_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> encoderModels = new HashMap<>();
        
        // BGE ONNX model (legacy)
        encoderModels.put("bge-base-en-v1.5-onnx", new ModelDescriptor(
                "anserini_encoder_bge-base-en-v1.5-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "https://rgw.cs.uwaterloo.ca/pyserini/data/bge-base-en-v1.5-optimized.onnx",
                "anserini/encoders/onnx/bge-base-en-v1.5/model.onnx",
                "v1.5", null,
                Map.of("description", "BGE Base English v1.5 ONNX model (legacy)", "framework", "onnx")
        ));

        // SPLADE++ SelfDistil ONNX model (legacy)
        encoderModels.put("splade-pp-sd-onnx", new ModelDescriptor(
                "anserini_encoder_splade-pp-sd-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "https://rgw.cs.uwaterloo.ca/pyserini/data/splade-pp-sd-optimized.onnx",
                "anserini/encoders/onnx/splade-pp-sd/splade-pp-self-distil.onnx",
                "self-distil", null,
                Map.of("description", "SPLADE++ SelfDistil ONNX model (legacy)", "framework", "onnx")
        ));

        ANSERINI_ENCODER_MODEL_DESCRIPTORS = Collections.unmodifiableMap(encoderModels);
    }

    public static ModelDescriptor getAnseriniEncoderModelDescriptor(String modelId) {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.get(modelId);
    }

    // Helper methods for model configuration extraction
    public static String getVocabUrl(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            return (String) descriptor.getMetadata().get("vocab_url");
        }
        return null;
    }

    public static int getMaxSequenceLength(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            Object maxSeq = descriptor.getMetadata().get("max_sequence_length");
            if (maxSeq instanceof Integer) {
                return (Integer) maxSeq;
            }
        }
        return 512; // Default
    }

    public static boolean shouldNormalizeEmbeddings(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            Object normalize = descriptor.getMetadata().get("normalize_embeddings");
            if (normalize instanceof Boolean) {
                return (Boolean) normalize;
            }
        }
        return false; // Default
    }

    public static String getInstructionPrefix(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            return (String) descriptor.getMetadata().get("instruction_prefix");
        }
        return null;
    }

    public static String[] getInputTensorNames(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            String tensorNames = (String) descriptor.getMetadata().get("input_tensor_names");
            if (tensorNames != null) {
                return tensorNames.split(",");
            }
        }
        return new String[]{"input_ids", "attention_mask", "token_type_ids"}; // Default
    }

    public static String[] getOutputTensorNames(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            String tensorNames = (String) descriptor.getMetadata().get("output_tensor_names");
            if (tensorNames != null) {
                return tensorNames.split(",");
            }
        }
        return new String[]{"last_hidden_state"}; // Default for dense models
    }

    public static int getWeightRange(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            Object weightRange = descriptor.getMetadata().get("weight_range");
            if (weightRange instanceof Integer) {
                return (Integer) weightRange;
            }
        }
        return 10; // Default for sparse models
    }

    public static int getQuantRange(String modelId) {
        ModelDescriptor descriptor = getSameDiffEncoderDescriptor(modelId);
        if (descriptor != null && descriptor.getMetadata() != null) {
            Object quantRange = descriptor.getMetadata().get("quant_range");
            if (quantRange instanceof Integer) {
                return (Integer) quantRange;
            }
        }
        return 256; // Default for sparse models
    }
}
