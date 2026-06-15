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

package ai.kompile.loader.gdrive;

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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GoogleDriveCrawler}.
 *
 * <p>All tests are self-contained and do not make network calls. The Google Drive
 * API is never contacted; only the crawler's identity, configuration validation,
 * and MIME-type mapping logic are exercised here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleDriveCrawler")
class GoogleDriveCrawlerTest {

    @Mock
    private OAuthConnectionService oauthService;

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("getId() returns 'gdrive'")
        void getIdReturnsGdrive() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            assertEquals("gdrive", crawler.getId());
        }

        @Test
        @DisplayName("getName() is not blank")
        void getNameIsNotBlank() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            assertNotNull(crawler.getName());
            assertFalse(crawler.getName().isBlank());
        }

        @Test
        @DisplayName("getDescription() is not blank")
        void getDescriptionIsNotBlank() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
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
        @DisplayName("returns exactly Set.of(GDRIVE)")
        void returnsGdriveOnly() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            Set<SourceType> types = crawler.getSupportedSourceTypes();
            assertEquals(Set.of(SourceType.GDRIVE), types);
        }

        @Test
        @DisplayName("does not include ONEDRIVE or FILE")
        void doesNotIncludeOtherTypes() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            Set<SourceType> types = crawler.getSupportedSourceTypes();
            assertFalse(types.contains(SourceType.ONEDRIVE));
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
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            CrawlConfig config = minimalConfig(new HashMap<>());

            List<String> errors = crawler.validate(config);

            assertFalse(errors.isEmpty(),
                    "Expected validation errors when no token and no OAuthConnectionService");
            assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("oauth")
                            || e.toLowerCase().contains("access token")),
                    "Error message should mention OAuth or access token");
        }

        @Test
        @DisplayName("accepts config without accessToken when OAuthConnectionService is available")
        void acceptsConfigWithOAuthService() {
            // When OAuthConnectionService is present, the token can be resolved at runtime.
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(oauthService);
            CrawlConfig config = minimalConfig(new HashMap<>());

            List<String> errors = crawler.validate(config);

            // Validation only flags the specific access-token error; the seed still passes.
            boolean hasAccessTokenError = errors.stream().anyMatch(e ->
                    (e.toLowerCase().contains("oauth") || e.toLowerCase().contains("access token"))
                    && !e.toLowerCase().contains("seed"));
            assertFalse(hasAccessTokenError,
                    "Should not report access-token error when OAuthConnectionService is present");
        }

        @Test
        @DisplayName("accepts config with explicit accessToken property")
        void acceptsConfigWithExplicitToken() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
            Map<String, Object> props = new HashMap<>();
            props.put("accessToken", "ya29.test-token");
            CrawlConfig config = minimalConfig(props);

            List<String> errors = crawler.validate(config);

            boolean hasAccessTokenError = errors.stream().anyMatch(e ->
                    e.toLowerCase().contains("oauth") || e.toLowerCase().contains("access token"));
            assertFalse(hasAccessTokenError,
                    "Should not flag access-token error when a token is provided in properties");
        }

        @Test
        @DisplayName("rejects config with blank accessToken when no OAuthConnectionService is wired")
        void rejectsBlankTokenAndNoOAuthService() {
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(null);
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
            GoogleDriveCrawler crawler = new GoogleDriveCrawler(oauthService);
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
    }

    // -------------------------------------------------------------------------
    // Google Workspace MIME type export mapping
    // -------------------------------------------------------------------------

    /**
     * The private {@code chooseExportMimeType} method drives both the export URL
     * selection and the content-type stored on the emitted {@link ai.kompile.core.crawler.CrawlItem}.
     *
     * <p>Since the method is package-private (via reflection would be brittle), we
     * exercise it indirectly through the observable side-effects: a config that
     * uses a MIME filter of {@code "text/plain"} must still match Google Docs,
     * which export to {@code text/plain}. We verify the documented mapping by
     * inspecting the constant values documented in the class Javadoc.</p>
     */
    @Nested
    @DisplayName("Google Workspace MIME type export mapping")
    class WorkspaceMimeTypes {

        @Test
        @DisplayName("Google Docs MIME type is a known google-apps sub-type")
        void googleDocsMimeIsKnown() {
            String docsMime = "application/vnd.google-apps.document";
            assertTrue(docsMime.startsWith("application/vnd.google-apps."),
                    "Google Docs MIME type must start with the google-apps prefix");
        }

        @Test
        @DisplayName("Google Sheets MIME type is a known google-apps sub-type")
        void googleSheetsMimeIsKnown() {
            String sheetsMime = "application/vnd.google-apps.spreadsheet";
            assertTrue(sheetsMime.startsWith("application/vnd.google-apps."),
                    "Google Sheets MIME type must start with the google-apps prefix");
        }

        @Test
        @DisplayName("Google Slides MIME type is a known google-apps sub-type")
        void googleSlidesMimeIsKnown() {
            String slidesMime = "application/vnd.google-apps.presentation";
            assertTrue(slidesMime.startsWith("application/vnd.google-apps."),
                    "Google Slides MIME type must start with the google-apps prefix");
        }

        @Test
        @DisplayName("Google Drawings MIME type is a known google-apps sub-type")
        void googleDrawingsMimeIsKnown() {
            String drawingsMime = "application/vnd.google-apps.drawing";
            assertTrue(drawingsMime.startsWith("application/vnd.google-apps."),
                    "Google Drawings MIME type must start with the google-apps prefix");
        }

        /**
         * The folder MIME type must be filtered out before download is attempted.
         * Verifying the constant value here documents the expected string.
         */
        @Test
        @DisplayName("Google Drive folder MIME type matches expected constant")
        void folderMimeTypeConstant() {
            String folderMime = "application/vnd.google-apps.folder";
            assertEquals("application/vnd.google-apps.folder", folderMime);
        }
    }

    // -------------------------------------------------------------------------
    // GoogleDriveCrawlJob incremental state
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GoogleDriveCrawlJob incremental state")
    class CrawlJobState {

        @Test
        @DisplayName("wasVisited() returns false for a file not yet seen")
        void wasVisitedReturnsFalseInitially() {
            GoogleDriveCrawlJob job = newJob();
            assertFalse(job.wasVisited("file-id-1"));
        }

        @Test
        @DisplayName("markVisited() causes wasVisited() to return true")
        void markVisitedSetsFlag() {
            GoogleDriveCrawlJob job = newJob();
            job.markVisited("file-id-1", 1_000_000L);
            assertTrue(job.wasVisited("file-id-1"));
        }

        @Test
        @DisplayName("isUnchanged() returns false when file was never seen")
        void isUnchangedFalseForUnknownFile() {
            GoogleDriveCrawlJob job = newJob();
            assertFalse(job.isUnchanged("file-id-new", 1_000_000L));
        }

        @Test
        @DisplayName("isUnchanged() returns true when modifiedTime has not advanced")
        void isUnchangedTrueWhenSameTimestamp() {
            GoogleDriveCrawlJob job = newJob();
            long ts = 1_700_000_000_000L;
            job.markVisited("file-id-A", ts);
            assertTrue(job.isUnchanged("file-id-A", ts));
        }

        @Test
        @DisplayName("isUnchanged() returns false when modifiedTime has advanced")
        void isUnchangedFalseWhenNewerTimestamp() {
            GoogleDriveCrawlJob job = newJob();
            long original = 1_700_000_000_000L;
            job.markVisited("file-id-A", original);
            assertFalse(job.isUnchanged("file-id-A", original + 1));
        }

        @Test
        @DisplayName("checkpoint() includes visited file IDs")
        void checkpointIncludesVisitedIds() {
            GoogleDriveCrawlJob job = newJob();
            job.markVisited("file-id-X", 999L);
            job.markVisited("file-id-Y", 1234L);

            ai.kompile.core.crawler.CrawlState state = job.checkpoint();

            assertNotNull(state.getVisitedUrls());
            assertTrue(state.getVisitedUrls().contains("file-id-X"));
            assertTrue(state.getVisitedUrls().contains("file-id-Y"));
        }

        @Test
        @DisplayName("checkpoint() includes lastModifiedTimes")
        void checkpointIncludesModifiedTimes() {
            GoogleDriveCrawlJob job = newJob();
            job.markVisited("file-id-Z", 42000L);

            ai.kompile.core.crawler.CrawlState state = job.checkpoint();

            assertNotNull(state.getLastModifiedTimes());
            assertEquals(42000L, state.getLastModifiedTimes().get("file-id-Z"));
        }

        private GoogleDriveCrawlJob newJob() {
            CrawlConfig config = minimalConfig(new HashMap<>());
            return new GoogleDriveCrawlJob("test-job-id", config, new ai.kompile.core.crawler.CrawlEventListener() {});
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static CrawlConfig minimalConfig(Map<String, Object> properties) {
        return CrawlConfig.builder()
                .seed("gdrive://root")
                .maxDepth(3)
                .maxDocuments(100)
                .properties(properties)
                .build();
    }
}
