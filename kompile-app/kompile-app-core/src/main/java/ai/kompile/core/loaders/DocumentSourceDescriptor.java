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

import lombok.AllArgsConstructor;
import lombok.Builder; // Added
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map; // Added for metadata

/**
 * Describes a source from which documents can be loaded.
 * This includes the type of source (URL, File, Directory) and its path,
 * as well as optional sourceId, metadata, and target collectionName.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder // Added Lombok Builder pattern
public class DocumentSourceDescriptor {

    public enum SourceType {
        URL,    // Represents a web URL
        FILE,   // Represents a single file on the filesystem
        DIRECTORY // Represents a directory on the filesystem to be scanned
    }

    private SourceType type;
    private String pathOrUrl;        // The actual URL or file/directory path
    private String originalFileName; // Optional: Can be used to preserve a name, e.g., for URL-sourced docs or if path is a UUID

    // Added fields
    private String sourceId;         // Typically related to the file or a logical group
    private Map<String, Object> metadata; // Optional, additional metadata for all docs from this source
    private String collectionName;   // Optional: target collection for these documents
}