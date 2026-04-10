package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for heartbeat message JSON serialization/deserialization,
 * message prefix parsing, and memory field round-trips.
 */
@DisplayName("Subprocess Heartbeat Serialization")
class SubprocessHeartbeatSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested @DisplayName("Heartbeat JSON serialization")
    class HeartbeatJsonSerialization {

        @Test @DisplayName("heartbeat serializes all memory fields to JSON")
        void serializesAllMemoryFields() throws Exception {
            var hb = new SubprocessMessage.Heartbeat(
                    "task-1", 30000, 65.5,
                    500_000_000L, 1_000_000_000L,
                    200_000_000L, 800_000_000L, 25.0,
                    1_500_000_000L, 8_000_000_000L, 18.75);

            String json = MAPPER.writeValueAsString(hb);
            JsonNode node = MAPPER.readTree(json);

            assertEquals("task-1", node.get("taskId").asText());
            assertEquals(30000, node.get("uptimeMs").asLong());
            assertEquals(65.5, node.get("memoryUsagePercent").asDouble(), 0.01);
            assertEquals(500_000_000L, node.get("heapUsedBytes").asLong());
            assertEquals(1_000_000_000L, node.get("heapMaxBytes").asLong());
            // Off-heap
            assertEquals(200_000_000L, node.get("offHeapUsedBytes").asLong());
            assertEquals(800_000_000L, node.get("offHeapMaxBytes").asLong());
            assertEquals(25.0, node.get("offHeapUsagePercent").asDouble(), 0.01);
            // GPU
            assertEquals(1_500_000_000L, node.get("gpuUsedBytes").asLong());
            assertEquals(8_000_000_000L, node.get("gpuMaxBytes").asLong());
            assertEquals(18.75, node.get("gpuUsagePercent").asDouble(), 0.01);
        }

        @Test @DisplayName("heartbeat with zero off-heap and GPU")
        void serializesZeroMemoryFields() throws Exception {
            var hb = new SubprocessMessage.Heartbeat(
                    "t2", 1000, 10.0,
                    100_000L, 1_000_000L,
                    0L, 0L, 0.0,
                    0L, 0L, 0.0);

            String json = MAPPER.writeValueAsString(hb);
            JsonNode node = MAPPER.readTree(json);

            assertEquals(0L, node.get("offHeapUsedBytes").asLong());
            assertEquals(0L, node.get("offHeapMaxBytes").asLong());
            assertEquals(0.0, node.get("offHeapUsagePercent").asDouble(), 0.001);
            assertEquals(0L, node.get("gpuUsedBytes").asLong());
            assertEquals(0L, node.get("gpuMaxBytes").asLong());
            assertEquals(0.0, node.get("gpuUsagePercent").asDouble(), 0.001);
        }

        @Test @DisplayName("heartbeat JSON round-trip via Jackson type info")
        void jsonRoundTrip() throws Exception {
            var original = new SubprocessMessage.Heartbeat(
                    "t3", 5000, 42.5,
                    400_000_000L, 1_073_741_824L,
                    300_000_000L, 2_147_483_648L, 13.95,
                    2_000_000_000L, 16_000_000_000L, 12.5);

            String json = MAPPER.writeValueAsString(original);
            // Deserialize via the sealed interface (uses @JsonTypeInfo)
            SubprocessMessage deserialized = MAPPER.readValue(json, SubprocessMessage.class);

            assertInstanceOf(SubprocessMessage.Heartbeat.class, deserialized);
            var hb = (SubprocessMessage.Heartbeat) deserialized;

            assertEquals(original.taskId(), hb.taskId());
            assertEquals(original.uptimeMs(), hb.uptimeMs());
            assertEquals(original.memoryUsagePercent(), hb.memoryUsagePercent(), 0.01);
            assertEquals(original.heapUsedBytes(), hb.heapUsedBytes());
            assertEquals(original.heapMaxBytes(), hb.heapMaxBytes());
            assertEquals(original.offHeapUsedBytes(), hb.offHeapUsedBytes());
            assertEquals(original.offHeapMaxBytes(), hb.offHeapMaxBytes());
            assertEquals(original.offHeapUsagePercent(), hb.offHeapUsagePercent(), 0.01);
            assertEquals(original.gpuUsedBytes(), hb.gpuUsedBytes());
            assertEquals(original.gpuMaxBytes(), hb.gpuMaxBytes());
            assertEquals(original.gpuUsagePercent(), hb.gpuUsagePercent(), 0.01);
        }

        @Test @DisplayName("heartbeat type discriminator is HEARTBEAT")
        void typeDiscriminator() throws Exception {
            var hb = new SubprocessMessage.Heartbeat(
                    "t4", 1000, 50.0,
                    500L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0);

            String json = MAPPER.writeValueAsString(hb);
            JsonNode node = MAPPER.readTree(json);
            assertEquals("HEARTBEAT", node.get("type").asText());
        }

        @Test @DisplayName("high memory values serialize correctly")
        void highMemoryValues() throws Exception {
            // Simulate 32GB heap, 64GB off-heap, 80GB GPU
            var hb = new SubprocessMessage.Heartbeat(
                    "t5", 600000, 99.9,
                    34_359_738_000L, 34_359_738_368L,
                    68_719_476_736L, 68_719_476_736L, 100.0,
                    85_899_345_920L, 85_899_345_920L, 100.0);

            String json = MAPPER.writeValueAsString(hb);
            SubprocessMessage deserialized = MAPPER.readValue(json, SubprocessMessage.class);
            var result = (SubprocessMessage.Heartbeat) deserialized;

            assertEquals(34_359_738_000L, result.heapUsedBytes());
            assertEquals(68_719_476_736L, result.offHeapUsedBytes());
            assertEquals(85_899_345_920L, result.gpuUsedBytes());
        }
    }

    @Nested @DisplayName("Message prefix parsing")
    class MessagePrefixParsing {

        @Test @DisplayName("extract heartbeat JSON from prefixed string")
        void extractHeartbeatFromPrefixedString() throws Exception {
            var hb = new SubprocessMessage.Heartbeat(
                    "t1", 5000, 45.0,
                    500_000_000L, 1_073_741_824L,
                    100_000_000L, 500_000_000L, 20.0,
                    0L, 0L, 0.0);

            String json = MAPPER.writeValueAsString(hb);
            String prefixedLine = SubprocessMessage.MESSAGE_PREFIX + json;

            // Simulate the parent-side parsing
            assertTrue(prefixedLine.startsWith(SubprocessMessage.MESSAGE_PREFIX));
            String extracted = prefixedLine.substring(SubprocessMessage.MESSAGE_PREFIX.length());
            SubprocessMessage parsed = MAPPER.readValue(extracted, SubprocessMessage.class);

            assertInstanceOf(SubprocessMessage.Heartbeat.class, parsed);
            var result = (SubprocessMessage.Heartbeat) parsed;
            assertEquals("t1", result.taskId());
            assertEquals(5000, result.uptimeMs());
            assertEquals(100_000_000L, result.offHeapUsedBytes());
        }

        @Test @DisplayName("extract progress from prefixed string")
        void extractProgressFromPrefixedString() throws Exception {
            var progress = SubprocessMessage.progress("t2", "EMBEDDING", 75, "Batch 3/4", "Processing");
            String json = MAPPER.writeValueAsString(progress);
            String prefixedLine = SubprocessMessage.MESSAGE_PREFIX + json;

            String extracted = prefixedLine.substring(SubprocessMessage.MESSAGE_PREFIX.length());
            SubprocessMessage parsed = MAPPER.readValue(extracted, SubprocessMessage.class);

            assertInstanceOf(SubprocessMessage.Progress.class, parsed);
            assertEquals(75, ((SubprocessMessage.Progress) parsed).progressPercent());
        }

        @Test @DisplayName("extract completed from prefixed string")
        void extractCompletedFromPrefixedString() throws Exception {
            var completed = SubprocessMessage.completed("t3", 10, 500, 500, 500,
                    75000L, 150000L, 25000L, "/data/index",
                    java.util.Map.of("LOADING", 5000L, "EMBEDDING", 20000L));

            String json = MAPPER.writeValueAsString(completed);
            String extracted = (SubprocessMessage.MESSAGE_PREFIX + json)
                    .substring(SubprocessMessage.MESSAGE_PREFIX.length());
            SubprocessMessage parsed = MAPPER.readValue(extracted, SubprocessMessage.class);

            assertInstanceOf(SubprocessMessage.Completed.class, parsed);
            assertEquals("/data/index", ((SubprocessMessage.Completed) parsed).indexPath());
        }

        @Test @DisplayName("extract failed from prefixed string")
        void extractFailedFromPrefixedString() throws Exception {
            var failed = SubprocessMessage.failed("t4", "INDEXING",
                    new RuntimeException("Lock acquisition timeout"));

            String json = MAPPER.writeValueAsString(failed);
            String extracted = (SubprocessMessage.MESSAGE_PREFIX + json)
                    .substring(SubprocessMessage.MESSAGE_PREFIX.length());
            SubprocessMessage parsed = MAPPER.readValue(extracted, SubprocessMessage.class);

            assertInstanceOf(SubprocessMessage.Failed.class, parsed);
            assertTrue(((SubprocessMessage.Failed) parsed).errorMessage().contains("Lock"));
        }

        @Test @DisplayName("non-prefixed lines are not messages")
        void nonPrefixedLinesAreNotMessages() {
            String logLine = "2024-01-01 10:00:00 INFO Starting subprocess...";
            assertFalse(logLine.startsWith(SubprocessMessage.MESSAGE_PREFIX));
        }

        @Test @DisplayName("all message types round-trip through prefix")
        void allTypesRoundTripThroughPrefix() throws Exception {
            SubprocessMessage[] messages = {
                    SubprocessMessage.progress("t", "LOADING", 50, "step", "msg"),
                    SubprocessMessage.phaseTransition("t", "LOADING", "CHUNKING", 5000),
                    new SubprocessMessage.Heartbeat("t", 1000, 50.0,
                            500L, 1000L, 100L, 500L, 20.0, 0L, 0L, 0.0),
                    SubprocessMessage.completed("t", 1, 1, 1, 1, 100, 100, 1000, "/idx",
                            java.util.Map.of()),
                    SubprocessMessage.failed("t", "P", "err", "Type", "trace"),
                    SubprocessMessage.workerStatus("t", "w1", "emb", "proc", 10, 32, 5.0, "b1"),
                    SubprocessMessage.log("t", "INFO", "src", "msg")
            };

            for (SubprocessMessage original : messages) {
                String json = MAPPER.writeValueAsString(original);
                String prefixed = SubprocessMessage.MESSAGE_PREFIX + json;

                assertTrue(prefixed.startsWith(SubprocessMessage.MESSAGE_PREFIX));
                String extracted = prefixed.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                SubprocessMessage parsed = MAPPER.readValue(extracted, SubprocessMessage.class);

                assertEquals(original.getClass(), parsed.getClass(),
                        "Type mismatch for " + original.getClass().getSimpleName());
                assertEquals(original.taskId(), parsed.taskId());
            }
        }
    }

    @Nested @DisplayName("Heartbeat factory method")
    class HeartbeatFactory {

        @Test @DisplayName("factory produces valid memory percentages")
        void factoryProducesValidPercentages() {
            var hb = SubprocessMessage.heartbeat("t1", 5000);
            assertTrue(hb.memoryUsagePercent() >= 0 && hb.memoryUsagePercent() <= 100,
                    "Heap usage should be 0-100, was " + hb.memoryUsagePercent());
            assertTrue(hb.heapUsedBytes() >= 0);
            assertTrue(hb.heapMaxBytes() > 0);
            assertTrue(hb.heapUsedBytes() <= hb.heapMaxBytes());
        }

        @Test @DisplayName("factory off-heap values are non-negative")
        void factoryOffHeapNonNegative() {
            var hb = SubprocessMessage.heartbeat("t2", 1000);
            assertTrue(hb.offHeapUsedBytes() >= 0, "Off-heap used should be >= 0");
            assertTrue(hb.offHeapMaxBytes() >= 0, "Off-heap max should be >= 0");
            assertTrue(hb.offHeapUsagePercent() >= 0, "Off-heap percent should be >= 0");
        }

        @Test @DisplayName("factory GPU values are non-negative")
        void factoryGpuNonNegative() {
            var hb = SubprocessMessage.heartbeat("t3", 2000);
            assertTrue(hb.gpuUsedBytes() >= 0, "GPU used should be >= 0");
            assertTrue(hb.gpuMaxBytes() >= 0, "GPU max should be >= 0");
            assertTrue(hb.gpuUsagePercent() >= 0, "GPU percent should be >= 0");
        }

        @Test @DisplayName("factory uptime is preserved")
        void factoryUptimePreserved() {
            var hb = SubprocessMessage.heartbeat("t4", 99999);
            assertEquals(99999, hb.uptimeMs());
            assertEquals("t4", hb.taskId());
        }

        @Test @DisplayName("consecutive heartbeats have consistent heap data")
        void consecutiveHeartbeatsConsistent() {
            var hb1 = SubprocessMessage.heartbeat("t5", 1000);
            var hb2 = SubprocessMessage.heartbeat("t5", 2000);
            // Both should report the same max heap (JVM max doesn't change)
            assertEquals(hb1.heapMaxBytes(), hb2.heapMaxBytes(),
                    "Max heap should be consistent between heartbeats");
        }
    }

    @Nested @DisplayName("Edge cases")
    class EdgeCases {

        @Test @DisplayName("heartbeat with maximum long values")
        void maxLongValues() throws Exception {
            var hb = new SubprocessMessage.Heartbeat(
                    "t-max", Long.MAX_VALUE, 100.0,
                    Long.MAX_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, Long.MAX_VALUE, 100.0,
                    Long.MAX_VALUE, Long.MAX_VALUE, 100.0);

            String json = MAPPER.writeValueAsString(hb);
            var parsed = (SubprocessMessage.Heartbeat) MAPPER.readValue(json, SubprocessMessage.class);
            assertEquals(Long.MAX_VALUE, parsed.heapUsedBytes());
            assertEquals(Long.MAX_VALUE, parsed.offHeapUsedBytes());
            assertEquals(Long.MAX_VALUE, parsed.gpuUsedBytes());
        }

        @Test @DisplayName("heartbeat with empty taskId")
        void emptyTaskId() {
            var hb = new SubprocessMessage.Heartbeat(
                    "", 0, 0.0, 0L, 0L, 0L, 0L, 0.0, 0L, 0L, 0.0);
            assertEquals("", hb.taskId());
        }

        @Test @DisplayName("malformed JSON after prefix is not parseable")
        void malformedJsonAfterPrefix() {
            String malformed = SubprocessMessage.MESSAGE_PREFIX + "{not-valid-json";
            assertThrows(Exception.class, () ->
                    MAPPER.readValue(malformed.substring(SubprocessMessage.MESSAGE_PREFIX.length()),
                            SubprocessMessage.class));
        }

        @Test @DisplayName("JSON with unknown type field fails gracefully")
        void unknownTypeField() {
            String json = "{\"type\":\"UNKNOWN\",\"taskId\":\"t1\"}";
            assertThrows(Exception.class, () ->
                    MAPPER.readValue(json, SubprocessMessage.class));
        }
    }
}
