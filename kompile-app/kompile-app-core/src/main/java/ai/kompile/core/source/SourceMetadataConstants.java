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

package ai.kompile.core.source;

/**
 * Standardized metadata keys for source document attribution.
 *
 * These constants ensure consistent source tracking across:
 * - Document loading
 * - Keyword (Lucene) indexing
 * - Vector store indexing
 * - Vector population from keyword index
 *
 * Usage example:
 * <pre>
 * Map<String, Object> metadata = new HashMap<>();
 * metadata.put(SourceMetadataConstants.SOURCE_PATH, "/path/to/original.pdf");
 * metadata.put(SourceMetadataConstants.SOURCE_ID, "doc-123");
 * metadata.put(SourceMetadataConstants.STORED_COPY_PATH, "/home/user/.kompile/documents/abc123.pdf");
 * </pre>
 */
public final class SourceMetadataConstants {

    private SourceMetadataConstants() {
        // Prevent instantiation
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ORIGINAL SOURCE INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The original file system path of the source document.
     * Example: "/home/user/documents/report.pdf"
     */
    public static final String SOURCE_PATH = "source_path";

    /**
     * The original URL if the document was fetched from the web.
     * Example: "https://example.com/document.pdf"
     */
    public static final String SOURCE_URL = "source_url";

    /**
     * A stable identifier for the source document.
     * This should be consistent across re-indexing operations.
     * Example: "file:///home/user/documents/report.pdf" or "https://example.com/doc"
     */
    public static final String SOURCE_ID = "source_id";

    /**
     * The original file name of the source document.
     * Example: "report.pdf"
     */
    public static final String SOURCE_FILENAME = "source_filename";

    /**
     * The type of source (FILE, URL, DIRECTORY, etc.)
     * Maps to DocumentSourceDescriptor.SourceType
     */
    public static final String SOURCE_TYPE = "source_type";

    // ═══════════════════════════════════════════════════════════════════════════
    // STORED COPY INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Path to the stored copy of the original document in Kompile's managed directory.
     * Example: "/home/user/.kompile/documents/abc123def456.pdf"
     */
    public static final String STORED_COPY_PATH = "stored_copy_path";

    /**
     * SHA-256 hash of the original document content.
     * Used for deduplication and integrity verification.
     */
    public static final String SOURCE_CHECKSUM = "source_checksum";

    /**
     * Timestamp when the document was first stored.
     * ISO 8601 format: "2025-01-15T10:30:00Z"
     */
    public static final String STORED_AT = "stored_at";

    /**
     * Size of the original document in bytes.
     */
    public static final String SOURCE_SIZE_BYTES = "source_size_bytes";

    // ═══════════════════════════════════════════════════════════════════════════
    // CHUNK/SEGMENT INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The page number within the source document (1-indexed).
     * For PDFs and paginated documents.
     */
    public static final String PAGE_NUMBER = "page_number";

    /**
     * Total number of pages in the source document.
     */
    public static final String TOTAL_PAGES = "total_pages";

    /**
     * The chunk/segment index within the document (0-indexed).
     */
    public static final String CHUNK_INDEX = "chunk_index";

    /**
     * Total number of chunks created from this document.
     */
    public static final String TOTAL_CHUNKS = "total_chunks";

    /**
     * Character offset where this chunk starts in the original document.
     */
    public static final String CHAR_OFFSET_START = "char_offset_start";

    /**
     * Character offset where this chunk ends in the original document.
     */
    public static final String CHAR_OFFSET_END = "char_offset_end";

    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTION/ORGANIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The collection or folder name this document belongs to.
     */
    public static final String COLLECTION_NAME = "collection_name";

    /**
     * Tags associated with this document.
     * Typically stored as a comma-separated string or JSON array.
     */
    public static final String TAGS = "tags";

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT TYPE INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * MIME type of the source document.
     * Example: "application/pdf", "text/plain"
     */
    public static final String MIME_TYPE = "mime_type";

    /**
     * File extension of the source document.
     * Example: "pdf", "docx", "txt"
     */
    public static final String FILE_EXTENSION = "file_extension";

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESSING INFORMATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The name of the loader that processed this document.
     * Example: "PDFLoader", "TikaLoader", "LuceneIndexLoader"
     */
    public static final String LOADER_NAME = "loader_name";

    /**
     * Timestamp when the document was last indexed.
     * ISO 8601 format: "2025-01-15T10:30:00Z"
     */
    public static final String INDEXED_AT = "indexed_at";

    /**
     * Processing version - can be used to track if reprocessing is needed.
     */
    public static final String PROCESSING_VERSION = "processing_version";

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List of all core source tracking metadata keys.
     * Useful for ensuring these keys are preserved during document transformations.
     */
    public static final String[] CORE_SOURCE_KEYS = {
            SOURCE_PATH,
            SOURCE_URL,
            SOURCE_ID,
            SOURCE_FILENAME,
            SOURCE_TYPE,
            STORED_COPY_PATH,
            SOURCE_CHECKSUM
    };

    /**
     * List of all chunk-related metadata keys.
     */
    public static final String[] CHUNK_KEYS = {
            PAGE_NUMBER,
            TOTAL_PAGES,
            CHUNK_INDEX,
            TOTAL_CHUNKS,
            CHAR_OFFSET_START,
            CHAR_OFFSET_END
    };
}
