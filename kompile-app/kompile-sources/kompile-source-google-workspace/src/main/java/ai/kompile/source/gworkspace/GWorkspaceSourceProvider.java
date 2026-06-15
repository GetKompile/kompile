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

package ai.kompile.source.gworkspace;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;

import java.util.*;

/**
 * Source provider that registers Google Workspace as a data source in the Kompile UI.
 * Provides form fields for configuring the Google OAuth token, service selection,
 * Gmail/Drive/Calendar query options, and history depth.
 */
public class GWorkspaceSourceProvider implements SourceProvider {

    private boolean enabled = true;

    @Override
    public String getId() {
        return "google-workspace";
    }

    @Override
    public String getDisplayName() {
        return "Google Workspace";
    }

    @Override
    public String getDescription() {
        return "Import emails from Gmail, files from Google Drive, and events from Google Calendar using OAuth";
    }

    @Override
    public String getIcon() {
        return "cloud";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 7;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Google Workspace integration is disabled. Set kompile.gworkspace.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public String getAuthType() {
        return "oauth2";
    }

    @Override
    public String getOAuthProvider() {
        return "google";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oauthProvider", "google");
        config.put("requiredScopes", List.of(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/drive.readonly",
                "https://www.googleapis.com/auth/calendar.readonly"
        ));
        return config;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        List<SourceFormField> fields = new ArrayList<>();

        // Services to crawl
        fields.add(SourceFormField.builder()
                .id("services")
                .label("Services")
                .type(SourceFormField.FieldType.TEXT)
                .defaultValue("gmail,drive,calendar")
                .helpText("Comma-separated list of services to crawl: gmail, drive, calendar")
                .required(false)
                .order(1)
                .build());

        // Days of history
        fields.add(SourceFormField.builder()
                .id("daysBack")
                .label("Days of History")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("30")
                .min(1)
                .max(3650)
                .helpText("Number of days of history to fetch across all services")
                .order(2)
                .build());

        // Gmail query
        fields.add(SourceFormField.builder()
                .id("gmailQuery")
                .label("Gmail Search Query")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. label:inbox, from:user@example.com")
                .helpText("Gmail search query to filter messages (leave empty for all messages within date range)")
                .group("gmail")
                .order(3)
                .build());

        // Gmail max messages
        fields.add(SourceFormField.builder()
                .id("gmailMaxMessages")
                .label("Max Gmail Messages")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .min(1)
                .max(50000)
                .helpText("Maximum number of Gmail messages to fetch")
                .group("gmail")
                .order(4)
                .build());

        // Include Gmail attachments
        fields.add(SourceFormField.builder()
                .id("includeGmailAttachments")
                .label("Include Gmail Attachments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Download and index file attachments from Gmail messages")
                .group("gmail")
                .order(5)
                .build());

        // Drive query
        fields.add(SourceFormField.builder()
                .id("driveQuery")
                .label("Drive Search Query")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. name contains 'report', mimeType='application/pdf'")
                .helpText("Drive search query to filter files (leave empty for all recently modified files)")
                .group("drive")
                .order(6)
                .build());

        // Drive max files
        fields.add(SourceFormField.builder()
                .id("driveMaxFiles")
                .label("Max Drive Files")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .min(1)
                .max(50000)
                .helpText("Maximum number of Drive files to fetch")
                .group("drive")
                .order(7)
                .build());

        // Include Drive comments
        fields.add(SourceFormField.builder()
                .id("includeDriveComments")
                .label("Include Drive Comments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Include comments and discussions on Drive files")
                .group("drive")
                .order(8)
                .build());

        // Calendar IDs
        fields.add(SourceFormField.builder()
                .id("calendarIds")
                .label("Calendar IDs")
                .type(SourceFormField.FieldType.TEXT)
                .defaultValue("primary")
                .placeholder("primary, team@group.calendar.google.com")
                .helpText("Comma-separated calendar IDs to crawl (use 'primary' for the main calendar)")
                .group("calendar")
                .order(9)
                .build());

        // Calendar max events
        fields.add(SourceFormField.builder()
                .id("calendarMaxEvents")
                .label("Max Calendar Events")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .min(1)
                .max(10000)
                .helpText("Maximum number of calendar events to fetch per calendar")
                .group("calendar")
                .order(10)
                .build());

        return fields;
    }
}
