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

package ai.kompile.app.sync.dto;

import ai.kompile.app.sync.domain.SyncAuthMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncConnectionTestResponse {
    private Long connectionId;
    private boolean success;
    private SyncAuthMode authMode;
    private String authStatus;
    private String message;
    private Instant checkedAt;

    public static SyncConnectionTestResponse success(Long connectionId, SyncAuthMode authMode, String message) {
        return SyncConnectionTestResponse.builder()
                .connectionId(connectionId)
                .success(true)
                .authMode(authMode)
                .authStatus("VALID")
                .message(message)
                .checkedAt(Instant.now())
                .build();
    }

    public static SyncConnectionTestResponse failure(Long connectionId, SyncAuthMode authMode, String message) {
        return SyncConnectionTestResponse.builder()
                .connectionId(connectionId)
                .success(false)
                .authMode(authMode)
                .authStatus("INVALID")
                .message(message)
                .checkedAt(Instant.now())
                .build();
    }
}
