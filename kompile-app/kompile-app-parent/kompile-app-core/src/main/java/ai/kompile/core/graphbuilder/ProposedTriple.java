/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphbuilder;

import java.util.Map;

/**
 * A proposed triple (subject-predicate-object) extracted from text.
 *
 * @param subjectName name of the subject entity
 * @param subjectType type of the subject entity (e.g., PERSON, ORGANIZATION)
 * @param predicateName the relationship/predicate connecting subject to object
 * @param objectName name of the object entity
 * @param objectType type of the object entity
 * @param confidence extraction confidence score (0.0 to 1.0)
 * @param sourceChunkId ID of the chunk where this was extracted
 * @param sourceDocumentId ID of the source document
 * @param sourceContext text snippet providing context
 * @param metadata additional metadata
 */
public record ProposedTriple(
        String subjectName,
        String subjectType,
        String predicateName,
        String objectName,
        String objectType,
        Double confidence,
        String sourceChunkId,
        String sourceDocumentId,
        String sourceContext,
        Map<String, Object> metadata
) {
    /**
     * Create a simple triple without source context.
     */
    public static ProposedTriple of(
            String subjectName, String subjectType,
            String predicateName,
            String objectName, String objectType,
            double confidence) {
        return new ProposedTriple(
                subjectName, subjectType, predicateName,
                objectName, objectType, confidence,
                null, null, null, Map.of()
        );
    }

    /**
     * Create a triple with source information.
     */
    public static ProposedTriple withSource(
            String subjectName, String subjectType,
            String predicateName,
            String objectName, String objectType,
            double confidence,
            String chunkId, String documentId, String context) {
        return new ProposedTriple(
                subjectName, subjectType, predicateName,
                objectName, objectType, confidence,
                chunkId, documentId, context, Map.of()
        );
    }
}
