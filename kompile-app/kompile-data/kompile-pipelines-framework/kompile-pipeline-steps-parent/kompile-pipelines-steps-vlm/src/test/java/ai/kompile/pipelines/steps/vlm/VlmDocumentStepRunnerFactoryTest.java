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
        assertEquals("DOCTAGS", schema.getParameterSchema("outputFormat")
                .orElseThrow().getDefaultValue());
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
