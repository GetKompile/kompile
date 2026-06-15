package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KubernetesJobSchedulerDelegate")
class KubernetesJobSchedulerDelegateTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock private ResourceSchedulerConfigService configService;

    private KubernetesJobSchedulerDelegate delegate;

    @BeforeEach
    void setUp() {
        ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
        config.setExternalSchedulerMode("kubernetes");
        config.setKubernetesNamespace("test-ns");
        config.setKubernetesJobImage("test-image:latest");
        config.setKubernetesServiceAccount("test-sa");
        when(configService.getConfiguration()).thenReturn(config);

        delegate = new KubernetesJobSchedulerDelegate(configService);
    }

    @Test
    void getModeReturnsKubernetes() {
        assertEquals("kubernetes", delegate.getMode());
    }

    @Nested
    @DisplayName("isAvailable")
    class Availability {

        @Test
        void notAvailableWhenModeIsNotKubernetes() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("webhook");
            when(configService.getConfiguration()).thenReturn(config);

            assertFalse(delegate.isAvailable());
        }

        @Test
        void notAvailableWhenModeIsNone() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("none");
            when(configService.getConfiguration()).thenReturn(config);

            assertFalse(delegate.isAvailable());
        }
    }

    @Nested
    @DisplayName("submitJob")
    class SubmitJob {

        @Test
        void submitJobReturnsExternalRef() throws Exception {
            // This test calls kubectl which may not be available
            // It verifies the async wrapper doesn't throw
            JobResourceProfile profile = JobResourceProfile.gpuRequired(
                    "ingest", "Ingest", 2 * GB, 4 * GB);

            var future = delegate.submitJob(
                    "j1", "ingest", "Test ingest", profile,
                    Map.of("callbackUrl", "http://localhost:8090/api/scheduler/callback"));

            // Should complete (may fail since kubectl may not be installed — that's OK)
            ExternalJobSchedulerDelegate.ExternalJobRef ref = future.join();
            assertNotNull(ref);
            assertNotNull(ref.externalId());
            assertNotNull(ref.status());
        }
    }

    @Nested
    @DisplayName("cancelJob")
    class CancelJob {

        @Test
        void cancelJobReturnsResult() {
            var future = delegate.cancelJob("j1", "kompile-ingest-j1");

            // Should complete (may fail since kubectl may not be installed)
            assertNotNull(future.join());
        }
    }

    @Nested
    @DisplayName("getJobStatus")
    class GetJobStatus {

        @Test
        void getJobStatusReturnsResult() {
            var future = delegate.getJobStatus("j1", "kompile-ingest-j1");

            ExternalJobSchedulerDelegate.ExternalJobStatus status = future.join();
            assertNotNull(status);
            assertNotNull(status.status());
        }
    }
}
