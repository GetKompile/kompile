package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookJobSchedulerDelegate")
class WebhookJobSchedulerDelegateTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock private ResourceSchedulerConfigService configService;

    private WireMockServer wireMock;
    private WebhookJobSchedulerDelegate delegate;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
        config.setExternalSchedulerMode("webhook");
        config.setExternalWebhookUrl("http://localhost:" + wireMock.port());
        config.setExternalAuthToken("test-token");
        when(configService.getConfiguration()).thenReturn(config);

        delegate = new WebhookJobSchedulerDelegate(configService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void getModeReturnsWebhook() {
        assertEquals("webhook", delegate.getMode());
    }

    @Nested
    @DisplayName("isAvailable")
    class Availability {

        @Test
        void availableWhenHealthEndpointReturns200() {
            stubFor(get(urlEqualTo("/health"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));

            assertTrue(delegate.isAvailable());
        }

        @Test
        void notAvailableWhenHealthEndpointFails() {
            stubFor(get(urlEqualTo("/health"))
                    .willReturn(aResponse().withStatus(500)));

            assertFalse(delegate.isAvailable());
        }

        @Test
        void notAvailableWhenModeIsNotWebhook() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("kubernetes");
            when(configService.getConfiguration()).thenReturn(config);

            assertFalse(delegate.isAvailable());
        }

        @Test
        void notAvailableWhenUrlIsBlank() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("webhook");
            config.setExternalWebhookUrl("");
            when(configService.getConfiguration()).thenReturn(config);

            assertFalse(delegate.isAvailable());
        }
    }

    @Nested
    @DisplayName("submitJob")
    class SubmitJob {

        @Test
        void submitJobPostsToWebhookAndReturnsRef() throws Exception {
            String responseBody = objectMapper.writeValueAsString(
                    Map.of("externalId", "ext-123", "status", "SUBMITTED"));

            stubFor(post(urlEqualTo("/submit"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(responseBody)));

            JobResourceProfile profile = JobResourceProfile.gpuRequired(
                    "ingest", "Ingest", 2 * GB, 4 * GB);

            ExternalJobSchedulerDelegate.ExternalJobRef ref = delegate.submitJob(
                    "j1", "ingest", "Test ingest", profile,
                    Map.of("filePath", "/data/test.pdf")).join();

            assertEquals("ext-123", ref.externalId());
            assertEquals("SUBMITTED", ref.status());

            // Verify the request
            verify(postRequestedFor(urlEqualTo("/submit"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withRequestBody(matchingJsonPath("$.jobId", equalTo("j1")))
                    .withRequestBody(matchingJsonPath("$.jobType", equalTo("ingest")))
                    .withRequestBody(matchingJsonPath("$.resources.requiresGpu", equalTo("true")))
                    .withRequestBody(matchingJsonPath("$.metadata.filePath", equalTo("/data/test.pdf"))));
        }

        @Test
        void submitJobHandles500() throws Exception {
            stubFor(post(urlEqualTo("/submit"))
                    .willReturn(aResponse().withStatus(500).withBody("Server error")));

            JobResourceProfile profile = JobResourceProfile.cpuOnly("crawl", "Crawl", GB);

            ExternalJobSchedulerDelegate.ExternalJobRef ref = delegate.submitJob(
                    "j1", "crawl", "Test", profile, Map.of()).join();

            assertEquals("FAILED", ref.status());
            assertTrue(ref.message().contains("500"));
        }

        @Test
        void submitJobHandlesNoExternalIdInResponse() throws Exception {
            stubFor(post(urlEqualTo("/submit"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"accepted\":true}")));

            JobResourceProfile profile = JobResourceProfile.cpuOnly("crawl", "Crawl", GB);

            ExternalJobSchedulerDelegate.ExternalJobRef ref = delegate.submitJob(
                    "j1", "crawl", "Test", profile, Map.of()).join();

            // Falls back to jobId when no externalId in response
            assertEquals("j1", ref.externalId());
            assertEquals("SUBMITTED", ref.status());
        }
    }

    @Nested
    @DisplayName("cancelJob")
    class CancelJob {

        @Test
        void cancelJobPostsToWebhook() throws Exception {
            stubFor(post(urlEqualTo("/cancel"))
                    .willReturn(aResponse().withStatus(200)));

            boolean result = delegate.cancelJob("j1", "ext-123").join();

            assertTrue(result);
            verify(postRequestedFor(urlEqualTo("/cancel"))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withRequestBody(matchingJsonPath("$.jobId", equalTo("j1")))
                    .withRequestBody(matchingJsonPath("$.externalRef", equalTo("ext-123"))));
        }

        @Test
        void cancelJobReturnsFalseOn500() throws Exception {
            stubFor(post(urlEqualTo("/cancel"))
                    .willReturn(aResponse().withStatus(500)));

            boolean result = delegate.cancelJob("j1", "ext-123").join();
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("getJobStatus")
    class GetJobStatus {

        @Test
        void getJobStatusReturnsStatus() throws Exception {
            String responseBody = objectMapper.writeValueAsString(
                    Map.of("status", "COMPLETED", "message", "All done"));

            stubFor(get(urlEqualTo("/status/ext-123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(responseBody)));

            ExternalJobSchedulerDelegate.ExternalJobStatus status =
                    delegate.getJobStatus("j1", "ext-123").join();

            assertEquals("ext-123", status.externalId());
            assertEquals("COMPLETED", status.status());
            assertEquals("All done", status.message());
        }

        @Test
        void getJobStatusReturnsUnknownOn500() throws Exception {
            stubFor(get(urlEqualTo("/status/ext-123"))
                    .willReturn(aResponse().withStatus(500)));

            ExternalJobSchedulerDelegate.ExternalJobStatus status =
                    delegate.getJobStatus("j1", "ext-123").join();

            assertEquals("UNKNOWN", status.status());
        }

        @Test
        void getJobStatusIncludesAuthHeader() throws Exception {
            stubFor(get(urlEqualTo("/status/ext-123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"RUNNING\"}")));

            delegate.getJobStatus("j1", "ext-123").join();

            verify(getRequestedFor(urlEqualTo("/status/ext-123"))
                    .withHeader("Authorization", equalTo("Bearer test-token")));
        }
    }

    @Nested
    @DisplayName("Auth token handling")
    class AuthToken {

        @Test
        void noAuthHeaderWhenTokenIsBlank() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("webhook");
            config.setExternalWebhookUrl("http://localhost:" + wireMock.port());
            config.setExternalAuthToken("");
            when(configService.getConfiguration()).thenReturn(config);

            stubFor(post(urlEqualTo("/submit"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            JobResourceProfile profile = JobResourceProfile.cpuOnly("crawl", "Crawl", GB);
            delegate.submitJob("j1", "crawl", "Test", profile, Map.of()).join();

            verify(postRequestedFor(urlEqualTo("/submit"))
                    .withoutHeader("Authorization"));
        }
    }
}
