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
 * Response with archive assembly status
 */
public class AssembleArchiveResponse {
    public boolean success;
    public String archiveId;
    public String archivePath;
    public long totalSizeBytes;
    public int modelCount;
    public List<String> includedModelIds;
    public List<BuiltInModelInfo> includedModels;
    public String error;

    public AssembleArchiveResponse(boolean success, String archiveId, String archivePath, long totalSizeBytes,
                                   int modelCount, List<String> includedModelIds, List<BuiltInModelInfo> includedModels,
                                   String error) {
        this.success = success;
        this.archiveId = archiveId;
        this.archivePath = archivePath;
        this.totalSizeBytes = totalSizeBytes;
        this.modelCount = modelCount;
        this.includedModelIds = includedModelIds;
        this.includedModels = includedModels;
        this.error = error;
    }
}
