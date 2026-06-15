package ai.kompile.app.ingest.domain;

import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CrawlJobRecord")
class CrawlJobRecordTest {

    @Nested
    @DisplayName("isResumable")
    class IsResumable {

        @Test
        void interruptedWithCheckpoint() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-1")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .lastCheckpointJson("{\"visitedUrls\":[]}")
                    .build();
            assertTrue(record.isResumable());
        }

        @Test
        void failedWithCheckpoint() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-2")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.FAILED)
                    .lastCheckpointJson("{}")
                    .build();
            assertTrue(record.isResumable());
        }

        @Test
        void cancelledWithCheckpoint() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-3")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.CANCELLED)
                    .lastCheckpointJson("{}")
                    .build();
            assertTrue(record.isResumable());
        }

        @Test
        void completedNotResumable() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-4")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.COMPLETED)
                    .lastCheckpointJson("{}")
                    .build();
            assertFalse(record.isResumable());
        }

        @Test
        void runningNotResumable() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-5")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .lastCheckpointJson("{}")
                    .build();
            assertFalse(record.isResumable());
        }

        @Test
        void interruptedWithoutCheckpointNotResumable() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-6")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .lastCheckpointJson(null)
                    .build();
            assertFalse(record.isResumable());
        }
    }

    @Nested
    @DisplayName("updateProgress")
    class UpdateProgress {

        @Test
        void setsAllCounters() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-7")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .build();

            record.updateProgress(100, 80, 10, 5);

            assertEquals(100, record.getDocumentsDiscovered());
            assertEquals(80, record.getDocumentsProcessed());
            assertEquals(10, record.getDocumentsSkipped());
            assertEquals(5, record.getDocumentsFailed());
        }

        @Test
        void overwritesPreviousValues() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-8")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .documentsDiscovered(50)
                    .documentsProcessed(40)
                    .build();

            record.updateProgress(200, 180, 15, 5);

            assertEquals(200, record.getDocumentsDiscovered());
            assertEquals(180, record.getDocumentsProcessed());
        }
    }

    @Nested
    @DisplayName("Builder and fields")
    class BuilderAndFields {

        @Test
        void builderSetsAllFields() {
            Instant now = Instant.now();
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-9")
                    .crawlerId("web")
                    .seed("https://example.com")
                    .crawlConfigJson("{\"maxDepth\":3}")
                    .lastCheckpointJson("{\"visitedUrls\":[\"https://example.com\"]}")
                    .status(CrawlJobStatus.RUNNING)
                    .startedAt(now)
                    .historyTaskId("task-123")
                    .documentsDiscovered(50)
                    .documentsProcessed(45)
                    .documentsSkipped(3)
                    .documentsFailed(2)
                    .build();

            assertEquals("job-9", record.getCrawlJobId());
            assertEquals("web", record.getCrawlerId());
            assertEquals("https://example.com", record.getSeed());
            assertNotNull(record.getCrawlConfigJson());
            assertNotNull(record.getLastCheckpointJson());
            assertEquals(CrawlJobStatus.RUNNING, record.getStatus());
            assertEquals(now, record.getStartedAt());
            assertEquals("task-123", record.getHistoryTaskId());
            assertEquals(50, record.getDocumentsDiscovered());
        }

        @Test
        void statusEnumValues() {
            assertEquals(6, CrawlJobStatus.values().length);
            assertNotNull(CrawlJobStatus.valueOf("RUNNING"));
            assertNotNull(CrawlJobStatus.valueOf("PAUSED"));
            assertNotNull(CrawlJobStatus.valueOf("COMPLETED"));
            assertNotNull(CrawlJobStatus.valueOf("FAILED"));
            assertNotNull(CrawlJobStatus.valueOf("CANCELLED"));
            assertNotNull(CrawlJobStatus.valueOf("INTERRUPTED"));
        }
    }
}
