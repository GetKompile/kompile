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
package ai.kompile.knowledgegraph.domain;

/**
 * Represents the hierarchical level of a node in the knowledge graph.
 * The graph follows a three-level hierarchy: Sources -> Documents -> Snippets.
 */
public enum NodeLevel {
    /**
     * Top level: Represents a data source (URL, FILE, DIRECTORY, SLACK, CONFLUENCE, etc.)
     */
    SOURCE,

    /**
     * Middle level: Represents an individual document from a source
     */
    DOCUMENT,

    /**
     * Bottom level: Represents a chunk/segment of a document
     */
    SNIPPET,

    /**
     * Entity node extracted via GraphRAG or NER
     */
    ENTITY,

    /**
     * Custom user-defined node
     */
    CUSTOM,

    /**
     * Table or spreadsheet-sheet node extracted from tabular data
     */
    TABLE,

    /**
     * Email attachment node (e.g. a file attached to an email message)
     */
    ATTACHMENT
}
