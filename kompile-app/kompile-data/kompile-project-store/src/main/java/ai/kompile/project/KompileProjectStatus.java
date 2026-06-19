/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.project;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KompileProjectStatus {
    private String root;
    private String manifestPath;
    private boolean manifestPresent;
    private String metadataPath;
    private boolean metadataPresent;
    private String openStatePath;
    private boolean open;
    private String openProjectId;
    private Instant openedAt;
    private Instant openStateUpdatedAt;
    private boolean gitRepository;
    private boolean gitDirty;
    private String branch;
    private String remoteUrl;
    private boolean gitXetAvailable;
    private boolean gitXetEnabled;
    private int componentCount;
    private int codingProjectCount;
    private int modelCount;
    private int pipelineCount;
    private int scriptCount;
    private int crawlProfileCount;
    private int workflowCount;
    private int markdownCount;
    private int crawlResultCount;
    private int sourceDocumentCount;
    private int promptTemplateCount;
    private int factSheetCount;
    private int chatSessionCount;
    private int noteSyncConnectionCount;
    private int indexedDocumentCount;
}
