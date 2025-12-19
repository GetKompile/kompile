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
 * Status of the Confluence connection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceConnectionStatus {

    /**
     * Whether a valid connection to Confluence is established.
     */
    private boolean connected;

    /**
     * The base URL of the connected Confluence instance.
     */
    private String baseUrl;

    /**
     * The username/email used for the connection.
     */
    private String username;

    /**
     * The display name of the authenticated user.
     */
    private String displayName;

    /**
     * Cloud ID for Confluence Cloud instances.
     */
    private String cloudId;

    /**
     * Server version for Confluence Server/Data Center.
     */
    private String serverVersion;

    /**
     * The deployment type: cloud, server, or datacenter.
     */
    private String deploymentType;

    /**
     * Error message if connection failed.
     */
    private String errorMessage;
}
