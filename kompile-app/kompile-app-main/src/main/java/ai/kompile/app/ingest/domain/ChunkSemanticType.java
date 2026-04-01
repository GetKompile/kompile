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

package ai.kompile.app.ingest.domain;

/**
 * Semantic type classification for document chunks/passages.
 * Helps categorize the type of content for better retrieval and context understanding.
 */
public enum ChunkSemanticType {

    /**
     * General text content without specific semantic classification
     */
    TEXT,

    /**
     * Definition of a term, concept, or entity
     */
    DEFINITION,

    /**
     * Explanation or elaboration of a concept
     */
    EXPLANATION,

    /**
     * Step-by-step procedure or instructions
     */
    PROCEDURE,

    /**
     * Example or illustration of a concept
     */
    EXAMPLE,

    /**
     * Summary or abstract of content
     */
    SUMMARY,

    /**
     * Factual claim or statement
     */
    FACT,

    /**
     * Opinion, analysis, or interpretation
     */
    OPINION,

    /**
     * Question or query
     */
    QUESTION,

    /**
     * Answer or response to a question
     */
    ANSWER,

    /**
     * Code snippet or programming content
     */
    CODE,

    /**
     * Tabular data
     */
    TABLE,

    /**
     * List or enumeration
     */
    LIST,

    /**
     * Header, title, or section heading
     */
    HEADER,

    /**
     * Quote or citation from another source
     */
    QUOTE,

    /**
     * Reference or bibliography entry
     */
    REFERENCE,

    /**
     * Metadata or structured information
     */
    METADATA,

    /**
     * Warning, caution, or important notice
     */
    WARNING,

    /**
     * Note, tip, or sidebar information
     */
    NOTE,

    /**
     * Conclusion or key takeaway
     */
    CONCLUSION,

    /**
     * Unknown or unclassified content
     */
    UNKNOWN
}
