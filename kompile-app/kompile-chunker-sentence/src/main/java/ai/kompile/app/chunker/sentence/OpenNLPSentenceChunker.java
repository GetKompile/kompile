package ai.kompile.app.chunker.sentence;

import ai.kompile.app.core.chunking.TextChunker;
import lombok.extern.slf4j.Slf4j;
import opennlp.tools.sentdetect.SentenceDetectorFactory;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.springframework.ai.document.Document;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Slf4j
@Component("openNLPSentenceChunker")
@ImportRuntimeHints(OpenNLPSentenceChunker.Hints.class)
public class OpenNLPSentenceChunker implements TextChunker {

    private static final String CHUNKER_NAME = "opennlp_sentence";
    public static final String OPTION_LANGUAGE = "language";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String MODEL_FILENAME_PATTERN = "%s-sent.bin";
    private static final String OPENNLP_MODELS_DIR = "models";
    private static final String OPENNLP_MODEL_DOWNLOAD_URL = "https://dlcdn.apache.org/opennlp/models/opennlp-models-1.5/%s-sent.bin";


    private final Map<String, SentenceDetectorME> sentenceDetectors = new ConcurrentHashMap<>();
    private final List<String> availableLanguages = new ArrayList<>();

    public OpenNLPSentenceChunker() {
        // Discover available models in the classpath (e.g., bundled by RagPomGenerator)
        discoverAvailableModels();
        // Attempt to load a default model if available, e.g., English
        if (availableLanguages.contains(DEFAULT_LANGUAGE)) {
            try {
                loadModel(DEFAULT_LANGUAGE);
            } catch (IOException e) {
                log.warn("Failed to load default OpenNLP sentence model for language '{}': {}", DEFAULT_LANGUAGE, e.getMessage());
            }
        } else if (!availableLanguages.isEmpty()) {
            log.info("Default language model '{}' not found. Available models: {}", DEFAULT_LANGUAGE, availableLanguages);
        } else {
            log.warn("No OpenNLP sentence models found in classpath under '{}'. Please ensure models are bundled or downloadable.", OPENNLP_MODELS_DIR);
        }
    }

