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

package ai.kompile.source.gdocs;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source provider for Google Docs crawling.
 * Registers Google Docs as a data source in the UI with configuration form fields
 * for Drive queries, folder selection, structured parsing, and comment indexing.
 */
public class GoogleDocsSourceProvider implements SourceProvider {

    private boolean enabled = true;

    private String clientId = "";

    private final OAuthConnectionService oauthService;

    @Autowired
    public GoogleDocsSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "gdocs";
    }

    @Override
    public String getDisplayName() {
        return "Google Docs";
    }

    @Override
    public String getDescription() {
        return "Index Google Docs documents with structured content parsing. "
                + "Supports folder-recursive discovery, headings/tables/lists extraction, "
                + "comment indexing, and incremental sync.";
    }

    @Override
    public String getIcon() {
        return "description";
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
        return enabled && clientId != null && !clientId.isEmpty();
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Google Docs integration is disabled. Set kompile.gdocs.enabled=true to enable.";
        }
        if (clientId == null || clientId.isEmpty()) {
            return "Google Docs requires Google OAuth client ID. Configure via Settings > Connections > Google.";
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
                "https://www.googleapis.com/auth/drive.readonly",
                "https://www.googleapis.com/auth/documents.readonly"
        ));

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("google");
            config.put("oauthConnected", status.isConnected());
            config.put("oauthStatus", status.getStatus().name());
            if (status.getUserEmail() != null) {
                config.put("connectedUserEmail", status.getUserEmail());
            }
            if (status.getUserName() != null) {
                config.put("connectedUserName", status.getUserName());
            }
        } else {
            config.put("oauthConnected", false);
            config.put("oauthStatus", "NOT_CONFIGURED");
        }

        return config;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        List<SourceFormField> fields = new ArrayList<>();

        // Drive query
        fields.add(SourceFormField.builder()
                .id("driveQuery")
                .label("Drive Search Query")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. name contains 'project', 'folderId' in parents")
                .helpText("Additional Google Drive file query to filter documents. "
                        + "Leave empty to index all Google Docs you have access to.")
                .order(1)
                .group("search")
                .build());

        // Folder ID
        fields.add(SourceFormField.builder()
                .id("folderId")
                .label("Folder ID")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("e.g. 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgVE2ktIs")
                .helpText("Restrict crawling to a specific Drive folder (recursive). "
                        + "Find the folder ID in the URL when viewing the folder in Drive.")
                .order(2)
                .group("search")
                .build());

        // Days back
        fields.add(SourceFormField.builder()
                .id("daysBack")
                .label("Days Back")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("90")
                .helpText("How many days of document history to index on the first crawl. "
                        + "Subsequent crawls only fetch modified documents.")
                .min(1)
                .max(3650)
                .order(3)
                .group("search")
                .build());

        // Max documents
        fields.add(SourceFormField.builder()
                .id("maxDocuments")
                .label("Max Documents")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue("500")
                .helpText("Maximum number of documents to index per crawl.")
                .min(1)
                .max(50000)
                .order(4)
                .group("search")
                .build());

        // Use Docs API (structured parsing)
        fields.add(SourceFormField.builder()
                .id("useDocsApi")
                .label("Structured Parsing")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("true")
                .helpText("Use the Google Docs API for rich structured parsing "
                        + "(headings, tables, lists, footnotes). "
                        + "Disable to use plain text export instead.")
                .order(5)
                .group("advanced")
                .build());

        // Include comments
        fields.add(SourceFormField.builder()
                .id("includeComments")
                .label("Index Comments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("false")
                .helpText("Index document comments and replies as separate searchable items.")
                .order(6)
                .group("advanced")
                .build());

        // Include revisions
        fields.add(SourceFormField.builder()
                .id("includeRevisions")
                .label("Index Revisions")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue("false")
                .helpText("Index document revision history metadata for change tracking.")
                .order(7)
                .group("advanced")
                .build());

        return fields;
    }
}
