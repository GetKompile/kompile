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
