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

package ai.kompile.presets;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.*;

/**
 * JavaCPP Presets for the tokenizers library.
 *
 * This class configures the JavaCPP bindings for the tokenizers Rust library
 * through its C wrapper layer. It follows the same pattern as nd4j's presets
 * to ensure compatibility and consistent behavior.
 *
 * The presets define:
 * - Header file mappings from the C wrapper
 * - Type mappings between C and Java
 * - Platform-specific compilation and linking settings
 * - Library loading and path configuration
 *
 * @author tokenizers-rust project
 */
@Properties(
        target = "ai.kompile.bindings.TokenizersNative",
        helper = "ai.kompile.presets.TokenizersHelper",
        value = {
                @Platform(
                        define = {"TOKENIZERS_CPU"},
                        include = {
                                // Main C API header - this is the key header that contains all the C function declarations
                                "tokenizers_c.h"
                        },
                        includepath = {
                                // JavaCPP-style include paths
                                "/ai/kompile/tokenizers/include/",
                                // Fallback to legacy paths for development
                                "../libtokenizers/target/native/ai/kompile/tokenizers/include/",
                                "../libtokenizers/include/"
                        },
                        exclude = {
                                // Exclude any problematic headers
                                "internal/*"
                        },
                        compiler = {"cpp17", "nowarnings"},
                        library = "jnitokenizers",
                        link = "tokenizers_wrapper",
                        // NOTE: linkpath is configured via Maven pom.xml <linkPaths> because
                        // ${javacpp.platform} in Java annotations is NOT substituted by Maven.
                        // The pom.xml linkPaths use proper Maven property substitution.
                        preload = "libtokenizers_wrapper"
                        // NOTE: preloadpath is also configured via Maven pom.xml for the same reason
                ),
                @Platform(
                        value = "linux",
                        preload = "gomp@.1",
                        preloadpath = {"/lib64/", "/lib/", "/usr/lib64/", "/usr/lib/"}
                ),
                @Platform(
                        value = "linux-armhf",
                        preload = "gomp@.1",
                        preloadpath = {"/usr/arm-linux-gnueabihf/lib/", "/usr/lib/arm-linux-gnueabihf/"}
                ),
                @Platform(
                        value = "linux-arm64",
                        preload = "gomp@.1",
                        preloadpath = {"/usr/aarch64-linux-gnu/lib/", "/usr/lib/aarch64-linux-gnu/"}
                ),
                @Platform(
                        value = "windows",
                        preload = {
                                "libwinpthread-1",
                                "libgcc_s_seh-1",
                                "libstdc++-6",
                                "libtokenizers_wrapper"
                        }
                ),
                @Platform(
                        value = "macosx",
                        preload = "libtokenizers_wrapper"
                )
       }
)
public class TokenizersPresets implements InfoMapper, BuildEnabled {

    private Logger logger;
    private java.util.Properties properties;
    private String encoding;

    @Override
    public void init(Logger logger, java.util.Properties properties, String encoding) {
        this.logger = logger;
        this.properties = properties;
        this.encoding = encoding;
    }

