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

package ai.kompile.modelmanager.vlm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * On-disk layout of a staged vision-language model package: one directory holding a vision
 * encoder, a decoder and {@code tokenizer.json} (SmolVLM or SmolDocling exported to ONNX, or their
 * SDZ conversions), the flat layout {@link VlmModelSetDownloader} stages under
 * {@code ~/.kompile/models/vlm/<set>}.
 *
 * <p>The serving subprocess loads such a directory as one image+text chat model and the CLI
 * resolves and launches it, so both read this one definition: a directory the CLI hands over as a
 * package is exactly one the server loads as one.</p>
 */
public final class VisionLanguagePackageLayout {

    public static final String TOKENIZER = "tokenizer.json";
    public static final List<String> VISION_ENCODER_ONNX = List.of("vision_encoder.onnx");
    public static final List<String> DECODER_ONNX = List.of(
            "decoder_model_merged.onnx", "decoder_model.onnx", "decoder.onnx");
    public static final List<String> EMBED_TOKENS_ONNX = List.of("embed_tokens.onnx");
    /** SDZ names {@code MultiPartModelLoader} resolves, minus its ambiguous bare {@code encoder}. */
    public static final List<String> VISION_ENCODER_SDZ = List.of(
            "vision_encoder.sdz", "image_encoder.sdz", "visual_encoder.sdz");
    public static final List<String> DECODER_SDZ = List.of(
            "decoder.sdz", "decoder_model.sdz", "decoder_model_merged.sdz", "language_model.sdz");
    /** {@code MultiPartModelLoader} also takes a bare {@code model.sdz} as the decoder. */
    public static final String GENERIC_DECODER_SDZ = "model.sdz";

    private VisionLanguagePackageLayout() {
    }

    /**
     * The package directory {@code modelPath} names: the directory itself, or the directory of a
     * vision-encoder, decoder or embed-tokens file inside it. A generic {@code model.sdz} counts as
     * the decoder only when the directory is named, never as a file path, so a text model staged
     * under that name beside VLM parts keeps loading as the text model it is.
     */
    public static Optional<Path> packageDirectory(Path modelPath) {
        if (modelPath == null) {
            return Optional.empty();
        }
        Path absolute = modelPath.toAbsolutePath().normalize();
        Path directory;
        if (Files.isDirectory(absolute)) {
            directory = absolute;
        } else if (absolute.getFileName() != null
                && isComponentName(absolute.getFileName().toString())) {
            directory = absolute.getParent();
        } else {
            return Optional.empty();
        }
        return directory != null && isPackageDirectory(directory)
                ? Optional.of(directory) : Optional.empty();
    }

    /** A vision encoder and a decoder, each in ONNX or SDZ form. */
    public static boolean isPackageDirectory(Path directory) {
        boolean visionEncoder = firstExisting(directory, VISION_ENCODER_ONNX) != null
                || firstExisting(directory, VISION_ENCODER_SDZ) != null;
        boolean decoder = firstExisting(directory, DECODER_ONNX) != null
                || firstExisting(directory, DECODER_SDZ) != null
                || Files.isRegularFile(directory.resolve(GENERIC_DECODER_SDZ));
        return visionEncoder && decoder;
    }

    /** A name only a package part carries; a generic {@code model.sdz} is not one. */
    public static boolean isComponentName(String fileName) {
        return VISION_ENCODER_ONNX.contains(fileName)
                || DECODER_ONNX.contains(fileName)
                || EMBED_TOKENS_ONNX.contains(fileName)
                || VISION_ENCODER_SDZ.contains(fileName)
                || DECODER_SDZ.contains(fileName);
    }

    /** The first of {@code names} that is a regular file in {@code directory}, or null. */
    public static Path firstExisting(Path directory, List<String> names) {
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
