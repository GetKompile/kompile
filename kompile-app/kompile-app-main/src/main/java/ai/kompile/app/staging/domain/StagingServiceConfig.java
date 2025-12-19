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

package ai.kompile.app.staging.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistent configuration for connecting to a Model Staging Service.
 * This allows the main Kompile app to interact with a remote or local
 * staging service for downloading, converting, and managing ML models.
 */
@Entity
@Table(name = "staging_service_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StagingServiceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User-friendly name for this staging service configuration.
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Base URL of the staging service (e.g., http://localhost:8090).
     */
    @Column(nullable = false, length = 1024)
    private String endpointUrl;

    /**
     * Optional API key or token for authentication.
     */
    @Column(length = 512)
    private String apiKey;

    /**
     * Whether this configuration is the active/default one.
     * Only one configuration can be active at a time.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    /**
     * Whether the connection was successfully verified.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    /**
     * Last time the connection was tested/verified.
     */
    private Instant lastVerifiedAt;

    /**
     * Last error message if verification failed.
     */
    @Column(length = 2048)
    private String lastError;

    /**
     * Connection timeout in milliseconds.
     */
    @Column(nullable = false)
    @Builder.Default
    private int connectionTimeoutMs = 5000;

    /**
     * Read timeout in milliseconds.
     */
    @Column(nullable = false)
    @Builder.Default
    private int readTimeoutMs = 30000;

    /**
     * Whether to automatically sync models from staging to local registry.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean autoSync = false;

    /**
     * Sync interval in minutes (if autoSync is enabled).
     */
    @Column(nullable = false)
    @Builder.Default
    private int syncIntervalMinutes = 60;

    /**
     * Optional description or notes about this configuration.
     */
    @Column(length = 1024)
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Get the full API base URL for staging endpoints.
     */
    public String getApiBaseUrl() {
        String url = endpointUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/api/staging";
    }
}
