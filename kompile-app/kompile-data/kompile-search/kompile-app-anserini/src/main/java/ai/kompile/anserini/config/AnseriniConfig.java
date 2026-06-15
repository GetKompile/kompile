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

package ai.kompile.anserini.config;

import ai.kompile.cli.common.util.PlatformDetector;
import org.eclipse.deeplearning4j.tokenizers.bindings.TokenizersNative;
import io.anserini.collection.JsonCollection;
import io.anserini.index.generator.DefaultLuceneDocumentGenerator;
import lombok.Data;
import org.apache.lucene.analysis.tokenattributes.KeywordAttributeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.ClassPathResource;

@Data
public class AnseriniConfig {
    /**
     * Path where the final Anserini/Lucene index will be built and stored.
     */
    private String indexPath;

    /**
     * Path used by AnseriniIndexerServiceImpl as the staging directory
     * for intermediate JSON files that Anserini's JsonCollection will ingest.
     */
    private String corpusPath; // This was used as the staging path for JSONs

    public static class AnseriniReflectionHints implements RuntimeHintsRegistrar {

        private static final Logger log = LoggerFactory.getLogger(AnseriniReflectionHints.class);

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // Register reflection hints for required classes
            for (Class<?> clazz : new Class[]{
                    JsonCollection.class,
                    KeywordAttributeImpl.class,
                    DefaultLuceneDocumentGenerator.class,
                    TokenizersNative.class
            }) {
                for (MemberCategory memberCategory : MemberCategory.values()) {
                    hints.reflection().registerType(clazz, memberCategory);
                }
            }

            // Register JNI hints
            hints.jni().registerType(TokenizersNative.class,
                    MemberCategory.PUBLIC_FIELDS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.DECLARED_CLASSES,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

            // Register native library resources
            registerNativeLibraryResources(hints);
        }

        private void registerNativeLibraryResources(RuntimeHints hints) {
            PlatformDetector.PlatformInfo platformInfo = PlatformDetector.detectPlatform();

            // Define all possible native library resource paths (DL4J nd4j-tokenizers)
            String[] resourcePaths = {
                    // Primary tokenizers library
                    "/org/eclipse/deeplearning4j/tokenizers/" + platformInfo.getIdentifier() + "/libtokenizers_wrapper.so",
                    "/org/eclipse/deeplearning4j/tokenizers/" + platformInfo.getIdentifier() + "/libtokenizers_wrapper.so.1",
                    "/org/eclipse/deeplearning4j/tokenizers/" + platformInfo.getIdentifier() + "/libtokenizers_wrapper.so.1.0.0",

                    // JNI bindings library
                    "/org/eclipse/deeplearning4j/tokenizers/bindings/" + platformInfo.getIdentifier() + "/libjnitokenizers.so",

                    // Include manifest files that might be needed
                    "/org/eclipse/deeplearning4j/tokenizers/" + platformInfo.getIdentifier() + "/manifest.properties"
            };

            log.debug("=== Registering Native Library Resources ===");
            log.debug("Platform: {}", platformInfo.getIdentifier());
            log.debug("File Extension: {}", platformInfo.getFileExtension());

            for (String resourcePath : resourcePaths) {
                try {
                    // Force creation of ClassPathResource (not FileSystemResource)
                    ClassPathResource resource = new ClassPathResource(resourcePath);

                    if (resource.exists()) {
                        hints.resources().registerResource(resource);
                        log.debug("Registered ClassPathResource: {}", resourcePath);

                        // Verify the resource is readable
                        try {
                            long size = resource.contentLength();
                            log.debug("  Size: {} bytes", size);
                        } catch (Exception e) {
                            log.debug("  Warning: Could not read size - {}", e.getMessage());
                        }
                    } else {
                        log.debug("Resource not found: {}", resourcePath);
                    }
                } catch (Exception e) {
                    log.debug("Failed to register: {} - {}", resourcePath, e.getMessage());
                }
            }

            log.debug("=== Native Library Resource Registration Complete ===");
        }
    }
}