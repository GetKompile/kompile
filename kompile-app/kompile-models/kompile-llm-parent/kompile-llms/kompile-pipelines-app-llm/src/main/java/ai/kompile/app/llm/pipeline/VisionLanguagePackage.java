/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.llm.pipeline;

import ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.eclipse.deeplearning4j.vlm.model.patching.SmolDoclingPositionIdsPatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout.DECODER_ONNX;
import static ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout.EMBED_TOKENS_ONNX;
import static ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout.TOKENIZER;
import static ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout.VISION_ENCODER_ONNX;
import static ai.kompile.modelmanager.vlm.VisionLanguagePackageLayout.firstExisting;

/**
 * A staged vision-language model package served as the chat model: one directory in the
 * {@link VisionLanguagePackageLayout} (SmolVLM or SmolDocling exported to ONNX, or their SDZ
 * conversions). Text and image turns share the package's single decoder, so nothing is loaded
 * twice.
 *
 * <p>Detection looks only at the served path. A text model that merely sits beside VLM parts is
 * served text-only; the path has to name the package directory or one of its components.</p>
 */
final class VisionLanguagePackage {

    /** Native workspace per graph, the size {@code VisionLanguageModel.fromOnnx} defaults to. */
    private static final long ONNX_WORKSPACE_BYTES = 8L * 1024 * 1024;

    private final Path directory;

    private VisionLanguagePackage(Path directory) {
        this.directory = directory;
    }

    /**
     * The package {@code modelPath} names, by {@link VisionLanguagePackageLayout#packageDirectory}:
     * the directory itself, or a component file inside it.
     */
    static Optional<VisionLanguagePackage> detect(Path modelPath) {
        return VisionLanguagePackageLayout.packageDirectory(modelPath)
                .map(VisionLanguagePackage::new);
    }

    Path directory() {
        return directory;
    }

    Path tokenizer() {
        return directory.resolve(TOKENIZER);
    }

    /**
     * Load the package. A complete ONNX set wins over SDZ files beside it: those are
     * {@code OnnxModelCache}'s unpatched import caches, and graph patches are applied only on
     * the ONNX path. Anything else loads through the SDZ multi-part loader.
     */
    VisionLanguageModel load() throws IOException {
        Path tokenizer = tokenizer();
        if (!Files.isRegularFile(tokenizer)) {
            throw new IOException("Vision-language package " + directory + " has no " + TOKENIZER);
        }
        Path visionOnnx = firstExisting(directory, VISION_ENCODER_ONNX);
        Path decoderOnnx = firstExisting(directory, DECODER_ONNX);
        Path embedOnnx = firstExisting(directory, EMBED_TOKENS_ONNX);
        VisionLanguageModel model;
        if (visionOnnx != null && decoderOnnx != null && embedOnnx != null) {
            model = VisionLanguageModel.fromOnnxWithPatches(
                    visionOnnx.toFile(), decoderOnnx.toFile(), embedOnnx.toFile(), tokenizer.toFile(),
                    ONNX_WORKSPACE_BYTES, null,
                    // Self-detecting: it rewrites only a constant position_ids of shape [1,32]
                    // (the SmolDocling export bug) and leaves every other encoder untouched.
                    List.of(new SmolDoclingPositionIdsPatch()), null, null);
        } else {
            model = VisionLanguageModel.fromDirectory(directory.toFile());
        }
        if (model.getVisionEncoder() == null) {
            model.close();
            throw new IOException("Vision-language package " + directory
                    + " loaded without a vision encoder; stage vision_encoder.onnx with"
                    + " embed_tokens.onnx and the ONNX decoder, or vision_encoder.sdz with the SDZ decoder");
        }
        return model;
    }
}