    static class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
         for(Class clazz : new Class[] {
                 SentenceDetectorFactory.class,
                 opennlp.tools.sentdetect.SentenceModel.class,
                 opennlp.tools.util.model.BaseModel.class
         }) {
             hints.reflection().registerType(clazz,
                     MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                     MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
                     MemberCategory.DECLARED_CLASSES,MemberCategory.DECLARED_FIELDS,
                     MemberCategory.PUBLIC_FIELDS,MemberCategory.PUBLIC_CLASSES);
         }


        }
    }

    private void discoverAvailableModels() {
        try {
            ClassPathResource modelsDirResource = new ClassPathResource(OPENNLP_MODELS_DIR);
            if (modelsDirResource.exists() && modelsDirResource.isFile() && modelsDirResource.getFile().isDirectory()) {
                try (Stream<Path> paths = Files.list(modelsDirResource.getFile().toPath())) {
                    paths.filter(path -> path.toString().endsWith("-sent.bin"))
                            .map(path -> path.getFileName().toString().replace("-sent.bin", ""))
                            .forEach(availableLanguages::add);
                    log.info("Discovered OpenNLP sentence models for languages: {}", availableLanguages);
                }
            } else {
                // Fallback for when running from within a JAR where direct file listing might not work as expected
                // This part is tricky and might require more sophisticated classpath scanning or a manifest file.
                // For now, we rely on pre-configuration or known bundled models.
                log.warn("Could not list models in classpath resource folder: {}. Relying on pre-loaded/configured models.", OPENNLP_MODELS_DIR);
                // As a simple fallback, assume 'en' if nothing else is found and allow dynamic download
                if(availableLanguages.isEmpty()) availableLanguages.add("en");


            }
        } catch (IOException e) {
            log.warn("Error discovering available OpenNLP models: {}", e.getMessage());
        }
    }


    private SentenceDetectorME loadModel(String languageCode) throws IOException {
        if (sentenceDetectors.containsKey(languageCode)) {
            return sentenceDetectors.get(languageCode);
        }

        String modelFilename = String.format(MODEL_FILENAME_PATTERN, languageCode);
        InputStream modelIn = null;
        try {
            // Try loading from classpath first (e.g., if bundled)
            ClassPathResource modelResource = new ClassPathResource(OPENNLP_MODELS_DIR + "/" + modelFilename);
            if (modelResource.exists()) {
                modelIn = modelResource.getInputStream();
                log.info("Loading OpenNLP model for language '{}' from classpath: {}", languageCode, modelResource.getPath());
            } else {
                // If not in classpath, try to download it (this part would be done by RagPomGenerator for generated projects)
                // For standalone use, this chunker might attempt a download if configured to do so.
                // However, for consistency with RagPomGenerator, we'll assume models are pre-bundled or pre-downloaded.
                log.warn("OpenNLP model for language '{}' not found in classpath at '{}/{}'. Attempting download (conceptual - should be handled by build).",
                        languageCode, OPENNLP_MODELS_DIR, modelFilename);
                // The download logic for runtime is removed here as RagPomGenerator should handle pre-bundling.
                // If it needs to be truly dynamic at runtime for non-generated projects, it could be added back.
                throw new IOException("Model for language '" + languageCode + "' not found in classpath and runtime download is disabled in this context.");
            }

            SentenceModel model = new SentenceModel(modelIn);
            SentenceDetectorME detector = new SentenceDetectorME(model);
            sentenceDetectors.put(languageCode, detector);
            if (!availableLanguages.contains(languageCode)) {
                availableLanguages.add(languageCode);
            }
            return detector;
        } finally {
            if (modelIn != null) {
                try {
                    modelIn.close();
                } catch (IOException e) {
                    log.error("Failed to close model input stream", e);
                }
            }
        }
    }


    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String language = (String) options.getOrDefault(OPTION_LANGUAGE, DEFAULT_LANGUAGE);
        SentenceDetectorME detector;
        try {
            detector = sentenceDetectors.get(language);
            if(detector == null) { // Attempt to load if not already loaded
                detector = loadModel(language);
            }
        } catch (IOException e) {
            log.error("Failed to load OpenNLP sentence model for language '{}'. Defaulting to English if available, or failing.", language, e);
            if (!language.equals(DEFAULT_LANGUAGE) && sentenceDetectors.containsKey(DEFAULT_LANGUAGE)) {
                log.warn("Falling back to default English model.");
                detector = sentenceDetectors.get(DEFAULT_LANGUAGE);
            } else {
                log.error("No suitable OpenNLP model available for language '{}' or default '{}'. Chunking will fail.", language, DEFAULT_LANGUAGE);
                throw new RuntimeException("Failed to initialize OpenNLPSentenceChunker for language: " + language, e);
            }
        }
        if (detector == null) {
            log.error("OpenNLP SentenceDetector is null for language '{}' even after attempting load/fallback. Cannot proceed.", language);
            throw new RuntimeException("Sentence detector could not be initialized for language: " + language);
        }


        String[] sentences = detector.sentDetect(text);
        List<Document> chunks = new ArrayList<>();
        int chunkNumber = 0;
        for (String sentence : sentences) {
            if (!sentence.isBlank()) {
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                metadata.put("original_document_id", document.getId());
                metadata.put("chunk_number", chunkNumber++);
                metadata.put("chunker", getName());
                metadata.put("language", language);
                chunks.add(new Document(UUID.randomUUID().toString(), sentence.trim(), metadata));
            }
        }
        log.debug("Split document {} into {} chunks using OpenNLP for language {}.", document.getId(), chunks.size(), language);
        return chunks;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        if (availableLanguages.isEmpty() && !sentenceDetectors.isEmpty()) {
            // If discovery failed but models were loaded programmatically
            return new ArrayList<>(sentenceDetectors.keySet());
        }
        return Collections.unmodifiableList(new ArrayList<>(availableLanguages));
    }
}