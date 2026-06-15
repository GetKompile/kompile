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

package ai.kompile.core.source.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO for transmitting source provider information to the frontend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceProviderDto {

    private String id;
    private String displayName;
    private String description;
    private String icon;
    private String category;
    private int order;
    private boolean available;
    private String unavailableReason;
    private boolean requiresAuth;
    private String authType;
    private String oauthProvider;
    private boolean supportsBatch;
    private boolean hasCustomDialog;
    private String customDialogComponent;
    private List<SourceFormField> formFields;
    private Map<String, Object> configuration;

    /**
     * Creates a DTO from a SourceProvider.
     */
    public static SourceProviderDto fromProvider(SourceProvider provider) {
        return SourceProviderDto.builder()
                .id(provider.getId())
                .displayName(provider.getDisplayName())
                .description(provider.getDescription())
                .icon(provider.getIcon())
                .category(provider.getCategory())
                .order(provider.getOrder())
                .available(provider.isAvailable())
                .unavailableReason(provider.getUnavailableReason())
                .requiresAuth(provider.requiresAuth())
                .authType(provider.getAuthType())
                .oauthProvider(provider.getOAuthProvider())
                .supportsBatch(provider.supportsBatch())
                .hasCustomDialog(provider.hasCustomDialog())
                .customDialogComponent(provider.getCustomDialogComponent())
                .formFields(provider.getFormFields())
                .configuration(provider.getConfiguration())
                .build();
    }
}
