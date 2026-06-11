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

import ai.kompile.core.source.SourceAttributionHelper;
import ai.kompile.core.source.SourceMetadataConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Describes a source from which documents can be loaded.
 * This includes the type of source (URL, File, Directory) and its path,
 * as well as optional sourceId, metadata, and target collectionName.
 *
 * Enhanced with source attribution tracking to ensure original document
 * references are preserved throughout the indexing pipeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSourceDescriptor {

    public enum SourceType {
        URL,            // Represents a web URL
        FILE,           // Represents a single file on the filesystem
        DIRECTORY,      // Represents a directory on the filesystem to be scanned
        WEB_CRAWL,      // Represents a recursive web crawl starting from a seed URL
        SLACK,          // Represents a Slack channel or workspace for real-time ingestion
        SLACK_HISTORY,  // Represents historical Slack messages to be ingested
        CONFLUENCE,     // Represents Confluence pages/spaces for document ingestion
        EMAIL,          // Represents generic email source (IMAP or POP3)
        IMAP,           // Represents IMAP mail server connection
        POP3,           // Represents POP3 mail server connection
        MBOX,           // Represents an mbox email archive
        MAILDIR,        // Represents a Maildir email archive
        EMLX_DIR,       // Represents an Apple Mail .emlx directory
        PST,            // Represents an Outlook PST archive
        DISCORD,        // Represents a Discord server for real-time ingestion
        DISCORD_HISTORY,// Represents exported Discord message history
        GMAIL,          // Represents Gmail messages via the Gmail API
        GDOCS,          // Represents Google Docs via the Google APIs
        GDRIVE,           // Represents Google Drive files/folders accessed via Google OAuth
        ONEDRIVE,         // Represents Microsoft OneDrive files/folders accessed via Microsoft OAuth
        GOOGLE_WORKSPACE  // Represents Google Workspace (Gmail, Drive, Docs, Calendar)
    }

    private SourceType type;
    private String pathOrUrl;        // The actual URL or file/directory path
    private String originalFileName; // Optional: Can be used to preserve a name, e.g., for URL-sourced docs or if path is a UUID

    // Core identification
    private String sourceId;         // Typically related to the file or a logical group
    private Map<String, Object> metadata; // Optional, additional metadata for all docs from this source
    private String collectionName;   // Optional: target collection for these documents

    // Source document storage tracking (populated after storage)
    private String storedCopyPath;   // Path to the stored copy in Kompile's document storage
    private String checksum;         // SHA-256 hash of the original document
    private Long sizeBytes;          // Size of the original document in bytes

    /**
     * Creates a complete metadata map for documents loaded from this source.
     * This includes all source attribution fields from SourceMetadataConstants.
     *
     * @return Map containing all source attribution metadata
     */
    public Map<String, Object> toSourceMetadata() {
        Map<String, Object> sourceMetadata = SourceAttributionHelper.createSourceMetadata(this);

        // Add storage information if available
        if (storedCopyPath != null) {
            sourceMetadata.put(SourceMetadataConstants.STORED_COPY_PATH, storedCopyPath);
        }
        if (checksum != null) {
            sourceMetadata.put(SourceMetadataConstants.SOURCE_CHECKSUM, checksum);
        }
        if (sizeBytes != null) {
            sourceMetadata.put(SourceMetadataConstants.SOURCE_SIZE_BYTES, sizeBytes);
        }

        return sourceMetadata;
    }

    /**
     * Merges additional metadata into the descriptor's metadata map.
     * Existing values are not overwritten.
     *
     * @param additionalMetadata Additional metadata to merge
     * @return this descriptor (for chaining)
     */
    public DocumentSourceDescriptor mergeMetadata(Map<String, Object> additionalMetadata) {
        if (additionalMetadata == null || additionalMetadata.isEmpty()) {
            return this;
        }

        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }

        for (Map.Entry<String, Object> entry : additionalMetadata.entrySet()) {
            if (!this.metadata.containsKey(entry.getKey())) {
                this.metadata.put(entry.getKey(), entry.getValue());
            }
        }

        return this;
    }

    /**
     * Sets storage information from a storage result.
     *
     * @param storedPath Path to the stored copy
     * @param checksum   SHA-256 checksum
     * @param sizeBytes  File size in bytes
     * @return this descriptor (for chaining)
     */
    public DocumentSourceDescriptor withStorageInfo(String storedPath, String checksum, Long sizeBytes) {
        this.storedCopyPath = storedPath;
        this.checksum = checksum;
        this.sizeBytes = sizeBytes;
        return this;
    }

    /**
     * Checks if this descriptor has source attribution information.
     *
     * @return true if sourceId or pathOrUrl is present
     */
    public boolean hasSourceAttribution() {
        return sourceId != null || pathOrUrl != null;
    }

    /**
     * Gets the best available source identifier.
     * Prefers sourceId, falls back to pathOrUrl.
     *
     * @return Source identifier or null if none available
     */
    public String getEffectiveSourceId() {
        if (sourceId != null && !sourceId.isEmpty()) {
            return sourceId;
        }
        return pathOrUrl;
    }
}