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
package ai.kompile.app.web.dto.modelregistry;

import java.util.List;

/**
 * Response from archive import
 */
public class ArchiveImportResponse {
    public boolean success;
    public String archiveId;
    public String version;
    public int importedCount;
    public int skippedCount;
    public List<String> importedModels;
    public List<String> skippedModels;
    public String error;

    public ArchiveImportResponse(boolean success, String archiveId, String version, int importedCount,
                                 int skippedCount, List<String> importedModels, List<String> skippedModels,
                                 String error) {
        this.success = success;
        this.archiveId = archiveId;
        this.version = version;
        this.importedCount = importedCount;
        this.skippedCount = skippedCount;
        this.importedModels = importedModels;
        this.skippedModels = skippedModels;
        this.error = error;
    }
}
