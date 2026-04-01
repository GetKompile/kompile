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

package ai.kompile.oauth.domain;

/**
 * Status of an OAuth connection.
 */
public enum ConnectionStatus {
    /**
     * Connection is active and tokens are valid.
     */
    CONNECTED,

    /**
     * Access token has expired but refresh token may still be valid.
     */
    EXPIRED,

    /**
     * An error occurred with the connection (e.g., token refresh failed).
     */
    ERROR,

    /**
     * User has explicitly disconnected or connection was revoked.
     */
    DISCONNECTED
}
