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

import java.util.List;
import java.util.Map;

/**
 * Interface for pluggable source providers.
 * Each source provider represents a type of document source (File Upload, URL,
 * Google Drive, Confluence, Jira, Slack, etc.) that can be dynamically registered
 * and discovered by the UI.
 *
 * Source providers are automatically detected via Spring's dependency injection
 * and exposed to the frontend through a registry API.
 */
public interface SourceProvider {

    /**
     * Gets the unique identifier for this source provider.
     * This is used to identify the provider in API calls.
     * Examples: "file", "url", "youtube", "confluence", "jira", "gdrive", "notion", "slack"
     *
     * @return unique string identifier (lowercase, no spaces)
     */
    String getId();

    /**
     * Gets the display name for this source provider.
     * This is shown in the UI menu.
     * Examples: "Upload Files", "Add Web Page", "Google Drive", "Confluence"
     *
     * @return human-readable display name
     */
    String getDisplayName();

    /**
     * Gets a brief description of what this source provider does.
     * Shown as a tooltip or help text in the UI.
     *
     * @return description text
     */
    String getDescription();

    /**
     * Gets the Material icon name to display for this source.
     * See: https://fonts.google.com/icons
     * Examples: "upload_file", "link", "cloud", "view_kanban", "auto_stories"
     *
     * @return Material icon name
     */
    String getIcon();

    /**
     * Gets the category this source belongs to.
     * Used to group sources in the UI menu.
     * Standard categories: "local", "web", "cloud", "collaboration"
     *
     * @return category identifier
     */
    String getCategory();

    /**
     * Gets the display order within the category.
     * Lower numbers appear first.
     *
     * @return sort order (0 is highest priority)
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Checks if this source provider is currently available/enabled.
     * A provider might be unavailable if required configuration is missing
     * or if a required external service is not accessible.
     *
     * @return true if the provider can be used, false otherwise
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Gets a message explaining why this provider is unavailable.
     * Only relevant when isAvailable() returns false.
     *
     * @return explanation message or null if available
     */
    default String getUnavailableReason() {
        return null;
    }

    /**
     * Gets the UI form configuration for this source provider.
     * This describes the form fields needed to configure this source.
     * The frontend uses this to dynamically render the appropriate dialog.
     *
     * @return list of form field configurations
     */
    List<SourceFormField> getFormFields();

    /**
     * Gets any additional configuration/settings specific to this provider.
     * This can include things like supported file types, default values, etc.
     *
     * @return map of configuration key-value pairs
     */
    default Map<String, Object> getConfiguration() {
        return Map.of();
    }

    /**
     * Whether this source provider requires authentication.
     * If true, the UI will prompt for credentials or OAuth.
     *
     * @return true if authentication is required
     */
    default boolean requiresAuth() {
        return false;
    }

    /**
     * Gets the authentication type if required.
     * Examples: "oauth2", "api_key", "basic", "token"
     *
     * @return auth type identifier or null if no auth required
     */
    default String getAuthType() {
        return null;
    }

    /**
     * Gets the OAuth provider name if using OAuth.
     * Examples: "google", "microsoft", "atlassian", "notion"
     *
     * @return OAuth provider name or null
     */
    default String getOAuthProvider() {
        return null;
    }

    /**
     * Whether this source supports batch/bulk operations.
     * For example, selecting multiple files or pages at once.
     *
     * @return true if batch selection is supported
     */
    default boolean supportsBatch() {
        return false;
    }

    /**
     * Whether this source needs a specialized dialog component.
     * If true, the frontend should load a provider-specific dialog.
     * If false, the generic form field renderer is used.
     *
     * @return true if a custom dialog is needed
     */
    default boolean hasCustomDialog() {
        return false;
    }

    /**
     * Gets the custom dialog component name if hasCustomDialog() returns true.
     * This is the Angular component name to dynamically load.
     *
     * @return component name or null if using generic dialog
     */
    default String getCustomDialogComponent() {
        return null;
    }
}
