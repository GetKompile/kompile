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

package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Kompile SaaS credentials stored at {@code ~/.kompile/saas-credentials.json}.
 * Contains the JWT token, API base URL, and display username for the authenticated user.
 *
 * <p>Credentials can be loaded from the JSON file or from environment variables
 * ({@code KOMPILE_TOKEN} and {@code KOMPILE_SAAS_URL}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudConfig {

    private static final String CREDENTIALS_FILE = "saas-credentials.json";
    private static final String DEFAULT_BASE_URL = "https://api.getkompile.com";
    private static final String ENV_TOKEN = "KOMPILE_TOKEN";
    private static final String ENV_BASE_URL = "KOMPILE_SAAS_URL";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty
    private String token;

    @JsonProperty
    private String baseUrl = DEFAULT_BASE_URL;

    @JsonProperty
    private String username;

    public CloudConfig() {}

    public CloudConfig(String token, String baseUrl, String username) {
        this.token = token;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.username = username;
    }

    // --- Getters / Setters ---

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getBaseUrl() { return baseUrl != null ? baseUrl : DEFAULT_BASE_URL; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    // --- Instance methods ---

    /**
     * Returns {@code true} if this config holds a non-null, non-empty JWT token.
     */
    public boolean isValid() {
        return token != null && !token.isBlank();
    }

    /**
     * Persist this config to {@code ~/.kompile/saas-credentials.json}.
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), this);
    }

    /**
     * Clear the stored credentials by nulling the token and username, then saving.
     *
     * @throws IOException if the file cannot be written
     */
    public void clearCredentials() throws IOException {
        this.token = null;
        this.username = null;
        save();
    }

    // --- Static helpers ---

    /**
     * Returns the path to the credentials file: {@code ~/.kompile/saas-credentials.json}.
     */
    public static Path configPath() {
        return KompileHome.homeDirectory().toPath().resolve(CREDENTIALS_FILE);
    }

    /**
     * Returns {@code true} if {@code ~/.kompile/saas-credentials.json} exists on disk.
     */
    public static boolean exists() {
        return Files.exists(configPath());
    }

    /**
     * Load credentials from {@code ~/.kompile/saas-credentials.json}.
     *
     * @return the loaded {@link CloudConfig}, or {@code null} if the file does not exist
     *         or cannot be parsed
     */
    public static CloudConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), CloudConfig.class);
        } catch (IOException e) {
            System.err.println("Warning: Could not load cloud credentials: " + e.getMessage());
            return null;
        }
    }

    /**
     * Load credentials, falling back to environment variables if no valid credentials file
     * is present.
     *
     * <p>Environment variables checked:
     * <ul>
     *   <li>{@code KOMPILE_TOKEN} — JWT bearer token</li>
     *   <li>{@code KOMPILE_SAAS_URL} — override for the API base URL (optional)</li>
     * </ul>
     *
     * @return a {@link CloudConfig} with valid credentials, or {@code null} if none found
     */
    public static CloudConfig loadOrFromEnv() {
        CloudConfig config = load();
        if (config != null && config.isValid()) {
            return config;
        }

        String envToken = System.getenv(ENV_TOKEN);
        if (envToken != null && !envToken.isBlank()) {
            String envBaseUrl = System.getenv(ENV_BASE_URL);
            config = new CloudConfig();
            config.setToken(envToken);
            if (envBaseUrl != null && !envBaseUrl.isBlank()) {
                config.setBaseUrl(envBaseUrl);
            }
            return config;
        }

        return null;
    }

    @Override
    public String toString() {
        return "CloudConfig{baseUrl=" + getBaseUrl() +
                ", username=" + username +
                ", hasToken=" + (token != null && !token.isBlank()) + "}";
    }
}