    @Override
    public void map(InfoMap infoMap) {
        // Basic type mappings and annotations
        infoMap.put(new Info("TOKENIZERS_EXPORT", "TOKENIZERS_INLINE",
                        "TOKENIZERS_HOST", "TOKENIZERS_DEVICE", "TOKENIZERS_API")
                        .cppTypes().annotations())

                // KEY FIX: Objectify the C API header to generate native methods
                // This is equivalent to nd4j's .put(new Info("NativeOps.h", "build_info.h").objectify())
                .put(new Info("tokenizers_c.h").objectify())

                // Opaque pointer types - these map to the C API opaque handles
                .put(new Info("OpaqueTokenizer").pointerTypes("OpaqueTokenizer"))
                .put(new Info("OpaqueEncoding").pointerTypes("OpaqueEncoding"))
                .put(new Info("OpaqueModelManager").pointerTypes("OpaqueModelManager"))

                // Enum mappings
                .put(new Info("TokenizerError").cast().valueTypes("int").pointerTypes("IntPointer"))
                .put(new Info("TokenizerResult").pointerTypes("TokenizerResult"))

                // String handling - Basic string types
                .put(new Info("char").valueTypes("byte").pointerTypes("@Cast(\"char*\") String",
                        "@Cast(\"char*\") BytePointer"))
                .put(new Info("char").valueTypes("char").pointerTypes("@Cast(\"char*\") BytePointer",
                        "@Cast(\"char*\") String"))

                // Basic type mappings
                .put(new Info("bool").cast().valueTypes("boolean").pointerTypes("BooleanPointer", "boolean[]"))
                .put(new Info("size_t").cast().valueTypes("long").pointerTypes("SizeTPointer"))
                .put(new Info("uint32_t").cast().valueTypes("int").pointerTypes("IntPointer", "IntBuffer", "int[]"))
                .put(new Info("int32_t").cast().valueTypes("int").pointerTypes("IntPointer", "IntBuffer", "int[]"))
                .put(new Info("uint64_t").cast().valueTypes("long").pointerTypes("LongPointer", "LongBuffer", "long[]"))

                // CRITICAL FIX: Skip the problematic functions entirely
                .put(new Info("encode_batch").skip())
                .put(new Info("free_embedded_models").skip())

                // Function name mappings for better Java API names
                .put(new Info("create_tokenizer_from_file").javaNames("createTokenizerFromFile"))
                .put(new Info("create_tokenizer_from_json").javaNames("createTokenizerFromJson"))
                .put(new Info("free_tokenizer").javaNames("freeTokenizer"))
                .put(new Info("tokenizer_is_valid").javaNames("tokenizerIsValid"))
                .put(new Info("get_vocab_size").javaNames("getVocabSize"))
                .put(new Info("encode_text").javaNames("encodeText"))
                .put(new Info("free_encoding").javaNames("freeEncoding"))
                .put(new Info("free_encoding_batch").javaNames("freeEncodingBatch"))
                .put(new Info("encoding_get_length").javaNames("encodingGetLength"))
                .put(new Info("encoding_get_ids").javaNames("encodingGetIds"))
                .put(new Info("encoding_get_tokens").javaNames("encodingGetTokens"))
                .put(new Info("encoding_get_offsets").javaNames("encodingGetOffsets"))
                .put(new Info("decode_ids").javaNames("decodeIds"))
                .put(new Info("free_string").javaNames("freeString"))
                .put(new Info("create_model_manager").javaNames("createModelManager"))
                .put(new Info("free_model_manager").javaNames("freeModelManager"))
                .put(new Info("is_valid_model_file").javaNames("isValidModelFile"))
                .put(new Info("get_embedded_models").javaNames("getEmbeddedModels"))
                .put(new Info("get_last_error").javaNames("getLastError"))
                .put(new Info("clear_last_error").javaNames("clearLastError"))
                .put(new Info("get_tokenizer_version").javaNames("getTokenizerVersion"))
                .put(new Info("get_build_info").javaNames("getBuildInfo"));

        // Platform-specific configurations
        String platform = properties.getProperty("platform", "");
        if (platform.contains("windows")) {
            // Windows-specific mappings
            infoMap.put(new Info("WINAPI", "__declspec(dllexport)").cppTypes().annotations());
        } else if (platform.contains("linux")) {
            // Linux-specific mappings
            infoMap.put(new Info("__attribute__((visibility(\"default\")))").cppTypes().annotations());
        } else if (platform.contains("macosx")) {
            // macOS-specific mappings
            infoMap.put(new Info("__attribute__((visibility(\"default\")))").cppTypes().annotations());
        }

        // Skip C++ specific constructs
        infoMap.put(new Info("std::shared_ptr", "std::unique_ptr").skip())
                .put(new Info("std::initializer_list").skip());

        if (logger != null) {
            logger.info("TokenizersPresets: Configured mappings for platform: " + platform);
            logger.info("TokenizersPresets: Using JavaCPP platform-specific directory structure");
        }
    }
}
