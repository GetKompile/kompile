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

package ai.kompile.langdetect;

import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.ModelType;

import java.util.Map;

/**
 * Model constants for the OpenNLP language detection model.
 * Follows the same pattern as {@code ModelConstants.createOpenNLPSentenceModelDescriptor()}.
 */
public final class LangDetectModelConstants {

    private LangDetectModelConstants() {
    }

    public static final String OPENNLP_LANGDETECT_MODEL_URL =
            "https://dlcdn.apache.org/opennlp/models/langdetect/1.8.3/langdetect-183.bin";

    public static final String OPENNLP_LANGDETECT_LOCAL_PATH =
            "opennlp/langdetect/langdetect-183.bin";

    public static final String MODEL_ID = "opennlp-langdetect-183";

    /**
     * Creates a {@link ModelDescriptor} for the OpenNLP 183-language detection model.
     */
    public static ModelDescriptor createLangDetectModelDescriptor() {
        return new ModelDescriptor(
                MODEL_ID,
                ModelType.NLP_MODEL,
                OPENNLP_LANGDETECT_MODEL_URL,
                OPENNLP_LANGDETECT_LOCAL_PATH,
                "1.8.3",
                null,
                Map.of(
                        "description", "OpenNLP 183-language detection model",
                        "framework", "opennlp",
                        "languages", "183"
                )
        );
    }
}
