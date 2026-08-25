/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.modelmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedModelArtifactDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void multilingualE5ManifestIsPinnedAndDryRunDoesNotWrite() throws Exception {
        ManagedModelArtifactCatalog.Definition definition =
                ManagedModelArtifactCatalog.find("multilingual-e5-small").orElseThrow();

        assertEquals("intfloat/multilingual-e5-small", definition.repository());
        assertEquals("614241f622f53c4eeff9890bdc4f31cfecc418b3", definition.revision());
        assertEquals("MEAN", definition.metadata().get("pooling_strategy"));
        assertEquals("query: ", definition.metadata().get("input_prefix"));
        assertEquals("https://huggingface.co/intfloat/multilingual-e5-small/resolve/"
                        + "614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/model.onnx",
                ManagedModelArtifactDownloader.componentUrl(
                        definition, definition.component("model").orElseThrow()).toString());

        ManagedModelArtifactDownloader.Acquisition acquisition =
                new ManagedModelArtifactDownloader().acquire(
                        definition, tempDir.resolve("model"), false, true);
        assertFalse(acquisition.downloaded());
        assertFalse(Files.exists(acquisition.directory()));
        assertTrue(acquisition.primaryModel().endsWith("model.onnx"));
        assertTrue(acquisition.tokenizer().endsWith("tokenizer.json"));
    }

    @Test
    void relativeRedirectIsResolvedAgainstPinnedSourceUrl() throws Exception {
        assertEquals(
                "https://huggingface.co/intfloat/cdn/model.onnx",
                ManagedModelArtifactDownloader.resolveRedirect(
                        URI.create("https://huggingface.co/intfloat/model/resolve/rev/model.onnx").toURL(),
                        "../../../cdn/model.onnx").toString());
    }
}
