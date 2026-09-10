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

package ai.kompile.source.reddit;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source provider for Reddit posts and comments.
 * Uses the centralized Reddit OAuth connection managed by {@code kompile auth source}.
 */
public class RedditSourceProvider implements SourceProvider {

    @Value("${kompile.reddit.enabled:true}")
    private boolean enabled;

    private final OAuthConnectionService oauthService;

    @Autowired
    public RedditSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "reddit";
    }

    @Override
    public String getDisplayName() {
        return "Reddit";
    }

    @Override
    public String getDescription() {
        return "Import posts and comments from Reddit subreddits";
    }

    @Override
    public String getIcon() {
        return "forum";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Reddit integration is disabled. Set kompile.reddit.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        return oauthService == null || !oauthService.isConnectionUsable("reddit");
    }

    @Override
    public String getAuthType() {
        return "oauth2";
    }

    @Override
    public String getOAuthProvider() {
        return "reddit";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oauthConfigured", oauthConfigured());

        if (oauthService != null) {
            OAuthConnectionStatus status = oauthService.getConnectionStatus("reddit");
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

        // Reddit-specific configuration
        config.put("supportedSortTypes", Arrays.asList("hot", "new", "top", "rising", "controversial"));
        config.put("supportedTimePeriods", Arrays.asList("hour", "day", "week", "month", "year", "all"));

        return config;
    }

    private boolean oauthConfigured() {
        return oauthService != null && oauthService.getProviderInfo("reddit")
                        .map(info -> info.isConfigured()).orElse(false);
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("subreddit")
                        .label("Subreddit")
                        .type(SourceFormField.FieldType.TEXT)
                        .required(true)
                        .placeholder("programming")
                        .helpText("The subreddit name (without the r/ prefix)")
                        .prefixIcon("tag")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("sortType")
                        .label("Sort By")
                        .type(SourceFormField.FieldType.SELECT)
                        .defaultValue("hot")
                        .options(Arrays.asList(
                                SourceFormField.SelectOption.builder().value("hot").label("Hot").build(),
                                SourceFormField.SelectOption.builder().value("new").label("New").build(),
                                SourceFormField.SelectOption.builder().value("top").label("Top").build(),
                                SourceFormField.SelectOption.builder().value("rising").label("Rising").build(),
                                SourceFormField.SelectOption.builder().value("controversial").label("Controversial").build()
                        ))
                        .helpText("How to sort the posts")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("timePeriod")
                        .label("Time Period")
                        .type(SourceFormField.FieldType.SELECT)
                        .defaultValue("week")
                        .options(Arrays.asList(
                                SourceFormField.SelectOption.builder().value("hour").label("Past Hour").build(),
                                SourceFormField.SelectOption.builder().value("day").label("Past 24 Hours").build(),
                                SourceFormField.SelectOption.builder().value("week").label("Past Week").build(),
                                SourceFormField.SelectOption.builder().value("month").label("Past Month").build(),
                                SourceFormField.SelectOption.builder().value("year").label("Past Year").build(),
                                SourceFormField.SelectOption.builder().value("all").label("All Time").build()
                        ))
                        .helpText("Time period for top/controversial posts")
                        .order(3)
                        .showWhen(Map.of("sortType", Arrays.asList("top", "controversial")))
                        .build(),
                SourceFormField.builder()
                        .id("postLimit")
                        .label("Post Limit")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(100)
                        .min(1)
                        .max(1000)
                        .helpText("Maximum number of posts to import")
                        .order(4)
                        .build(),
                SourceFormField.builder()
                        .id("includeComments")
                        .label("Include Comments")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Include comment threads on each post")
                        .order(5)
                        .build(),
                SourceFormField.builder()
                        .id("commentDepth")
                        .label("Comment Depth")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(3)
                        .min(1)
                        .max(10)
                        .helpText("Maximum depth of comment threads to fetch")
                        .order(6)
                        .group("advanced")
                        .showWhen(Map.of("includeComments", true))
                        .build(),
                SourceFormField.builder()
                        .id("commentLimit")
                        .label("Comments per Post")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(50)
                        .min(0)
                        .max(500)
                        .helpText("Maximum comments per post (0 disables comment loading)")
                        .order(7)
                        .group("advanced")
                        .showWhen(Map.of("includeComments", true))
                        .build(),
                SourceFormField.builder()
                        .id("minScore")
                        .label("Minimum Score")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(0)
                        .min(0)
                        .helpText("Only include posts with this minimum score (upvotes)")
                        .order(8)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("includeNsfw")
                        .label("Include NSFW Content")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(false)
                        .helpText("Include posts marked as NSFW")
                        .order(9)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("searchQuery")
                        .label("Search Query")
                        .type(SourceFormField.FieldType.TEXT)
                        .required(false)
                        .placeholder("Optional search query")
                        .helpText("Filter posts by search query within the subreddit")
                        .order(10)
                        .group("advanced")
                        .build()
        );
    }
}
