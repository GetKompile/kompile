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

package ai.kompile.app.web.dto.confluence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from a Confluence ingest operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceIngestResponse {

    /**
     * Task IDs for tracking the ingest progress.
     */
    private List<String> taskIds;

    /**
     * Number of pages queued for ingestion.
     */
    private Integer pagesQueued;

    /**
     * Status message.
     */
    private String message;

    /**
     * Whether the operation was successful.
     */
    private Boolean success;

    /**
     * Error message if the operation failed.
     */
    private String errorMessage;
}
