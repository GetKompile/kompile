package ai.kompile.cli.main.models;

/**
 * Enumerates the types of models managed by the KompileModelManager.
 */
public enum ModelType {
    /**
     * OpenNLP sentence detection model.
     */
    OPENNLP_SENTENCE,

    /**
     * Anserini prebuilt Lucene index. These are typically .tar.gz archives
     * containing Lucene index data.
     */
    ANSERINI_INDEX,

    /**
     * Anserini encoder model. These are typically neural network model files
     * (e.g., ONNX, TensorFlow SavedModel, DL4J zip) used by Anserini's
     * dense or sparse encoders (like SameDiff encoders).
     */
    ANSERINI_ENCODER_MODEL,

    /**
     * Other generic NLP models not fitting specific categories above.
     */
    NLP_MODEL,

    /**
     * Generic embedding models not specifically tied to Anserini encoders.
     */
    EMBEDDING_MODEL
}