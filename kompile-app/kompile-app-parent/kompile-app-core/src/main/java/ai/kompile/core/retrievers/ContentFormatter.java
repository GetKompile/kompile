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

package ai.kompile.core.retrievers;

/**
 * Interface for formatting document content based on metadata inclusion mode.
 * 
 * ContentFormatter implementations determine how document content and metadata
 * should be formatted for different use cases.
 */
public interface ContentFormatter {
    
    /**
     * Formats the content of a document according to the specified metadata mode.
     * 
     * @param document the document to format
     * @param metadataMode the mode determining how metadata should be included
     * @return the formatted content as a string
     */
    String format(RetrievedDoc document, MetadataMode metadataMode);
}
