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
package ai.kompile.app.web.dto.crossindex;

import java.time.Instant;

/**
 * Response representing a document and its high-level index status.
 */
public record IndexedDocumentResponse(
        Long id,
        String sourceId,
        String fileName,
        Long factId,
        String overallStatus,
        String keywordStatus,
        String vectorStatus,
        String graphStatus,
        int passageCount,
        Instant createdAt,
        Instant updatedAt
) {}
