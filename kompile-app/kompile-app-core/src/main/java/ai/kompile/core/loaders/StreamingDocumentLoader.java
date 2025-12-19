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

package ai.kompile.core.loaders;

import org.springframework.ai.document.Document;

import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Extended DocumentLoader interface that supports streaming page-by-page loading.
 *
 * <p>This interface is designed for processing large documents that would cause memory
 * issues if loaded entirely into memory. Instead of loading all content at once,
 * implementations provide an iterator that yields documents one page/section at a time.</p>
 *
 * <h2>Benefits of Streaming</h2>
 * <ul>
 *   <li><b>Memory efficiency</b>: Only one page in memory at a time</li>
 *   <li><b>Better batching</b>: Chunks can flow to embedding pipeline immediately</li>
 *   <li><b>Resume support</b>: Processing can be interrupted and resumed from last page</li>
 *   <li><b>Progress tracking</b>: Page-level progress updates for large documents</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * StreamingDocumentLoader loader = ...;
 * if (loader.supportsStreaming(source)) {
 *     LargeDocumentInfo info = loader.getDocumentInfo(source);
 *     if (info.isLargeDocument()) {
 *         Iterator<Document> pages = loader.streamPages(source, progress -> {
 *             System.out.println("Page " + progress.currentPage() + "/" + progress.totalPages());
 *         });
 *         while (pages.hasNext()) {
 *             Document page = pages.next();
 *             // Process page...
 *         }
 *     }
 * }
 * }</pre>
 */
public interface StreamingDocumentLoader extends DocumentLoader {

    /**
     * Checks if this loader supports streaming for the given source.
     *
     * @param source The document source descriptor
     * @return true if streaming is supported for this document type
     */
    boolean supportsStreaming(DocumentSourceDescriptor source);

    /**
     * Gets information about the document without loading full content.
     *
     * <p>This method should be fast and only read minimal information needed to
     * determine document size, page count, and whether streaming is appropriate.</p>
     *
     * @param source The document source descriptor
     * @return Document information including size and page count
     * @throws Exception if document info cannot be read
     */
    LargeDocumentInfo getDocumentInfo(DocumentSourceDescriptor source) throws Exception;

    /**
     * Returns an iterator that yields documents page-by-page or section-by-section.
     *
     * <p>Each yielded Document represents one logical unit (page for PDF, sheet for Excel,
     * slide for PowerPoint, paragraph batch for Word).</p>
     *
     * <p><b>Important:</b> The iterator manages its own resources. The underlying document
     * will be closed automatically when the iterator is exhausted or when {@code hasNext()}
     * returns false.</p>
     *
     * @param source The document source descriptor
     * @return An iterator over document pages/sections
     * @throws Exception if streaming cannot be started
     */
    default Iterator<Document> streamPages(DocumentSourceDescriptor source) throws Exception {
        return streamPages(source, null);
    }

    /**
     * Returns an iterator with progress callback support.
     *
     * @param source            The document source descriptor
     * @param progressCallback  Optional callback for progress updates (may be null)
     * @return An iterator over document pages/sections
     * @throws Exception if streaming cannot be started
     */
    Iterator<Document> streamPages(DocumentSourceDescriptor source,
                                   Consumer<PageProgress> progressCallback) throws Exception;

    /**
     * Progress information for page streaming.
     *
     * @param currentPage  Current page number (1-indexed)
     * @param totalPages   Total number of pages (-1 if unknown)
     * @param section      Description of current section being processed
     */
    record PageProgress(
        int currentPage,
        int totalPages,
        String section
    ) {
        /**
         * Returns progress as a percentage (0-100).
         * Returns -1 if total pages is unknown.
         */
        public int progressPercent() {
            if (totalPages <= 0) return -1;
            return Math.min(100, (currentPage * 100) / totalPages);
        }

        /**
         * Returns a human-readable progress string.
         */
        public String progressString() {
            if (totalPages > 0) {
                return String.format("Page %d/%d (%d%%)", currentPage, totalPages, progressPercent());
            }
            return String.format("Page %d (total unknown)", currentPage);
        }

        /**
         * Creates a progress for a specific page.
         */
        public static PageProgress of(int currentPage, int totalPages) {
            return new PageProgress(currentPage, totalPages, "page " + currentPage);
        }

        /**
         * Creates a progress with a custom section description.
         */
        public static PageProgress of(int currentPage, int totalPages, String section) {
            return new PageProgress(currentPage, totalPages, section);
        }
    }

    /**
     * Progress information for large document processing, including chunk counts.
     *
     * @param pageProgress   Current page progress
     * @param chunksCreated  Total chunks created so far
     * @param phase          Current processing phase
     */
    record LargeDocumentProgress(
        PageProgress pageProgress,
        int chunksCreated,
        String phase
    ) {
        /**
         * Creates progress from individual components.
         */
        public static LargeDocumentProgress of(int currentPage, int totalPages,
                                               int chunksCreated, String phase) {
            return new LargeDocumentProgress(
                new PageProgress(currentPage, totalPages, "page " + currentPage),
                chunksCreated,
                phase
            );
        }

        public int currentPage() {
            return pageProgress.currentPage();
        }

        public int totalPages() {
            return pageProgress.totalPages();
        }

        public int progressPercent() {
            return pageProgress.progressPercent();
        }
    }
}
