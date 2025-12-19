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

package ai.kompile.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User information retrieved from OAuth provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthUserInfo {

    /**
     * User's email address.
     */
    private String email;

    /**
     * User's display name.
     */
    private String name;

    /**
     * URL to user's profile picture.
     */
    private String picture;

    /**
     * Provider-specific user ID.
     */
    private String userId;

    /**
     * Whether the email is verified (if available from provider).
     */
    private Boolean emailVerified;
}
