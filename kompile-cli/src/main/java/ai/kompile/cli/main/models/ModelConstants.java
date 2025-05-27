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


    // Anserini Encoder Models (Neural network files like ONNX, TF, DL4J for encoders)
    // These are distinct from Lucene Indexes.
    private static final Map<String, ModelDescriptor> ANSERINI_ENCODER_MODEL_DESCRIPTORS;
    static {
        Map<String, ModelDescriptor> encoderModels = new HashMap<>();
        // **IMPORTANT**: Replace placeholder URLs with actual downloadable URLs for these models.
        // These might come from your Anserini fork's releases, HuggingFace, or other model repositories.

        // Example for BGE ONNX model (user needs to provide actual URL)
        encoderModels.put("bge-base-en-v1.5-onnx", new ModelDescriptor(
                "anserini_encoder_bge-base-en-v1.5-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "https://huggingface.co/BAAI/bge-base-en-v1.5/resolve/main/onnx/model.onnx?download=true", // Placeholder, verify/update
                "anserini/encoders/onnx/bge-base-en-v1.5/model.onnx", // Cache subpath
                "v1.5", null, // Checksum if available
                Map.of("description", "BGE Base English v1.5 ONNX model for Anserini dense encoding.", "framework", "onnx")
        ));

        // Example for SPLADE++ SelfDistil ONNX model (user needs to provide actual URL)
        // Pyserini uses castorini/splade-pp-selfdistil from HuggingFace.
        // The specific .onnx file path within that repo needs to be identified if not directly downloadable.
        encoderModels.put("splade-pp-sd-onnx", new ModelDescriptor(
                "anserini_encoder_splade-pp-sd-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "https://huggingface.co/castorini/splade-pp-selfdistil/resolve/main/splade-pp-self-distil.onnx?download=true", // Placeholder, verify/update
                "anserini/encoders/onnx/splade-pp-sd/splade-pp-self-distil.onnx",
                "castorini-main", null,
                Map.of("description", "SPLADE++ SelfDistil ONNX model for Anserini sparse encoding.", "framework", "onnx")
        ));

        // Example for UniCOIL (original from MS MARCO, typically Tile-optimized)
        // This might be a DL4J zip or specific ONNX. Assuming an ONNX version for consistency.
        encoderModels.put("unicoil-msmarco-passage-onnx", new ModelDescriptor(
                "anserini_encoder_unicoil-msmarco-passage-onnx",
                ModelType.ANSERINI_ENCODER_MODEL,
                "http://your-model-host.com/path/to/unicoil_msmarco_passage.onnx", // Placeholder
                "anserini/encoders/onnx/unicoil-msmarco-passage/model.onnx",
                "msmarco-v1", null,
                Map.of("description", "UniCOIL (MS MARCO Passage) ONNX model for Anserini sparse encoding.", "framework", "onnx")
        ));

        // Add other SameDiff/ONNX/DL4J encoder models your Anserini fork uses.
        // For example, if ArcticEmbedSameDiffEncoder uses a specific downloadable DL4J model:
        // encoderModels.put("arctic-embed-dl4j", new ModelDescriptor(...));

        ANSERINI_ENCODER_MODEL_DESCRIPTORS = Collections.unmodifiableMap(encoderModels);
    }
    public static ModelDescriptor getAnseriniEncoderModelDescriptor(String modelId) {
        return ANSERINI_ENCODER_MODEL_DESCRIPTORS.get(modelId);
    }
}