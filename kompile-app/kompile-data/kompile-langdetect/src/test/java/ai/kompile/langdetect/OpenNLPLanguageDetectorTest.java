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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.langdetect;

import opennlp.tools.langdetect.Language;
import opennlp.tools.langdetect.LanguageDetectorFactory;
import opennlp.tools.langdetect.LanguageDetectorME;
import opennlp.tools.langdetect.LanguageDetectorModel;
import opennlp.tools.langdetect.LanguageSample;
import opennlp.tools.util.CollectionObjectStream;
import opennlp.tools.util.TrainingParameters;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenNLPLanguageDetectorTest {

    @Test
    void serializedModelLoadsWithoutReflectiveFactoryConstruction() throws Exception {
        List<LanguageSample> samples = List.of(
                new LanguageSample(new Language("eng"), "The quick brown fox jumps over the lazy dog."),
                new LanguageSample(new Language("eng"), "This is an English language training sentence."),
                new LanguageSample(new Language("eng"), "Software systems need reliable tests."),
                new LanguageSample(new Language("deu"), "Der schnelle braune Fuchs springt über den faulen Hund."),
                new LanguageSample(new Language("deu"), "Dies ist ein deutscher Beispielsatz für das Training."),
                new LanguageSample(new Language("deu"), "Zuverlässige Software braucht gute Tests."));
        TrainingParameters parameters = TrainingParameters.defaultParams();
        parameters.put(TrainingParameters.CUTOFF_PARAM, 1);
        parameters.put(TrainingParameters.ITERATIONS_PARAM, 20);

        LanguageDetectorModel trained = LanguageDetectorME.train(
                new CollectionObjectStream<>(samples),
                parameters,
                new LanguageDetectorFactory());
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        trained.serialize(serialized);

        OpenNLPLanguageDetector.NativeSafeLanguageDetector detector =
                OpenNLPLanguageDetector.loadModelArchive(
                        new ByteArrayInputStream(serialized.toByteArray()));

        assertNotNull(detector);
        assertTrue(detector.predictLanguages("This sentence is written in English.").length > 0);
        assertEquals("eng", detector.predictLanguage(
                "This is a reliable English software testing sentence.").getLang());
        assertEquals("deu", detector.predictLanguage(
                "Dies ist ein zuverlässiger deutscher Satz für Softwaretests.").getLang());
    }

    @Test
    void productionArchiveLoadsAndPredictsWhenProvided() throws Exception {
        String configuredPath = System.getProperty("kompile.langdetect.production-model", "");
        assumeTrue(!configuredPath.isBlank(),
                "Set kompile.langdetect.production-model to exercise the Apache 183-language archive");

        try (InputStream input = Files.newInputStream(Path.of(configuredPath))) {
            OpenNLPLanguageDetector.NativeSafeLanguageDetector detector =
                    OpenNLPLanguageDetector.loadModelArchive(input);

            assertEquals("eng", detector.predictLanguage(
                    "This is a substantial English paragraph about reliable software systems and testing.").getLang());
            assertEquals("deu", detector.predictLanguage(
                    "Dies ist ein ausführlicher deutscher Absatz über zuverlässige Softwaresysteme und Tests.").getLang());
        }
    }
}
