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
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KompileProjectOpenState {
    @Builder.Default
    private int schemaVersion = 1;
    private String projectId;
    private String name;
    private String root;
    private String manifestPath;
    @Builder.Default
    private KompileProjectLifecycleState lifecycle = KompileProjectLifecycleState.ACTIVE;
    private Instant openedAt;
    private Instant updatedAt;
    private int markdownCount;
    private int crawlResultCount;
    private int sourceDocumentCount;
    private int promptTemplateCount;
    private int factSheetCount;
    private int chatSessionCount;
    private int noteSyncConnectionCount;
    private int indexedDocumentCount;
    private Instant lastCrawlAt;
    @Builder.Default
    private Map<String, String> metadata = new LinkedHashMap<>();

    // Custom setters with defensive logic — override Lombok-generated ones.

    public void setLifecycle(KompileProjectLifecycleState lifecycle) {
        this.lifecycle = lifecycle == null ? KompileProjectLifecycleState.ACTIVE : lifecycle;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
