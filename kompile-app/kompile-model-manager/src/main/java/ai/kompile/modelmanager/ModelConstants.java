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
        ANSERINI_INDEX_DESCRIPTORS = Collections.unmodifiableMap(anseriniIndexes);
    }
    
    public static ModelDescriptor getAnseriniIndexDescriptor(String indexId) {
        return ANSERINI_INDEX_DESCRIPTORS.get(indexId);
    }

    // Anserini Encoder Models (Neural network files like ONNX, TF, DL4J for encoders)
    private static final Map<String, ModelDescriptor> ANSERINI_ENCODER_MODEL_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> encoderModels = new HashMap<>();
        
        // Example for BGE ONNX model (placeholder URL - update with actual URLs)
        encoderModels.put("bge-base-en-v1.5-onnx", new ModelDescriptor(
                "anserini_encoder_bge-base-en-v1.5-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "https://huggingface.co/BAAI/bge-base-en-v1.5/resolve/main/onnx/model.onnx?download=true", // Placeholder
                "anserini/encoders/onnx/bge-base-en-v1.5/model.onnx",
                "v1.5", null,
                Map.of("description", "BGE Base English v1.5 ONNX model for Anserini dense encoding.", "framework", "onnx")
        ));

        ANSERINI_ENCODER_MODEL_DESCRIPTORS = Collections.unmodifiableMap(encoderModels);
    }
    
    public static ModelDescriptor getAnseriniEncoderModelDescriptor(String modelId) {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.get(modelId);
    }
}
