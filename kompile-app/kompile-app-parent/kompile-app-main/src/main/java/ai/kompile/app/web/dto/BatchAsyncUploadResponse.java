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

package ai.kompile.app.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for batch async document upload requests.
 * Contains results for each file in the batch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchAsyncUploadResponse(
        List<AsyncUploadResponse> files,
        int acceptedCount,
        int rejectedCount,
        String message,
        String websocketTopic
) {
    /**
     * Creates an error response for batch upload failures.
     */
    public static BatchAsyncUploadResponse error(String errorMessage) {
        return new BatchAsyncUploadResponse(
                List.of(),
                0,
                0,
                errorMessage,
                null
        );
    }
}
