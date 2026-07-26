/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.DownloadResult;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.sdx.SdxProjectOutputService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagingServiceSdxOutputTest {

    @TempDir
    Path temp;

    @Mock
    ConversionService conversionService;
    @Mock
    DownloadService downloadService;
    @Mock
    OptimizationService optimizationService;
    @Mock
    SdxProjectOutputService projectOutputService;

    private RegistryService registryService;

    @BeforeEach
    void setUp() {
        registryService = new RegistryService(temp.resolve("models"));
        when(downloadService.canHandle("fixture")).thenReturn(true);
        when(downloadService.download(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.createDirectories(destination);
            Path model = destination.resolve("model.sdz");
            Files.writeString(model, "same-diff");
            return DownloadResult.success(model, null, "sha256");
        });
        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(1, 1));
    }

    @Test
    void publishesCompletedProjectThroughVerifiedOutputDirectory() throws Exception {
        stubTargetOutput();
        StagingService service = service();

        StagingModelInfo result = service.stageModel(projectRequest("mobile"));

        assertEquals(StagingStatus.COMPLETED, result.getStatus());
        assertEquals(
                "outputs/mobile-android-arm64-nnapi-accelerator.kproject",
                result.getCurrentFile());
        assertTrue(service.getStagedOutput("mobile").isPresent());
        assertTrue(Files.isRegularFile(service.getStagedOutput("mobile").orElseThrow()));
        verify(projectOutputService).createOutput(any(), any(), any(), any());
    }

    @Test
    void publishesCompletedTargetModelAsDownloadableSdz() throws Exception {
        stubTargetOutput();
        StagingService service = service();
        DownloadRequest request = baseRequest("model-only")
                .outputFormat("model")
                .targetProfile("android-arm64-vulkan")
                .targetSoc("Adreno_715")
                .build();

        StagingModelInfo result = service.stageModel(request);

        assertEquals(StagingStatus.COMPLETED, result.getStatus());
        assertEquals(
                "outputs/model-only-android-arm64-vulkan.sdz",
                result.getCurrentFile());
        assertTrue(service.getStagedOutput("model-only").isPresent());
        verify(projectOutputService).createOutput(any(), any(), any(), any());
    }

    @Test
    void packagingFailureMovesWorkspaceToFailedAndPublishesNothing() throws Exception {
        doThrow(new IOException("vendor compiler unavailable"))
                .when(projectOutputService).createOutput(any(), any(), any(), any());
        StagingService service = service();

        StagingModelInfo result = service.stageModel(projectRequest("broken"));

        assertEquals(StagingStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("vendor compiler unavailable"));
        assertTrue(Files.isDirectory(
                registryService.getModelDir().resolve(".staging/failed/broken")));
        assertFalse(Files.exists(
                registryService.getModelDir().resolve(".staging/verified/broken")));
        assertTrue(service.getStagedOutput("broken").isEmpty());
    }

    @Test
    void legacyModelStagingDoesNotInvokeTargetCompiler() {
        StagingService service = service();
        DownloadRequest request = baseRequest("legacy")
                .outputFormat("model")
                .build();

        StagingModelInfo result = service.stageModel(request);

        assertEquals(StagingStatus.COMPLETED, result.getStatus());
        verifyNoInteractions(projectOutputService);
    }

    private StagingService service() {
        return new StagingService(
                registryService,
                conversionService,
                List.of(downloadService),
                optimizationService,
                projectOutputService);
    }

    private void stubTargetOutput() throws IOException {
        when(projectOutputService.createOutput(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Path workspace = invocation.getArgument(0);
                    DownloadRequest request = invocation.getArgument(2);
                    String extension = "kproject".equals(request.getOutputFormat())
                            ? ".kproject"
                            : ".sdz";
                    Path output = workspace.resolve("outputs").resolve(
                            request.getModelId() + "-" + request.getTargetProfile() + extension);
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, "target artifact");
                    return output;
                });
    }

    private DownloadRequest projectRequest(String modelId) {
        return baseRequest(modelId)
                .outputFormat("kproject")
                .targetProfile("android-arm64-nnapi-accelerator")
                .quantizationProfile("int8")
                .targetSoc("Tensor_G3")
                .build();
    }

    private DownloadRequest.DownloadRequestBuilder baseRequest(String modelId) {
        return DownloadRequest.builder()
                .source("fixture")
                .repository("fixture/model")
                .modelId(modelId)
                .modelType(ModelType.LLM_GGML)
                .format("sdz");
    }
}
