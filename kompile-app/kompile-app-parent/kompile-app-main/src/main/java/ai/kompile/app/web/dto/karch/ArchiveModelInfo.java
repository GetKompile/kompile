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
package ai.kompile.app.web.dto.karch;

/**
 * Summary info for a model contained in a loaded Kompile Archive.
 */
public class ArchiveModelInfo {
    public String modelId;
    public String type;
    public String path;
    public Integer embeddingDim;
    public Integer maxSequenceLength;
    public String description;

    public ArchiveModelInfo(String modelId, String type, String path, Integer embeddingDim,
                            Integer maxSequenceLength, String description) {
        this.modelId = modelId;
        this.type = type;
        this.path = path;
        this.embeddingDim = embeddingDim;
        this.maxSequenceLength = maxSequenceLength;
        this.description = description;
    }
}
