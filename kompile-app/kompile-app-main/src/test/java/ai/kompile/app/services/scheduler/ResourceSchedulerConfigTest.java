package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResourceSchedulerConfig")
class ResourceSchedulerConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        void defaultsReturnsExpectedValues() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();

            assertTrue(config.isEnabled());
            assertEquals(50, config.getGlobalQueueDepth());
            assertEquals("PRIORITY", config.getSchedulingAlgorithm());
            assertEquals(500, config.getDispatchIntervalMs());
            assertEquals(3_600_000, config.getQueueTimeoutMs());
            assertTrue(config.isPhaseAwareYieldEnabled());
            assertEquals(2000, config.getBatchWindowMs());
            assertEquals(30, config.getHistoryRetentionDays());
            assertEquals(10_000, config.getMaxHistoryEntries());
        }

        @Test
        void defaultExternalSchedulerIsNone() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();

            assertEquals("none", config.getExternalSchedulerMode());
            assertFalse(config.isExternalSchedulerEnabled());
            assertEquals("kompile", config.getKubernetesNamespace());
            assertEquals("kompile-worker", config.getKubernetesServiceAccount());
            assertEquals("konduitai/kompile:latest", config.getKubernetesJobImage());
            assertEquals("", config.getExternalWebhookUrl());
            assertEquals("", config.getExternalAuthToken());
        }

        @Test
        void defaultMaxConcurrentByType() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();

            assertEquals(4, config.getMaxConcurrentForType("ingest"));
            assertEquals(1, config.getMaxConcurrentForType("vectorPopulation"));
            assertEquals(4, config.getMaxConcurrentForType("crawl"));
            assertEquals(1, config.getMaxConcurrentForType("training"));
            assertEquals(1, config.getMaxConcurrentForType("vlm"));
            assertEquals(1, config.getMaxConcurrentForType("modelInit"));
            assertEquals(1, config.getMaxConcurrentForType("llm"));
            // Unknown type defaults to 1
            assertEquals(1, config.getMaxConcurrentForType("unknown-type"));
        }
    }

    @Nested
    @DisplayName("External scheduler mode")
    class ExternalMode {

        @Test
        void isExternalSchedulerEnabledForKubernetes() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setExternalSchedulerMode("kubernetes");
            assertTrue(config.isExternalSchedulerEnabled());
        }

        @Test
        void isExternalSchedulerEnabledForWebhook() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setExternalSchedulerMode("webhook");
            assertTrue(config.isExternalSchedulerEnabled());
        }

        @Test
        void isExternalSchedulerDisabledForNone() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setExternalSchedulerMode("none");
            assertFalse(config.isExternalSchedulerEnabled());
        }

        @Test
        void isExternalSchedulerDisabledForBlank() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setExternalSchedulerMode("");
            assertFalse(config.isExternalSchedulerEnabled());
        }

        @Test
        void isExternalSchedulerDisabledForNull() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setExternalSchedulerMode(null);
            assertFalse(config.isExternalSchedulerEnabled());
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonTests {

        @Test
        void roundTripSerialization() throws Exception {
            ResourceSchedulerConfig original = new ResourceSchedulerConfig();
            original.setEnabled(false);
            original.setGlobalQueueDepth(100);
            original.setSchedulingAlgorithm("FAIR");
            original.setExternalSchedulerMode("kubernetes");
            original.setKubernetesNamespace("prod");

            String json = objectMapper.writeValueAsString(original);
            ResourceSchedulerConfig deserialized = objectMapper.readValue(json, ResourceSchedulerConfig.class);

            assertFalse(deserialized.isEnabled());
            assertEquals(100, deserialized.getGlobalQueueDepth());
            assertEquals("FAIR", deserialized.getSchedulingAlgorithm());
            assertEquals("kubernetes", deserialized.getExternalSchedulerMode());
            assertEquals("prod", deserialized.getKubernetesNamespace());
        }

        @Test
        void unknownFieldsAreIgnored() throws Exception {
            String json = """
                    {"enabled": true, "futureField": "should-be-ignored", "globalQueueDepth": 25}
                    """;
            ResourceSchedulerConfig config = objectMapper.readValue(json, ResourceSchedulerConfig.class);
            assertTrue(config.isEnabled());
            assertEquals(25, config.getGlobalQueueDepth());
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        void allSettersWork() {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();

            config.setEnabled(false);
            assertFalse(config.isEnabled());

            config.setDispatchIntervalMs(1000);
            assertEquals(1000, config.getDispatchIntervalMs());

            config.setQueueTimeoutMs(7200000);
            assertEquals(7200000, config.getQueueTimeoutMs());

            config.setPhaseAwareYieldEnabled(false);
            assertFalse(config.isPhaseAwareYieldEnabled());

            config.setBatchWindowMs(5000);
            assertEquals(5000, config.getBatchWindowMs());

            config.setHistoryRetentionDays(7);
            assertEquals(7, config.getHistoryRetentionDays());

            config.setMaxHistoryEntries(5000);
            assertEquals(5000, config.getMaxHistoryEntries());

            config.setExternalWebhookUrl("https://example.com/hook");
            assertEquals("https://example.com/hook", config.getExternalWebhookUrl());

            config.setExternalAuthToken("secret-token");
            assertEquals("secret-token", config.getExternalAuthToken());

            config.setKubernetesJobImage("my-registry/kompile:v2");
            assertEquals("my-registry/kompile:v2", config.getKubernetesJobImage());

            config.setKubernetesServiceAccount("custom-sa");
            assertEquals("custom-sa", config.getKubernetesServiceAccount());
        }
    }
}
