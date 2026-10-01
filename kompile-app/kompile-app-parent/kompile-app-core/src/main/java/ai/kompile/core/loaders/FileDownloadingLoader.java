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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.loaders;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Contract for loaders that deliver original files (PDF, Office exports, images, ...) for
 * pipeline processing instead of text documents.
 *
 * <p>File-backed providers (Google Drive, OneDrive, ...) implement this so a caller can run
 * the download step separately from processing: originals land on disk, then content-type
 * pipelines (text extraction, VLM OCR, table-aware, ...) process them exactly like local
 * files. The download step never decides how a file is parsed.</p>
 */
public interface FileDownloadingLoader {

    /**
     * Downloads this source's original files into {@code destination}, returning the written
     * paths. Implementations create {@code destination} when missing, deduplicate name
     * collisions, and skip (not fail) individual unreachable items when possible.
     */
    List<Path> downloadTo(DocumentSourceDescriptor sourceDescriptor, Path destination)
            throws Exception;

    /**
     * Same as {@link #downloadTo(DocumentSourceDescriptor, Path)}, but reports each per-file
     * failure message to {@code warnings} instead of only logging it, so a caller materializing
     * a snapshot can surface partial failures to the user. The default delegates to the two-arg
     * method, so existing implementations keep compiling unchanged; file-backed loaders override
     * this to route their per-file catch blocks to {@code warnings} and to fail with the first
     * failure when every file fails.
     */
    default List<Path> downloadTo(
            DocumentSourceDescriptor sourceDescriptor, Path destination, Consumer<String> warnings)
            throws Exception {
        return downloadTo(sourceDescriptor, destination);
    }
}
