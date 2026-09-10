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
}
