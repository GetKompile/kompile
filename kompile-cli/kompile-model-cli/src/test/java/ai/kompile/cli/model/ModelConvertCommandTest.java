/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.cli.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConvertCommandTest {

    @Test
    void onnxUsesDirectImporterByFormatOrExtension() {
        assertTrue(ModelConvertCommand.isOnnx(Path.of("model.bin"), "ONNX"));
        assertTrue(ModelConvertCommand.isOnnx(Path.of("model.onnx"), null));
        assertFalse(ModelConvertCommand.isOnnx(Path.of("model.safetensors"), null));
        assertFalse(ModelConvertCommand.isOnnx(Path.of("model.onnx"), "tensorflow"));
    }
}
