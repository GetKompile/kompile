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

package ai.kompile.modelmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KompileModelManagerTest {

    @Test
    void testModelManagerInitialization(@TempDir Path tempDir) {
        KompileModelManager manager = new KompileModelManager(tempDir);
        assertEquals(tempDir, manager.getBaseCachePath());
    }

    @Test
    void testModelDescriptorCreation() {
        ModelDescriptor descriptor = ModelConstants.createOpenNLPSentenceModelDescriptor("en");
        
        assertNotNull(descriptor);
        assertEquals("opennlp-sentence-en", descriptor.getModelId());
        assertEquals(ModelType.OPENNLP_SENTENCE, descriptor.getModelType());
        assertTrue(descriptor.getDownloadUrl().contains("opennlp-en-ud-ewt-sentence"));
        assertEquals("opennlp/sentence/en-sent.bin", descriptor.getExpectedCacheSubpath());
    }

    @Test
    void testSupportedLanguages() {
        assertTrue(ModelConstants.isOpenNLPLanguageSupported("en"));
        assertTrue(ModelConstants.isOpenNLPLanguageSupported("de"));
        assertTrue(ModelConstants.isOpenNLPLanguageSupported("fr"));
        assertFalse(ModelConstants.isOpenNLPLanguageSupported("xyz"));
        
        assertTrue(ModelConstants.getSupportedOpenNLPLanguages().contains("en"));
        assertTrue(ModelConstants.getSupportedOpenNLPLanguages().size() > 20);
    }

    @Test
    void testUnsupportedLanguageThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            ModelConstants.createOpenNLPSentenceModelDescriptor("invalid-lang");
        });
    }
}
