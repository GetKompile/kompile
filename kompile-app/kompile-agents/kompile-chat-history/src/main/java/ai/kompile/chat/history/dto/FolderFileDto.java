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

package ai.kompile.chat.history.dto;

import ai.kompile.chat.history.domain.FolderFile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for FolderFile.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderFileDto {
    private String fileId;
    private String fileName;
    private String storedPath;
    private Long fileSize;
    private String mimeType;
    private LocalDateTime uploadedAt;

    public static FolderFileDto fromEntity(FolderFile file) {
        return FolderFileDto.builder()
            .fileId(file.getFileId())
            .fileName(file.getFileName())
            .storedPath(file.getStoredPath())
            .fileSize(file.getFileSize())
            .mimeType(file.getMimeType())
            .uploadedAt(file.getUploadedAt())
            .build();
    }
}
