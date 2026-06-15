package ai.kompile.app.web.controllers;

import ai.kompile.app.services.IngestJobResumeService;
import ai.kompile.app.services.IngestJobResumeService.ResumableJobSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IngestJobResumeController")
class IngestJobResumeControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestJobResumeService resumeService;

    @BeforeEach
    void setUp() {
        IngestJobResumeController controller = new IngestJobResumeController(resumeService);
        ObjectMapper mapper = new ObjectMapper();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(mapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("GET /api/ingest/resume/jobs")
    class ListResumableJobs {

        @Test
        void returnsEmptyList() throws Exception {
            when(resumeService.listResumableJobs()).thenReturn(List.of());

            mockMvc.perform(get("/api/ingest/resume/jobs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void returnsJobList() throws Exception {
            var summary = new ResumableJobSummary(
                    "task-1", "doc.pdf", "/tmp/cp.json", "EMBEDDING",
                    50, 30, 100, "2025-01-01T00:00:00Z", "FAILED",
                    "OOM", "TikaLoader", "recursive", "bge-base");

            when(resumeService.listResumableJobs()).thenReturn(List.of(summary));

            mockMvc.perform(get("/api/ingest/resume/jobs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].taskId", is("task-1")))
                    .andExpect(jsonPath("$[0].fileName", is("doc.pdf")))
                    .andExpect(jsonPath("$[0].chunksEmbedded", is(50)))
                    .andExpect(jsonPath("$[0].totalChunks", is(100)));
        }
    }

    @Nested
    @DisplayName("POST /api/ingest/resume/jobs/{taskId}")
    class ResumeJob {

        @Test
        void resumeSuccess() throws Exception {
            when(resumeService.resumeJob("task-1")).thenReturn("resume-task-1-12345");

            mockMvc.perform(post("/api/ingest/resume/jobs/task-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", is("Job resumed from checkpoint")))
                    .andExpect(jsonPath("$.originalTaskId", is("task-1")))
                    .andExpect(jsonPath("$.newTaskId", is("resume-task-1-12345")))
                    .andExpect(jsonPath("$.status", is("RUNNING")));
        }

        @Test
        void resumeJobNotFound() throws Exception {
            when(resumeService.resumeJob("missing"))
                    .thenThrow(new IllegalArgumentException("Job not found: missing"));

            mockMvc.perform(post("/api/ingest/resume/jobs/missing"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("Job not found")));
        }

        @Test
        void resumeNoServiceAvailable() throws Exception {
            when(resumeService.resumeJob("task-2"))
                    .thenThrow(new IllegalStateException("No ingest service available for resume"));

            mockMvc.perform(post("/api/ingest/resume/jobs/task-2"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("No ingest service")));
        }

        @Test
        void resumeUnexpectedError() throws Exception {
            when(resumeService.resumeJob("task-3"))
                    .thenThrow(new RuntimeException("Unexpected failure"));

            mockMvc.perform(post("/api/ingest/resume/jobs/task-3"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Unexpected failure")));
        }
    }

    @Nested
    @DisplayName("GET /api/ingest/resume/jobs/{taskId}/checkpoint")
    class GetCheckpointStatus {

        @Test
        void returnsCheckpointDetails() throws Exception {
            when(resumeService.getCheckpointStatus("task-1"))
                    .thenReturn(Map.of(
                            "taskId", "task-1",
                            "status", "FAILED",
                            "checkpointExists", true,
                            "totalChunks", 100,
                            "chunksEmbedded", 50
                    ));

            mockMvc.perform(get("/api/ingest/resume/jobs/task-1/checkpoint"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-1")))
                    .andExpect(jsonPath("$.checkpointExists", is(true)))
                    .andExpect(jsonPath("$.totalChunks", is(100)));
        }

        @Test
        void returnsErrorForUnknownJob() throws Exception {
            when(resumeService.getCheckpointStatus("missing"))
                    .thenReturn(Map.of("error", "Job not found", "taskId", "missing"));

            mockMvc.perform(get("/api/ingest/resume/jobs/missing/checkpoint"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error", is("Job not found")));
        }
    }
}
