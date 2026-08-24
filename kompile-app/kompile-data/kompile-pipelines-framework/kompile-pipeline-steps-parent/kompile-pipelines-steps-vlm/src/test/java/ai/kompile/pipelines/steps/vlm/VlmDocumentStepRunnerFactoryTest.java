package ai.kompile.pipelines.steps.vlm;

import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlmDocumentStepRunnerFactoryTest {
    @Test
    void schemaPublishesPdfBoundaryInputsOutputsAndDefaults() {
        StepSchema schema = new VlmDocumentStepRunnerFactory().getSchema();

        assertTrue(schema.getDescription().contains("application/pdf"));
        assertTrue(schema.getDescription().contains("Direct raster-image"));
        assertTrue(schema.getInputSchema("filePath").orElseThrow().isRequired());
        assertTrue(schema.getInputSchema("resolvedModels").orElseThrow().isRequired());
        assertEquals(300L, schema.getParameterSchema("pdfRenderDpi")
                .orElseThrow().getDefaultValue());
        assertEquals(true, schema.getParameterSchema("failFastOnPageError")
                .orElseThrow().getDefaultValue());
        assertEquals("RAW", schema.getParameterSchema("outputFormat")
                .orElseThrow().getDefaultValue());
        assertTrue(schema.getParameterSchema("outputProtocol").isPresent());
        assertTrue(schema.getParameterSchema("task").isPresent());
        assertTrue(schema.getParameterSchema("prompt").isPresent());
        assertEquals(0L, schema.getParameterSchema("maxNewTokens")
                .orElseThrow().getDefaultValue());
        assertEquals(16L * 1024L * 1024L, schema.getParameterSchema("maxResponseBytes")
                .orElseThrow().getDefaultValue());
        assertEquals(false, schema.getParameterSchema("adaptiveRegionFallbackEnabled")
                .orElseThrow().getDefaultValue());
        assertEquals(3584L, schema.getParameterSchema("adaptiveFullPageMaxNewTokens")
                .orElseThrow().getDefaultValue());
        assertEquals(1024L, schema.getParameterSchema("adaptiveRegionMaxNewTokens")
                .orElseThrow().getDefaultValue());
        assertEquals(1.1, schema.getParameterSchema("adaptiveRegionRepetitionPenalty")
                .orElseThrow().getDefaultValue());
        assertEquals(64L, schema.getParameterSchema("adaptiveNativeRepetitionMaxPeriod")
                .orElseThrow().getDefaultValue());
        assertEquals(4L, schema.getParameterSchema("adaptiveNativeRepetitionMaxRepeats")
                .orElseThrow().getDefaultValue());
        assertEquals(0L, schema.getParameterSchema("topK").orElseThrow().getDefaultValue());
        assertTrue(schema.getParameterSchema("samplingPreset").isPresent());
        assertTrue(schema.getOutputSchema("markdown").isPresent());
        assertTrue(schema.getOutputSchema("pageCount").isPresent());
    }

    @Test
    void runnerRejectsStandaloneRasterBeforeModelInitialization(@TempDir Path directory)
            throws Exception {
        Path image = directory.resolve("scan.png");
        Files.write(image, new byte[]{1, 2, 3});

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                new VlmDocumentStepRunner().exec(
                        Data.fromMap(Map.of("filePath", image.toString())), null));

        assertTrue(failure.getMessage().contains("application/pdf"), failure.getMessage());
        assertTrue(failure.getMessage().contains("direct raster-image"), failure.getMessage());
    }
}
