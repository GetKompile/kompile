package ai.kompile.app.chunker.sentence;

import ai.kompile.app.core.chunking.TextChunker;
import lombok.extern.slf4j.Slf4j;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.core.io.ClassPathResource;


import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("openNLPSentenceChunker")
public class OpenNLPSentenceChunker implements TextChunker {

    private final SentenceDetectorME sentenceDetector;
    private static final String CHUNKER_NAME = "opennlp_sentence";

    public OpenNLPSentenceChunker() {
        try (InputStream modelIn = new ClassPathResource("opennlp-models/en-sent.bin").getInputStream()) {
            // You'll need to download the OpenNLP sentence model (e.g., en-sent.bin)
            // and place it in src/main/resources/opennlp-models/
            // Download from: https://opennlp.apache.org/models.html
            SentenceModel model = new SentenceModel(modelIn);
            this.sentenceDetector = new SentenceDetectorME(model);
        } catch (IOException e) {
            log.error("Failed to load OpenNLP sentence model", e);
            throw new RuntimeException("Failed to initialize OpenNLPSentenceChunker", e);
        }
    }

    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] sentences = sentenceDetector.sentDetect(text);
        List<Document> chunks = new ArrayList<>();
        int chunkNumber = 0;
        for (String sentence : sentences) {
            if (!sentence.isBlank()) {
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                metadata.put("original_document_id", document.getId());
                metadata.put("chunk_number", chunkNumber++);
                metadata.put("chunker", getName());
                chunks.add(new Document(UUID.randomUUID().toString(), sentence, metadata));
            }
        }
        return chunks;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }
}