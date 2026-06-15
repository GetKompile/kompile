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

/**
 * Configuration for connecting to a Confluence instance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceConnectionConfig {

    /**
     * The base URL of the Confluence instance.
     * For Cloud: https://your-domain.atlassian.net/wiki
     * For Server/Data Center: https://confluence.yourcompany.com
     */
    private String baseUrl;

    /**
     * The email address associated with the Atlassian account (for Cloud)
     * or username (for Server/Data Center).
     */
    private String email;

    /**
     * The API token for authentication.
     * For Cloud: Generated from https://id.atlassian.com/manage-profile/security/api-tokens
     * For Server/Data Center: Personal Access Token or password
     */
    private String apiToken;

    /**
     * Optional Cloud ID for Confluence Cloud instances.
     */
    private String cloudId;
}
