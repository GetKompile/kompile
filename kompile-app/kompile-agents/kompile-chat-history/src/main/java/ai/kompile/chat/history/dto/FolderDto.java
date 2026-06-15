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

import ai.kompile.chat.history.domain.ChatFolder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DTO for ChatFolder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderDto {
    private String folderId;
    private String name;
    private String description;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int fileCount;
    private List<FolderFileDto> files;

    public static FolderDto fromEntity(ChatFolder folder, boolean includeFiles) {
        FolderDtoBuilder builder = FolderDto.builder()
            .folderId(folder.getFolderId())
            .name(folder.getName())
            .description(folder.getDescription())
            .userId(folder.getUserId())
            .createdAt(folder.getCreatedAt())
            .updatedAt(folder.getUpdatedAt())
            .fileCount(folder.getFileCount());

        if (includeFiles && folder.getFiles() != null) {
            builder.files(folder.getFiles().stream()
                .map(FolderFileDto::fromEntity)
                .collect(Collectors.toList()));
        }

        return builder.build();
    }
}
