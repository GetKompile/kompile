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

package ai.kompile.ocr.document;

/**
 * Classification of document complexity for pipeline routing.
 */
public enum DocumentComplexity {
    /**
     * Simple document - plain text, single column.
     */
    SIMPLE("Simple text document", false, false, false),

    /**
     * Document with tables that need extraction.
     */
    TABLES("Document with tables", true, false, false),

    /**
     * Form document with structured fields.
     */
    FORMS("Structured form", false, true, false),

    /**
     * Document with handwritten content.
     */
    HANDWRITTEN("Contains handwriting", false, false, true),

    /**
     * Complex multi-column layout.
     */
    MULTI_COLUMN("Multi-column layout", false, false, false),

    /**
     * Mixed complexity - tables, forms, and/or handwriting.
     */
    MIXED("Mixed complexity", true, true, false),

    /**
     * Technical document with diagrams.
     */
    TECHNICAL("Technical with diagrams", true, false, false);

    private final String description;
    private final boolean hasTables;
    private final boolean hasForms;
    private final boolean hasHandwriting;

    DocumentComplexity(String description, boolean hasTables, boolean hasForms, boolean hasHandwriting) {
        this.description = description;
        this.hasTables = hasTables;
        this.hasForms = hasForms;
        this.hasHandwriting = hasHandwriting;
    }

    public String getDescription() {
        return description;
    }

    public boolean hasTables() {
        return hasTables;
    }

    public boolean hasForms() {
        return hasForms;
    }

    public boolean hasHandwriting() {
        return hasHandwriting;
    }

    /**
     * Determines if table extraction is recommended.
     */
    public boolean recommendsTableExtraction() {
        return hasTables || this == MIXED || this == TECHNICAL;
    }

    /**
     * Determines if layout analysis is recommended.
     */
    public boolean recommendsLayoutAnalysis() {
        return hasForms || this == MIXED || this == MULTI_COLUMN;
    }

    /**
     * Determines if LLM post-processing is recommended.
     */
    public boolean recommendsLlmPostProcessing() {
        return hasHandwriting || this == MIXED;
    }
}
