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

package ai.kompile.loader.onedrive;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OneDriveCrawler}.
 *
 * <p>All tests are self-contained and do not make network calls. The Microsoft
 * Graph API is never contacted; only the crawler's identity and configuration
 * validation logic are exercised here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OneDriveCrawler")
class OneDriveCrawlerTest {

    @Mock
    private OAuthConnectionService oauthService;

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("getId() returns 'onedrive'")
        void getIdReturnsOnedrive() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            assertEquals("onedrive", crawler.getId());
        }

        @Test
        @DisplayName("getName() is not blank")
        void getNameIsNotBlank() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            assertNotNull(crawler.getName());
            assertFalse(crawler.getName().isBlank());
        }

        @Test
        @DisplayName("getDescription() is not blank")
        void getDescriptionIsNotBlank() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            assertNotNull(crawler.getDescription());
            assertFalse(crawler.getDescription().isBlank());
        }
    }

    // -------------------------------------------------------------------------
    // Supported source types
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getSupportedSourceTypes()")
    class SupportedSourceTypes {

        @Test
        @DisplayName("returns exactly Set.of(ONEDRIVE)")
        void returnsOnedriveOnly() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            Set<SourceType> types = crawler.getSupportedSourceTypes();
            assertEquals(Set.of(SourceType.ONEDRIVE), types);
        }

        @Test
        @DisplayName("does not include GDRIVE or FILE")
        void doesNotIncludeOtherTypes() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            Set<SourceType> types = crawler.getSupportedSourceTypes();
            assertFalse(types.contains(SourceType.GDRIVE));
            assertFalse(types.contains(SourceType.FILE));
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validate()")
    class Validation {

        @Test
        @DisplayName("rejects config without accessToken when no OAuthConnectionService is wired")
        void rejectsNoTokenAndNoOAuthService() {
            // No OAuthConnectionService and no token in properties → must report an error.
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            CrawlConfig config = minimalConfig(new HashMap<>());

            List<String> errors = crawler.validate(config);

            assertFalse(errors.isEmpty(),
                    "Expected validation errors when no token and no OAuthConnectionService");
            assertTrue(errors.stream().anyMatch(e ->
                            e.toLowerCase().contains("oauth") || e.toLowerCase().contains("access token")
                            || e.toLowerCase().contains("microsoft")),
                    "Error message should mention OAuth, access token, or microsoft");
        }

        @Test
        @DisplayName("accepts config without accessToken when OAuthConnectionService is present")
        void acceptsConfigWithOAuthService() {
            // OAuthConnectionService can supply the token at runtime; validation should not fail.
            when(oauthService.getValidAccessToken(eq("microsoft"))).thenReturn(null);

            OneDriveCrawler crawler = new OneDriveCrawler(oauthService);
            CrawlConfig config = minimalConfig(new HashMap<>());

            List<String> errors = crawler.validate(config);

            boolean hasTokenError = errors.stream().anyMatch(e ->
                    (e.toLowerCase().contains("oauth") || e.toLowerCase().contains("access token")
                     || e.toLowerCase().contains("microsoft"))
                    && !e.toLowerCase().contains("seed"));
            assertFalse(hasTokenError,
                    "Should not report token error when OAuthConnectionService is wired");
        }

        @Test
        @DisplayName("accepts config with explicit accessToken property")
        void acceptsConfigWithExplicitToken() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            Map<String, Object> props = new HashMap<>();
            props.put("accessToken", "eyJ0eXAiOiJKV1QiLCJhbGciOiJub25lIn0.test");
            CrawlConfig config = minimalConfig(props);

            List<String> errors = crawler.validate(config);

            boolean hasTokenError = errors.stream().anyMatch(e ->
                    e.toLowerCase().contains("oauth") || e.toLowerCase().contains("access token")
                    || e.toLowerCase().contains("microsoft"));
            assertFalse(hasTokenError,
                    "Should not flag token error when accessToken is supplied in properties");
        }

        @Test
        @DisplayName("rejects config with blank accessToken and no OAuthConnectionService")
        void rejectsBlankTokenAndNoOAuthService() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            Map<String, Object> props = new HashMap<>();
            props.put("accessToken", "   ");
            CrawlConfig config = minimalConfig(props);

            List<String> errors = crawler.validate(config);

            assertFalse(errors.isEmpty(),
                    "Blank token with no OAuthConnectionService should produce validation errors");
        }

        @Test
        @DisplayName("base class requires a non-blank seed")
        void baseClassRequiresSeed() {
            OneDriveCrawler crawler = new OneDriveCrawler(oauthService);
            CrawlConfig config = CrawlConfig.builder()
                    .seed("")
                    .maxDepth(3)
                    .maxDocuments(100)
                    .properties(new HashMap<>())
                    .build();

            List<String> errors = crawler.validate(config);

            assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("seed")),
                    "Expected seed validation error for blank seed");
        }

        @Test
        @DisplayName("passes with valid config containing an explicit token")
        void passesWithValidConfig() {
            OneDriveCrawler crawler = new OneDriveCrawler(null);
            Map<String, Object> props = new HashMap<>();
            props.put("accessToken", "valid-token");
            props.put("driveId", "me");
            props.put("folderId", "root");
            CrawlConfig config = minimalConfig(props);

            List<String> errors = crawler.validate(config);

            // The only errors at this point would come from the base-class checks
            // (seed, maxDepth, maxDocuments). None of those are violated here.
            boolean hasCrawlerSpecificError = errors.stream().anyMatch(e ->
                    e.toLowerCase().contains("microsoft") || e.toLowerCase().contains("access token"));
            assertFalse(hasCrawlerSpecificError);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static CrawlConfig minimalConfig(Map<String, Object> properties) {
        return CrawlConfig.builder()
                .seed("onedrive://root")
                .maxDepth(3)
                .maxDocuments(100)
                .properties(properties)
                .build();
    }
}
